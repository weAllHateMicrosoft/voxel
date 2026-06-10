package com.leaf.game.net;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.Block;

import java.io.*;
import java.net.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NetworkSession {

    private static final int PORT = 25566;

    public volatile float   remoteX, remoteY, remoteZ;
    public volatile float   remoteYaw, remotePitch, remoteRoll;
    public volatile int     remoteState = 0;
    public volatile boolean remoteHooked = false;
    public volatile float   remoteHookX, remoteHookY, remoteHookZ;
    public volatile float   remoteHealth = 1f, remoteMaxHealth = 1f;

    /** Latest snapshot of the PEER's troops, packed [x,y,z, x,y,z, ...] (opponent team). */
    public volatile float[] remoteSummons = new float[0];

    public volatile boolean connected = false;
    public volatile boolean seedReceived = false;
    public volatile long    newSeed = 0;

    private final Queue<int[]>  incomingBreaks  = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>  incomingPlaces  = new ConcurrentLinkedQueue<>();
    private final Queue<String> incomingChats   = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>  incomingPickups = new ConcurrentLinkedQueue<>();
    private final Queue<int[]>   incomingCraters = new ConcurrentLinkedQueue<>();
    private final Queue<Float>   incomingDamage  = new ConcurrentLinkedQueue<>();
    private final Queue<float[]> incomingFx      = new ConcurrentLinkedQueue<>();   // ability VFX events
    private final Queue<float[]> incomingDeaths  = new ConcurrentLinkedQueue<>();   // "I died at x,y,z"

    private final boolean isHost;
    private final String  hostIp;
    private DataOutputStream out;
    private final Object writeLock = new Object();

    public NetworkSession(boolean isHost, String hostIp) {
        this.isHost = isHost;
        this.hostIp = hostIp;
    }

    public void start() {
        Thread t = new Thread(this::runNetworkLoop, "network-thread");
        t.setDaemon(true);
        t.start();
    }

    private void runNetworkLoop() {
        try {
            Socket socket = isHost ? waitForConnection() : connectToHost();
            if (socket == null) return;

            // CRITICAL SYNC FIX: Disable TCP batching to prevent network stutter
            socket.setTcpNoDelay(true);

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            synchronized (writeLock) {
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            }

            connected = true;
            System.out.println("[Net] Connected via Binary Protocol!");

            if (isHost) sendSeed(GameConfig.seed);

            // Binary Read Loop (Zero String-GC overhead)
            while (true) {
                byte packetId = in.readByte();
                handleIncoming(packetId, in);
            }

        } catch (EOFException e) {
            System.out.println("[Net] Disconnected.");
        } catch (IOException e) {
            System.err.println("[Net] Connection error: " + e.getMessage());
        } catch (Throwable t) {
            // A bug in a packet handler must NOT silently kill sync while leaving us
            // "connected" (the symptom: remote freezes, chat/damage stop, but we still
            // think we're online). Log it and fall through to mark disconnected.
            System.err.println("[Net] Read loop crashed: " + t);
            t.printStackTrace();
        } finally {
            connected = false;   // ALWAYS reflect the dead loop (no zombie connection)
        }
    }

    private Socket waitForConnection() throws IOException {
        System.out.println("[Net] Hosting on port " + PORT + " — waiting for friend...");
        try (ServerSocket serverSocket = new ServerSocket(PORT, 1)) {
            return serverSocket.accept();
        }
    }

    private Socket connectToHost() {
        System.out.println("[Net] Connecting to " + hostIp + ":" + PORT + " ...");
        try { return new Socket(hostIp, PORT); } catch (IOException e) { return null; }
    }

    private void handleIncoming(byte id, DataInputStream in) throws IOException {
        switch (id) {
            case 1: // POS
                remoteX = in.readFloat(); remoteY = in.readFloat(); remoteZ = in.readFloat();
                remoteYaw = in.readFloat(); remotePitch = in.readFloat(); remoteRoll = in.readFloat();
                break;
            case 2: // STATE
                remoteState = in.readByte();
                break;
            case 3: // GRAPPLE
                remoteHooked = in.readBoolean();
                remoteHookX = in.readFloat(); remoteHookY = in.readFloat(); remoteHookZ = in.readFloat();
                break;
            case 4: // BREAK
                incomingBreaks.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
                break;
            case 5: // PLACE
                incomingPlaces.add(new int[]{in.readInt(), in.readInt(), in.readInt(), in.readInt()});
                break;
            case 6: // CHAT
                incomingChats.add(in.readUTF());
                break;
            case 7: // PICKUP
                incomingPickups.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
                break;
            case 8: // CRATER
                incomingCraters.add(new int[]{in.readInt(), in.readInt(), in.readInt(), in.readInt()});
                break;
            case 9: // SEED
                newSeed = in.readLong();
                seedReceived = true;
                break;
            case 10: // HEALTH
                remoteHealth    = in.readFloat();
                remoteMaxHealth = in.readFloat();
                break;
            case 11: // DAMAGE (PvP / troop hit relayed to us)
                incomingDamage.add(in.readFloat());
                break;
            case 12: { // SUMMONS snapshot (peer's troops)
                int n = in.readUnsignedByte();
                float[] arr = new float[n * 3];
                for (int i = 0; i < arr.length; i++) arr[i] = in.readFloat();
                remoteSummons = arr;
                break;
            }
            case 13: { // ABILITY VFX event: [type, x,y,z, dx,dy,dz, roll,sweep, life, s0,s1, r,g,b]
                float[] f = new float[15];
                f[0] = in.readUnsignedByte();
                for (int i = 1; i < 15; i++) f[i] = in.readFloat();
                incomingFx.add(f);
                break;
            }
            case 14: // DEATH (peer was eliminated at x,y,z)
                incomingDeaths.add(new float[]{ in.readFloat(), in.readFloat(), in.readFloat() });
                break;
            default:
                // An unknown id means the byte stream has DESYNCED (a sender wrote a
                // packet a reader didn't fully consume). We can't recover the framing,
                // so throw to drop the connection cleanly rather than read garbage.
                throw new IOException("Desync: unknown packet id " + id);
        }
    }

    // --- High-Performance Binary Senders ---
    public void sendPosition(float x, float y, float z, float yaw, float pitch, float roll) {
        synchronized (writeLock) {
            try { if (out == null) return;
                out.writeByte(1); out.writeFloat(x); out.writeFloat(y); out.writeFloat(z);
                out.writeFloat(yaw); out.writeFloat(pitch); out.writeFloat(roll); out.flush();
            } catch (IOException ignored) {}
        }
    }

    public void sendState(int state) {
        synchronized (writeLock) {
            try { if (out == null) return; out.writeByte(2); out.writeByte((byte)state); out.flush(); } catch (IOException ignored) {}
        }
    }

    public void sendGrapple(boolean hooked, float hx, float hy, float hz) {
        synchronized (writeLock) {
            try { if (out == null) return; out.writeByte(3); out.writeBoolean(hooked);
                out.writeFloat(hx); out.writeFloat(hy); out.writeFloat(hz); out.flush();
            } catch (IOException ignored) {}
        }
    }

    public void sendBreak(int x, int y, int z) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(4); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendPlace(int x, int y, int z, Block b) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(5); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeInt(b.ordinal()); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendChat(String msg) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(6); out.writeUTF(msg); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendPickup(int x, int y, int z) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(7); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendCrater(int x, int y, int z, int r) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(8); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeInt(r); out.flush(); } catch (IOException ignored) {} }
    }
    private void sendSeed(long seed) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(9); out.writeLong(seed); out.flush(); } catch (IOException ignored) {} }
    }
    public void sendHealth(float hp, float maxHp) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(10); out.writeFloat(hp); out.writeFloat(maxHp); out.flush(); } catch (IOException ignored) {} }
    }
    /** Tell the peer to take {@code amount} damage (our ability/troop hit them). */
    public void sendDamage(float amount) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(11); out.writeFloat(amount); out.flush(); } catch (IOException ignored) {} }
    }
    /** Broadcast an ability VFX event so the peer sees it. {@code p} = 14 floats
     *  [x,y,z, dx,dy,dz, roll,sweep, life, s0,s1, r,g,b]. */
    public void sendFx(int type, float[] p) {
        synchronized (writeLock) {
            try {
                if (out == null) return;
                out.writeByte(13); out.writeByte(type);
                for (int i = 0; i < 14; i++) out.writeFloat(p[i]);
                out.flush();
            } catch (IOException ignored) {}
        }
    }
    /** Tell the peer we were eliminated (drives their kill banner + sound). */
    public void sendDeath(float x, float y, float z) {
        synchronized (writeLock) { try { if (out == null) return; out.writeByte(14); out.writeFloat(x); out.writeFloat(y); out.writeFloat(z); out.flush(); } catch (IOException ignored) {} }
    }
    /** Stream our troops' positions (count ≤ 255), packed [x,y,z,...]. */
    public void sendSummons(float[] packed, int count) {
        synchronized (writeLock) {
            try {
                if (out == null) return;
                out.writeByte(12); out.writeByte(count);
                for (int i = 0; i < count * 3; i++) out.writeFloat(packed[i]);
                out.flush();
            } catch (IOException ignored) {}
        }
    }

    // Queue Pollers (unchanged)
    public int[] pollBreak() { return incomingBreaks.poll(); }
    public int[] pollPlace() { return incomingPlaces.poll(); }
    public String pollChat() { return incomingChats.poll(); }
    public int[] pollPickup() { return incomingPickups.poll(); }
    public int[] pollCrater() { return incomingCraters.poll(); }
    public Float   pollDamage() { return incomingDamage.poll(); }
    public float[] pollFx()     { return incomingFx.poll(); }
    public float[] pollDeath()  { return incomingDeaths.poll(); }
    public boolean isHost() {return isHost; }
}