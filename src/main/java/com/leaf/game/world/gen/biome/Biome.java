package com.leaf.game.world.gen.biome;

import com.leaf.game.world.Block;

/**
 * All biomes in the world.
 */
public enum Biome {

    // ── AQUATIC ───────────────────────────────────────────────────────────────
    OCEAN,           // deep or shallow basin below sea level
    BEACH,           // narrow coastal strip straddling sea level

    // ── TRANSITIONAL ─────────────────────────────────────────────────────────
    RIVER,           // carved channel — always has water post-pass

    // ── HOT (temp > 0.40) ────────────────────────────────────────────────────
    DESERT,          // hot + dry  — sand dunes, no life
    SAVANNA,         // hot + moist — flat warm grassland

    // ── TEMPERATE (temp 0.0 → 0.40) ──────────────────────────────────────────
    PLAINS,          // warm + dry  — open grassy flatlands
    FOREST,          // warm + wet  — rolling hills, dense trees (future)

    // ── COLD (temp -0.30 → 0.0) ──────────────────────────────────────────────
    TAIGA,           // cold boreal — dark grass, pines (future)

    // ── FROZEN (temp < -0.30) ─────────────────────────────────────────────────
    TUNDRA,          // frozen + wet  — patchy snow over dirt
    SNOWY_PLAINS,    // frozen + dry  — white plains, stone subsurface

    // ── ALTITUDE OVERRIDE (any temp, ty >= snowAltitude) ─────────────────────
    ICY_PEAKS,       // forced by elevation — snow capping stone

    // ── SPECIAL PATCH BIOMES (rare, chosen by the biome-patch selector noise) ──
    VOLCANIC,        // black basalt + magma + ash — home of the Inferno Towers
    SAKURA,          // pink cherry-blossom grove
    MUSHROOM,        // bioluminescent fungal forest
    CRYSTAL_FIELDS,  // lavender meadow studded with amethyst geodes
    AUTUMN;          // golden maple woods

    /** Top-most visible block at terrain surface. */
    public Block surfaceBlock() {
        return switch (this) {
            case DESERT       -> Block.SAND;
            case BEACH, RIVER -> Block.SAND;
            case SAVANNA      -> Block.RED_SAND;
            case TUNDRA, SNOWY_PLAINS,
                 ICY_PEAKS    -> Block.SNOW;
            case VOLCANIC       -> Block.BASALT;
            case SAKURA         -> Block.SAKURA_GRASS;
            case MUSHROOM       -> Block.MYCELIUM;
            case CRYSTAL_FIELDS -> Block.AMETHYST_GRASS;
            case AUTUMN         -> Block.AUTUMN_GRASS;
            default           -> Block.GRASS;
        };
    }

    /**
     * The subsurface material below the surface block.
     */
    public Block subSurfaceBlock() {
        return switch (this) {
            case DESERT, BEACH, RIVER    -> Block.SAND;
            case SAVANNA                 -> Block.RED_SAND;
            case ICY_PEAKS               -> Block.ICE;
            case SNOWY_PLAINS            -> Block.STONE;
            case VOLCANIC                -> Block.BASALT;
            case SAKURA                  -> Block.SAKURA_SOIL;
            case MUSHROOM                -> Block.DIRT;
            case CRYSTAL_FIELDS          -> Block.CRYSTAL_SOIL;
            case AUTUMN                  -> Block.DIRT;
            default                      -> Block.DIRT;
        };
    }
}