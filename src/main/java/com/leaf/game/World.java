package com.leaf.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class World {

    // Increased HEIGHT from 16 → 64 to give terrain room to breathe.
    // With HEIGHT=16, hills only had a 10-block range. Now they have 48.
    public static final int WIDTH  = 64;  // X axis (was 32)
    public static final int HEIGHT = 64;  // Y axis — up (was 16)
    public static final int DEPTH  = 64;  // Z axis (was 32)

    private final Block[][][] blocks;
    private final long seed;

    /**
     * Create a world with a random seed and generate terrain.
     * Each run will look different.
     */
    public World() {
        this(new Random().nextLong());
    }

    /**
     * Create a world with a specific seed.
     * Same seed always produces the same terrain — great for debugging.
     * Example: new World(12345L) will always look the same.
     */
    public World(long seed) {
        this.seed = seed;
        this.blocks = new Block[WIDTH][HEIGHT][DEPTH];

        // Fill everything with air first
        for (int x = 0; x < WIDTH;  x++)
            for (int y = 0; y < HEIGHT; y++)
                for (int z = 0; z < DEPTH;  z++)
                    blocks[x][y][z] = Block.AIR;

        // Generate terrain using Perlin noise
        WorldGen gen = new WorldGen(seed);
        gen.generate(this);

        System.out.println("World generated with seed: " + seed);
    }

    // --- BLOCK ACCESS ---

    public Block getBlock(int x, int y, int z) {
        if (!inBounds(x, y, z)) return Block.AIR;
        return blocks[x][y][z];
    }

    public void setBlock(int x, int y, int z, Block block) {
        if (!inBounds(x, y, z)) return;
        blocks[x][y][z] = block;
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < WIDTH
                && y >= 0 && y < HEIGHT
                && z >= 0 && z < DEPTH;
    }

    // --- MESH BUILDING ---
    // (Identical to before — only draws visible faces.)

    public Mesh buildMesh() {
        List<Float>   verts   = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int vertexIndex = 0;

        for (int x = 0; x < WIDTH;  x++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < DEPTH;  z++) {

                    Block block = blocks[x][y][z];
                    if (!block.isSolid()) continue;

                    if (!getBlock(x, y + 1, z).isSolid()) {
                        addFace(verts, indices, vertexIndex, topFace(x, y, z), block, 1.0f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(x, y - 1, z).isSolid()) {
                        addFace(verts, indices, vertexIndex, bottomFace(x, y, z), block, 0.5f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(x, y, z + 1).isSolid()) {
                        addFace(verts, indices, vertexIndex, frontFace(x, y, z), block, 0.75f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(x, y, z - 1).isSolid()) {
                        addFace(verts, indices, vertexIndex, backFace(x, y, z), block, 0.75f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(x + 1, y, z).isSolid()) {
                        addFace(verts, indices, vertexIndex, rightFace(x, y, z), block, 0.6f);
                        vertexIndex += 4;
                    }
                    if (!getBlock(x - 1, y, z).isSolid()) {
                        addFace(verts, indices, vertexIndex, leftFace(x, y, z), block, 0.6f);
                        vertexIndex += 4;
                    }
                }
            }
        }

        float[] vertArray = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) vertArray[i] = verts.get(i);

        int[] idxArray = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) idxArray[i] = indices.get(i);

        return new Mesh(vertArray, idxArray);
    }

    private void addFace(List<Float> verts, List<Integer> indices,
                         int baseIndex, float[] faceVertices,
                         Block block, float brightness) {
        for (int i = 0; i < 4; i++) {
            verts.add(faceVertices[i * 3]);
            verts.add(faceVertices[i * 3 + 1]);
            verts.add(faceVertices[i * 3 + 2]);
            verts.add(block.r * brightness);
            verts.add(block.g * brightness);
            verts.add(block.b * brightness);
        }
        indices.add(baseIndex);
        indices.add(baseIndex + 1);
        indices.add(baseIndex + 2);
        indices.add(baseIndex + 2);
        indices.add(baseIndex + 3);
        indices.add(baseIndex);
    }

    private float[] topFace(int x, int y, int z) {
        return new float[] { x, y+1, z,  x+1, y+1, z,  x+1, y+1, z+1,  x, y+1, z+1 };
    }
    private float[] bottomFace(int x, int y, int z) {
        return new float[] { x, y, z+1,  x+1, y, z+1,  x+1, y, z,  x, y, z };
    }
    private float[] frontFace(int x, int y, int z) {
        return new float[] { x, y, z+1,  x+1, y, z+1,  x+1, y+1, z+1,  x, y+1, z+1 };
    }
    private float[] backFace(int x, int y, int z) {
        return new float[] { x+1, y, z,  x, y, z,  x, y+1, z,  x+1, y+1, z };
    }
    private float[] rightFace(int x, int y, int z) {
        return new float[] { x+1, y, z+1,  x+1, y, z,  x+1, y+1, z,  x+1, y+1, z+1 };
    }
    private float[] leftFace(int x, int y, int z) {
        return new float[] { x, y, z,  x, y, z+1,  x, y+1, z+1,  x, y+1, z };
    }
}