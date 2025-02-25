package server;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import Utilities.*;

// instance based as it may be used by multiple threads
public class ProtocolHandler {
    DataInputStream is;
    DataOutputStream os;
    ServerActionNotifier notifier; 
    private final String PREPEND = "C:\\CPSC559Proj\\SERVERFILES\\";

    //To notify load balancer server of download to propogate to other servers 
    private final int loadBalancerPort = ServicePorts.LOAD_BALANCE_SERVER_PORT; 
    private final int filePropagationPort = ServicePorts.FILE_PROPAGATION_PORT; 
    private final int databasePropagationPort = ServicePorts.DATABASE_PROPAGATION_PORT;
    //private String loadBalancerAddress = "127.0.0.1";
    private String loadBalancerAddress = IPadr.OWEN_IP; //TODO: FIX THE IP ON THIS LATER (UNLESS WE KEEP IT AT LOCALHOST IDK)
    private List<String> serversToShareTo; 
    private String failedPropagationServer;
    private int successfulPropagations; 

    //related to retries for propagation 
    private static final int MAX_RETRIES = 3; //maximum number of retries
    private static final long RETRY_DELAY_MS = 1000; //delay between retries in Milliseconds (i.e., 1 second)
    //private List<String> activeServers; 

    public ProtocolHandler(DataOutputStream os, DataInputStream is, ServerActionNotifier notifier) {
        this.os = os;
        this.is = is;
        this.notifier = notifier;
    }

    //START OF FILE PROPAGATION CODE
    private List<String> requestActiveServers()
    {
        //set private List<String> activeServers so we can do other stuffs
        return  notifier.requestActiveServers(loadBalancerAddress, loadBalancerPort); //List for active servers from load balancer  
    }

    //TODO: IMPLEMENT LOGIC FOR THIS LATER 
    // private void someMethodThatNeedsLeaderInfo(){
    //     String leaderAddress = LeaderState.getLeaderAddress();

    //     //use leader address as needed
    // }

    private boolean propagateDatabaseToServer(File file, String serverAddress, String checksum)
    {
        //Get server's IP address by splitting; use databasePropagationPort in tandem with this to connect 
        String[] parts = serverAddress.split(":");
        String host = parts[0];
        int attempt = 0;
        boolean success = false;

        while (attempt < MAX_RETRIES && !success) {
            try (Socket socket = new Socket(host, databasePropagationPort);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            FileInputStream fileIn = new FileInputStream(file)) {

                //Send file metadata and checksum 
                out.writeUTF(file.getName()); //send file name
                out.writeLong(file.length()); 
                out.writeUTF(checksum);


                //Send file content
                byte[] buffer = new byte[4096];
                int bytesRead; 

                while((bytesRead = fileIn.read(buffer)) != -1)
                {
                    out.write(buffer, 0, bytesRead); 
                    out.flush(); 
                }
    
                // Check response from the receiving server
                byte responseFromReceiver = in.readByte();

                if (responseFromReceiver == codes.DATABASEPROPAGATIONFAILURE) {
                    System.err.println("Failed to propagate database to server " + serverAddress + " on attempt " + (attempt + 1));
                    // Prepare for a retry
                    attempt++;
                    Thread.sleep(RETRY_DELAY_MS);
                } else {
                    System.out.println("Database successfully propagated to server " + serverAddress);
                    success = true; // Exit the loop on success
                }



            }catch(Exception e)
            {
                System.err.println("Failed to propagate database to server " + serverAddress + " on attempt " + (attempt + 1) + ": " + e.getMessage());
                attempt++;
                try {
                    Thread.sleep(RETRY_DELAY_MS); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore the interrupted status
                    System.err.println("Retry interrupted for server: " + serverAddress);
                    return false;
                }

            }

        }

        if (!success) {
            System.err.println("Failed to propagate database to server " + serverAddress + " after " + MAX_RETRIES + " attempts.");
        }
        return success;
    }

