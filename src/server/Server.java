package server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import Utilities.codes;
import Utilities.ChecksumUtil;
import Utilities.LeaderChangeNotification;
import Utilities.ServicePorts;
import Utilities.IPadr;
public class Server {

    //SERVICE BOOLEAN CONTROLS
    private boolean isMainServiceRunning = false; 
    private boolean isFilePropagationServiceRunning = false; 
    private boolean isDatabasePropagationServiceRunning = false;

    //PORTS 
    private final int mainServicePort = ServicePorts.MAIN_SERVICE_PORT;
    private final int managementPort = ServicePorts.MANAGEMENT_PORT; 
    private final int filePropagationPort = ServicePorts.FILE_PROPAGATION_PORT; 
    private final int databasePropagationPort = ServicePorts.DATABASE_PROPAGATION_PORT;

    //OTHER USEFUL STUFF
    private final String PREPEND = "C:\\CPSC559Proj\\SERVERFILES\\";
    private final String DATABASE_PREPEND = "bin/server/database/";
    private String thisServersAddress; 
    private ServerActionNotifier actionNotifier;

    //Threads
    Thread MAIN_SERVICE_THREAD;
    Thread FILE_PROPAGATION_THREAD;
    

    public Server(String thisServersAddress)
    {
        this.thisServersAddress = thisServersAddress; //so that we can track who the leader is
    }

    public static void main(String[] args)
    {
        //should probably parse from args the server address so we can do other stuffs (for now I'll hardcode to 127.0.0.1 but we need to change that )
            //TODO: parse args to get correct server IP addr 
        String thisAddress = "192.168.117.25:50005"; //for now, we will hardcode the server address to be the same as the load balancer address (since we are running on the same machine)


        new Server(thisAddress).start();
    }

    public void start()
    {
        actionNotifier = new ServerActionNotifier(); 
        // Start the management listener to listen for leader change notifications
        startManagementListener();

        // Start the main service if this server is the leader, otherwise start the file propagation listener
        initializeServerState(); // Initialize the server state based on the current leader

        // Start HealthCheckService in a separate thread
        startHealthCheckService();
    }


    //START OF LISTENER SERVICE INITIALIZATION 
    private void startHealthCheckService() {
        new Thread(new HealthCheckService(), "HealthCheckServiceThread").start();
    }

