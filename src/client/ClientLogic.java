package client;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import Utilities.*;


// class that is responsible for handling the logic of the client
// class is static because it is not meant to be instantiated by several threads, may change later
public class ClientLogic {

	private final int loadBalancerPort = ServicePorts.LOAD_BALANCE_SERVER_PORT;
    DataInputStream in;
    DataOutputStream out;
	byte id;
	Socket serverSocket; 


    //constructor called when given host and port to instantiate variables associated
	
	/**
	 * Sets up a connection with the load balancer/master which sends them the IP of the leader server to conenct to
	 */
    public ClientLogic() //for the load balancer server (PRETTY MUCH THE MAIN METHOD)
    {
		//String loadBalancerAddress = "127.0.0.1"; //TODO: change this later 
		String loadBalancerAddress = IPadr.OWEN_IP;

		try 
		{
			System.out.println("Requesting Server Address");
			Socket loadBalancerSocket = new Socket(loadBalancerAddress, loadBalancerPort);
			DataInputStream loadBalancerIS = new DataInputStream(loadBalancerSocket.getInputStream());
			DataOutputStream loadBalancerOS = new DataOutputStream(loadBalancerSocket.getOutputStream());
			loadBalancerOS.writeByte(codes.REQUESTLEADERDETAILS);
			String serverInfo = loadBalancerIS.readUTF();
			loadBalancerSocket.close(); //close socket to LB Server

			//break the string into hostName and portNumber 
			String[]parts = serverInfo.split(":");
			String serverAddress = parts[0]; //get server addr 
			int serverPort = Integer.parseInt(parts[1]); //get server port 
			System.out.println("Attempting to Connect to IP: "+serverAddress+" Port: "+serverPort);

			//Connect to the actual server provided by the Load Balancer Server 
			try
			{
				//Instantiate serverSocket with 
				this.serverSocket = new Socket(serverAddress, serverPort);
				this.out = new DataOutputStream(serverSocket.getOutputStream());
				this.in = new DataInputStream(serverSocket.getInputStream()); 

			}catch(IOException e)
			{
				System.out.println("Couldn't connect to the server provided by the load balancer " + e.getMessage());

			}
		}
		catch(IOException e)
		{
			System.out.println("Couldn't connect to the load balancer server " + e.getMessage());
			//e.printStackTrace();
			this.serverSocket = null;
			this.out = null;
			this.in = null; 
		}
    }
    
	//START OF SERVICE RELATED METHODS
    /**
     * Check if the client is connected
     * @return - true if connected, false otherwise
     */
    public boolean isConnected() {
    	return this.serverSocket == null;
    }
    
    /**
     * Stop the connection with the server
     */
    public void stop() {
    	try {
    		out.writeByte(codes.QUIT);
    		serverSocket.close();
    	} catch(IOException e) {
    		e.printStackTrace();
    	}
    }
	//END OF SERVICE RELATED METHODS
    
	//START OF USER REQUEST CODE LOGIC 
    /**
     * Send a login request to the server for a user logging in
     * @param username
     * @param password
     * @return - the userID of the user logged in or error
     */
    public int loginRequest(String username, String password) {
    	try {
    		out.writeByte(codes.LOGINREQUEST);
    		out.writeUTF(username);
    		out.writeUTF(password);//should be hashed
    		System.out.println("Sent Request");
    		
    		byte returned = in.readByte();
    		System.out.println("Recieved Response: " + returned);
    		if(returned == codes.LOGINFAIL) {
    			String msg = in.readUTF();
    			System.out.println(msg);
                return codes.LOGINFAIL; 
    		}
    		
			id = in.readByte(); //If success then ID was also written
			System.out.println("Read ID: " + id);
    		return codes.LOGINSUCCESS; //should be ok since it made it here and thus we return OK 
    	} catch(IOException e) {
    		e.printStackTrace();
            return codes.ERR;
    	}
    }

    /**
     * Send a register request to the server for a new user
     * @param username
     * @param password
     * @return - UserID of the registered user or error
     */
    public int registerRequest(String username, String password) {
    	try {
            out.writeByte(codes.REGISTERREQUEST);
            byte response = in.readByte(); 
            if(response == codes.REGISTERRESPONSE)
            {
                out.writeUTF(username);
                //byte user = in.readByte(); 
                //if (user == codes.USEREXISTS) then the user already exists and thus we cant create new user with same name 
                out.writeUTF(password);//should be hashed
                byte returned = in.readByte();
                System.out.println("Returned: " + returned);
                if(returned == codes.PASSWORDINVALID)
                {
                    return codes.PASSWORDINVALID; 
                }
                if(returned == codes.REGISTERFAIL) {
                    //String msg = in.readUTF(); unreachable, but doesnt happen yet - just in case
                    //System.out.println(msg);
                    return codes.REGISTERFAIL;
                }
				id = in.readByte();
                return codes.REGISTERSUCCESS;
            }
            else//some sort of error
            {
                return codes.ERR; 
            }
    	} catch(IOException e) {
    		e.printStackTrace();
            return codes.ERR;
    	}
    }

