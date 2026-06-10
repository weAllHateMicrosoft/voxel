package com.leaf.game.core;

import org.joml.Vector3f;

public class DayNight {

    public float time = GameConfig.dayStartTime;

    public boolean starsDirty = true;
    public java.util.List<com.leaf.game.core.Astronomy.StarGPU> visibleStars = new java.util.ArrayList<>();
    public final MilkyWayUniforms milkyWay = new MilkyWayUniforms();
    public double currentJD = 2460000.0;
    // --- NEW MILKY WAY & ATMOSPHERE FIELDS ---
    public float lunarEclipseFactor = 0f;
    public float auroraStrength = 0f;
    private float auroraTimer = 0f;
    private float auroraTarget = 0f;
    public float moonBrightness = 0f;
    private float lastDt = 0.016f; // Track delta time for recompute

    // Live Nebula coordinates for the shader
    public final Vector3f nebulaDir_M42 = new Vector3f();
    public final Vector3f nebulaDir_M31 = new Vector3f();
    public final Vector3f nebulaDir_M8 = new Vector3f();
    public final Vector3f nebulaDir_EtaCar = new Vector3f();
    public final Vector3f sunDir   = new Vector3f(0, 1, 0);
    public final Vector3f moonDir  = new Vector3f(0, -1, 0);
    public final Vector3f lightDir = new Vector3f(0, 1, 0);
    public final Vector3f lightColor   = new Vector3f(1);
    public final Vector3f ambientColor = new Vector3f(1);
    public float lightStrength   = 0.8f;
    public float ambientStrength = 0.25f;
    public final Vector3f skyZenith  = new Vector3f();
    public final Vector3f skyHorizon = new Vector3f();
    public float sunElev;
    public float dayFactor;
    public float nightFactor;
    public float sunsetFactor;

    public float moonPhaseAngle = 0f;
    public float moonBrightLimbAngle = 0f;

    // FIX: Static catalog so we only read the file/generate background stars ONCE at startup.
    private static java.util.List<Astronomy.StarRecord> starCatalog;

    public void init() {
        if (starCatalog == null) {
            starCatalog = Astronomy.loadBSC5("/bsc5.csv", 6.0f);
        }
        recompute();
    }

    public void update(float dt) {
        this.lastDt = dt;
        float delta = dt * GameConfig.dayNightSpeed / Math.max(1f, GameConfig.dayLengthSec);
        time += delta;
        currentJD += delta;
        time -= (float) Math.floor(time);
        recompute();
    }

    // 30 in-game seconds worth of JD fractions (≈ 30/86400 day).
    // Stars update this often — enough to keep the sky accurate without per-frame
    // rebuilds (which would run 2600-star horizon transforms at 60 fps = 156k/s).
    private static final double STAR_UPDATE_INTERVAL_JD = 30.0 / 86400.0;
    private double lastStarUpdateJD = -1e9;
    // ADD THIS FIELD: Tracks exactly when the stars were last moved
    public double lastStarJD = 2460000.0;

