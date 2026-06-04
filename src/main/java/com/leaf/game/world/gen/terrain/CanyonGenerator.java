package com.leaf.game.world.gen.terrain;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Noise;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.gen.WorldGen;

/**
 * CANYON / MESA terrain — a faithful voxel port of gelami's hybrid SDF-voxel
 * Shadertoy ("Hybrid SDF-Voxel Traversal").
 *
 * The Shadertoy's terrain is defined entirely by its {@code map()} function:
 *
 *   d  = noise(q*1)*0.5 + noise(q*2)*0.25 + noise(q*4)*0.125;   // 3-octave FBM, [0,1]
 *   d  = (d/0.875 - SURFACE_FACTOR) / sc;                       // SDF: <0 inside (solid)
 *   d  = smax(d, p.y - MAX_HEIGHT, 0.6);                        // flat sky cap at the top
 *
 * The crucial properties we reproduce exactly:
 *   • PURE 3D ISOSURFACE — solid wherever {@code fbm(x,y,z) < SURFACE_FACTOR},
 *     with NO heightmap and NO vertical bias.  This (not a heightmap) is what
 *     creates the caverns, arches, overhangs and stacked rock masses.
 *   • ISOTROPIC — the same frequency on x, y and z, so features are as tall as
 *     they are wide (the bulbous mesa look), never squashed.
 *   • FLAT TOP CAP — everything above the ceiling is open sky; near the ceiling
 *     the threshold ramps off so the tops round and thin out (the {@code smax}).
 *
 * Colouring follows the Shadertoy's scheme: warm tan/terracotta sedimentary
 * strata, vivid green only on up-facing surfaces above water (its
 * {@code smoothstep(0.3,0.7,gn.y)} grass test, done here via the field gradient),
 * pale sand shorelines and turquoise pools.
 *
 * Like {@link AbyssGenerator} the canyon lives in a circular zone with a cheap
 * cull ({@link #isInZone}) and a smooth rim ({@link #zoneBlend}) that lowers the
 * sky cap toward water at the edge so it sits in a bowl, not behind a wall.
 *
 * All shaping lives in {@link GameConfig} (the {@code canyon*} block); the two
 * you'll tune most are {@code canyonFreq} (feature size) and
 * {@code canyonSurfaceFactor} (how solid vs. airy the rock is).
 */
public class CanyonGenerator {

    private final Noise density;   // the 3D value-noise field (the FBM source)
    private final Noise band;      // per-column jitter for the strata banding

    /** Seed-derived fractional offsets so Perlin's integer-cell zeros don't alias. */
    private final float offX, offZ;

    public CanyonGenerator(long seed) {
        this.density = new Noise(seed + 70001L);
        this.band    = new Noise(seed + 70004L);

        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        offX = ((h & 0xFFFFL) / (float) 0xFFFF) * 512f;
        offZ = (((h >>> 16) & 0xFFFFL) / (float) 0xFFFF) * 512f;
    }

    // ── ZONE TEST ──────────────────────────────────────────────────────────────

    /** Cheap cull: true if this column is anywhere inside the canyon + its blend band. */
    public boolean isInZone(int wx, int wz) {
        float dx = wx - GameConfig.canyonCenterX;
        float dz = wz - GameConfig.canyonCenterZ;
        float outer = GameConfig.canyonRadius + GameConfig.canyonEdgeBand;
        return (dx * dx + dz * dz) < (outer * outer);
    }

    /** 1 in the canyon core, smoothstep to 0 across the outer band, 0 beyond. */
    public float zoneBlend(int wx, int wz) {
        float dx = wx - GameConfig.canyonCenterX;
        float dz = wz - GameConfig.canyonCenterZ;
        float r  = (float) Math.sqrt(dx * dx + dz * dz);
        if (r <= GameConfig.canyonRadius) return 1f;
        float outer = GameConfig.canyonRadius + GameConfig.canyonEdgeBand;
        if (r >= outer) return 0f;
        float u = (outer - r) / GameConfig.canyonEdgeBand;
        return u * u * (3f - 2f * u);
    }

    // ── COLUMN GENERATION ────────────────────────────────────────────────────────

