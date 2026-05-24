package com.leaf.game.world.gen.biome;

import com.leaf.game.world.Block;

public class BlockCladder {

    private final int snowAltitudeY;

    // The threshold at which a slope becomes an exposed cliff face
    private static final float CLIFF_SLOPE = 0.60f;

    // The threshold required to hold snow on peaks
    private static final float SNOW_SLOPE_LIMIT = 0.45f;

    public BlockCladder(int snowAltitudeY) {
        this.snowAltitudeY = snowAltitudeY;
    }

    public Block surfaceBlock(int surfaceY, float slopeMag) {
        if (slopeMag > CLIFF_SLOPE) {
            return Block.STONE; // Sheer cliff face
        }
        if (surfaceY >= snowAltitudeY) {
            if (slopeMag < SNOW_SLOPE_LIMIT) return Block.SNOW; // Flat mountain peak
            return Block.STONE;
        }
        return Block.GRASS; // Foothills and valleys
    }

    public Block subSurfaceBlock(int surfaceY, float slopeMag) {
        if (slopeMag > CLIFF_SLOPE) return Block.STONE;
        if (surfaceY >= snowAltitudeY) return Block.ICE;
        return Block.DIRT;
    }
}