    private void handleDatabasePropagation(String serverAddress)
    {

        try{
            File file = new File("bin/server/database/server.db");
            //file.createNewFile(); //ensures file isn't already there? or just will create a new version idk the exact working of this 

            String checksum = ChecksumUtil.generateChecksum(file);

            boolean successfulDatabasePropagation = propagateDatabaseToServer(file, serverAddress, checksum);

            if(!successfulDatabasePropagation)
            {
                System.out.println("Failure to send updated database to server " + serverAddress);
            }
            else{
                System.out.println("Successfully sent updated database to server " + serverAddress);
            }

        }catch(NoSuchFileException f)
        {
            System.out.println("Couldn't open the database file to send contents.");//os.writeByte(codes.NOSUCHFILE); //If user somehow requests a file which does not exist. 
        }catch(Exception e)
        {
            System.out.println("Checksum util failed on server.db");
        }

    }

    public void handleFilePropagation(File file, int ownerID)
    {
        successfulPropagations = 0; //set to 0 so we can check whether this actually worked properly 
        failedPropagationServer = "";

        List<String> servers = requestActiveServers(); 
        int amountOfServersToPropagateTo = servers.size() - 1; //-1 as we don't want to propagate to leader 
        try{
        String checksum = ChecksumUtil.generateChecksum(file); 

        System.out.println("Checksum for propagation is: " + checksum);

        for(String serverAddress: servers)
        {
            if(!serverAddress.equals(LeaderState.getLeaderAddress())){ //don't want to propagate to leader obviously 
                boolean success = propagateFileToNextServer(file, serverAddress, checksum, ownerID); 
                if(!success)
                {
                    System.err.println("Failed to send file to server: " + serverAddress);
                    // TODO: LOG? 
                }
                else{
                    successfulPropagations += 1;
                    handleDatabasePropagation(serverAddress); 
                }
            }
        }

        if(successfulPropagations == (amountOfServersToPropagateTo - 1))
        {
            //TODO: UPDATE DATABASE AND SEND TO OTHER SERVERS
        }
        }catch(Exception e)
        {
            System.err.println("Error generating checksum for file: " + file.getName() + ". Error: " + e.getMessage());
            //TODO: Handle the error, e.g., notify the client of failure, log the error, retry, etc. 
        }
    }


    private boolean propagateFileToNextServer(File file, String serverAddress, String checksum, int fileOwnerID) {
        //Get server's IP address by splitting; use filePropagationPort in tandem with this to connect 
        String[] parts = serverAddress.split(":");
        String host = parts[0];
        int attempt = 0;
        boolean success = false;
    
        while (attempt < MAX_RETRIES && !success) {
            try (Socket socket = new Socket(host, filePropagationPort);
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 FileInputStream fileIn = new FileInputStream(file)) {
    

                //Send file metadata and checksum 
                out.writeUTF(file.getName()); //send file name
                out.writeInt(fileOwnerID); //give the file owner ID (not sure if we need this but probably do for database updates)
                out.writeLong(file.length()); 
                out.writeUTF(checksum);


                //Send file content
                byte[] buffer = new byte[4096];
                int bytesRead; 

                while((bytesRead = fileIn.read(buffer)) != -1)
                {
                    out.write(buffer, 0, bytesRead); 
                    out.flush(); 
                }
    
                // Check response from the receiving server
                byte responseFromReceiver = in.readByte();
                if (responseFromReceiver == codes.FILEPROPAGATIONFAILURE) {
                    System.err.println("Failed to propagate file to server " + serverAddress + " on attempt " + (attempt + 1));
                    // Prepare for a retry
                    attempt++;
                    Thread.sleep(RETRY_DELAY_MS);
                } else {
                    System.out.println("File successfully propagated to server " + serverAddress);
                    success = true; // Exit the loop on success
                }
            } catch (Exception e) {
                System.err.println("Failed to propagate file to server " + serverAddress + " on attempt " + (attempt + 1) + ": " + e.getMessage());
                attempt++;
                try {
                    Thread.sleep(RETRY_DELAY_MS); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // Restore the interrupted status
                    System.err.println("Retry interrupted for server: " + serverAddress);
                    return false;
                }
            }
        }
    
        if (!success) {
            System.err.println("Failed to propagate file to server " + serverAddress + " after " + MAX_RETRIES + " attempts.");
        }
        return success;
    }
    //END OF FILE PROPAGATION RELATED CODE
    
