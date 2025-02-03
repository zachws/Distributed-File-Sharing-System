package LoadBalancer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

import Utilities.ServicePorts;

//Runnable allows multiple Asynchronous executions (i.e., Parallel Execution to reduce time to check all servers)
//Using separate threads for health checks also ensures these calls are non-blocking (i.e., don't slow or stop main thread executions)
//Runnable allows us to use 'ScheduledExecutorService' for regular interval checks (for round robin)
//Can be re-used for other asynchronous checks (not just health checks) later
public class ServerHealthCheck implements Runnable{
    private String serverAddress; //the address of the server to ping/check
    private LoadBalancer loadBalancer; //instance of the load balancer so we can actually remove failed servers from the current list and such
    private boolean isLeaderCheck; //differentiate leader checks vs regular checks

    public ServerHealthCheck(String serverAddress, LoadBalancer loadBalancer, boolean isLeaderCheck)
    {
        this.serverAddress = serverAddress; 
        this.loadBalancer = loadBalancer;
        this.isLeaderCheck = isLeaderCheck; 
    }

    @Override
    public void run() {

        //Health check logic here (connect to socket, ping server, get response, react)
        try{
            boolean isServerUp = checkServerHealth(serverAddress);
            if (!isServerUp) //Server has failed
            {
                if(isLeaderCheck) //Failed server is the leader 
                {
                    loadBalancer.handleLeaderFailure(serverAddress);//Handle leader failure
                }
                else{
                    loadBalancer.removeFailedServer(serverAddress);//not a leader so just gets removed (No special handling for now)
                }
            }
            else //Server was down? And came back up 
            {
                loadBalancer.addRecoveredServer(serverAddress);
            }
        }catch(Exception e){
            e.printStackTrace(); //probably return a better error code later once I know how to handle this better 
            if(isLeaderCheck){
            //TODO: implement logging or leader-specific failure logic 
            }
            loadBalancer.removeFailedServer(serverAddress); //if we get an exception we should still remove it as it had to have failed to throw this
        }
    }

    private boolean checkServerHealth(String serverAddress) //server address can be a string in the format: 127.0.0.1:6969 and just split it (saves stress of using Triple data struct)
    {
        String host = serverAddress.split(":")[0]; //Split serverAddress into hostname and port; get the host (IP) [0]
        int healthCheckPort = ServicePorts.HEALTH_CHECK_PORT; //TODO: LEAVE THIS

        try (Socket socket = new Socket())
        {
            //connect with timeout to avoid hanging if server isn't active 
            socket.connect(new InetSocketAddress(host, healthCheckPort), 5000); //timeout currently set to 5000 milisecond (5 Seconds)
            //ping server 
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true); 
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println("PING"); //send ping string
            String response = in.readLine(); //get response 
            return "OK".equals(response); //if the server says OK as a response we check and evaluate to set the bool 
        }catch(IOException e)
        {
            return false; //connection failed
        }
    }

}
