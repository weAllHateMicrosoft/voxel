package com.leaf.game.world;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Noise;

public class WorldGen {

    private Noise continentalness;
    private Noise erosion;
    private Noise warpNoise;
    private Noise peaksValleys;
    private Noise temperature;
    private Noise humidity;
    private Noise density3D;
    private Noise riverNoise;

    // ── Cave noise generators ─────────────────────────────────────────────────
    private Noise cheeseCave;
    private Noise spagNoise1;
    private Noise spagNoise2;

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
        riverNoise      = new Noise(seed + 6000L);
        cheeseCave      = new Noise(seed + 7000L);
        spagNoise1      = new Noise(seed + 8000L);
        spagNoise2      = new Noise(seed + 9000L);
    }

    public void resetSeed(long seed) { init(seed); }

    // =========================================================================
    // PUBLIC SAMPLERS
    // =========================================================================

    public float sampleContinentalness(int wx, int wz) {
        float raw = continentalness.octave(
                wx * GameConfig.contFreq, wz * GameConfig.contFreq,
                GameConfig.contOctaves, GameConfig.contPersist);
        return (float)(Math.signum(raw) * Math.pow(Math.abs(raw), 0.6));
    }

    public float sampleErosion(int wx, int wz) {
        float wf = GameConfig.erosWarpFreq;
        float ws = GameConfig.erosWarpStrength;
        float offsetX = ws * warpNoise.get(wx * wf,         wz * wf);
        float offsetZ = ws * warpNoise.get(wx * wf + 31.7f, wz * wf + 17.3f);
        return erosion.octave(
                (wx + offsetX) * GameConfig.erosFreq,
                (wz + offsetZ) * GameConfig.erosFreq,
                GameConfig.erosOctaves, GameConfig.erosPersist);
    }

    public float samplePeaksValleys(int wx, int wz) {
        return peaksValleys.ridgedOctave(
                wx * GameConfig.pvFreq, wz * GameConfig.pvFreq,
                GameConfig.pvOctaves, GameConfig.pvPersist);
    }

    public float sampleTemperature(int wx, int wz) {
        return temperature.octave(
                wx * GameConfig.tempFreq, wz * GameConfig.tempFreq,
                GameConfig.tempOctaves, GameConfig.tempPersist);
    }

    public float sampleHumidity(int wx, int wz) {
        return humidity.octave(
                wx * GameConfig.humFreq, wz * GameConfig.humFreq,
                GameConfig.humOctaves, GameConfig.humPersist);
    }

    public float sampleRiver(int wx, int wz) {
        return riverNoise.octave(
                wx * GameConfig.riverFreq, wz * GameConfig.riverFreq,
                GameConfig.riverOctaves, GameConfig.riverPersist);
    }

    /** Combined terrain height [0, 1] before caves. 0 = ocean floor, 1 = peak. */
    public float sampleHeight(int wx, int wz) {
        return computeFinalShape(
                sampleContinentalness(wx, wz),
                sampleErosion(wx, wz),
                samplePeaksValleys(wx, wz));
    }

    // =========================================================================
    // CHUNK GENERATION
    // =========================================================================

    public void generateChunk(Chunk chunk) {
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        // seaFrac: normalized sea level in [0,1] shape space.
        // Columns with shape < seaFrac have their surface below water.
        final float seaFrac =
                (GameConfig.seaLevel - GameConfig.heightBase) / (float) GameConfig.heightRange;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = worldX + lx;
                int wz = worldZ + lz;

                // ── SAMPLE TERRAIN NOISE ──────────────────────────────────
                float c    = sampleContinentalness(wx, wz);
                float e    = sampleErosion(wx, wz);
                float pv   = samplePeaksValleys(wx, wz);
                float temp = sampleTemperature(wx, wz);

                float eNorm    = (e + 1f) / 2f;
                float flatness = erosionFlatnessSpline(eNorm);
                float shape    = computeFinalShape(c, e, pv);

                // ── RIVER CARVING ─────────────────────────────────────────
                // |river| < threshold selects the zero-crossing band of the
                // noise field, which forms naturally branching filaments.
                //
                // FIX vs previous version: we now carve RELATIVE to the current
                // terrain shape, not down to a fixed seaFrac target.
                // A mountain river and a plains river both cut the same small
                // groove (≈2–3 blocks), so neither becomes a ravine.
                //
                // The floor clamp ensures the carve never goes more than
                // riverFloorMargin below sea level — water always fills the bed.
                float river    = sampleRiver(wx, wz);
                float absRiver = Math.abs(river);
                boolean isRiver = absRiver < GameConfig.riverThreshold
                        && shape > seaFrac + GameConfig.riverElevationBuffer;

                if (isRiver) {
                    float carveT      = 1f - (absRiver / GameConfig.riverThreshold); // 1=centre, 0=edge
                    float carvedShape = shape - GameConfig.riverCarveDepth * carveT;
                    float riverFloor  = seaFrac - GameConfig.riverFloorMargin;
                    shape = Math.max(riverFloor, carvedShape);
                }

                float targetY = GameConfig.heightBase + shape * GameConfig.heightRange;

                // ── 3D DENSITY FIELD ──────────────────────────────────────
                // Rivers zero out 3D noise — flat, smooth channel walls.
                float vertScale = lerp(GameConfig.densityVerticalScale, 0.55f, flatness);
                float d3dAmp    = isRiver ? 0f
                        : GameConfig.density3DAmplitude * (1f - flatness);

                boolean[] solid = new boolean[Chunk.HEIGHT];
                for (int ly = 0; ly < Chunk.HEIGHT; ly++) {
                    float heightBias = (targetY - ly) * vertScale;
                    float n3d = 0f;
                    if (d3dAmp > 0.5f) {
                        n3d = density3D.octave3D(
                                wx  * GameConfig.density3DFreq,
                                ly  * GameConfig.density3DFreq * GameConfig.density3DVerticalCompress,
                                wz  * GameConfig.density3DFreq,
                                GameConfig.density3DOctaves,
                                GameConfig.density3DPersist) * d3dAmp;
                    }
                    solid[ly] = (heightBias + n3d) > 0;
                }

                // ── FIND SURFACE Y ────────────────────────────────────────
                // Needed so cave generators know how far they are from the top.
                int surfaceY = 0;
                for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
                    if (solid[ly]) { surfaceY = ly; break; }
                }

                // ── CHEESE CAVES ──────────────────────────────────────────
                // Large open cavern pockets. The noise is sampled at each voxel;
                // if the value exceeds a threshold (adjusted for depth), hollow out.
                //
                // Depth bias: deeper blocks get a lower effective threshold
                // (cheeseDepthBoost at y=0, 0 at y=surfaceY). More voids at depth.
                //
                // Guards: bedrock floor is always solid, surface buffer prevents
                // sinkholes opening at terrain top.
                int caveTop = surfaceY - GameConfig.caveSurfaceBuffer;
                for (int ly = GameConfig.caveBedrockFloor; ly < caveTop; ly++) {
                    if (!solid[ly]) continue;

                    float depthT  = 1f - (float) ly / Math.max(1, surfaceY); // 1 at y=0, 0 at surface
                    float adjThresh = GameConfig.cheeseThreshold
                            - depthT * GameConfig.cheeseDepthBoost;

                    float cheese = cheeseCave.octave3D(
                            wx * GameConfig.cheeseFreq,
                            ly * GameConfig.cheeseFreq * GameConfig.cheeseVertCompress,
                            wz * GameConfig.cheeseFreq,
                            GameConfig.cheeseOctaves,
                            GameConfig.cheesePersist);

                    // octave3D is in [-1, 1]; remap to [0, 1] for threshold test.
                    if ((cheese + 1f) * 0.5f > adjThresh) {
                        solid[ly] = false;
                    }
                }

                // ── SPAGHETTI / NOODLE CAVES ─────────────────────────────
                // Two independent 3D noises, each treated as a ridged field:
                //   ridged(x) = 1 - |x|   →   peak (1.0) exactly at zero-crossings
                //
                // Thinking in 3D: each ridged field defines a thin "peel" —
                // a shell around wherever the underlying noise equals zero.
                // Where BOTH peels overlap we get a long squiggly tube.
                //
                // min(ridge1, ridge2) > spagThreshold = inside both peels = tunnel.
                for (int ly = GameConfig.caveBedrockFloor; ly < caveTop; ly++) {
                    if (!solid[ly]) continue;

                    float raw1 = spagNoise1.octave3D(
                            wx * GameConfig.spagFreq,
                            ly * GameConfig.spagFreq * GameConfig.spagVertCompress,
                            wz * GameConfig.spagFreq,
                            GameConfig.spagOctaves,
                            GameConfig.spagPersist);

                    float raw2 = spagNoise2.octave3D(
                            wx * GameConfig.spagFreq,
                            ly * GameConfig.spagFreq * GameConfig.spagVertCompress,
                            wz * GameConfig.spagFreq,
                            GameConfig.spagOctaves,
                            GameConfig.spagPersist);

                    float ridge1 = 1f - Math.abs(raw1);  // peak = zero-crossing
                    float ridge2 = 1f - Math.abs(raw2);  // peak = zero-crossing
                    float tunnel = Math.min(ridge1, ridge2); // intersection of peels

                    if (tunnel > GameConfig.spagThreshold) {
                        solid[ly] = false;
                    }
                }

                // ── BIOME SURFACE SELECTION ───────────────────────────────
                int     ty      = (int) targetY;
                boolean isBeach = ty >= GameConfig.seaLevel - 1
                        && ty <= GameConfig.seaLevel + GameConfig.beachMaxAltitude;
                boolean isCold  = temp < GameConfig.coldTempThreshold;
                boolean isHigh  = ty >= GameConfig.snowAltitude;

                Block surface, subSurface;
                if (isRiver || isBeach) {
                    surface = subSurface = Block.SAND;
                } else if (isCold || isHigh) {
                    surface    = Block.SNOW;
                    subSurface = Block.DIRT;
                } else {
                    surface    = Block.GRASS;
                    subSurface = Block.DIRT;
                }

                // ── PLACE BLOCKS ──────────────────────────────────────────
                // Scan top-down. First solid voxel at/above seaLevel = surface block.
                // Non-solid voxels at or below seaLevel become WATER automatically —
                // this fills ocean basins, river channels, AND underwater cave pockets
                // without any extra per-column logic.
                boolean hitSurface = false;
                int     dirtCount  = 0;

                for (int ly = Chunk.HEIGHT - 1; ly >= 0; ly--) {
                    if (!solid[ly]) {
                        chunk.setBlock(lx, ly, lz,
                                ly <= GameConfig.seaLevel ? Block.WATER : Block.AIR);
                    } else {
                        if (!hitSurface) {
                            hitSurface = true;
                            dirtCount  = 0;
                            if (ly >= GameConfig.seaLevel) {
                                chunk.setBlock(lx, ly, lz, surface);
                            } else if (ly >= GameConfig.seaLevel - 4) {
                                chunk.setBlock(lx, ly, lz, Block.SAND); // shallow ocean floor
                            } else {
                                chunk.setBlock(lx, ly, lz, Block.STONE); // deep ocean floor
                            }
                        } else if (dirtCount < 3) {
                            dirtCount++;
                            chunk.setBlock(lx, ly, lz, subSurface);
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
    // SPLINE SYSTEM
    // =========================================================================

    private float computeFinalShape(float c, float e, float pv) {
        float eNorm     = (e + 1f) / 2f;
        float contH     = continentalnessSpline(c);
        float pvContrib = pvContribMax(eNorm) * ((pv + 1f) / 2f);
        float mountainH = Math.min(1f, contH + pvContrib);
        float flatness  = erosionFlatnessSpline(eNorm);
        return lerp(mountainH, contH, flatness);
    }

    /**
     * Continentalness [-1, 1] → base height [0, 1].
     *
     * The steep zone c=[0.30, 0.65] maps 0.35 c-units → 22 y-blocks (~63 y per c-unit).
     * Plains c=[0.05, 0.30] maps 0.25 c-units → 6 y-blocks (~24 y per c-unit).
     * Mountains are ~2.6× more sensitive to continentalness than plains.
     */
    private float continentalnessSpline(float c) {
        if (c < -0.45f) return remap(c, -1.00f, -0.45f, 0.02f, 0.08f);
        if (c < -0.10f) return remap(c, -0.45f, -0.10f, 0.08f, 0.20f);
        if (c <  0.05f) return remap(c, -0.10f,  0.05f, 0.20f, 0.25f);
        if (c <  0.30f) return remap(c,  0.05f,  0.30f, 0.25f, 0.36f);
        if (c <  0.65f) return remap(c,  0.30f,  0.65f, 0.36f, 0.78f); // ← steep
        if (c <  0.85f) return remap(c,  0.65f,  0.85f, 0.78f, 0.88f);
        return             remap(c,  0.85f,  1.00f, 0.88f, 0.92f);
    }

    private float erosionFlatnessSpline(float eNorm) {
        if (eNorm < 0.25f) return remap(eNorm, 0f,    0.25f, 0f,    0.05f);
        if (eNorm < 0.55f) return remap(eNorm, 0.25f, 0.55f, 0.05f, 0.40f);
        if (eNorm < 0.75f) return remap(eNorm, 0.55f, 0.75f, 0.40f, 0.85f);
        return               remap(eNorm, 0.75f, 1f,   0.85f, 1.00f);
    }

    private float pvContribMax(float eNorm) {
        if (eNorm < 0.30f) return 0.45f;
        if (eNorm < 0.60f) return remap(eNorm, 0.30f, 0.60f, 0.45f, 0.10f);
        return               remap(eNorm, 0.60f, 1.00f, 0.10f, 0.00f);
    }

    private float lerp(float a, float b, float t) { return a + t * (b - a); }

    private float remap(float val, float inMin, float inMax, float outMin, float outMax) {
        float t = (val - inMin) / (inMax - inMin);
        t = Math.max(0f, Math.min(1f, t));
        return outMin + t * (outMax - outMin);
    }
}