    /**
     * Fully generate one canyon column from the 3D isosurface (overwrites every
     * Y level).  Call instead of the normal surface pipeline for columns where
     * {@link #isInZone} is true, then {@code continue}.
     */
    public void generateColumn(Chunk chunk, int lx, int lz, int wx, int wz, WorldGen wg) {
        final int H = Chunk.HEIGHT;
        float t = zoneBlend(wx, wz);                 // 1 core → 0 rim

        int   floorY = GameConfig.canyonFloorY;      // below = solid foundation
        int   ceilY  = GameConfig.canyonCeilingY;    // sky cap (core height)
        int   waterY = GameConfig.canyonWaterLevel;
        float surfF  = GameConfig.canyonSurfaceFactor;
        float fadeH  = Math.max(1f, GameConfig.canyonTopFade);

        // Rim easing: drop the ceiling toward the water at the edge so the canyon
        // sits in a bowl rather than ending in a sheer wall against the world.
        int effCeil = Math.round(lerp(waterY + 4, ceilY, t));

        boolean[] solid = new boolean[H];
        for (int ly = 0; ly < H; ly++) {
            if (ly <= 1)      { solid[ly] = true;  continue; }   // bedrock
            if (ly < floorY)  { solid[ly] = true;  continue; }   // buried foundation
            if (ly > effCeil) { solid[ly] = false; continue; }   // open sky
            float d = fbm(wx, ly, wz);                            // [0,1] FBM
            // Soft sky cap: threshold ramps below the FBM floor over the top fadeH
            // blocks → rounded, thinning mesa tops (the shader's smax).
            float topFade = clamp((ly - (effCeil - fadeH)) / fadeH, 0f, 1f);
            float thr = surfF - topFade * (surfF + 0.06f);
            solid[ly] = d < thr;
        }

        // ── Paint blocks top-down ─────────────────────────────────────────────
        boolean underGround = false, hitSurface = false, surfaceGrass = false;
        int soil = 0;

        for (int ly = H - 1; ly >= 0; ly--) {
            if (!solid[ly]) {
                boolean pool = !underGround && ly <= waterY;     // basins fill, caves don't
                chunk.setBlock(lx, ly, lz, pool ? Block.MESA_WATER : Block.AIR);
                continue;
            }
            underGround = true;

            if (!hitSurface) {
                hitSurface = true;
                soil = 0;
                if (ly > waterY && surfaceFacesUp(wx, ly, wz)) {
                    chunk.setBlock(lx, ly, lz, Block.MESA_GRASS);   // vivid green on up-faces
                    surfaceGrass = true;
                } else if (ly > waterY) {
                    chunk.setBlock(lx, ly, lz, bandBlock(wx, wz, ly)); // bare banded cliff
                    surfaceGrass = false;
                } else {
                    chunk.setBlock(lx, ly, lz, Block.MESA_SAND);    // shoreline / lake bed
                    surfaceGrass = false;
                }
            } else if (surfaceGrass && soil < 3) {
                soil++;
                chunk.setBlock(lx, ly, lz, Block.MESA_DIRT);
            } else {
                chunk.setBlock(lx, ly, lz,
                        (ly < floorY + 6) ? Block.MESA_STONE : bandBlock(wx, wz, ly));
            }
        }

        // Bedrock floor — last, so nothing can drop the player out the bottom.
        chunk.setBlock(lx, 0, lz, Block.STONE);
        chunk.setBlock(lx, 1, lz, Block.STONE);
    }

    // ── NOISE ────────────────────────────────────────────────────────────────────

    /**
     * 3-octave value noise in [0,1], ISOTROPIC in block space — the faithful
     * port of the Shadertoy FBM:
     *   d = n(q)*0.5 + n(q*2)*0.25 + n(q*4)*0.125, normalised by 0.875.
     */
    private float fbm(float x, float y, float z) {
        float f = GameConfig.canyonFreq;
        float n1 = val(x * f,            y * f,            z * f);
        float n2 = val(x * 2f * f + 0.3f, y * 2f * f + 0.3f, z * 2f * f + 0.3f);
        float n3 = val(x * 4f * f + 0.7f, y * 4f * f + 0.7f, z * 4f * f + 0.7f);
        return (n1 * 0.5f + n2 * 0.25f + n3 * 0.125f) / 0.875f;
    }

    /** Single value-noise sample remapped to [0,1] (Perlin is ~[-1,1]). */
    private float val(float x, float y, float z) {
        return (density.get3D(x + offX, y, z + offZ) + 1f) * 0.5f;
    }

    /**
     * True where the isosurface faces up enough to grow grass — the shader's
     * {@code smoothstep(0.3,0.7,gn.y)} test.  Solid is where fbm &lt; threshold,
     * so the outward (air-side) normal is +gradient; a top surface has gradient.y &gt; 0.
     */
    private boolean surfaceFacesUp(int wx, int ly, int wz) {
        float gx = fbm(wx + 1, ly, wz) - fbm(wx - 1, ly, wz);
        float gy = fbm(wx, ly + 1, wz) - fbm(wx, ly - 1, wz);
        float gz = fbm(wx, ly, wz + 1) - fbm(wx, ly, wz - 1);
        float len = (float) Math.sqrt(gx * gx + gy * gy + gz * gz) + 1e-5f;
        return (gy / len) > 0.45f;
    }

    /**
     * Horizontal sedimentary strata by world-Y, with a little per-column jitter
     * so the bands waver — the layered-sandstone look (light → mid → dark → mid).
     */
    private Block bandBlock(int wx, int wz, int y) {
        int thick = Math.max(1, GameConfig.canyonBandThickness);
        float j = band.get((wx + offX) * 0.07f, (wz + offZ) * 0.07f) * thick;
        int idx = Math.floorDiv((int) (y + j), thick);
        switch (((idx % 4) + 4) % 4) {
            case 0:  return Block.MESA_SAND;        // light band
            case 2:  return Block.MESA_TERRACOTTA;  // dark band
            default: return Block.MESA_CLAY;        // mid bands (1, 3)
        }
    }

