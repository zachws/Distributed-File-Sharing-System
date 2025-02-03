package LoadBalancer;

import java.util.ArrayList;
import java.util.List;


public class LoadBalancer {
    private List<String> serverAddresses; //private list of serverAddresses
    private int leaderPort = -1; //keep track of current leaders port (default/uninitialized = -1)

    //For notificaiton management (i.e., new leader, file propagation, etc.)
    private LeaderNotifier leaderNotifier = new LeaderNotifier();

    //Basic constructor to ensure that we get all the server addresses here. This is now being initialized by LoadBalancerInit (which also initializes the HealthChecks to run at a set interval to ensure servers are up)
    public LoadBalancer(List<String> initialServerAddresses)
    {
        serverAddresses = new ArrayList<>(initialServerAddresses);

        System.out.println("Server addresses are:" + serverAddresses);

        //Set initial leader (upon program initializaiton) as first server in our server list 
        if(!serverAddresses.isEmpty())
        {
            updateLeaderPort(serverAddresses.get(0));
        }
    }

    public synchronized void handleLeaderFailure(String failedLeaderAddress)
    {
        System.out.println("The leader has failed, transitioning leader status to next leader in the active server list ");
        
        if(!serverAddresses.isEmpty()){ //check to see if we can even establish a new leader before trying 
        String newLeaderAddress = determineNewLeader(failedLeaderAddress); //removes failed leader from serverAddresses list and gets the next in line

        // Ensure theres actually a new leader 
        if(newLeaderAddress != null)
        {
            leaderNotifier.notifyServersOfNewLeader(newLeaderAddress, serverAddresses);
        }
        }else{//case where no more servers after leader dies
            System.err.println("Critical Failure; Leader died and no other servers exist"); 
        }
    }

    private String determineNewLeader(String failedLeaderAddress)
    {
        serverAddresses.remove(failedLeaderAddress); //Remove the failed leader 
        return !serverAddresses.isEmpty() ? serverAddresses.get(0) : null; //Elect a new leader 
    }

    // Synchronized method to update the leader's port based on the address (Prevents multiple thread access to shared resource(s))
    public synchronized void updateLeaderPort(String leaderAddress)
    {
        String[] parts = leaderAddress.split(":"); //split it from IP:Port into string array 
        if(parts.length > 1){
            try
            {
                this.leaderPort = Integer.parseInt(parts[1]); //IP:Port format  
            }
            catch(NumberFormatException e)
            {
                System.err.println("Error parsing leader port: " + e.getMessage());
                this.leaderPort = -1; //set to negative as it obviously isn't a port and is easy to handle if we need 
            }
        }
    }

    // Synchronized method to get the current leader's port (Prevents multiple thread access to shared resource(s))
    public synchronized int getLeaderPort()
    {
        return leaderPort;
    }

    // Synchronized method to get the current leader's full address (Prevents multiple thread access to shared resource(s))
    public synchronized String getLeaderAddress()
    {
        if(!serverAddresses.isEmpty())
        {
            return serverAddresses.get(0); //first in the list should always be the leader (changeLeader() ensures that list order is maintained properly)
        }
        return null; //TODO: either we return NULL or we handle no servers here
    }

    //Method to get list of active servers 
    public synchronized List<String> getActiveServers(){
        return new ArrayList<>(serverAddresses); // Returns a new ArrayList to avoid modification of the original list outside this class
    }

    //synchronized as we can't have more than 1 thread trying to access the global var at the same time or we get RACE CONDITIONS
    public synchronized void removeFailedServer(String serverAddress)
    {
        if(serverAddresses.contains(serverAddress))
        {
            serverAddresses.remove(serverAddress);
            System.out.println("Server removed due to failure: " + serverAddress);
        }
        else
        {
            System.out.println("Attempted to remove a server that was not in the list: " + serverAddress);
        }
        //can also re-sync list if shared (but I don't think we share this so we should be good)
    }

    //synchronized as we can't have more than 1 thread trying to access the global var at the same time or we get RACE CONDITIONS
    public synchronized void addRecoveredServer(String serverAddress)
    {
        //perform a check to ensure that this server isn't already in the serverlist so that our algorithm actually works properly 
        if (!serverAddresses.contains(serverAddress))
        {
            serverAddresses.add(serverAddress);
            System.out.println("Server recovered and added: " + serverAddress);
        }
        else
        {
            //Do we want to log anything? 
            System.out.println("Server already in the list: " + serverAddress); 
        }
    } 
}