    /**
     * Send a request to the server to upload a file and handle the sending of the file to the server
     * @param file
     * @return - The code of the status of the upload
     */
    public byte uploadRequest(File file){

		try{
        	out.writeByte(codes.UPLOADREQUEST); //send request to server
            byte response = in.readByte();  //get response from server
        	if(response == codes.UPLOADRESPONSE) //server responded with valid code so we can proceed 
        	{

				try{
					//Generate checksum to send to Server. Allows Server to quickly check file validity. 
					String checksum = ChecksumUtil.generateChecksum(file);
					//SEND FILE PERTINENT INFO TO SERVER 
					out.writeUTF(file.getName()); //write filename to server 
					long fileSize = file.length(); //get file size so we can write it to the server 
					out.writeLong(fileSize); //send fileSize to the runner so that they can determine storage/server to use and other stuffs
					out.writeByte(id);
					out.writeUTF(checksum);

					System.out.println("Checksum sent to server is: " + checksum); 

					//Variables pertinent to file transfer (Streams, bufers, etc.)
					FileInputStream fileIS = new FileInputStream(file); //instantiate FileInputStream to get file contents to send over the socket input stream after
					byte[] buffer = new byte[4096]; //buffer of 4kb
					int bytesRead; 
		
					//Actually send the file contents. 
					while((bytesRead = (fileIS.read(buffer))) != -1)//while file still has contents to read we should read them 
					{
						out.write(buffer, 0, bytesRead);
						out.flush();
					}
					fileIS.close(); //Close file stream after done
					System.out.println("File " + file.getName() + " has been uploaded."); //send message for debugging 

					byte result = in.readByte();
					if(result == codes.UPLOADSUCCESS){
					return codes.UPLOADSUCCESS; 
					}else {
						//TODO: DO WE WANT TO RETRY OR NAH? a
						return codes.UPLOADFAIL; 
					}
				}catch(Exception e){
					System.err.println("Error generating checksum for file: " + file.getName() + ". Error: " + e.getMessage());
					//TODO: Handle the error, e.g., notify the client of failure, log the error, retry, etc. 
					return codes.ERR; 
				}

			//Server didn't respond that it could handle an upload from the client. 
			}else{
				System.out.println("Something went wrong when trying to upload, try again");
				return codes.UPLOADFAIL; 
			}
    }catch(IOException e) //Something went wrong with the Input/Output stream idk 
	{
		e.printStackTrace();
		return codes.ERR; 
	}
}

    /**
     * Send a download request to the server and handle recieving a file from the server
     * @param destination
     * @param filename
     * @return - the status code of the download
     */
    public byte downloadRequest(String destination, String filename){
        try{
        	
            out.writeByte(codes.DOWNLOADREQUEST);
            byte response = in.readByte(); 
            if(response == codes.DOWNLOADRESPONSE)
            {
                out.writeUTF(filename);//send filename to server 
                //String PREPEND = "C:\\CPSC559Proj\\CLIENTFILES\\"; //TODO: solidify this?
                File file = new File(destination+"\\"+filename);
                // file.getParentFile().mkdirs(); creates parent dir if it doesn't exist
                file.createNewFile(); //ensures that it doesn't already exist

				String checksum = in.readUTF(); 

                long fileSize = in.readLong(); 
                
                FileOutputStream fileOS = new FileOutputStream(file, false); //false so it doesn't append to the file if it exists (we could use this to resume downloads if one fails later potentially)
                byte[] buffer = new byte[4096];
                int bytesRead = 0; 
                long totalRead = 0; 

				//Get the file
                while(totalRead < fileSize)
                {
                    bytesRead = in.read(buffer, 0, Math.min(buffer.length, Math.min(buffer.length, (int)fileSize-bytesRead)));
                    fileOS.write(buffer,0,bytesRead);
                    totalRead += bytesRead;
                }
                fileOS.close();

				//Server says its done so now we should generate checksum and see if the download is truly successful 
                byte result = in.readByte();

				//if the server told us it offloaded all data for the file to us it is a DOWNLOAD success; still need to verify file is actually complete and such via checksum. 
                if(result == codes.DOWNLOADSUCCESS)
                {

					try{
						System.out.println("Checksum received from server is: " + checksum);
						boolean isValid = ChecksumUtil.verifyChecksum(file, checksum);
						if(!isValid){
							System.err.println("Received file checksum is invalid.");
							//TODO: HANDLE INVALID CHECKSUM (i.e., failed propagaiton)
							return codes.DOWNLOADFAIL; 
						}
						else
						{
							System.out.println("File " + filename + " received and verified.");
							return codes.DOWNLOADSUCCESS;
						}

					}catch(Exception e)
					{
						System.err.println("Error generating checksum for file: " + filename + ". Error: " + e.getMessage());
						return codes.DOWNLOADFAIL; //error generating checksum so something is wrong/broken; return failure. 
					}
                }
                else
                {
                    return codes.DOWNLOADFAIL; 
                }
            }
            else if(response == codes.NOSUCHFILE)
            {
                System.out.println("No such file exists, try again");
                return codes.NOSUCHFILE; 
            }
            else
            {
                System.out.println("No response from the server");
                return codes.DOWNLOADFAIL; 
            }

        }catch(IOException e)
        {
            //can happen if user is out of storage space and/or connection lost to server
            e.printStackTrace();
            return codes.ERR; 
        }
    }

