// --- FILE: src/main/java/com/leaf/game/world/World.java ---
package com.leaf.game.world;

import com.leaf.game.entity.Player;
import com.leaf.game.core.GameConfig;
import com.leaf.game.world.gen.WorldGen;
import com.leaf.game.render.ChunkMesher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class World {
    public static final int WIDTH  = 128;
    public static final int HEIGHT = 512;
    public static final int DEPTH  = 128;

    private final HashMap<Long, Chunk> chunks = new HashMap<>();
    private final Map<Long, Map<Integer, Block>> modifiedBlocks = new HashMap<>();

    private final Set<Long> activeLiquids = ConcurrentHashMap.newKeySet();
    private float fluidTimer = 0.0f;

    public World() {}

    public Map<Long, Map<Integer, Block>> getModifiedBlocksMap() { return modifiedBlocks; }

    // ── Chunk key encoding ────────────────────────────────────────────────────
    // 3D key: 21 bits each for cx, cy, cz — handles ±1M chunks per axis.
    // worldY of any block = chunk.cy * Chunk.HEIGHT + localY.
    private static long chunkKey(int cx, int cy, int cz) {
        return ((long)(cx & 0x1FFFFF) << 42)
             | ((long)(cy & 0x1FFFFF) << 21)
             |  (long)(cz & 0x1FFFFF);
    }
    // 2D surface key kept for modifiedBlocks (player edits are surface-only)
    private static long chunkKey2D(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    // ── 3D chunk access ───────────────────────────────────────────────────────
    public Chunk getChunk(int cx, int cy, int cz) { return chunks.get(chunkKey(cx, cy, cz)); }
    /** Backward-compatible overload — assumes surface chunk (cy=0). */
    public Chunk getChunk(int cx, int cz) { return getChunk(cx, 0, cz); }

    public Chunk getOrCreateChunk(int cx, int cy, int cz) {
        return chunks.computeIfAbsent(chunkKey(cx, cy, cz), k -> new Chunk(cx, cy, cz));
    }
    /** Backward-compatible overload — creates surface chunk (cy=0). */
    public Chunk getOrCreateChunk(int cx, int cz) { return getOrCreateChunk(cx, 0, cz); }

    // ── Block access (routes to the correct vertical chunk) ───────────────────
    public Block getBlock(int wx, int wy, int wz) {
        int cy = Math.floorDiv(wy, Chunk.HEIGHT);
        int ly = Math.floorMod(wy, Chunk.HEIGHT);
        Chunk chunk = getChunk(Math.floorDiv(wx, Chunk.SIZE), cy, Math.floorDiv(wz, Chunk.SIZE));
        if (chunk == null) return Block.AIR;
        return chunk.getBlock(Math.floorMod(wx, Chunk.SIZE), ly, Math.floorMod(wz, Chunk.SIZE));
    }

    public byte getMeta(int wx, int wy, int wz) {
        int cy = Math.floorDiv(wy, Chunk.HEIGHT);
        int ly = Math.floorMod(wy, Chunk.HEIGHT);
        Chunk chunk = getChunk(Math.floorDiv(wx, Chunk.SIZE), cy, Math.floorDiv(wz, Chunk.SIZE));
        if (chunk == null) return 0;
        return chunk.getMeta(Math.floorMod(wx, Chunk.SIZE), ly, Math.floorMod(wz, Chunk.SIZE));
    }

    public void setBlock(int wx, int wy, int wz, Block b) {
        setBlockWithMeta(wx, wy, wz, b, (byte)0, true);
    }

    public void setBlockWithMeta(int wx, int wy, int wz, Block b, byte meta, boolean triggerUpdate) {
        int cy = Math.floorDiv(wy, Chunk.HEIGHT);
        int ly = Math.floorMod(wy, Chunk.HEIGHT);
        // Safety guard: don't allocate chunks absurdly far from the surface
        if (cy < -32 || cy > 4) return;
        int cx = Math.floorDiv(wx, Chunk.SIZE), cz = Math.floorDiv(wz, Chunk.SIZE);
        Chunk chunk = getOrCreateChunk(cx, cy, cz);
        int lx = Math.floorMod(wx, Chunk.SIZE), lz = Math.floorMod(wz, Chunk.SIZE);

        chunk.setBlock(lx, ly, lz, b);
        chunk.setMeta(lx, ly, lz, meta);
        chunk.dirty = true;

        // Track player-placed block modifications (surface world only)
        if (cy == 0) {
            int localIdx = (ly << 8) | (lz << 4) | lx;
            modifiedBlocks.computeIfAbsent(chunkKey2D(cx, cz), k -> new HashMap<>()).put(localIdx, b);
        }

        if (triggerUpdate) {
            scheduleFluidUpdate(wx, wy, wz);
            scheduleFluidUpdate(wx + 1, wy, wz); scheduleFluidUpdate(wx - 1, wy, wz);
            scheduleFluidUpdate(wx, wy + 1, wz); scheduleFluidUpdate(wx, wy - 1, wz);
            scheduleFluidUpdate(wx, wy, wz + 1); scheduleFluidUpdate(wx, wy, wz - 1);
        }
    }

    public java.util.Collection<Chunk> getAllChunks() { return chunks.values(); }
    public void clearAllChunks() { chunks.clear(); activeLiquids.clear(); }

    public void updateChunks(World world, WorldGen gen, Player player) {
        int RENDER_DISTANCE = GameConfig.renderDistance;
        int playerCX = Math.floorDiv((int) player.position.x, Chunk.SIZE);
        int playerCZ = Math.floorDiv((int) player.position.z, Chunk.SIZE);
        // Which vertical chunk-slab the player is currently in
        int playerCY = Math.floorDiv((int) player.position.y, Chunk.HEIGHT);

        for (int dx = -RENDER_DISTANCE; dx <= RENDER_DISTANCE; dx++) {
            for (int dz = -RENDER_DISTANCE; dz <= RENDER_DISTANCE; dz++) {
                int cx = playerCX + dx;
                int cz = playerCZ + dz;

                // Always load the surface chunk (cy=0)
                loadChunkIfNeeded(world, gen, cx, 0, cz);

                // For columns that overlap the Abyss zone, also generate the deep
                // chunks below.  We load two chunk-heights ahead of the player so
                // the shaft is always ready before they arrive.
                if (gen.isChunkInAbyssZone(cx, cz)) {
                    int deepestNeeded = Math.min(playerCY - 2, -1);  // always at least -1
                    for (int cy = -1; cy >= deepestNeeded; cy--) {
                        loadChunkIfNeeded(world, gen, cx, cy, cz);
                    }
                }
            }
        }
    }

    /**
     * Generates and initialises a chunk at (cx, cy, cz) if it does not yet exist.
     * Applies any saved player-block modifications (surface chunks only),
     * schedules water updates, and marks horizontal + vertical neighbours dirty.
     */
    private void loadChunkIfNeeded(World world, WorldGen gen, int cx, int cy, int cz) {
        if (world.getChunk(cx, cy, cz) != null) return;

        Chunk chunk = world.getOrCreateChunk(cx, cy, cz);
        gen.generateChunk(chunk);

        // Restore player-placed block edits (only tracked for cy=0)
        if (cy == 0) {
            Map<Integer, Block> mods = modifiedBlocks.get(chunkKey2D(cx, cz));
            if (mods != null) {
                for (Map.Entry<Integer, Block> entry : mods.entrySet()) {
                    int idx = entry.getKey();
                    chunk.setBlock(idx & 15, (idx >> 8) & 1023, (idx >> 4) & 15, entry.getValue());
                }
            }

            // Schedule fluid updates for exposed water (surface world only)
            int worldX = cx * Chunk.SIZE;
            int worldZ = cz * Chunk.SIZE;
            for (int lx = 0; lx < Chunk.SIZE; lx++) {
                for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                    for (int lz = 0; lz < Chunk.SIZE; lz++) {
                        if (chunk.getBlock(lx, ly, lz) == Block.WATER) {
                            boolean exposed = false;
                            if (lx == 0 || lx == Chunk.SIZE - 1 || lz == 0 || lz == Chunk.SIZE - 1) {
                                exposed = true;
                            } else if (chunk.getBlock(lx+1, ly, lz) == Block.AIR ||
                                    chunk.getBlock(lx-1, ly, lz) == Block.AIR ||
                                    chunk.getBlock(lx, ly, lz+1) == Block.AIR ||
                                    chunk.getBlock(lx, ly, lz-1) == Block.AIR ||
                                    (ly > 0 && chunk.getBlock(lx, ly-1, lz) == Block.AIR)) {
                                exposed = true;
                            }
                            if (exposed) world.scheduleFluidUpdate(worldX + lx, ly, worldZ + lz);
                        }
                    }
                }
            }
        }

        // Mark horizontal and vertical neighbours dirty so their meshes update
        Chunk nX = world.getChunk(cx + 1, cy, cz); if (nX != null) nX.dirty = true;
        Chunk pX = world.getChunk(cx - 1, cy, cz); if (pX != null) pX.dirty = true;
        Chunk nZ = world.getChunk(cx, cy, cz + 1); if (nZ != null) nZ.dirty = true;
        Chunk pZ = world.getChunk(cx, cy, cz - 1); if (pZ != null) pZ.dirty = true;
        Chunk uY = world.getChunk(cx, cy + 1, cz); if (uY != null) uY.dirty = true;
        Chunk dY = world.getChunk(cx, cy - 1, cz); if (dY != null) dY.dirty = true;
    }

    public void scheduleFluidUpdate(int wx, int wy, int wz) {
        if (wy >= 0 && wy < Chunk.HEIGHT) activeLiquids.add(packPos(wx, wy, wz));
    }

    public void tickLiquids(float deltaTime) {
        fluidTimer += deltaTime;
        if (fluidTimer < 0.40f) return;
        fluidTimer = 0;

        if (activeLiquids.isEmpty()) return;
        List<Long> currentQueue = new ArrayList<>(activeLiquids);
        activeLiquids.clear();

        for (long p : currentQueue) {
            int wx = unpackX(p), wy = unpackY(p), wz = unpackZ(p);
            if (getBlock(wx, wy, wz) == Block.WATER) processWaterFlow(wx, wy, wz);
        }
    }

    private void processWaterFlow(int wx, int wy, int wz) {
        byte meta = getMeta(wx, wy, wz);

        if (meta > 0) {
            boolean validSource = false;
            if (getBlock(wx, wy + 1, wz) == Block.WATER) validSource = true;
            else if (isValidSource(wx + 1, wy, wz, meta) || isValidSource(wx - 1, wy, wz, meta) ||
                    isValidSource(wx, wy, wz + 1, meta) || isValidSource(wx, wy, wz - 1, meta)) {
                validSource = true;
            }
            if (!validSource) {
                setBlockWithMeta(wx, wy, wz, Block.AIR, (byte)0, true);
                return;
            }
        }

        Block below = getBlock(wx, wy - 1, wz);
        if (below == Block.AIR) {
            setBlockWithMeta(wx, wy - 1, wz, Block.WATER, (byte) 1, true);
            return;
        }

        if (meta < 7 && below.isSolid()) {
            byte nextMeta = (byte) (meta + 1);
            tryFlowOut(wx + 1, wy, wz, nextMeta);
            tryFlowOut(wx - 1, wy, wz, nextMeta);
            tryFlowOut(wx, wy, wz + 1, nextMeta);
            tryFlowOut(wx, wy, wz - 1, nextMeta);
        }
    }

    private boolean isValidSource(int wx, int wy, int wz, byte currentMeta) {
        return getBlock(wx, wy, wz) == Block.WATER && getMeta(wx, wy, wz) < currentMeta;
    }

    private void tryFlowOut(int wx, int wy, int wz, byte nextMeta) {
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        if (getChunk(cx, cz) == null) return;

        Block b = getBlock(wx, wy, wz);
        if (b == Block.AIR || (b == Block.WATER && getMeta(wx, wy, wz) > nextMeta)) {
            setBlockWithMeta(wx, wy, wz, Block.WATER, nextMeta, true);
        }
    }

    // ── DECOUPLED MESH TRIGGER (Redirects to specialized ChunkMesher) ──
    public void buildChunkMeshes(Chunk chunk) {
        ChunkMesher.buildChunkMeshes(this, chunk);
    }

    private long packPos(int x, int y, int z) {
        return ((long)(x & 0x3FFFFFF) << 38) | ((long)(z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
    private int unpackX(long p) { return (int) (p >> 38); }
    private int unpackZ(long p) { return (int) ((p << 26) >> 38); }
    private int unpackY(long p) { return (int) (p & 0xFFF); }
}