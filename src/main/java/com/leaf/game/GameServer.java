package com.leaf.game;

import java.io.*;
import java.net.*;

/**
 * MULTIPLAYER — HOST SIDE
 *
 * The host runs this. It opens a port and waits for one friend to connect.
 * Once connected, each player continuously sends their own position and reads
 * the other player's position. No central server — the host IS the server.
 *
 * Protocol: plain text, one message per line.
 *   "POS:x,y,z,yaw,pitch"  →  a player's current position
 *
 * Threading model:
 *   - start() spawns a background thread so the network never blocks the game loop.
 *   - The game loop reads remoteX/Y/Z etc. from the main thread.
 *   - 'volatile' ensures changes on the network thread are visible on the main thread.
 *     (You'll learn about this in a concurrency unit — for now just know it's required.)
 */
public class GameServer {

    public static final int PORT = 25565; // Same port Minecraft uses — easy to remember.

    // The friend's last known position. Read these from the game loop each frame.
    // 'volatile' = safe to read from one thread and write from another.
    public volatile float remoteX, remoteY, remoteZ;
    public volatile float remoteYaw, remotePitch;
    public volatile boolean friendConnected = false;

    private PrintWriter out;            // Stream to send data to friend
    private final Object writeLock = new Object(); // Prevent two threads writing at once

    /**
     * Call this once at startup (before the game loop).
     * It returns immediately — the actual waiting happens on a background thread.
     */
    public void start() {
        Thread serverThread = new Thread(() -> {
            try {
                // Open a socket that listens on our port.
                // backlog=1 means we only accept 1 queued connection (2-player game).
                ServerSocket serverSocket = new ServerSocket(PORT, 1);
                System.out.println("[Server] Waiting for friend on port " + PORT + "...");
                System.out.println("[Server] Tell your friend to run: join <your-local-ip>");

                // accept() BLOCKS until someone connects. That's fine — we're on a background thread.
                Socket friendSocket = serverSocket.accept();
                System.out.println("[Server] Friend connected from: " + friendSocket.getInetAddress());

                // Set up input/output streams for text communication
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(friendSocket.getInputStream()));
                synchronized (writeLock) {
                    out = new PrintWriter(
                            new OutputStreamWriter(friendSocket.getOutputStream()), true);
                    // 'true' = auto-flush: data sends immediately on println()
                }

                friendConnected = true;

                // Now just loop: read whatever the friend sends us.
                // Our position gets sent from the game loop via sendPosition().
                String line;
                while ((line = in.readLine()) != null) {
                    parseIncoming(line);
                }

                System.out.println("[Server] Friend disconnected.");
                friendConnected = false;

            } catch (IOException e) {
                System.err.println("[Server] Error: " + e.getMessage());
            }
        }, "server-thread");

        // Daemon = this thread won't prevent the JVM from shutting down when the game closes
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * Call this every frame from the game loop to send your current position.
     * Thread-safe: uses writeLock so the network thread can't interfere.
     */
    public void sendPosition(float x, float y, float z, float yaw, float pitch) {
        synchronized (writeLock) {
            if (out != null) {
                out.println("POS:" + x + "," + y + "," + z + "," + yaw + "," + pitch);
            }
        }
    }

    // Parse a "POS:x,y,z,yaw,pitch" message and update the remote player's position.
    private void parseIncoming(String line) {
        if (!line.startsWith("POS:")) return;
        String[] parts = line.substring(4).split(",");
        if (parts.length < 5) return;
        try {
            remoteX     = Float.parseFloat(parts[0]);
            remoteY     = Float.parseFloat(parts[1]);
            remoteZ     = Float.parseFloat(parts[2]);
            remoteYaw   = Float.parseFloat(parts[3]);
            remotePitch = Float.parseFloat(parts[4]);
        } catch (NumberFormatException ignored) {
            // Malformed packet — just skip it
        }
    }
}