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

        // 2.5 SPECIAL PATCH BIOMES — coherent regions selected by the low-frequency
        //     patch noise. The climate space is PARTITIONED (every branch returns a
        //     biome) so that whenever a patch qualifies it always becomes one of the
        //     special biomes — this makes them common enough to actually find while
        //     exploring, rather than rare slivers that fall through to the matrix.
        //     Ordered hot → cold; humidity breaks the cold tie (damp = mushroom).
        if (patch > 0.16f) {
            if (temp >  0.30f)  return Biome.VOLCANIC;        // hot & harsh
            if (temp >  0.06f)  return Biome.SAKURA;          // warm temperate
            if (temp > -0.12f)  return Biome.AUTUMN;          // cool temperate
            if (hum  >  0.00f)  return Biome.MUSHROOM;        // cold & damp
            return Biome.CRYSTAL_FIELDS;                       // cold & dry
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