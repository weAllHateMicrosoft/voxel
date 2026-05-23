package com.leaf.game;

public class GameConfig {

    // ── WORLD ────────────────────────────────────────────────
    public static long    seed           = System.currentTimeMillis();
    public static int     renderDistance = 6;   // chunks in each direction
    public static int     seaLevel       = 20;

    // ── NOISE — CONTINENTALNESS ───────────────────────────────
    public static float   contFreq       = 0.005f;
    public static int     contOctaves    = 3;
    public static float   contPersist    = 0.5f;

    // ── NOISE — EROSION ───────────────────────────────────────
    public static float   erosFreq       = 0.015f;
    public static int     erosOctaves    = 4;
    public static float   erosPersist    = 0.5f;

    // ── NOISE — PEAKS & VALLEYS ───────────────────────────────
    public static float   pvFreq         = 0.04f;
    public static int     pvOctaves      = 5;
    public static float   pvPersist      = 0.4f;

    // ── HEIGHT MAPPING ────────────────────────────────────────
    public static int     heightBase     = 5;   // minimum surface Y
    public static int     heightRange    = 55;  // added on top of heightBase

    // ── PLAYER / CAMERA ──────────────────────────────────────
    public static float   mouseSensitivity = 0.001f;
    public static float   moveSpeed        = 10.0f;
    public static float   fov              = 70.0f;
}