    public void workerHandleUploadRequest() {
        try {
            os.writeByte(codes.UPLOADRESPONSE); //so client knows it can start passing other important information 
            String fileName = is.readUTF();
            long fileSize = is.readLong();
            int userID = is.readByte();
            //Get checksum from client (Will compute our own to check validity later)
            String checksum = is.readUTF(); 


            //create new file incase the file already exists (MAYBE WE SHOULD DO A DATABASE CHECK HERE IDK)
            File file = new File(PREPEND+fileName); 
            file.createNewFile(); //ensures file isn't already there? or just will create a new version idk the exact working of this 
            FileOutputStream fos = new FileOutputStream(file, false); //false to not append if the file already exists within the system


            //Download the file from the client 
            byte[] buf = new byte[4096]; //4kb buffer
            int read;
            long totalRead = 0;
            while (totalRead < fileSize){//read = is.read(buf, 0, Math.min(buf.length, (int) (fileSize - totalRead)))) > 0) {
                read = is.read(buf, 0, buf.length); //read 
                fos.write(buf, 0, read);
                totalRead += read; 
            }
            fos.close();

            //Verify checksum by calculating new checksum and comparing it to the one provided from the user. 
            try{
                //Checksum Utility allows us to generate checksum and verify it against checksum provided by user at the same time.
                boolean isValid = ChecksumUtil.verifyChecksum(file, checksum);

                //If the checksum isn't valid
                if(!isValid)
                {
                    System.err.println("Received file checksum is invalid.");
                    //TODO: HANDLE INVALID CHECKSUM (i.e., failed propagaiton)
                    os.writeByte(codes.UPLOADFAIL);
                }else
                {
                    System.out.println("File " + fileName + " received and verified.");
                    os.writeByte(codes.UPLOADSUCCESS);
                    handleFilePropagation(file, userID);
                    MasterDatabase.addFile(fileName, userID);
                    

                    //Notify load balancer server? or let leader notify after all success. Probably let leader notify.
                    //notifyLoadBalancerDownload(); //let the load balancer know  
                }
            }
            catch(Exception e)
            {
                System.err.println("Error generating checksum for file: " + fileName + ". Error: " + e.getMessage());
                //TODO: Handle the error, e.g., notify the client of failure, log the error, retry, etc. 
            }

        } catch(IOException e) {
            e.printStackTrace();
        }
    }


    public void workerHandleDownloadRequest() {
        try {
            os.writeByte(codes.DOWNLOADRESPONSE);//send response to client so the client can proceed
            String fileName = is.readUTF();
            try{
                //Get the file & open file input stream
                File file = new File(PREPEND+fileName);
                FileInputStream fis = new FileInputStream(file);
                //Generate files checksum 
                String checksum = ChecksumUtil.generateChecksum(file);
                os.writeUTF(checksum);
	            os.writeLong(file.length()); //give file length to the client requesting so they know how long to download for 
	            os.flush();

                System.out.println("Checksum sent to client: " + checksum);
                //Start transfer of file from server to client 
	            System.out.println("filename: "+fileName);
	            byte[] buf = new byte[4096]; //4kb buffer
	            int read;
	            while ((read = fis.read(buf)) != -1) {
	            	System.out.println("first byte of current buf: "+buf[1]);
	                os.write(buf, 0, read);
	                os.flush();
	            }
                //Close file input stream as we are now done with transfer
	            fis.close();
                //Write success code to the server 
	            os.writeByte(codes.DOWNLOADSUCCESS); 
	            System.out.println("Finished"); //TODO: Remove later since this is for debugging. 


                }catch(NoSuchFileException f)
                {
                    os.writeByte(codes.NOSUCHFILE); //If user somehow requests a file which does not exist. 
                }
                catch(Exception e) //caught after no such file as this is a more general error and it would occur after file creation in logical order
                {
                    System.err.println("Error generating checksum for file: " + fileName + ". Error: " + e.getMessage());
                }
        } catch(IOException e) {
            //os.writeByte(codes.ERR); 
            e.printStackTrace();
        }
    }

