package server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import Utilities.ServicePorts;

public class HealthCheckService implements Runnable{

    private final int healthCheckPort = ServicePorts.HEALTH_CHECK_PORT; 


    public HealthCheckService()//, String leaderAddress)
    {
    }

    @Override
    public void run() {
        // Listening for health check pings on healthCheckPort
        listenForPings();
    }

    private void listenForPings() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(healthCheckPort)) {
                System.out.println("Health Check Service listening on port: " + healthCheckPort);
                while (true) {
                    try (Socket socket = serverSocket.accept();
                         PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                        String request = in.readLine();
                        if ("PING".equals(request)) {
                            out.println("OK");
                        }
                    } catch (Exception e) {
                        System.err.println("Error in health check ping listener: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to start health check ping listener: " + e.getMessage());
            }
        }).start();
    }


}





