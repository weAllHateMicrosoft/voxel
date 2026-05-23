// --- FILE: src/main/java/com/leaf/game/world/World.java ---
package com.leaf.game.world;

import com.leaf.game.render.Mesh;
import com.leaf.game.entity.Player;
import com.leaf.game.core.GameConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class World {
    public static final int WIDTH  = 128;
    public static final int HEIGHT = 64;
    public static final int DEPTH  = 128;

    private final HashMap<Long, Chunk> chunks = new HashMap<>();

    // ── SAVING SYSTEM: Tracking modified blocks ──
    // Maps ChunkKey -> (Local Block Index -> Block Type)
    private final Map<Long, Map<Integer, Block>> modifiedBlocks = new HashMap<>();

    public World() {}

    public Map<Long, Map<Integer, Block>> getModifiedBlocksMap() {
        return modifiedBlocks;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public Chunk getChunk(int cx, int cz) { return chunks.get(chunkKey(cx, cz)); }

    public Chunk getOrCreateChunk(int cx, int cz) {
        return chunks.computeIfAbsent(chunkKey(cx, cz), k -> new Chunk(cx, cz));
    }

    public Block getBlock(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return Block.AIR;
        int cx = Math.floorDiv(wx, Chunk.SIZE), cz = Math.floorDiv(wz, Chunk.SIZE);
        Chunk chunk = getChunk(cx, cz);
        if (chunk == null) return Block.AIR;
        int lx = Math.floorMod(wx, Chunk.SIZE), lz = Math.floorMod(wz, Chunk.SIZE);
        return chunk.getBlock(lx, wy, lz);
    }

    // Called by Player/Network when placing/breaking
    public void setBlock(int wx, int wy, int wz, Block b) {
        if (wy < 0 || wy >= Chunk.HEIGHT) return;
        int cx = Math.floorDiv(wx, Chunk.SIZE), cz = Math.floorDiv(wz, Chunk.SIZE);
        Chunk chunk = getOrCreateChunk(cx, cz);
        int lx = Math.floorMod(wx, Chunk.SIZE), lz = Math.floorMod(wz, Chunk.SIZE);

        chunk.setBlock(lx, wy, lz, b);
        chunk.dirty = true;

        // Record this modification so we can save it!
        int localIdx = (wy << 8) | (lz << 4) | lx;
        modifiedBlocks.computeIfAbsent(chunkKey(cx, cz), k -> new HashMap<>()).put(localIdx, b);
    }

    public java.util.Collection<Chunk> getAllChunks() { return chunks.values(); }
    public void clearAllChunks() { chunks.clear(); }

    public void updateChunks(World world, WorldGen gen, Player player) {
        int RENDER_DISTANCE = GameConfig.renderDistance;
        int playerCX = Math.floorDiv((int) player.position.x, Chunk.SIZE);
        int playerCZ = Math.floorDiv((int) player.position.z, Chunk.SIZE);

        for (int dx = -RENDER_DISTANCE; dx <= RENDER_DISTANCE; dx++) {
            for (int dz = -RENDER_DISTANCE; dz <= RENDER_DISTANCE; dz++) {
                int cx = playerCX + dx;
                int cz = playerCZ + dz;

                if (world.getChunk(cx, cz) == null) {
                    Chunk chunk = world.getOrCreateChunk(cx, cz);
                    gen.generateChunk(chunk);

                    // ── APPLY LOADED SAVED BLOCKS TO NEW CHUNKS ──
                    Map<Integer, Block> mods = modifiedBlocks.get(chunkKey(cx, cz));
                    if (mods != null) {
                        for (Map.Entry<Integer, Block> entry : mods.entrySet()) {
                            int idx = entry.getKey();
                            int lx = idx & 15;
                            int lz = (idx >> 4) & 15;
                            int ly = (idx >> 8) & 255;
                            chunk.setBlock(lx, ly, lz, entry.getValue());
                        }
                    }

                    Chunk nX = world.getChunk(cx + 1, cz); if (nX != null) nX.dirty = true;
                    Chunk pX = world.getChunk(cx - 1, cz); if (pX != null) pX.dirty = true;
                    Chunk nZ = world.getChunk(cx, cz + 1); if (nZ != null) nZ.dirty = true;
                    Chunk pZ = world.getChunk(cx, cz - 1); if (pZ != null) pZ.dirty = true;
                }
            }
        }

        world.chunks.entrySet().removeIf(entry -> {
            Chunk c = entry.getValue();
            int dx = Math.abs(c.cx - playerCX), dz = Math.abs(c.cz - playerCZ);
            if (dx > RENDER_DISTANCE + 2 || dz > RENDER_DISTANCE + 2) {
                if (c.mesh != null) c.mesh.cleanup();
                return true;
            }
            return false;
        });
    }

    public Mesh buildChunkMesh(Chunk chunk) {
        List<Float>   verts   = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int vertexIndex = 0;
        int worldXStart = chunk.cx * Chunk.SIZE;
        int worldZStart = chunk.cz * Chunk.SIZE;

        for (int x = 0; x < Chunk.SIZE;  x++) {
            for (int y = 0; y < Chunk.HEIGHT; y++) {
                for (int z = 0; z < Chunk.SIZE;  z++) {
                    int wx = worldXStart + x, wz = worldZStart + z;
                    Block block = chunk.getBlock(x, y, z);
                    if (!block.isSolid()) continue;

                    if (!getBlock(wx, y + 1, wz).isSolid()) {
                        float[] ao = { computeAO(wx-1,y+1,wz, wx,y+1,wz-1, wx-1,y+1,wz-1), computeAO(wx+1,y+1,wz, wx,y+1,wz-1, wx+1,y+1,wz-1), computeAO(wx+1,y+1,wz, wx,y+1,wz+1, wx+1,y+1,wz+1), computeAO(wx-1,y+1,wz, wx,y+1,wz+1, wx-1,y+1,wz+1) };
                        addFace(verts, indices, vertexIndex, topFace(wx, y, wz), block, ao,  0f, 1f, 0f); vertexIndex += 4;
                    }
                    if (!getBlock(wx, y - 1, wz).isSolid()) {
                        float[] ao = { computeAO(wx-1,y-1,wz, wx,y-1,wz+1, wx-1,y-1,wz+1), computeAO(wx+1,y-1,wz, wx,y-1,wz+1, wx+1,y-1,wz+1), computeAO(wx+1,y-1,wz, wx,y-1,wz-1, wx+1,y-1,wz-1), computeAO(wx-1,y-1,wz, wx,y-1,wz-1, wx-1,y-1,wz-1) };
                        addFace(verts, indices, vertexIndex, bottomFace(wx, y, wz), block, ao,  0f, -1f, 0f); vertexIndex += 4;
                    }
                    if (!getBlock(wx, y, wz + 1).isSolid()) {
                        float[] ao = { computeAO(wx-1,y,wz+1, wx,y-1,wz+1, wx-1,y-1,wz+1), computeAO(wx+1,y,wz+1, wx,y-1,wz+1, wx+1,y-1,wz+1), computeAO(wx+1,y,wz+1, wx,y+1,wz+1, wx+1,y+1,wz+1), computeAO(wx-1,y,wz+1, wx,y+1,wz+1, wx-1,y+1,wz+1) };
                        addFace(verts, indices, vertexIndex, frontFace(wx, y, wz), block, ao,  0f, 0f, 1f); vertexIndex += 4;
                    }
                    if (!getBlock(wx, y, wz - 1).isSolid()) {
                        float[] ao = { computeAO(wx+1,y,wz-1, wx,y-1,wz-1, wx+1,y-1,wz-1), computeAO(wx-1,y,wz-1, wx,y-1,wz-1, wx-1,y-1,wz-1), computeAO(wx-1,y,wz-1, wx,y+1,wz-1, wx-1,y+1,wz-1), computeAO(wx+1,y,wz-1, wx,y+1,wz-1, wx+1,y+1,wz-1) };
                        addFace(verts, indices, vertexIndex, backFace(wx, y, wz), block, ao,  0f, 0f, -1f); vertexIndex += 4;
                    }
                    if (!getBlock(wx + 1, y, wz).isSolid()) {
                        float[] ao = { computeAO(wx+1,y-1,wz, wx+1,y,wz+1, wx+1,y-1,wz+1), computeAO(wx+1,y-1,wz, wx+1,y,wz-1, wx+1,y-1,wz-1), computeAO(wx+1,y+1,wz, wx+1,y,wz-1, wx+1,y+1,wz-1), computeAO(wx+1,y+1,wz, wx+1,y,wz+1, wx+1,y+1,wz+1) };
                        addFace(verts, indices, vertexIndex, rightFace(wx, y, wz), block, ao,  1f, 0f, 0f); vertexIndex += 4;
                    }
                    if (!getBlock(wx - 1, y, wz).isSolid()) {
                        float[] ao = { computeAO(wx-1,y-1,wz, wx-1,y,wz-1, wx-1,y-1,wz-1), computeAO(wx-1,y-1,wz, wx-1,y,wz+1, wx-1,y-1,wz+1), computeAO(wx-1,y+1,wz, wx-1,y,wz+1, wx-1,y+1,wz+1), computeAO(wx-1,y+1,wz, wx-1,y,wz-1, wx-1,y+1,wz-1) };
                        addFace(verts, indices, vertexIndex, leftFace(wx, y, wz), block, ao,  -1f, 0f, 0f); vertexIndex += 4;
                    }
                }
            }
        }

        if (verts.isEmpty()) return null;
        float[] vertArray = new float[verts.size()]; for (int i = 0; i < verts.size(); i++) vertArray[i] = verts.get(i);
        int[] idxArray = new int[indices.size()]; for (int i = 0; i < indices.size(); i++) idxArray[i] = indices.get(i);
        return new Mesh(vertArray, idxArray);
    }

    private void addFace(List<Float> verts, List<Integer> indices, int baseIndex, float[] faceVertices, Block block, float[] ao, float nx, float ny, float nz) {
        for (int i = 0; i < 4; i++) {
            verts.add(faceVertices[i * 3]); verts.add(faceVertices[i * 3 + 1]); verts.add(faceVertices[i * 3 + 2]);
            verts.add(block.r * ao[i]); verts.add(block.g * ao[i]); verts.add(block.b * ao[i]);
            verts.add(nx); verts.add(ny); verts.add(nz);
        }
        if (ao[0] + ao[2] > ao[1] + ao[3]) {
            indices.add(baseIndex); indices.add(baseIndex + 1); indices.add(baseIndex + 2); indices.add(baseIndex + 2); indices.add(baseIndex + 3); indices.add(baseIndex);
        } else {
            indices.add(baseIndex); indices.add(baseIndex + 1); indices.add(baseIndex + 3); indices.add(baseIndex + 1); indices.add(baseIndex + 2); indices.add(baseIndex + 3);
        }
    }

    private float[] topFace(int x, int y, int z) { return new float[] { x, y+1, z,  x+1, y+1, z,  x+1, y+1, z+1,  x, y+1, z+1 }; }
    private float[] bottomFace(int x, int y, int z) { return new float[] { x, y, z+1,  x+1, y, z+1,  x+1, y, z,  x, y, z }; }
    private float[] frontFace(int x, int y, int z) { return new float[] { x, y, z+1,  x+1, y, z+1,  x+1, y+1, z+1,  x, y+1, z+1 }; }
    private float[] backFace(int x, int y, int z) { return new float[] { x+1, y, z,  x, y, z,  x, y+1, z,  x+1, y+1, z }; }
    private float[] rightFace(int x, int y, int z) { return new float[] { x+1, y, z+1,  x+1, y, z,  x+1, y+1, z,  x+1, y+1, z+1 }; }
    private float[] leftFace(int x, int y, int z) { return new float[] { x, y, z,  x, y, z+1,  x, y+1, z+1,  x, y+1, z }; }

    private static final float AO_STRENGTH = 0.2f;
    private float computeAO(int s1x, int s1y, int s1z, int s2x, int s2y, int s2z, int cx, int cy, int cz) {
        boolean s1 = getBlock(s1x, s1y, s1z).isSolid(), s2 = getBlock(s2x, s2y, s2z).isSolid(), co = getBlock( cx,  cy,  cz).isSolid();
        if (s1 && s2) return 1.0f - 3 * AO_STRENGTH;
        return 1.0f - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (co ? 1 : 0)) * AO_STRENGTH;
    }
}