    // private void notifyLoadBalancerDownload()
    // {
    //     //ServerActionNotifier notifier = new ServerActionNotifier();
    //     serversToShareTo = notifier.notifyLoadBalancerDownload(loadBalancerAddress, loadBalancerPort);

    // }

    // method that is called when the server recieves a request to login
    // should return the userID to the client, -1 on failure
    public void handleLoginRequest() {
        try{
            String username = is.readUTF();
            String password = is.readUTF();
            //System.out.println("Username: " + username + " Password: " + password);
            int loginReq = MasterDatabase.loginUser(username, password);
            //gui should return the clientID so we can store it in the instance of the client to help with share requests and such later (prevents an extra DB lookup)
            System.out.println("Got here");
            if(loginReq>=0) {
            	System.out.println("Login Success");
                os.writeByte(codes.LOGINSUCCESS);
                os.writeByte(loginReq);
            } else {
            	System.out.println("Login Fail");
                os.writeByte(codes.LOGINFAIL);
                os.writeUTF("Error message");
            }

        } catch(IOException e) {
            e.printStackTrace();
        }
    }

    // method that is called when the server recieves a request to register
    // should return the userID to the client, -1 on failure
    public void handleRegisterRequest() {
        try{
            //send response code back
            os.writeByte(codes.REGISTERRESPONSE);
            String username = is.readUTF();
            String password = is.readUTF();

            
            //if username already taken or password blank should return error code 
            //if (username already in database){
            //os.writeByte(codes.USEREXISTS); return}

            if(password.length() <= 2)
            {
                os.writeByte(codes.PASSWORDINVALID);
                return; 
            }

            int registerReq = MasterDatabase.registerUser(username, password);
            System.out.println("Registration Returned: "+registerReq);

            //successful registration
            if(registerReq >= 0) {
                os.writeByte(codes.REGISTERSUCCESS);
                os.writeByte(registerReq);

            } else {
                os.writeByte(codes.REGISTERFAIL);
                os.writeUTF("Error message");
            }


        } catch(IOException e) {
            e.printStackTrace();
        }
    }