    private void startManagementListener() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(managementPort)) {
                System.out.println("Listening for management commands on port: " + managementPort);
                while (true) {
                    try (Socket clientSocket = serverSocket.accept();
                         ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream())) {
                        Object object = in.readObject();
                        if (object instanceof LeaderChangeNotification) {
                            LeaderChangeNotification notification = (LeaderChangeNotification) object;
                            processLeaderChangeNotification(notification);
                            //continue;
                        }
                    } catch (Exception e) {
                        System.err.println("Error handling management connection: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to start management listener: " + e.getMessage());
            }
        }, "ManagementListenerThread").start();
    }


    private void startFilePropagationListener(){
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(filePropagationPort)) {
                System.out.println("Listening for file propagation on port " + filePropagationPort);
                while(true)
                {
                    try(Socket clientSocket = serverSocket.accept();
                    DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                    DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())){

                        String fileName = in.readUTF(); 
                        int fileOwnerID = in.readInt();
                        long fileSize = in.readLong(); 
                        String checksum = in.readUTF(); 
                        System.out.println("Checksum read from lead server for prop is: " +  checksum);

                        File receivedFile = new File(PREPEND + fileName); 
                        receivedFile.createNewFile(); 

                        //Handle incoming file transfer. 
                        try(FileOutputStream fileOut = new FileOutputStream(receivedFile)){

                            byte[] buffer = new byte[4096]; 
                            int read; 

                            while(fileSize > 0 && (read = in.read(buffer, 0, Math.min(buffer.length, (int) fileSize))) != -1)
                            {
                                fileOut.write(buffer, 0, read); 
                                fileSize -= read; 
                            }
                            
                        }
                        //Verify checksum
                        try{
                            //Verify the checksum received from the leader server by verifying it (calculating checksum on file created (received) and comparing to received checksum from leader )
                            boolean isValid = ChecksumUtil.verifyChecksum(receivedFile, checksum);
                            //handle cases based on checksum result 
                            if(!isValid){
                                System.err.println("Received file checksum is invalid.");
                                //TODO: HANDLE INVALID CHECKSUM (i.e., failed propagaiton)
                                out.writeByte(codes.FILEPROPAGATIONFAILURE);
                            }
                            else{
                                System.out.println("File " + fileName + " received and verified.");
                                out.writeByte(codes.FILEPROPAGATIONSUCCESS);

                                //Notify load balancer server? or let leader notify after all success. Probably let leader notify. 
                            }
                        }catch(Exception e)
                        {
                            System.err.println("Error generating checksum for file: " + fileName + ". Error: " + e.getMessage());
                            //TODO: Handle the error, e.g., notify the client of failure, log the error, retry, etc. 
                        }
                    }catch(IOException e)
                    {
                        System.err.println("Failed to start file propagation listener: " + e.getMessage());
                        // Additional startup error handling logic
                        //TODO: log/handle exception 
                    }
                }
            }catch(IOException e)
            {
                //TODO: log/handle exception here
            }
        }, "FilePropagationListenerThread").start(); // Naming the thread for easier identification
    }

    private void startDatabasePropagationListener(){
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(databasePropagationPort)) {
                System.out.println("Listening for database propagation on port " + databasePropagationPort);
                while(true)
                {
                    try(Socket clientSocket = serverSocket.accept();
                    DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                    DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())){

                        String fileName = in.readUTF();
                        long fileSize = in.readLong(); 
                        String checksum = in.readUTF(); 
                        System.out.println("Checksum read from lead server for database prop is: " +  checksum);

                        File receivedFile = new File(DATABASE_PREPEND + fileName); 
                        receivedFile.createNewFile(); 

                        //Handle incoming file transfer. 
                        try(FileOutputStream fileOut = new FileOutputStream(receivedFile)){

                            byte[] buffer = new byte[4096]; 
                            int read; 

                            while(fileSize > 0 && (read = in.read(buffer, 0, Math.min(buffer.length, (int) fileSize))) != -1)
                            {
                                fileOut.write(buffer, 0, read); 
                                fileSize -= read; 
                            }
                            
                        }
                        //Verify checksum
                        try{
                            //Verify the checksum received from the leader server by verifying it (calculating checksum on file created (received) and comparing to received checksum from leader )
                            boolean isValid = ChecksumUtil.verifyChecksum(receivedFile, checksum);
                            //handle cases based on checksum result 
                            if(!isValid){
                                System.err.println("Received file checksum is invalid.");
                                //TODO: HANDLE INVALID CHECKSUM (i.e., failed propagaiton)
                                out.writeByte(codes.DATABASEPROPAGATIONFAILURE);
                            }
                            else{
                                System.out.println("File " + fileName + " received and verified.");
                                out.writeByte(codes.DATABASEPROPAGATIONSUCCESS);

                                //Notify load balancer server? or let leader notify after all success. Probably let leader notify. 
                            }
                        }catch(Exception e)
                        {
                            System.err.println("Error generating checksum for file: " + fileName + ". Error: " + e.getMessage());
                            //TODO: Handle the error, e.g., notify the client of failure, log the error, retry, etc. 
                        }



                    }catch(IOException e)
                    {
                        System.err.println("Failed to start database propagation listener: " + e.getMessage());
                        // Additional startup error handling logic
                        //TODO: log/handle exception 
                    }
                }
            }catch(IOException e)
            {

            }
        }, "DatabasePropagationListenerThread").start();
    }

    private void startMainService(){
        isMainServiceRunning = true; //so we don't start multiple instances of this
        new Thread(() -> {
            try(ServerSocket serverSocket = new ServerSocket(mainServicePort)){
                System.out.println("Server listening on port: " + mainServicePort);
    
                while(ServerState.getInstance().getState() == ServerState.State.LEADER) //server can accept multiple clients on 1 port 
                {
                    // if(ServerState.getInstance().getState() == ServerState.State.LEADER) //only actually allow the client to connect to the LEADER server
                    // {
                        Socket clientSocket = serverSocket.accept();
                        new Thread(new Runner(clientSocket, actionNotifier)).start(); // spawn a runner thread to serve the client
                    // }
                }
               
            }catch(Exception e)
            {
                System.out.println("Server Exception: " + e.getMessage());
            }
        }, "MainServiceThread").start();
    }
    //END OF LISTENER SERVICE INITIALIZATION 

    //START OF HELPER METHODS (SERVER STATE & LEADER CHANGE METHODS)
    public void initializeServerState() {
        String leaderAddress = actionNotifier.requestLeaderDetails(); // Request the current leader's address
        System.out.println("Leader Address is: " + leaderAddress);
        if (thisServersAddress.equals(leaderAddress)) { // This server is the leader
            updateServerState(ServerState.State.LEADER, leaderAddress);
        }else { // This server is a follower
            updateServerState(ServerState.State.FOLLOWER, leaderAddress);
        }
    }

    public void updateServerState(ServerState.State newState, String newLeaderAddress) {
        ServerState.getInstance().setState(newState);
        if (newState == ServerState.State.LEADER) { // this server is now the LEADER 
            if (!isMainServiceRunning) {
                startMainService();
            }
            newLeaderAddress = thisServersAddress + "50005"; // Update the leader's address when this server becomes the leader

        } else if (newState == ServerState.State.FOLLOWER) { // this server is now a FOLLOWER
            if (isMainServiceRunning) {
                // This shouldn't ever be reached with our current setup for leader transitions (as previous leader coming back online will not become the leader again)
                isMainServiceRunning = false;
                //Thread.MainServiceThread.stop();
            }
            if (!isFilePropagationServiceRunning) { //Start file prop service if you are not the LEADER (since leader will propagate to you)
                startFilePropagationListener();
                isFilePropagationServiceRunning = true;
            }

            if(!isDatabasePropagationServiceRunning)
            {
                startDatabasePropagationListener();
                isDatabasePropagationServiceRunning = true; 
            }
        }
        LeaderState.setLeaderAddress(newLeaderAddress); // Update the leader's address when transitioning to a follower
    }

    private void processLeaderChangeNotification(LeaderChangeNotification notification) {
        if (notification.getMessageType() == LeaderChangeNotification.MessageType.LEADER_CHANGE_NOTIFICATION) { //if notification that leader has changed
            // Determine the new state based on whether this server's address matches the leader address in the notification
            String[] parts = thisServersAddress.split(":");
            String host = parts[0];

            ServerState.State newState = notification.getLeaderAddress().equals(host) ? ServerState.State.LEADER : ServerState.State.FOLLOWER;
            
            // Call updateServerState with the new state and the leader's address from the notification
            updateServerState(newState, notification.getLeaderAddress());
        }
    }
    //END OF HELPER METHODS (SERVER STATE & LEADER CHANGE METHODS)
}
