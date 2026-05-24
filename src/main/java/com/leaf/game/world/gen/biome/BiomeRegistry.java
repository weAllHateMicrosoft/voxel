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
     */
    public static Biome evaluate(float shape, float seaFrac, boolean isRiver,
                                 int ty, float temp, float hum) {

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