    // method that is called when the server recieves a request to share a file
    // should return a success or failure message to the client
    public void handleShareRequest() {
        try {
            os.writeByte(codes.SHARERESPONSE); //write response to client to tell them we are starting to handle the share request 
            
            int userID = is.readInt(); 
            String filename = is.readUTF();
            if(!MasterDatabase.isValidFile(filename,userID)){
                os.writeByte(codes.NOSUCHFILE);
                return; 
            } else os.writeByte(codes.FILEEXISTS); 

            String sharedTo = is.readUTF();
            if(!MasterDatabase.isValidUser(sharedTo)){
                os.writeByte(codes.NOSUCHUSER);
                return; 
            } else os.writeByte(codes.USEREXISTS); 
            
            boolean success = MasterDatabase.shareFile(filename, userID, sharedTo);
            os.writeByte(success ? codes.SHARESUCCESS : codes.SHAREFAIL);
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // method that is called when the server recieves a request to unshare a file
    // should return a success or failure message to the client
    public void handleUnshareRequest() {

        try{
            os.writeByte(codes.UNSHARERESPONSE); //write response to client to tell them we are starting to handle the share request 
            String filename = is.readUTF();
            os.writeByte(codes.FILEEXISTS); //file must exist so write OK
            String sharedTo = is.readUTF();
            os.writeByte(codes.USEREXISTS); 
            int userID = is.readInt(); //should be valid if gui can check data base , if not we should do checks here on the files and such 
            //Alternatively, we could get the userID of the sharer IF we instantiate it upon login and return it to the ClientLogic (client)

            MasterDatabase.unshareFile(filename, userID, sharedTo);
            os.writeByte(codes.UNSHARESUCCESS); 
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void handleAllFilesRequest(){
        try{
	        os.writeByte(codes.GETALLFILESRESPONSE);
	
	        int userID = is.readInt(); 
	
	        //check if userID is actually in system 
	        ArrayList<String> owned = MasterDatabase.getAllOwnedFiles(userID);
	        ArrayList<String> shared = MasterDatabase.getAllSharedFiles(userID);
	        ArrayList<Pair<String,String>> allFiles = new ArrayList<Pair<String,String>>();
	        for(String s : owned) {
	        	allFiles.add(new Pair<String, String>(s,"own"));
	        }
	        for(String s : shared) {
	        	allFiles.add(new Pair<String, String>(s,"share"));
	        }
	        //TODO: Send list allFiles over socket connection
	        //if(valid user id from caller (client)){
	            os.writeByte(codes.USEREXISTS); 
	            os.writeShort(allFiles.size());
	            allFiles.forEach((i) ->{
		        	try {
		        		os.writeUTF(i.first);
						os.writeUTF(i.second);
					} catch (IOException e) {
						e.printStackTrace();
					}
		        });
        }catch(Exception e)
        {
            e.printStackTrace();
            //os.writeByte(codes.GETALLFAIL);
        }

    }

    public void handleSharedFilesRequest(){
        try{
	        os.writeByte(codes.GETSHAREDFILESRESPONSE);
	
	        int userID = is.readInt(); 
	
	        //check if userID is actually in system 
	        ArrayList<Pair<String,String>> shared = MasterDatabase.getUserSharedFiles(userID);

	        //TODO: Send list allFiles over socket connection
	        //if(valid user id from caller (client)){
	            os.writeByte(codes.USEREXISTS); 
	            os.writeShort(shared.size());
	            shared.forEach((i) ->{
		        	try {
		        		os.writeUTF(i.first);
						os.writeUTF(i.second);
					} catch (IOException e) {
						e.printStackTrace();
					}
		        });
	            //os.writeByte(codes.GETALLSUCCESS); //if successful 
	            //if unsuccessful os.writeByte(codes.GETALLFAIL);
	        //}
        }catch(Exception e)
        {
            e.printStackTrace();
            //os.writeByte(codes.GETALLFAIL);
        }

    }

    // method that is called when the server recieves a request to delete a file
    // should return a success or failure message to the client
    public void handleDeleteRequest() {
        try {
            os.writeByte(codes.DELETERESPONSE); 

            String filename = is.readUTF();


            //TODO:get file from server if it exists 
            //byte doesFileExist = 
            //if (file does not exist){
                //os.writeByte(codes.NOSUCHFILE);
                //return; 
            //}
            //else file must exist
            os.writeByte(codes.FILEEXISTS); //file must exist so write OK
          
            int userID = is.readInt();


            //TODO: do checks for if the user exists
            //byte doesUserExist = 
            //if (user does not exist){
                //os.writeByte(codes.NOSUCHUSER);
                //return; 
            //}

            //else
            os.writeByte(codes.USEREXISTS); //check if this person is actually the owner of the file to stop malicious attempts of file deletions


            //DELETE THE FILE IDK HOW YOU'RE DOING IT WITH DATABASE

            //if successful write this back
            os.writeByte(codes.DELETESUCCESS);
            //if not successful but somehow user exists and file exists you can write back os.writeByte(codes.DELETEFAIL); 

            // //attempt to delete
            // if(true) {
            //     os.writeByte(codes.OK);
            // } else {
            //     os.writeByte(codes.ERR);
            //     os.writeUTF("Error message");
            // }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
