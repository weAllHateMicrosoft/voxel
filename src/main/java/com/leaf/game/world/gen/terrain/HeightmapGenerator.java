// --- FILE: src/main/java/com/leaf/game/world/gen/terrain/HeightmapGenerator.java ---
package com.leaf.game.world.gen.terrain;

/**
 * Common interface for all advanced terrain generators (DLA, Eroded FBM, etc).
 */
public interface HeightmapGenerator {

    /**
     * Calculates the normalized height contribution [0, 1] at a given world coordinate.
     *
     * @param wx World X coordinate
     * @param wz World Z coordinate
     * @return A value between 0.0 (no height) and 1.0 (maximum peak height)
     */
    float getHeight(int wx, int wz);
}