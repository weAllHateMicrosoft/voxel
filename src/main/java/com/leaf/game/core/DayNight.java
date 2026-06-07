package com.leaf.game.core;

import org.joml.Vector3f;

public class DayNight {

    public float time = GameConfig.dayStartTime;
    public boolean starsDirty = true;
    public java.util.List<com.leaf.game.core.Astronomy.StarGPU> visibleStars = new java.util.ArrayList<>();

    public double currentJD = 2460000.0;

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
        float delta = dt * GameConfig.dayNightSpeed / Math.max(1f, GameConfig.dayLengthSec);
        time += delta;
        currentJD += delta;
        time -= (float) Math.floor(time);
        recompute();
    }

    public void recompute() {
        float ang = (time - 0.25f) * (float) (Math.PI * 2.0);
        sunElev = (float) Math.sin(ang);
        sunDir.set((float) Math.cos(ang), sunElev, 0.35f).normalize();
        moonDir.set(sunDir).negate();

        Astronomy.LunarInfo lunar = Astronomy.moonPosition(currentJD);
        moonPhaseAngle = (float) lunar.phaseAngle;
        moonBrightLimbAngle = (float) lunar.brightLimbAngle;

        // FIX: Actually calculate where the stars are in the sky right now so the GPU can draw them!
        if (starCatalog != null) {
            visibleStars = Astronomy.buildVisibleStars(starCatalog, currentJD,
                    Math.toRadians(GameConfig.observerLatDeg),
                    Math.toRadians(GameConfig.observerLonDeg));
            starsDirty = true;
        }

        dayFactor    = smooth(-0.08f, 0.22f, sunElev);
        nightFactor  = 1f - dayFactor;
        float nearHz = clamp(1f - Math.abs(sunElev) / 0.22f, 0f, 1f);
        sunsetFactor = nearHz * smooth(-0.22f, 0.02f, sunElev);

        float sunUp = clamp((sunElev + 0.05f) / 0.10f, 0f, 1f);
        lightDir.set(moonDir).lerp(sunDir, sunUp).normalize();
        lightStrength = sunUp * (0.30f + 0.70f * dayFactor) + (1f - sunUp) * 0.18f;

        Vector3f warm = new Vector3f(1.00f, 0.96f, 0.88f)
                .lerp(new Vector3f(1.00f, 0.42f, 0.18f), sunsetFactor);
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
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    private static float smooth(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}