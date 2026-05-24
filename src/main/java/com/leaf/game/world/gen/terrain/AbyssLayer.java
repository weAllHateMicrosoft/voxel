package com.leaf.game.world.gen.terrain;

import com.leaf.game.world.Block;

/**
 * The six vertical layers of the Abyss.
 *
 * To add a new layer:
 *   1. Add an enum constant below.
 *   2. Add a boundary in AbyssConfig.
 *   3. Update forDepth() below.
 *   4. Add new Block entries (bioluminescent stone, basalt, etc.) in Block.java.
 *
 * Planned future fields (add here, consume in AbyssGenerator):
 *   - Block bioluminescentBlock    (Layer 2/4: glowing flora)
 *   - CaveStyle caveStyle          (CHEESE / CATHEDRAL / SPAGHETTI per layer)
 *   - float ambientLightBoost      (Layer 4 is brighter than Layer 5)
 */
public enum AbyssLayer {

    // ── Layer 1 ── Edge of the Abyss (surfaceY → layer2Start)
    // Open, bright, moss-covered. Wide ledges encourage exploration.
    EDGE(
            "Edge of the Abyss",
            Block.STONE, Block.DIRT,
            1.0f,   // normal wall amplitude
            1.0f    // normal ledge frequency
    ),

    // ── Layer 2 ── Forest of Temptation (layer2Start → layer3Start)
    // Dark organic walls. Dirt face with root-like textures. Slightly more jagged.
    FOREST(
            "Forest of Temptation",
            Block.DIRT,  Block.DIRT,
            1.15f,  // slightly more jagged walls
            0.90f   // slightly fewer but still common ledges
    ),

    // ── Layer 3 ── The Great Fault (layer3Start → layer4Start)
    // Near-vertical sheer stone walls. Almost no ledges — traversal is harrowing.
    // wallAmplitudeScale is low: walls are smooth/sheer like a real fault scarp.
    GREAT_FAULT(
            "The Great Fault",
            Block.STONE, Block.STONE,
            0.40f,  // very smooth walls — the defining visual of this layer
            0.25f   // effective threshold = 0.65/0.25 = 2.6 → NO ledges
    ),

    // ── Layer 4 ── Goblet of Giants (layer4Start → layer5Start)
    // Vast open chambers, wide irregular walls, frequent massive ledge shelves.
    GOBLET(
            "Goblet of Giants",
            Block.STONE, Block.STONE,
            1.30f,  // widest, most irregular walls
            1.15f   // more ledges (giant shelves, feel like floor-levels)
    ),

    // ── Layer 5 ── Sea of Corpses (layer5Start → layer6Start)
    // Oppressive, narrow. Pale stone. Very few ledges.
    SEA(
            "Sea of Corpses",
            Block.STONE, Block.STONE,
            0.65f,
            0.30f   // effective threshold = 0.65/0.30 = 2.17 → almost no ledges
    ),

    // ── Layer 6 ── Capital of the Unreturned (layer6Start → 0)
    // Ancient volcanic floor. Will gain basalt, obsidian, lava in future phases.
    CAPITAL(
            "Capital of the Unreturned",
            Block.STONE, Block.STONE,
            0.85f,
            0.55f
    );

    // ── Properties ───────────────────────────────────────────────────────────

    public final String displayName;

    /** The block type on the exposed wall face (visible from inside the shaft). */
    public final Block wallFaceBlock;

    /** Block type for ledge surfaces and the 3 blocks behind the wall face. */
    public final Block ledgeBlock;

    /**
     * Multiplier on waveAmplitude + ridgeAmplitude.
     * < 1 = smoother, sheer walls.  > 1 = more jagged, overhanging walls.
     */
    public final float wallAmplitudeScale;

    /**
     * The effective ledge threshold = AbyssConfig.ledgeThreshold / ledgeFreqScale.
     * Values that push the result > 1.0 produce ZERO ledges in that layer.
     */
    public final float ledgeFreqScale;

    AbyssLayer(String name, Block wall, Block ledge, float wallAmp, float ledgeFreq) {
        this.displayName        = name;
        this.wallFaceBlock      = wall;
        this.ledgeBlock         = ledge;
        this.wallAmplitudeScale = wallAmp;
        this.ledgeFreqScale     = ledgeFreq;
    }

    /**
     * Returns the correct layer for a world-Y coordinate.
     *
     * Handles infinite depth (worldY < 0) by cycling through increasingly
     * harsh strata every 512 blocks, matching the Chunk.HEIGHT stride so each
     * deep chunk has a consistent, stable character.
     *
     * Cycle layout per 512 blocks below y=0:
     *   0 – 80   → CAPITAL   (ancient volcanic remnants)
     *   80 – 200 → SEA / GREAT_FAULT (escalates with depth)
     *   200 – 340 → GREAT_FAULT  (oppressively smooth sheer walls)
     *   340 – 430 → GOBLET       (rare vast chambers break the monotony)
     *   430 – 512 → CAPITAL      (back to ancient base)
     */
    public static AbyssLayer forDepth(int worldY) {
        // ── Standard surface layers ───────────────────────────────────────────
        if (worldY >= AbyssConfig.layer2Start) return EDGE;
        if (worldY >= AbyssConfig.layer3Start) return FOREST;
        if (worldY >= AbyssConfig.layer4Start) return GREAT_FAULT;
        if (worldY >= AbyssConfig.layer5Start) return GOBLET;
        if (worldY >= AbyssConfig.layer6Start) return SEA;
        if (worldY >= 0)                       return CAPITAL;

        // ── Infinite depth: cycle every 512 blocks below y = 0 ───────────────
        int depthBelow = -worldY;                    // positive distance below y=0
        int cycleNum   = depthBelow / 512;           // which 512-block cycle we're in
        int cyclePos   = depthBelow % 512;           // position within that cycle

        // As cycles increase, layers grow harsher and ledges rarer
        if (cyclePos < 80)  return CAPITAL;
        if (cyclePos < 200) return (cycleNum >= 2) ? GREAT_FAULT : SEA;
        if (cyclePos < 340) return GREAT_FAULT;
        if (cyclePos < 430) return (cycleNum >= 1) ? SEA : GOBLET;
        return CAPITAL;
    }
}