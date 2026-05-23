package com.leaf.game;

public class WorldGen {

    // 2D noises — shape the terrain at a large/regional scale
    private Noise continentalness;
    private Noise erosion;
    private Noise peaksValleys;

    // 3D noise — adds surface detail and allows overhangs
    private Noise density3D;

    public WorldGen(long seed) {
        this.continentalness = new Noise(seed);
        this.erosion         = new Noise(seed + 1000L);
        this.peaksValleys    = new Noise(seed + 2000L);
        this.density3D       = new Noise(seed + 3000L);
    }

    public WorldGen() {
        this(System.currentTimeMillis());
    }

    public void resetSeed(long seed) {
        this.continentalness = new Noise(seed);
        this.erosion         = new Noise(seed + 1000L);
        this.peaksValleys    = new Noise(seed + 2000L);
        this.density3D       = new Noise(seed + 3000L);
    }

    // -------------------------------------------------------------------------
    // CHUNK GENERATION
    // -------------------------------------------------------------------------

    public void generateChunk(Chunk chunk) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;

                // --- STEP 1: Sample the 2D noises for this column ---
                float c  = continentalness.octave(wx * GameConfig.contFreq, wz * GameConfig.contFreq,
                        GameConfig.contOctaves, GameConfig.contPersist);
                float e  = erosion.octave(         wx * GameConfig.erosFreq, wz * GameConfig.erosFreq,
                        GameConfig.erosOctaves, GameConfig.erosPersist);
                float pv = peaksValleys.octave(    wx * GameConfig.pvFreq,   wz * GameConfig.pvFreq,
                        GameConfig.pvOctaves,   GameConfig.pvPersist);

                // --- STEP 2: Derive shape parameters from the 2D noises ---

                // Base height shape from continentalness spline [0, 1]
                float baseShape = continentalnessSpline(c);

                // Erosion: high erosion = flat region (factor near 0), low = jagged (factor near 1)
                float erosFactor = 1.0f - ((e + 1.0f) / 2.0f);

                // Peaks & valleys add fine height detail, scaled by how jagged this region is
                float pvContrib  = (pv + 1.0f) / 2.0f;
                float finalShape = baseShape + (pvContrib * 0.25f * erosFactor);
                finalShape = Math.max(0f, Math.min(1f, finalShape));

                // targetY: the "expected" surface height for this (x, z) column
                float targetY = GameConfig.heightBase + finalShape * GameConfig.heightRange;

                // verticalScale: how sharply density transitions at the surface.
                // High erosion region -> gentler slope. Low erosion -> steep cliff.
                float verticalScale = GameConfig.densityVerticalScale
                        + erosFactor * GameConfig.densityErosionBoost;

                // --- STEP 3: Compute density for every Y, decide solid or air ---
                // density > 0 -> solid,  density <= 0 -> air
                boolean[] solid = new boolean[Chunk.HEIGHT];
                for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                    // Height bias: positive below targetY, negative above.
                    // This is what makes ground solid below and sky air above.
                    float heightBias = (targetY - ly) * verticalScale;

                    // 3D noise wiggles the surface and can create overhangs.
                    // Y frequency is compressed so features stretch more horizontally
                    // (looks more natural than perfectly round blobs).
                    float n3d = density3D.octave3D(
                            wx * GameConfig.density3DFreq,
                            ly * GameConfig.density3DFreq * GameConfig.density3DVerticalCompress,
                            wz * GameConfig.density3DFreq,
                            GameConfig.density3DOctaves,
                            GameConfig.density3DPersist
                    );

                    solid[ly] = (heightBias + n3d * GameConfig.density3DAmplitude) > 0;
                }

                // --- STEP 4: Assign block types top-down ---
                // Only the first (topmost) solid block gets GRASS.
                // The next 3 below it get DIRT. Everything else solid is STONE.
                // This means overhang undersides and cave ceilings are correctly STONE.
                boolean hitSurface = false;
                int dirtCount = 0;

                for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
                    if (!solid[ly]) {
                        chunk.setBlock(lx, ly, lz, Block.AIR);
                    } else {
                        if (!hitSurface) {
                            hitSurface = true;
                            dirtCount  = 0;
                            chunk.setBlock(lx, ly, lz, Block.GRASS);
                        } else if (dirtCount < 3) {
                            dirtCount++;
                            chunk.setBlock(lx, ly, lz, Block.DIRT);
                        } else {
                            chunk.setBlock(lx, ly, lz, Block.STONE);
                        }
                    }
                }
            }
        }

        chunk.dirty = true;
    }

    // -------------------------------------------------------------------------
    // SPLINE — maps continentalness [-1, 1] to base height shape [0, 1]
    // The dramatic jump in the "cliff" range is what creates sheer drops.
    // -------------------------------------------------------------------------

    private float continentalnessSpline(float c) {
        if (c < -0.2f) return remap(c, -1.0f, -0.2f, 0.00f, 0.10f);  // ocean floor
        if (c <  0.0f) return remap(c, -0.2f,  0.0f, 0.10f, 0.25f);  // beach
        if (c <  0.4f) return remap(c,  0.0f,  0.4f, 0.25f, 0.35f);  // plains
        if (c <  0.5f) return remap(c,  0.4f,  0.5f, 0.35f, 0.85f);  // cliff (dramatic jump)
        return              remap(c,  0.5f,  1.0f, 0.85f, 1.00f);     // mountain peak
    }

    private float remap(float val, float inMin, float inMax, float outMin, float outMax) {
        float t = (val - inMin) / (inMax - inMin);
        t = Math.max(0f, Math.min(1f, t));
        return outMin + t * (outMax - outMin);
    }
}
