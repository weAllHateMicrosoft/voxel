package com.leaf.game.world.gen.terrain;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Diffusion Limited Aggregation (DLA) mountain generator.
 *
 * WHAT DLA IS:
 *   A particle simulation where individual "walkers" random-walk through space
 *   until they touch an existing cluster, then permanently attach. The result is a
 *   dendritic (branching, tree-like) structure. In nature, DLA produces shapes
 *   similar to frost crystals, river deltas, and lightning bolts.
 *   Mapped to height, the branching structure becomes a branching RIDGE NETWORK —
 *   exactly what large mountain ranges look like from above.
 *
 * THE CHUNK PROBLEM (and solution):
 *   DLA is a sequential, global simulation. Chunks generate independently.
 *   Solution: Voronoi cells (large world regions, e.g. 512×512 blocks).
 *   Only ~5% of cells are designated "alpine" based on their hash.
 *   For each alpine cell, we pre-compute one DLA heightmap at low resolution.
 *   The heightmap is cached after first use — any chunk in that region reads it.
 *
 * BLENDING:
 *   Near the boundary of an alpine cell, the DLA height fades to zero.
 *   This ensures smooth transitions into normal terrain (no sharp seams).
 *
 * INTEGRATION WITH WORLDGEN:
 *   WorldGen calls getHeight(wx, wz).
 *   If the result is above the normal terrain height for that column,
 *   WorldGen uses the DLA height instead (mountains "override" the landscape).
 */
public class DlaMountainGenerator implements HeightmapGenerator {

    // ── Configuration ──────────────────────────────────────────────────────────
    // World blocks that one Voronoi cell covers.
    // With REGION_SIZE=512: one mountain fits in a 512×512 block area.
    public static final int REGION_SIZE = 512;

    // DLA grid resolution. GRID_SIZE cells × BLOCKS_PER_CELL = REGION_SIZE.
    // 64 cells × 8 blocks/cell = 512. Increasing GRID_SIZE makes the mountain
    // more detailed but also slower to compute (once per region).
    private static final int GRID_SIZE      = 64;
    private static final int BLOCKS_PER_CELL = REGION_SIZE / GRID_SIZE;  // = 8

    // Number of DLA particles per region.
    // More particles → denser ridge network, but slower computation.
    // 350 ≈ 10–30ms to compute on first load, negligible afterward (cached).
    private static final int N_PARTICLES = 350;

    // Fraction of Voronoi cells that become alpine peaks (e.g. 0.05 = 5%).
    // This corresponds to roughly one peak every 2–3 km.
    private static final float ALPINE_CHANCE = 0.05f;

    // Minimum continentalness for a cell to qualify as alpine.
    // Mountains only appear in inland, high-continental regions — never in ocean.
    private static final float MIN_CONTINENTAL = 0.40f;

    // Distance from region boundary at which DLA height begins to fade out.
    // Keeps transitions into normal terrain smooth.
    private static final int BLEND_MARGIN = 80;  // world blocks

    // ── Internals ─────────────────────────────────────────────────────────────
    private final long worldSeed;
    private final ContinentalnessSampler contSampler;

    // Cache: regionKey → float[GRID_SIZE][GRID_SIZE] heightmap.
    // Computed lazily on first access, never recomputed.
    private final Map<Long, float[][]> cache = new HashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param worldSeed    the game seed (ensures mountains are at same positions every run)
     * @param contSampler  reference to WorldGen's continentalness sampler
     *                     (mountains only spawn where continentalness is high)
     */
    public DlaMountainGenerator(long worldSeed, ContinentalnessSampler contSampler) {
        this.worldSeed   = worldSeed;
        this.contSampler = contSampler;
    }

    @FunctionalInterface
    public interface ContinentalnessSampler {
        float sample(int wx, int wz);
    }

    // ── HeightmapGenerator ────────────────────────────────────────────────────

    /**
     * Returns the DLA height contribution [0, 1] at world position (wx, wz).
     * Returns 0 if this position is not inside an alpine Voronoi cell.
     *
     * WorldGen should ONLY override the normal terrain if this returns > 0:
     *   float dla = dlaGen.getHeight(wx, wz);
     *   if (dla > 0) { targetY = dlaMountainBase + dla * dlaMountainRange; }
     */
    @Override
    public float getHeight(int wx, int wz) {
        int regionX = Math.floorDiv(wx, REGION_SIZE);
        int regionZ = Math.floorDiv(wz, REGION_SIZE);

        if (!isAlpineRegion(regionX, regionZ)) return 0f;

        float[][] hmap  = getOrComputeHeightmap(regionX, regionZ);

        // Local position within region [0, REGION_SIZE)
        int localX = Math.floorMod(wx, REGION_SIZE);
        int localZ = Math.floorMod(wz, REGION_SIZE);

        // Bilinear-interpolate the DLA heightmap
        float h = bilinearSample(hmap, localX, localZ);

        // Fade to zero near region boundaries to avoid seams
        h *= boundaryBlend(localX, localZ);

        return h;
    }

