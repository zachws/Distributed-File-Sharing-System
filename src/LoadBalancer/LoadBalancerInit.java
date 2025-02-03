package LoadBalancer;

import java.util.Arrays;
import java.util.List;

import Utilities.IPadr;
public class LoadBalancerInit {

    public static void main(String[] args)
    {
        List<String> serverAddresses = Arrays.asList( //Need to have the server address list available for this
                IPadr.DERRICK_IP + ":50005",
                IPadr.ZACH_IP + ":50005",
                IPadr.VIC_IP+ ":50005"
            //"SOME IP ADDRESS:1972", 
            //"ANOTHER UNIQUE IP :1972"
        );
        LoadBalancer loadBalancer = new LoadBalancer(serverAddresses); //init load balancer object with server addresses list 
        startLoadBalancerServer(loadBalancer); //start the load balancer server 

        HealthCheckScheduler healthCheckScheduler = new HealthCheckScheduler(loadBalancer); //Init health check scheduler 
        healthCheckScheduler.startHealthCheck(serverAddresses); //start health check scheduler 
    }

    private static void startLoadBalancerServer(LoadBalancer loadBalancer)
    {
        new Thread(() -> { //start LoadBalancer server on a separate thread 
            LoadBalancerServer server = new LoadBalancerServer(loadBalancer);
            server.start();
        }, "LoadBalancerServerThread").start();
    }

}
