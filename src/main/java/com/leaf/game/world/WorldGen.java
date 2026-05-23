package com.leaf.game.world;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Noise;

public class WorldGen {

    private Noise continentalness;
    private Noise erosion;
    private Noise warpNoise;     // perturbs erosion coordinates — independent from erosion itself
    private Noise peaksValleys;
    private Noise temperature;
    private Noise humidity;
    private Noise density3D;

    public WorldGen(long seed) { init(seed); }
    public WorldGen()          { init(GameConfig.seed); }

    private void init(long seed) {
        continentalness = new Noise(seed);
        erosion         = new Noise(seed + 1000L);
        warpNoise       = new Noise(seed + 1500L);
        peaksValleys    = new Noise(seed + 2000L);
        temperature     = new Noise(seed + 3000L);
        humidity        = new Noise(seed + 4000L);
        density3D       = new Noise(seed + 5000L);
    }

    public void resetSeed(long seed) { init(seed); }

    // =========================================================================
    // PUBLIC SAMPLERS  — used by NoiseVisualizer and generateChunk
    // =========================================================================

    /**
     * Continentalness: smooth giant blobs, high contrast.
     * Contrast sharpening: sign(v) * |v|^0.6 pushes values toward ±1.
     * With exponent < 1, mid-gray becomes less common → clearer ocean/land split.
     */
    public float sampleContinentalness(int wx, int wz) {
        float raw = continentalness.octave(
                wx * GameConfig.contFreq,
                wz * GameConfig.contFreq,
                GameConfig.contOctaves,
                GameConfig.contPersist);

        // Sharpen contrast: exponent 0.6 expands values away from zero
        return (float)(Math.signum(raw) * Math.pow(Math.abs(raw), 0.6));
    }

    /**
     * Erosion: organic basin shapes via domain warping.
     *
     * The KEY fix vs the previous version:
     * warpStrength is in WORLD BLOCKS, applied BEFORE frequency scaling.
     *
     *   Old (wrong):  sample at (wx * freq + tinyOffset, ...)
     *                 offset was ±0.08 in Perlin-space = 8% of feature → invisible
     *
     *   New (correct): warp wx by ±180 blocks in world space, THEN scale by freq
     *                  offset is ±180 * 0.0015 = ±0.27 in Perlin-space = 27% of feature → clearly visible
     */
    public float sampleErosion(int wx, int wz) {
        float wf = GameConfig.erosWarpFreq;
        float ws = GameConfig.erosWarpStrength;  // world blocks

        // Two independent offsets from the same warp noise (different starting positions)
        float offsetX = ws * warpNoise.get( wx * wf,          wz * wf        );
        float offsetZ = ws * warpNoise.get((wx * wf) + 31.7f, (wz * wf) + 17.3f);

        // Warp the WORLD coordinate first, then multiply by frequency
        return erosion.octave(
                (wx + offsetX) * GameConfig.erosFreq,
                (wz + offsetZ) * GameConfig.erosFreq,
                GameConfig.erosOctaves,
                GameConfig.erosPersist);
    }

    /**
     * Peaks & Valleys: ridged noise.
     * ridgedOctave samples (1 - |noise|) each octave, turning smooth zero-crossings
     * into sharp bright ridgelines. Combined with lower frequency than before,
     * this creates a visible ridge network rather than gray static.
     */
    public float samplePeaksValleys(int wx, int wz) {
        return peaksValleys.ridgedOctave(
                wx * GameConfig.pvFreq,
                wz * GameConfig.pvFreq,
                GameConfig.pvOctaves,
                GameConfig.pvPersist);
    }

    /** Temperature: very smooth large-scale gradient. -1 = cold, +1 = hot. */
    public float sampleTemperature(int wx, int wz) {
        return temperature.octave(
                wx * GameConfig.tempFreq,
                wz * GameConfig.tempFreq,
                GameConfig.tempOctaves,
                GameConfig.tempPersist);
    }

    /** Humidity: medium-scale. -1 = dry, +1 = wet. */
    public float sampleHumidity(int wx, int wz) {
        return humidity.octave(
                wx * GameConfig.humFreq,
                wz * GameConfig.humFreq,
                GameConfig.humOctaves,
                GameConfig.humPersist);
    }

    // =========================================================================
    // CHUNK GENERATION
    // =========================================================================

    public void generateChunk(Chunk chunk) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;

                float c  = sampleContinentalness(wx, wz);
                float e  = sampleErosion(wx, wz);
                float pv = samplePeaksValleys(wx, wz);
                // ready for biomes when needed:
                // float temp = sampleTemperature(wx, wz);
                // float hum  = sampleHumidity(wx, wz);

                // Continentalness → base height via spline
                float baseShape = continentalnessSpline(c);

                // Erosion [-1,1] → erosion factor [0=flat, 1=jagged]
                float erosFactor = 1.0f - ((e + 1.0f) / 2.0f);

                // P&V adds ridge detail, scaled by how jagged this region should be
                float pvContrib  = (pv + 1.0f) / 2.0f;
                float finalShape = baseShape + pvContrib * 0.25f * erosFactor;
                finalShape = Math.max(0f, Math.min(1f, finalShape));

                float targetY       = GameConfig.heightBase + finalShape * GameConfig.heightRange;
                float verticalScale = GameConfig.densityVerticalScale
                        + erosFactor * GameConfig.densityErosionBoost;

                // 3D density pass
                boolean[] solid = new boolean[Chunk.HEIGHT];
                for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                    float heightBias = (targetY - ly) * verticalScale;
                    float n3d = density3D.octave3D(
                            wx * GameConfig.density3DFreq,
                            ly * GameConfig.density3DFreq * GameConfig.density3DVerticalCompress,
                            wz * GameConfig.density3DFreq,
                            GameConfig.density3DOctaves,
                            GameConfig.density3DPersist);
                    solid[ly] = (heightBias + n3d * GameConfig.density3DAmplitude) > 0;
                }

                // Assign block types top-down
                boolean hitSurface = false;
                int     dirtCount  = 0;
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

    // =========================================================================
    // SPLINE  —  continentalness [-1,1] → base height shape [0,1]
    // =========================================================================

    private float continentalnessSpline(float c) {
        if (c < -0.2f) return remap(c, -1.0f, -0.2f, 0.00f, 0.10f);  // deep ocean
        if (c <  0.0f) return remap(c, -0.2f,  0.0f, 0.10f, 0.25f);  // beach
        if (c <  0.4f) return remap(c,  0.0f,  0.4f, 0.25f, 0.35f);  // plains
        if (c <  0.5f) return remap(c,  0.4f,  0.5f, 0.35f, 0.85f);  // cliff jump
        return              remap(c,  0.5f,  1.0f, 0.85f, 1.00f);     // peak
    }

    private float remap(float val, float inMin, float inMax, float outMin, float outMax) {
        float t = (val - inMin) / (inMax - inMin);
        t = Math.max(0f, Math.min(1f, t));
        return outMin + t * (outMax - outMin);
    }
}