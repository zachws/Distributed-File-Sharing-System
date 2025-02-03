package LoadBalancer;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HealthCheckScheduler {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final LoadBalancer loadBalancer; //unsure if we want this to be final 

    //Constructor to ensure we get the active instance of the LoadBalancer to ensure we are correctly performing health checks via LoadBalancer functions
    public HealthCheckScheduler(LoadBalancer loadBalancer)
    {
        this.loadBalancer = loadBalancer; 
    }

    public void startHealthCheck(List<String> serverAddresses){
        serverAddresses.forEach(serverAddress -> {
            boolean isLeaderCheck = serverAddress.equals(loadBalancer.getLeaderAddress()); //get the current leader address to check if we are running regular or leader check
            ServerHealthCheck healthCheck = new ServerHealthCheck(serverAddress, loadBalancer, isLeaderCheck);
            //Currently set to run every 5 seconds (Set at 5 seconds to ensure we don't wait too long to check leader; we could separate this logic but I'm lazy)
            scheduler.scheduleAtFixedRate(healthCheck, 10, 5, TimeUnit.SECONDS); //Have a isLeaderCheck boolean so we can use this for normal checks and leader checks
        });
    }
}