    // ── Voronoi Cell Logic ────────────────────────────────────────────────────

    /**
     * Returns true if region (regionX, regionZ) should be an alpine peak.
     * Two conditions must both be true:
     *   1. Hash of the region coordinates lands in the alpine fraction
     *   2. The region's geographic centre is continental enough
     */
    private boolean isAlpineRegion(int regionX, int regionZ) {
        long hash = regionHash(regionX, regionZ);

        // Fast early exit: only 1 in 20 regions pass the hash gate
        long positiveHash = hash & Long.MAX_VALUE;  // strip sign bit
        if (positiveHash % 20 != 0) return false;   // 5% pass ≈ ALPINE_CHANCE

        // Slow check (only for the 5% that passed): continental gate
        int centerX = regionX * REGION_SIZE + REGION_SIZE / 2;
        int centerZ = regionZ * REGION_SIZE + REGION_SIZE / 2;
        return contSampler.sample(centerX, centerZ) >= MIN_CONTINENTAL;
    }

    /**
     * High-quality hash of region coordinates mixed with the world seed.
     * Uses xorshift64* mixing — produces good statistical uniformity.
     */
    private long regionHash(int regionX, int regionZ) {
        long v = worldSeed
                ^ ((long) regionX * 0x9E3779B97F4A7C15L)
                ^ ((long) regionZ * 0x6C62272E07BB0142L);
        v ^= (v >>> 30); v *= 0xBF58476D1CE4E5B9L;
        v ^= (v >>> 27); v *= 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    // ── Heightmap Management ──────────────────────────────────────────────────

    private float[][] getOrComputeHeightmap(int regionX, int regionZ) {
        long key = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
        return cache.computeIfAbsent(key, k -> computeDlaHeightmap(regionX, regionZ));
    }

    /**
     * Runs the DLA simulation for one region.
     * Only called once per alpine region encountered during play.
     *
     * Algorithm:
     *   1. Seed the aggregate at the grid centre (the mountain's peak).
     *   2. Spawn N particles one-by-one on a circle around the centre.
     *   3. Each particle random-walks (4-connected) until adjacent to the aggregate.
     *   4. When a particle touches the aggregate, it attaches. Its height = f(dist from centre).
     *   5. The result is a branching DLA cluster. Blur it for smooth transitions.
     */
    private float[][] computeDlaHeightmap(int regionX, int regionZ) {
        long   seed = regionHash(regionX, regionZ);
        Random rng  = new Random(seed);

        boolean[][] aggregate = new boolean[GRID_SIZE][GRID_SIZE];
        float[][]   heights   = new float  [GRID_SIZE][GRID_SIZE];

        int cx = GRID_SIZE / 2;
        int cz = GRID_SIZE / 2;

        // Seed: the mountain peak at the centre
        aggregate[cx][cz] = true;
        heights[cx][cz]   = 1.0f;

        // Maximum walk radius (leave a 2-cell buffer at the grid border)
        int maxR = (GRID_SIZE / 2) - 3;

        for (int p = 0; p < N_PARTICLES; p++) {
            // Spawn on a circle of radius maxR around the centre
            double angle = rng.nextDouble() * Math.PI * 2.0;
            int    px    = cx + (int)(maxR * Math.cos(angle));
            int    pz    = cz + (int)(maxR * Math.sin(angle));
            px = clamp(px, 1, GRID_SIZE - 2);
            pz = clamp(pz, 1, GRID_SIZE - 2);

            // Random walk — stop if the particle steps off the grid (discard)
            int maxSteps = GRID_SIZE * GRID_SIZE * 4;
            boolean attached = false;
            for (int step = 0; step < maxSteps; step++) {
                // Check adjacency to aggregate (4-connected neighbours)
                if (isAdjacentToAggregate(aggregate, px, pz)) {
                    // Attach here
                    aggregate[px][pz] = true;
                    // Height: inversely proportional to distance from peak.
                    // Ridge arms radiate outward and get shorter/lower as they branch.
                    float dist = (float) Math.sqrt((px - cx) * (px - cx) + (pz - cz) * (pz - cz));
                    heights[px][pz] = Math.max(0.05f, 1f - (dist / maxR) * 0.85f);
                    attached = true;
                    break;
                }

                // Random walk step — bias slightly toward centre to prevent
                // particles from wandering to the border and being trapped.
                int dir = rng.nextInt(5); // 4 directions + 1 "toward centre" bias step
                if (dir == 4) {
                    // Step toward centre (mild drift)
                    px += Integer.signum(cx - px);
                    pz += Integer.signum(cz - pz);
                } else {
                    switch (dir) {
                        case 0: px--; break;
                        case 1: px++; break;
                        case 2: pz--; break;
                        case 3: pz++; break;
                    }
                }

                // If walker exits the spawn radius, respawn it (prevents very long walks)
                float distFromCentre = (float) Math.sqrt((px - cx) * (px - cx) + (pz - cz) * (pz - cz));
                if (distFromCentre > maxR) {
                    angle = rng.nextDouble() * Math.PI * 2.0;
                    px    = cx + (int)(maxR * Math.cos(angle));
                    pz    = cz + (int)(maxR * Math.sin(angle));
                    px = clamp(px, 1, GRID_SIZE - 2);
                    pz = clamp(pz, 1, GRID_SIZE - 2);
                }
            }
        }

        // Build heightmap: aggregate cells have their height, rest = 0
        float[][] heightmap = new float[GRID_SIZE][GRID_SIZE];
        for (int gx = 0; gx < GRID_SIZE; gx++)
            for (int gz = 0; gz < GRID_SIZE; gz++)
                heightmap[gx][gz] = aggregate[gx][gz] ? heights[gx][gz] : 0f;

        // Blur 3 times to soften the ridges and spread the mountain base.
        // Without blurring, the mountain would be razor-thin lines (individual cells).
        heightmap = boxBlur(heightmap, 3);
        heightmap = boxBlur(heightmap, 2);
        heightmap = boxBlur(heightmap, 2);

        return heightmap;
    }

    private boolean isAdjacentToAggregate(boolean[][] agg, int x, int z) {
        return (x > 0           && agg[x-1][z])
                || (x < GRID_SIZE-1 && agg[x+1][z])
                || (z > 0           && agg[x][z-1])
                || (z < GRID_SIZE-1 && agg[x][z+1]);
    }

    // ── Sampling & Blending ───────────────────────────────────────────────────

    /**
     * Bilinear interpolation of the DLA heightmap.
     * Converts local world position [0, REGION_SIZE) to a smooth grid sample.
     */
    private float bilinearSample(float[][] hmap, int localX, int localZ) {
        float cellX = localX / (float) BLOCKS_PER_CELL;
        float cellZ = localZ / (float) BLOCKS_PER_CELL;

        int   gx = clamp((int) cellX, 0, GRID_SIZE - 2);
        int   gz = clamp((int) cellZ, 0, GRID_SIZE - 2);
        float fx = cellX - gx;
        float fz = cellZ - gz;

        float h00 = hmap[gx    ][gz    ];
        float h10 = hmap[gx + 1][gz    ];
        float h01 = hmap[gx    ][gz + 1];
        float h11 = hmap[gx + 1][gz + 1];

        return lerp(lerp(h00, h10, fx), lerp(h01, h11, fx), fz);
    }

    /**
     * Returns a [0, 1] blend factor that is 1.0 in the interior and fades to 0
     * within BLEND_MARGIN blocks of the region boundary.
     * This prevents a hard seam where the DLA region ends.
     */
    private float boundaryBlend(int localX, int localZ) {
        int distToEdge = Math.min(
                Math.min(localX, REGION_SIZE - 1 - localX),
                Math.min(localZ, REGION_SIZE - 1 - localZ));
        if (distToEdge >= BLEND_MARGIN) return 1f;
        float t = (float) distToEdge / BLEND_MARGIN;
        return t * t * (3f - 2f * t);   // smoothstep for less abrupt falloff
    }

    // ── Box Blur ──────────────────────────────────────────────────────────────

    private float[][] boxBlur(float[][] grid, int radius) {
        int size   = grid.length;
        float[][] r = new float[size][size];
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                float sum = 0;
                int   cnt = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int nx = x + dx, nz = z + dz;
                        if (nx >= 0 && nx < size && nz >= 0 && nz < size) {
                            sum += grid[nx][nz];
                            cnt++;
                        }
                    }
                }
                r[x][z] = cnt > 0 ? sum / cnt : 0;
            }
        }
        return r;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private float lerp(float a, float b, float t)    { return a + t * (b - a); }
    private int   clamp(int v, int min, int max)      { return Math.max(min, Math.min(max, v)); }
}