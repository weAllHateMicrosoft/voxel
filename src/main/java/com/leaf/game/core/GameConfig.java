package com.leaf.game.core;

public class GameConfig {

    // ── WORLD ────────────────────────────────────────────────────────────────
    public static long  seed           = 12345L;
    public static int   renderDistance = 6;
    public static int   seaLevel       = 20;

    // ── CONTINENTALNESS ───────────────────────────────────────────────────────
    // Target: 4-6 giant blobs spanning hundreds of blocks, clear contrast.
    // Low freq + very few octaves = wide smooth regions with no fine noise breaking them up.
    // Contrast sharpening (in WorldGen) pushes values toward ±1 for clearer ocean/land split.
    public static float contFreq    = 0.002f;   // feature size ≈ 500 blocks
    public static int   contOctaves = 2;        // keep smooth — more octaves = more speckle
    public static float contPersist = 0.35f;    // low persistence = second octave barely shows

    // ── EROSION ───────────────────────────────────────────────────────────────
    // Target: 1-3 large organic basins with river-carved boundaries.
    // Domain warp makes the basin edges look torn/eroded rather than blobby.
    // erosWarpStrength is in WORLD BLOCKS — it offsets the sample point before frequency scaling.
    public static float erosFreq         = 0.0015f;  // feature size ≈ 650 blocks
    public static int   erosOctaves      = 3;
    public static float erosPersist      = 0.45f;
    public static float erosWarpFreq     = 0.001f;   // scale of the warping field (coarser than erosion)
    public static float erosWarpStrength = 180f;     // ← WORLD UNITS (not frequency units!)
    //   ±180 blocks offset → 180*0.0015 = ±0.27 in Perlin space = ~27% of feature

    // ── PEAKS & VALLEYS ───────────────────────────────────────────────────────
    // Target: intricate ridgeline network — clear outlines, distinct peaks and valleys.
    // Uses ridgedOctave (not plain octave) in WorldGen.
    // Base feature size needs to be visible in the map: 1/freq blocks.
    public static float pvFreq    = 0.008f;  // feature size ≈ 125 blocks → clear ridges at map zoom
    public static int   pvOctaves = 5;       // many octaves = complex ridge branching
    public static float pvPersist = 0.55f;   // slightly higher = more fine ridge detail

    // ── TEMPERATURE ───────────────────────────────────────────────────────────
    // Target: very smooth large-scale gradient (larger than continents).
    public static float tempFreq    = 0.001f;  // feature size ≈ 1000 blocks
    public static int   tempOctaves = 2;
    public static float tempPersist = 0.3f;

    // ── HUMIDITY ──────────────────────────────────────────────────────────────
    public static float humFreq    = 0.002f;  // feature size ≈ 500 blocks
    public static int   humOctaves = 3;
    public static float humPersist = 0.4f;

    // ── HEIGHT MAPPING ────────────────────────────────────────────────────────
    public static int heightBase  = 10;
    public static int heightRange = 40;

    // ── 3D DENSITY NOISE ──────────────────────────────────────────────────────
    public static float density3DFreq             = 0.05f;
    public static float density3DVerticalCompress = 0.5f;
    public static int   density3DOctaves          = 3;
    public static float density3DPersist          = 0.5f;
    public static float density3DAmplitude        = 10f;

    // ── DENSITY SHAPE ─────────────────────────────────────────────────────────
    public static float densityVerticalScale = 0.15f;
    public static float densityErosionBoost  = 0.15f;

    // ── LIGHTING ──────────────────────────────────────────────────────────────
    public static float sunDirX         = 0.6f;
    public static float sunDirY         = 1.0f;
    public static float sunDirZ         = 0.4f;
    public static float sunStrength     = 0.75f;
    public static float ambientStrength = 0.25f;

    // ── PLAYER / CAMERA ───────────────────────────────────────────────────────
    public static float mouseSensitivity = 0.001f;
    public static float fov              = 70.0f;

    // ── PLAYER PHYSICS ─────────────────────────────────────────────────────────
    public static float GRAVITY      = 35.0f;
    public static float JUMP_FORCE   = 10.0f;
    public static float WALK_SPEED   = 5.0f;
    public static float SPRINT_SPEED = 8.5f;
    public static float FLY_SPEED    = 15.0f;
}