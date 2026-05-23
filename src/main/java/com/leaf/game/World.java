package com.leaf.game;

import java.util.ArrayList;
import java.util.List;

public class World {

    public static final int WIDTH  = 128;  // X axis
    public static final int HEIGHT =64;  // Y axis (up)
    public static final int DEPTH  = 128;  // Z axis

    private static final int TERRAIN_HEIGHT = 8; // y where grass appears

    private final java.util.HashMap<Long, Chunk> chunks = new java.util.HashMap<>();

    private final WorldGen generator;

    public World() {
        this.generator = new WorldGen(); // Generates with random seed based on system time
    }


    // --- CHUNK ACCESS ---
    // Pack (cx, cz) into a single long key.
    // cx >> 32 moves cx 32 places to the left
    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // Get a loaded chunk, or null if not loaded
    public Chunk getChunk(int cx, int cz) {
        return chunks.get(chunkKey(cx, cz));
    }

    // Get or create a chunk at (cx, cz)
    public Chunk getOrCreateChunk(int cx, int cz) {
        return chunks.computeIfAbsent(chunkKey(cx, cz), k -> new Chunk(cx, cz));
    }


    // --- BLOCK ACCESS ---
    public Block getBlock(int wx, int wy, int wz){
        // ── VERTICAL BOUNDS CHECK ──
        // Y is vertical and cannot go below 0 or above the chunk height (64)
        if (wy < 0 || wy >= Chunk.HEIGHT) {
            return Block.AIR;
        }

        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        //Locate the chunk
        Chunk chunk = getChunk(cx, cz);
        if(chunk == null) return Block.AIR;
        //Use the chunk coordinate to get the block from the chunk class.
        int lx = Math.floorMod(wx, Chunk.SIZE);
        int lz = Math.floorMod(wz, Chunk.SIZE);
        return chunk.getBlock(lx,wy,lz);
    }

    public void setBlock(int wx, int wy, int wz, Block b) {
        //Locate the chunk
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        Chunk chunk = getOrCreateChunk(cx, cz);
        //Use chunk cooridnate to get block in the chunk class.
        int lx = Math.floorMod(wx, Chunk.SIZE);
        int lz = Math.floorMod(wz, Chunk.SIZE);
        chunk.setBlock(lx, wy, lz, b);
        //Mark the chunk as needing a mesh update
        chunk.dirty = true;
    }

    public java.util.Collection<Chunk> getAllChunks() {
        return chunks.values();
    }

    public void clearAllChunks() {
        chunks.clear();
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < WIDTH
                && y >= 0 && y < HEIGHT
                && z >= 0 && z < DEPTH;
    }

    // Call this every frame (or every few frames to save performance)
    public void updateChunks(World world, WorldGen gen, Player player) {
        int RENDER_DISTANCE = GameConfig.renderDistance;; // chunks in each direction

        // Which chunk is the player currently in?
        int playerCX = Math.floorDiv((int) player.position.x, Chunk.SIZE);
        int playerCZ = Math.floorDiv((int) player.position.z, Chunk.SIZE);

        for (int dx = -RENDER_DISTANCE; dx <= RENDER_DISTANCE; dx++) {
            for (int dz = -RENDER_DISTANCE; dz <= RENDER_DISTANCE; dz++) {
                int cx = playerCX + dx;
                int cz = playerCZ + dz;

                // If chunk doesn't exist yet, generate it
                if (world.getChunk(cx, cz) == null) {
                    Chunk chunk = world.getOrCreateChunk(cx, cz);
                    gen.generateChunk(chunk);
                    // TODO later: rebuild the mesh for this chunk
                }
            }
        }
        world.chunks.entrySet().removeIf(entry -> {
            Chunk c = entry.getValue();
            int dx = Math.abs(c.cx - playerCX);
            int dz = Math.abs(c.cz - playerCZ);
            if (dx > RENDER_DISTANCE + 2 || dz > RENDER_DISTANCE + 2) {
                if (c.mesh != null) c.mesh.cleanup(); // free GPU memory!
                return true; // remove from map
            }
            return false;
        });
    }
    // --- MESH BUILDING ---

