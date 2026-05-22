package com.leaf.game;

/**
 * Procedural terrain generator using Perlin noise + octaves.
 *
 * This is "Technique 1 + 2" from the video:
 *   - Perlin noise gives smooth gradients (no TV-static cliffs)
 *   - Octaves layer detail on top (big mountains + smaller hills + bumps)
 *
 * What getBlock(x, y, z) does here:
 *   For each (x, z) column, sample noise to get a surface height.
 *   Then fill: stone below, dirt middle layers, grass on top, air above.
 *   (This is "2D heightmap generation" — the video's Phase 5 technique.)
 *
 * Later you can add:
 *   - Spline curves to get dramatic cliffs (Technique 3)
 *   - 3D noise to carve caves (Technique 4)
 *   - Multiple noise fields for biomes (Technique 5)
 */
public class WorldGen {

    private final Noise noise;

    // --- TERRAIN TUNING KNOBS ---
    // Change these to experiment with different landscape shapes.

    /** How zoomed-in the noise is. Smaller = wider, gentler hills. Larger = jagged, frequent. */
    private static final float NOISE_SCALE = 0.035f;

    /** How many octave layers. More = more fine detail, but slower to generate. */
    private static final int OCTAVES = 4;

    /**
     * How quickly each octave's amplitude drops.
     * 0.5 = each layer has half the influence of the last.
     * Higher (0.7) = rougher, more jagged terrain.
     * Lower (0.3) = smoother, more rounded.
     */
    private static final float PERSISTENCE = 0.5f;

    /** The Y level that counts as "sea level" — low terrain gets filled with water (if you add a WATER block later). */
    public static final int SEA_LEVEL = 12;

    /** Minimum surface height (ocean floor / deepest valley). */
    private static final int MIN_HEIGHT = 6;

    /** Maximum surface height (tallest mountain peaks). */
    private static final int MAX_HEIGHT = 54; // leave headroom below World.HEIGHT = 64

    /** How many layers of DIRT sit under the GRASS surface. */
    private static final int DIRT_DEPTH = 3;

    public WorldGen(long seed) {
        this.noise = new Noise(seed);
    }

    /**
     * Fill the entire world array with generated terrain.
     * Call this from World's constructor instead of generateFlat().
     */
    public void generate(World world) {
        for (int x = 0; x < World.WIDTH; x++) {
            for (int z = 0; z < World.DEPTH; z++) {

                int surfaceY = getSurfaceHeight(x, z);

                for (int y = 0; y < World.HEIGHT; y++) {
                    world.setBlock(x, y, z, pickBlock(y, surfaceY));
                }
            }
        }
    }

    /**
     * Decide what block type belongs at a given Y, given the surface height for this column.
     *
     * The layering (from video's "Coat of Paint" section):
     *   y == surfaceY       → GRASS  (the biome decides this later — desert = sand, snow = snow, etc.)
     *   surfaceY-DIRT_DEPTH to surfaceY-1 → DIRT
     *   y < surfaceY - DIRT_DEPTH          → STONE
     *   y == 0              → STONE  (bedrock layer)
     *   y > surfaceY        → AIR
     */
    private Block pickBlock(int y, int surfaceY) {
        if (y > surfaceY) {
            return Block.AIR;
        } else if (y == surfaceY) {
            return Block.GRASS;
        } else if (y >= surfaceY - DIRT_DEPTH) {
            return Block.DIRT;
        } else {
            return Block.STONE;
        }
    }

    /**
     * Sample the noise field and map it to a terrain height for column (x, z).
     *
     * The noise returns [-1, 1]. We remap that to [MIN_HEIGHT, MAX_HEIGHT].
     *
     * EXPERIMENT IDEAS:
     *   - Change NOISE_SCALE to zoom in/out of the terrain
     *   - Change OCTAVES to 1 to see how flat it looks without detail layers
     *   - Change PERSISTENCE to 0.8 for much rougher terrain
     *   - Add a second noise sample at a different scale and blend them
     */
    public int getSurfaceHeight(int x, int z) {
        // Sample octave Perlin noise — returns [-1, 1]
        float raw = noise.octave(x * NOISE_SCALE, z * NOISE_SCALE, OCTAVES, PERSISTENCE);

        // Shift to [0, 1]
        float normalized = (raw + 1.0f) / 2.0f;

        // Map to the height range we want
        return MIN_HEIGHT + (int)(normalized * (MAX_HEIGHT - MIN_HEIGHT));
    }
}