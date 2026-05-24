package com.leaf.game.world.gen.biome;

import com.leaf.game.world.Block;

public class BlockCladder {

    private final int snowAltitudeY;

    private static final float CLIFF_SLOPE = 1.30f;
    private static final float SNOW_SLOPE_LIMIT = 0.85f;

    public BlockCladder(int snowAltitudeY) {
        this.snowAltitudeY = snowAltitudeY;
    }

    public Block surfaceBlock(int surfaceY, float slopeMag) {
        // 1. Sheer cliffs are always exposed rock
        if (slopeMag > CLIFF_SLOPE) {
            return Block.STONE;
        }

        // 2. High peaks get snow
        if (surfaceY >= snowAltitudeY) {
            float localSnowLimit = SNOW_SLOPE_LIMIT + ((surfaceY - snowAltitudeY) * 0.015f);
            if (slopeMag < localSnowLimit) return Block.SNOW;
            return Block.STONE;
        }

        // 3. Mid-mountain gets rocky scree (Gravel)
        else if (surfaceY > snowAltitudeY - 30) {
            if (slopeMag > 0.6f) return Block.STONE;
            return Block.GRAVEL;
        }

        // 4. Mountain base gets barren dirt (No green grass!)
        else {
            if (slopeMag > 0.6f) return Block.STONE;
            return Block.DIRT;
        }
    }

    public Block subSurfaceBlock(int surfaceY, float slopeMag) {
        if (slopeMag > CLIFF_SLOPE) return Block.STONE;
        if (surfaceY >= snowAltitudeY) return Block.ICE;
        return Block.DIRT;
    }
}