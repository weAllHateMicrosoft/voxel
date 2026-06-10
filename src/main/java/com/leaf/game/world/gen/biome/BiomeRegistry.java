// --- FILE: src/main/java/com/leaf/game/world/gen/biome/BiomeRegistry.java ---
package com.leaf.game.world.gen.biome;

import com.leaf.game.core.GameConfig;

/**
 * Evaluates climate conditions to return the correct biome.
 * Keeps WorldGen clean and allows for easy addition of new biomes later.
 */
public class BiomeRegistry {

    /**
     * Determines the biome based on terrain shape, elevation, and climate.
     *
     * @param patch low-frequency "biome-patch selector" noise (~[-1,1]). High values
     *              carve out rare special biomes (volcanic, sakura, …) so they appear
     *              as coherent regions rather than smeared across the climate matrix.
     */
    public static Biome evaluate(float shape, float seaFrac, boolean isRiver,
                                 int ty, float temp, float hum, float patch) {

        // 1. Check aquatic / transitional states
        if (shape < seaFrac - 0.01f) return Biome.OCEAN;
        if (isRiver) return Biome.RIVER;

        // 2. Check strict elevation overrides
        if (ty >= GameConfig.seaLevel - 1 && ty <= GameConfig.seaLevel + GameConfig.beachMaxAltitude) {
            return Biome.BEACH;
        }
        if (ty >= GameConfig.snowAltitude) {
            return Biome.ICY_PEAKS;
        }

        // 2.5 SPECIAL PATCH BIOMES — rare, coherent regions selected by the patch
        //     noise and gated to a plausible climate so each reads naturally.
        //     (Volcanic lands in hot/dry zones, sakura in mild/wet, etc.)
        if (patch > 0.42f) {
            if (temp > 0.40f && hum < 0.12f)                    return Biome.VOLCANIC;
            if (temp > 0.05f && temp <= 0.40f && hum > 0.04f)   return Biome.SAKURA;
            if (temp <= 0.05f && temp > -0.30f && hum > 0.12f)  return Biome.MUSHROOM;
            if (temp <= -0.05f && hum < 0.02f)                  return Biome.CRYSTAL_FIELDS;
            if (temp > -0.05f && temp <= 0.30f)                 return Biome.AUTUMN;
        }

        // 3. Fallback to Temperature & Humidity Matrix
        if (temp > 0.55f) {
            return hum < 0.10f ? Biome.DESERT : Biome.SAVANNA;
        }
        if (temp > 0.20f) {
            return Biome.SAVANNA;
        }
        if (temp > -0.05f) {
            return hum < -0.10f ? Biome.PLAINS : Biome.FOREST;
        }
        if (temp > -0.30f) {
            return hum < -0.15f ? Biome.PLAINS : Biome.TAIGA;
        }

        return hum < 0.05f ? Biome.SNOWY_PLAINS : Biome.TUNDRA;
    }
}