    //fileName is the name of the file which user wants to share 
    /**
     * Send a share request to the server
     * @param fileName
     * @param sharedUser
     * @return - the status code of the request
     */
    public byte shareRequest(String fileName, String sharedUser){
    	try {
	        out.writeByte(codes.SHAREREQUEST); //send request to server to start share request functionality 
	        byte response = in.readByte();  //get response that server is now running the share request functionality 
	
	        if(response == codes.SHARERESPONSE) //valid response from server
	        {
							out.writeInt(id);
	            out.writeUTF(fileName); //send file name so we can check if it exists
	            byte doesFileExist = in.readByte();  //read from server to see if the file actually exists
	            //if file doesn't exist we should return as we can't share something not in the system duh
	            if(doesFileExist == codes.NOSUCHFILE)
	            {
	                return codes.NOSUCHFILE; 
	            }
	
	            out.writeUTF(sharedUser);
	            byte doesUserExist = in.readByte(); 
	
	            //if the user to share with doesn't exist then we shouldn't share with them 
	            if(doesUserExist == codes.NOSUCHUSER)
	            {
	                return codes.NOSUCHUSER; 
	            }
	
	            byte serverResponse = in.readByte(); 

	            //validity checks already done.
	            return serverResponse; 
	        }
	        else {
	            return codes.ERR; //something happened not sure if this can actually get hit though 
	        }
    	}catch(IOException e) {
    		e.printStackTrace();
    		return codes.ERR;
    	}

    }
    
    /**
     * Send an unshare request to the server
     * @param fileName
     * @param sharedUser
     * @return - The status code of the request
     */
    public byte unshareRequest(String fileName, String sharedUser){
    	try {
	        out.writeByte(codes.UNSHAREREQUEST); //send request to server to start share request functionality 
	        byte response = in.readByte();  //get response that server is now running the share request functionality 
	        
	
	        if(response == codes.UNSHARERESPONSE) //valid response from server
	        {
	            out.writeUTF(fileName); //send file name so we can check if it exists
	            byte doesFileExist = in.readByte();  //read from server to see if the file actually exists
	            //if file doesn't exist we should return as we can't share something not in the system duh
	            if(doesFileExist == codes.NOSUCHFILE)
	            {
	                return codes.NOSUCHFILE; 
	            }
	
	            out.writeUTF(sharedUser);
	            byte doesUserExist = in.readByte(); 
	
	            //if the user to share with doesn't exist then we shouldn't share with them 
	            if(doesUserExist == codes.NOSUCHUSER)
	            {
	                return codes.NOSUCHUSER; 
	            }
	
	            out.writeInt(id);
	            byte serverResponse = in.readByte(); 
	
	            //byte response = in.readByte(); 
	           // out.writeInt(idReceiver);  
	
	            //validity checks already done.
	            return serverResponse; 
	            //return codes.OK
	        }
	        else{ return codes.ERR;} //not sure if this can get hit 
    	} catch(IOException e) {
    		e.printStackTrace();
    		return codes.ERR;
    	}
    }

