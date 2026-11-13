package com.miniredis.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * A lightweight client for MiniRedis over TCP.
 * Can be used programmatically or launched from the command line.
 */
public class RedisClient implements AutoCloseable {

    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    public RedisClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public RedisClient() {
        this("127.0.0.1", 6379);
    }

    public synchronized void connect() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public synchronized void send(String command) throws IOException {
        ensureConnected();
        writer.write(command.trim() + "\r\n");
        writer.flush();
    }

    public synchronized String readLine() throws IOException {
        ensureConnected();
        return reader.readLine();
    }

    public synchronized String sendCommand(String command) throws IOException {
        send(command);
        return readLine();
    }

    private void ensureConnected() throws IOException {
        if (socket == null || socket.isClosed()) {
            connect();
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {}
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {}
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6379;

        System.out.println("Connecting to MiniRedis at " + host + ":" + port + "...");
        try (RedisClient client = new RedisClient(host, port);
             Scanner scanner = new Scanner(System.in)) {
            client.connect();
            System.out.println("Connected to MiniRedis! Type 'exit' or 'quit' to close.\n");

            while (true) {
                System.out.print(host + ":" + port + "> ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    client.send("QUIT");
                    String resp = client.readLine();
                    if (resp != null) {
                        System.out.println(resp);
                    }
                    break;
                }

                client.send(line);
                String resp = client.readLine();
                if (resp != null) {
                    System.out.println(resp);
                } else {
                    System.out.println("(Connection closed by server)");
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }
    }
}
