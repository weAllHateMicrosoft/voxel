package com.leaf.game;

public class GameConfig {

    // ── WORLD ────────────────────────────────────────────────
    public static long  seed           = System.currentTimeMillis();
    public static int   renderDistance = 6;
    public static int   seaLevel       = 20;

    // ── NOISE — CONTINENTALNESS (2D, large scale) ─────────────
    public static float contFreq    = 0.005f;
    public static int   contOctaves = 3;
    public static float contPersist = 0.5f;

    // ── NOISE — EROSION (2D, medium scale) ────────────────────
    public static float erosFreq    = 0.015f;
    public static int   erosOctaves = 4;
    public static float erosPersist = 0.5f;

    // ── NOISE — PEAKS & VALLEYS (2D, fine scale) ──────────────
    public static float pvFreq    = 0.04f;
    public static int   pvOctaves = 5;
    public static float pvPersist = 0.4f;

    // ── HEIGHT MAPPING ────────────────────────────────────────
    public static int heightBase  = 10;  // minimum target surface Y
    public static int heightRange = 40;  // range added on top

    // ── 3D DENSITY NOISE ──────────────────────────────────────
    // Controls the 3D noise that wiggles the surface and allows overhangs.
    public static float density3DFreq             = 0.05f;
    public static float density3DVerticalCompress = 0.5f;   // < 1 = features taller than wide
    public static int   density3DOctaves          = 3;
    public static float density3DPersist          = 0.5f;
    public static float density3DAmplitude        = 10f;    // how hard 3D noise fights the height bias

    // ── DENSITY SHAPE ─────────────────────────────────────────
    // Controls how quickly density transitions from solid to air.
    public static float densityVerticalScale = 0.15f;  // higher = sharper cliff edges
    public static float densityErosionBoost  = 0.15f;  // extra sharpness in jagged (low-erosion) regions

    // ── PLAYER / CAMERA ──────────────────────────────────────
    public static float mouseSensitivity = 0.001f;
    public static float fov              = 70.0f;

    // ── PLAYER PHYSICS ────────────────────────────────────────
    // Referenced directly by Player.java — keep these names exactly
    public static float GRAVITY      = 35.0f;
    public static float JUMP_FORCE   = 10.0f;
    public static float WALK_SPEED   = 5.0f;
    public static float SPRINT_SPEED = 8.5f;
    public static float FLY_SPEED    = 15.0f;
}