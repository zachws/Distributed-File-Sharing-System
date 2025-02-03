package com.distributed.filesharing;

import java.io.*;
import java.net.*;
import java.util.*;

public class UpdatedFile {
    private static final int PORT = 12345;
    private static final String DIRECTORY = "shared_files";

    public static void main(String[] args) {
        try {
            ServerSocket serverSocket = new ServerSocket(PORT);
            System.out.println("Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);

                String request = in.readLine();
                if (request.startsWith("GET")) {
                    String fileName = request.substring(4);
                    File file = new File(DIRECTORY, fileName);
                    if (file.exists() && !file.isDirectory()) {
                        out.println("OK");
                        sendFile(file, clientSocket.getOutputStream());
                    } else {
                        out.println("File not found");
                    }
                } else if (request.startsWith("PUT")) {
                    String fileName = request.substring(4);
                    File file = new File(DIRECTORY, fileName);
                    receiveFile(file, clientSocket.getInputStream());
                    out.println("File uploaded");
                } else {
                    out.println("Invalid request");
                }

                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void sendFile(File file, OutputStream out) throws IOException {
            byte[] buffer = new byte[4096];
            FileInputStream fis = new FileInputStream(file);
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            fis.close();
        }

        private void receiveFile(File file, InputStream in) throws IOException {
            byte[] buffer = new byte[4096];
            FileOutputStream fos = new FileOutputStream(file);
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.close();
        }
    }
}