    /**
     * Send a delete request to the server
     * @param filePath
     * @return - Status of the request
     */
    public byte deleteRequest(String filePath){
    	try {
	        out.writeByte(codes.DELETEREQUEST); //send request to server
	
	        byte response = in.readByte(); //get servers response to request
	        if(response == codes.DELETERESPONSE) //server responded so we can start doing the important stuff
	        {
	            out.writeUTF(filePath); 
	
	            byte doesFileExist = in.readByte(); 
	
	            if(doesFileExist == codes.NOSUCHFILE)
	            {
	                return codes.NOSUCHFILE; 
	            }
	
	            //check if the user owns it with USEREXISTS
	
	            out.writeInt(id); 
	
	            byte doesUserExist = in.readByte(); 
	            if(doesUserExist == codes.NOSUCHUSER)
	            {
	                return codes.NOSUCHUSER; 
	            }
	
	            response = in.readByte(); //should be returning DELETESUCCESS REALISTICALLY BUT SINCE THE CALL IS BEING SENT WE MUST READ IT OR ELSE IT WILL MESS WITH SOMETHING LATER 
	            return response; 
	        } else return codes.ERR;
    	} catch(IOException e) {
    		e.printStackTrace();
    		return codes.ERR;
    	}
    }

    /**
     * Send a request to get all files owned and shared from the server
     * @return - Array list of pairs stating the file name and weither it is owned or shared
     */
    public ArrayList<Pair<String, String>> getAllFilesRequest()
    {
    	ArrayList<Pair<String,String>> errorReturn = new ArrayList<Pair<String,String>>();
    	errorReturn.add(new Pair<String, String>("", "Error"));
    	try {
	        out.writeByte(codes.GETALLFILESREQUEST); 
	
	        byte response = in.readByte();
	
	        if(response == codes.GETALLFILESRESPONSE)
	        {
	            //PROBABLY SHOULD HAVE SOME SORT OF INSTANCE OF USERID TO ACTUALLY VALIDATE AGAINST OR THE GUI INPUTS THE USERID NOT THE USER THEMSELVES OR THEY COULD RETRIEVE OTHER PEOPLES FILES
	            out.writeInt(id); 
	            byte doesUserExist = in.readByte(); 
	            if(doesUserExist == codes.USEREXISTS)
	            {
	            	short records = in.readShort();
	            	ArrayList<Pair<String,String>> allFiles = new ArrayList<Pair<String,String>>();
	            	for(int i = 0; i < records; i++) {
	            		String name = in.readUTF();
						String perm = in.readUTF();
						allFiles.add(new Pair<String, String>(name, perm));
	            	}
	                //byte finalResponse = in.readByte(); 
	                return allFiles; 
	            }
	            else
	            {
	                return errorReturn; //wrong user input somehow
	            }
	        }
	        else
	        {
	            return errorReturn; 
	        }
    	} catch(IOException e) {
    		e.printStackTrace();
    		return errorReturn;
    	}
    }

    /**
     * Get the files shared to the user with the server
     * @return - Array list of pairs stating the filename and shared
     */
	public ArrayList<Pair<String, String>> getSharedFilesRequest()
    {
    	ArrayList<Pair<String,String>> errorReturn = new ArrayList<Pair<String,String>>();
    	errorReturn.add(new Pair<String, String>("", "Error"));
    	try {
	        out.writeByte(codes.GETSHAREDFILESREQUEST); 
	
	        byte response = in.readByte();
	
	        if(response == codes.GETSHAREDFILESRESPONSE)
	        {
	            //PROBABLY SHOULD HAVE SOME SORT OF INSTANCE OF USERID TO ACTUALLY VALIDATE AGAINST OR THE GUI INPUTS THE USERID NOT THE USER THEMSELVES OR THEY COULD RETRIEVE OTHER PEOPLES FILES
	            out.writeInt(id); 
	            byte doesUserExist = in.readByte(); 
	            if(doesUserExist == codes.USEREXISTS)
	            {
	            	short records = in.readShort();
	            	ArrayList<Pair<String,String>> sharedFiles = new ArrayList<Pair<String,String>>();
	            	for(int i = 0; i < records; i++) {
	            		String file = in.readUTF();
									String user = in.readUTF();
									sharedFiles.add(new Pair<String, String>(file, user));
	            	}
	                //byte finalResponse = in.readByte(); 
	                return sharedFiles; 
	            }
	            else
	            {
	                return errorReturn; //wrong user input somehow
	            }
	        }
	        else
	        {
	            return errorReturn; 
	        }
    	} catch(IOException e) {
    		e.printStackTrace();
    		return errorReturn;
    	}
    }
	//END OF USER REQUEST CODE LOGIC 
}