    // ── BLUE ZONE (Shadertoy snow biome) ──────────────────────────────────────

    /** True if this column falls inside the blue canyon zone (check BEFORE warm zone). */
    public boolean isInBlueZone(int wx, int wz) {
        float dx = wx - GameConfig.canyonBlueCenterX;
        float dz = wz - GameConfig.canyonBlueCenterZ;
        float outer = GameConfig.canyonBlueRadius + GameConfig.canyonBlueEdgeBand;
        return (dx * dx + dz * dz) < (outer * outer);
    }

    private float blueZoneBlend(int wx, int wz) {
        float dx = wx - GameConfig.canyonBlueCenterX;
        float dz = wz - GameConfig.canyonBlueCenterZ;
        float r  = (float) Math.sqrt(dx * dx + dz * dz);
        if (r <= GameConfig.canyonBlueRadius) return 1f;
        float outer = GameConfig.canyonBlueRadius + GameConfig.canyonBlueEdgeBand;
        if (r >= outer) return 0f;
        float u = (outer - r) / GameConfig.canyonBlueEdgeBand;
        return u * u * (3f - 2f * u);
    }

    /**
     * Same 3D isosurface as the warm canyon but coloured with the Shadertoy's
     * snow biome palette: blue-grey rock strata, white-blue snow tops.
     */
    public void generateBlueColumn(Chunk chunk, int lx, int lz, int wx, int wz) {
        final int H = Chunk.HEIGHT;
        float t = blueZoneBlend(wx, wz);

        int   floorY = GameConfig.canyonFloorY;
        int   ceilY  = GameConfig.canyonCeilingY;
        int   waterY = GameConfig.canyonWaterLevel;
        float surfF  = GameConfig.canyonSurfaceFactor;
        float fadeH  = Math.max(1f, GameConfig.canyonTopFade);

        int effCeil = Math.round(lerp(waterY + 4, ceilY, t));

        boolean[] solid = new boolean[H];
        for (int ly = 0; ly < H; ly++) {
            if (ly <= 1)      { solid[ly] = true;  continue; }
            if (ly < floorY)  { solid[ly] = true;  continue; }
            if (ly > effCeil) { solid[ly] = false; continue; }
            float d = fbm(wx, ly, wz);
            float topFade = clamp((ly - (effCeil - fadeH)) / fadeH, 0f, 1f);
            float thr = surfF - topFade * (surfF + 0.06f);
            solid[ly] = d < thr;
        }

        boolean underGround = false, hitSurface = false, surfaceSnow = false;
        int soil = 0;

        for (int ly = H - 1; ly >= 0; ly--) {
            if (!solid[ly]) {
                boolean pool = !underGround && ly <= waterY;
                chunk.setBlock(lx, ly, lz, pool ? Block.MESA_WATER : Block.AIR);
                continue;
            }
            underGround = true;

            if (!hitSurface) {
                hitSurface = true;
                soil = 0;
                if (ly > waterY && surfaceFacesUp(wx, ly, wz)) {
                    chunk.setBlock(lx, ly, lz, Block.MESA_BLUE_SNOW);
                    surfaceSnow = true;
                } else if (ly > waterY) {
                    chunk.setBlock(lx, ly, lz, bandBlockBlue(wx, wz, ly));
                    surfaceSnow = false;
                } else {
                    chunk.setBlock(lx, ly, lz, Block.MESA_BLUE_LIGHT);  // pale shore
                    surfaceSnow = false;
                }
            } else if (surfaceSnow && soil < 3) {
                soil++;
                chunk.setBlock(lx, ly, lz, Block.MESA_BLUE_SOIL);
            } else {
                chunk.setBlock(lx, ly, lz,
                        (ly < floorY + 6) ? Block.MESA_BLUE_STONE : bandBlockBlue(wx, wz, ly));
            }
        }

        chunk.setBlock(lx, 0, lz, Block.STONE);
        chunk.setBlock(lx, 1, lz, Block.STONE);
    }

    /** Blue sedimentary strata: light → mid → dark → mid, matching Shadertoy snow palette. */
    private Block bandBlockBlue(int wx, int wz, int y) {
        int thick = Math.max(1, GameConfig.canyonBandThickness);
        float j = band.get((wx + offX) * 0.07f, (wz + offZ) * 0.07f) * thick;
        int idx = Math.floorDiv((int) (y + j), thick);
        switch (((idx % 4) + 4) % 4) {
            case 0:  return Block.MESA_BLUE_LIGHT;  // light band
            case 2:  return Block.MESA_BLUE_DARK;   // dark band
            default: return Block.MESA_BLUE_MID;    // mid bands (1, 3)
        }
    }

    private static float lerp(float a, float b, float t) { return a + t * (b - a); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