    // Builds one large Mesh containing every visible face in the world.
    // Call this once at startup (and later when blocks change).
    // Builds a 3D mesh for just ONE specific chunk
    public Mesh buildChunkMesh(Chunk chunk) {
        List<Float>   verts   = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int vertexIndex = 0;

        int worldXStart = chunk.cx * Chunk.SIZE;
        int worldZStart = chunk.cz * Chunk.SIZE;

        for (int x = 0; x < Chunk.SIZE;  x++) {
            for (int y = 0; y < Chunk.HEIGHT; y++) {
                for (int z = 0; z < Chunk.SIZE;  z++) {

                    int wx = worldXStart + x;
                    int wz = worldZStart + z;

                    Block block = chunk.getBlock(x, y, z);
                    if (!block.isSolid()) continue;

                    // Check all 6 neighbors using WORLD coordinates (to check across chunk borders)
                    if (!getBlock(wx, y + 1, wz).isSolid()) {
                        addFace(verts, indices, vertexIndex, topFace(wx, y, wz), block, 1.0f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(wx, y - 1, wz).isSolid()) {
                        addFace(verts, indices, vertexIndex, bottomFace(wx, y, wz), block, 0.5f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(wx, y, wz + 1).isSolid()) {
                        addFace(verts, indices, vertexIndex, frontFace(wx, y, wz), block, 0.75f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(wx, y, wz - 1).isSolid()) {
                        addFace(verts, indices, vertexIndex, backFace(wx, y, wz), block, 0.75f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(wx + 1, y, wz).isSolid()) {
                        addFace(verts, indices, vertexIndex, rightFace(wx, y, wz), block, 0.6f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(wx - 1, y, wz).isSolid()) {
                        addFace(verts, indices, vertexIndex, leftFace(wx, y, wz), block, 0.6f);
                        vertexIndex += 4;
                    }
                }
            }
        }

        if (verts.isEmpty()) return null;

        float[] vertArray = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) vertArray[i] = verts.get(i);

        int[] idxArray = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) idxArray[i] = indices.get(i);

        return new Mesh(vertArray, idxArray);
    }
    // --- FACE HELPER: adds 4 vertices + 6 indices for one quad face ---
    private void addFace(List<Float> verts, List<Integer> indices,
                         int baseIndex, float[] faceVertices,
                         Block block, float brightness) {

        // faceVertices is an array of 12 floats: 4 vertices × (x, y, z)
        // We interleave position and color into the vertex buffer: x, y, z, r, g, b

        for (int i = 0; i < 4; i++) {
            // Position (from the face definition)
            verts.add(faceVertices[i * 3]);      // x
            verts.add(faceVertices[i * 3 + 1]);  // y
            verts.add(faceVertices[i * 3 + 2]);  // z

            // Color (block color × brightness for shading)
            verts.add(block.r * brightness);
            verts.add(block.g * brightness);
            verts.add(block.b * brightness);
        }

        // Two triangles from 4 vertices (same pattern as Phase 1)
        // v0-v1-v2 and v2-v3-v0
        indices.add(baseIndex);
        indices.add(baseIndex + 1);
        indices.add(baseIndex + 2);
        indices.add(baseIndex + 2);
        indices.add(baseIndex + 3);
        indices.add(baseIndex);
    }

    // --- FACE GEOMETRY DEFINITIONS ---
    // Each method returns 12 floats: 4 vertices × (x, y, z)
    // Listed CCW when viewed from outside the block.
    // Block occupies (x, y, z) to (x+1, y+1, z+1).

    private float[] topFace(int x, int y, int z) {
        // Viewed from above (+Y): CCW order
        return new float[] {
                x,   y+1, z,      // back-left
                x+1, y+1, z,      // back-right
                x+1, y+1, z+1,    // front-right
                x,   y+1, z+1     // front-left
        };
    }

    private float[] bottomFace(int x, int y, int z) {
        // Viewed from below (-Y): CCW order (reversed from top)
        return new float[] {
                x,   y, z+1,    // front-left
                x+1, y, z+1,    // front-right
                x+1, y, z,      // back-right
                x,   y, z       // back-left
        };
    }

    private float[] frontFace(int x, int y, int z) {
        // Viewed from +Z side: CCW order
        return new float[] {
                x,   y,   z+1,   // bottom-left
                x+1, y,   z+1,   // bottom-right
                x+1, y+1, z+1,   // top-right
                x,   y+1, z+1    // top-left
        };
    }

    private float[] backFace(int x, int y, int z) {
        // Viewed from -Z side: CCW order (reversed from front)
        return new float[] {
                x+1, y,   z,     // bottom-right (from outside)
                x,   y,   z,     // bottom-left
                x,   y+1, z,     // top-left
                x+1, y+1, z      // top-right
        };
    }

    private float[] rightFace(int x, int y, int z) {
        // Viewed from +X side: CCW order
        return new float[] {
                x+1, y,   z+1,   // bottom-front
                x+1, y,   z,     // bottom-back
                x+1, y+1, z,     // top-back
                x+1, y+1, z+1    // top-front
        };
    }

    private float[] leftFace(int x, int y, int z) {
        // Viewed from -X side: CCW order
        return new float[] {
                x, y,   z,       // bottom-back
                x, y,   z+1,     // bottom-front
                x, y+1, z+1,     // top-front
                x, y+1, z        // top-back
        };
    }

}