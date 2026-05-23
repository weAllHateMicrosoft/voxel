package com.leaf.game;

import java.io.*;
import java.net.*;

/**
 * MULTIPLAYER — CLIENT SIDE (the joining friend)
 *
 * Almost identical to GameServer in what it does once connected.
 * The only difference: instead of listening, it reaches out to the host's IP.
 *
 * Usage: GameClient client = new GameClient("192.168.1.42");
 *        client.connect();
 */
public class GameClient {

    private final String hostIp;
    private PrintWriter out;
    private final Object writeLock = new Object();

    // Same public fields as GameServer — the game loop reads these
    public volatile float remoteX, remoteY, remoteZ;
    public volatile float remoteYaw, remotePitch;
    public volatile boolean connected = false;

    public GameClient(String hostIp) {
        this.hostIp = hostIp;
    }

    /**
     * Call once at startup. Returns immediately; network runs on background thread.
     */
    public void connect() {
        Thread clientThread = new Thread(() -> {
            try {
                System.out.println("[Client] Connecting to " + hostIp + ":" + GameServer.PORT + "...");
                Socket socket = new Socket(hostIp, GameServer.PORT);
                System.out.println("[Client] Connected!");

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                synchronized (writeLock) {
                    out = new PrintWriter(
                            new OutputStreamWriter(socket.getOutputStream()), true);
                }

                connected = true;

                String line;
                while ((line = in.readLine()) != null) {
                    parseIncoming(line);
                }

                System.out.println("[Client] Disconnected from host.");
                connected = false;

            } catch (IOException e) {
                System.err.println("[Client] Could not connect: " + e.getMessage());
                System.err.println("[Client] Is the host running? Is the IP correct?");
            }
        }, "client-thread");

        clientThread.setDaemon(true);
        clientThread.start();
    }

    /** Send our position to the host. Call every frame from the game loop. */
    public void sendPosition(float x, float y, float z, float yaw, float pitch) {
        synchronized (writeLock) {
            if (out != null) {
                out.println("POS:" + x + "," + y + "," + z + "," + yaw + "," + pitch);
            }
        }
    }

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
        } catch (NumberFormatException ignored) {}
    }
}