    public void recompute() {
        double lst = Astronomy.localSiderealTime(currentJD, Math.toRadians(GameConfig.observerLonDeg));
        double lat = Math.toRadians(GameConfig.observerLatDeg);

        // 1. FIX: Restore the original procedural Sun & Moon paths! This fixes the ruined sunset.
        float ang = (time - 0.25f) * (float) (Math.PI * 2.0);
        sunElev = (float) Math.sin(ang);
        sunDir.set((float) Math.cos(ang), sunElev, 0.35f).normalize();
        moonDir.set(sunDir).negate();

        // 2. FIX: Fake the moon phase (a full cycle takes 29.5 in-game days)
        moonPhaseAngle = (float) ((currentJD % 29.53) / 29.53 * Math.PI * 2.0);

        // 3. Keep the astronomical stars, but expose lastStarJD for the Telescope sync
        if (starCatalog != null && (currentJD - lastStarJD) >= STAR_UPDATE_INTERVAL_JD) {
            lastStarJD = currentJD;
            visibleStars = Astronomy.buildVisibleStars(starCatalog, lastStarJD, lat, Math.toRadians(GameConfig.observerLonDeg));
            starsDirty = true;
        }

        // Lighting factors (Restored to original logic)
        dayFactor    = smooth(-0.08f, 0.22f, sunElev);
        nightFactor  = 1f - dayFactor;
        float nearHz = clamp(1f - Math.abs(sunElev) / 0.22f, 0f, 1f);
        sunsetFactor = nearHz * smooth(-0.22f, 0.02f, sunElev);

        float sunUp = clamp((sunElev + 0.05f) / 0.10f, 0f, 1f);
        lightDir.set(moonDir).lerp(sunDir, sunUp).normalize();
        lightStrength = sunUp * (0.30f + 0.70f * dayFactor) + (1f - sunUp) * 0.18f;

        Vector3f warm = new Vector3f(1.00f, 0.96f, 0.88f).lerp(new Vector3f(1.00f, 0.42f, 0.18f), sunsetFactor);
        lightColor.set(0.55f, 0.64f, 0.98f).lerp(warm, sunUp);

        ambientStrength = 0.10f + 0.22f * dayFactor + 0.05f * sunsetFactor;
        ambientColor.set(0.10f, 0.13f, 0.24f)
                .lerp(new Vector3f(0.58f, 0.70f, 0.92f), dayFactor)
                .lerp(new Vector3f(0.55f, 0.34f, 0.40f), sunsetFactor * 0.55f);

        skyZenith.set(0.015f, 0.02f, 0.08f)
                .lerp(new Vector3f(0.30f, 0.55f, 0.95f), dayFactor)
                .lerp(new Vector3f(0.16f, 0.13f, 0.42f), sunsetFactor * 0.85f);
        skyHorizon.set(0.04f, 0.05f, 0.13f)
                .lerp(new Vector3f(0.66f, 0.80f, 0.96f), dayFactor)
                .lerp(new Vector3f(1.00f, 0.46f, 0.26f), sunsetFactor);

        recomputeAdditions(currentJD, lst, sunDir, moonDir, moonPhaseAngle, nightFactor, lastDt);
        milkyWay.update(currentJD);
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    private static float smooth(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    public void recomputeAdditions(double currentJD, double lst, Vector3f sunDir, Vector3f moonDir, float moonPhaseAngle, float nightFactor, float dt) {
        moonBrightness = (float)(1.0 - Math.cos(moonPhaseAngle)) * 0.5f;

        float sunMoonDot = sunDir.dot(moonDir);
        if (moonPhaseAngle > 2.85f) {
            float opposition = (-sunMoonDot - 0.9995f) / 0.0005f;
            lunarEclipseFactor = clamp(opposition * 2.0f, 0f, 1f);
        } else {
            lunarEclipseFactor = Math.max(0f, lunarEclipseFactor - dt * 0.5f);
        }

        auroraTimer -= dt;
        if (auroraTimer <= 0f && nightFactor > 0.5f) {
            if (auroraTarget < 0.1f) {
                if (Math.random() < dt / 300f) { // Aurora roughly every 5 mins
                    auroraTarget = 0.3f + (float)Math.random() * 0.7f;
                    auroraTimer = 30f + (float)Math.random() * 90f;
                }
            } else {
                auroraTarget = 0f;
                auroraTimer = 60f + (float)Math.random() * 120f;
            }
        }
        auroraStrength += (auroraTarget - auroraStrength) * Math.min(1f, dt * 0.5f);
        auroraStrength = clamp(auroraStrength, 0f, 1f) * nightFactor;

        double latRad = Math.toRadians(GameConfig.observerLatDeg);
        double lonRad = Math.toRadians(GameConfig.observerLonDeg);
        computeObjectDir(nebulaDir_M42, 83.82, -5.39, latRad, lst);
        computeObjectDir(nebulaDir_M31, 10.68, 41.27, latRad, lst);
        computeObjectDir(nebulaDir_M8, 270.9, -24.38, latRad, lst);
        computeObjectDir(nebulaDir_EtaCar, 161.27, -59.69, latRad, lst);
    }

    private static void computeObjectDir(Vector3f out, double raDeg, double decDeg, double latRad, double lst) {
        org.joml.Vector2d h = Astronomy.equatorialToHorizontal(Math.toRadians(raDeg), Math.toRadians(decDeg), latRad, lst);
        out.set(Astronomy.azAltToDirection(h.x, h.y));
    }
    
}