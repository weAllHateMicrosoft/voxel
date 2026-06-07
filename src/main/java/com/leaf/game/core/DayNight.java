package com.leaf.game.core;

import org.joml.Vector2d;
import org.joml.Vector3f;
import java.util.List;

public class DayNight {

    // Markham / Toronto coordinates (as an example!)
    private final double latRad = Math.toRadians(43.8561);
    private final double lonRad = Math.toRadians(-79.3370);

    public double currentJD;
    private double lastStarUpdate = -1e9;

    public final Vector3f sunDir   = new Vector3f(0, 1, 0);
    public final Vector3f moonDir  = new Vector3f(0, -1, 0);
    public final Vector3f lightDir = new Vector3f(0, 1, 0);

    public final Vector3f lightColor   = new Vector3f(1);
    public final Vector3f ambientColor = new Vector3f(1);
    public float lightStrength   = 0.8f;
    public float ambientStrength = 0.25f;

    public final Vector3f skyZenith  = new Vector3f();
    public final Vector3f skyHorizon = new Vector3f();

    public float dayFactor;
    public float nightFactor;
    public float sunsetFactor;

    // Lunar properties for the shader
    public float moonPhaseAngle;
    public float moonBrightLimbAngle;

    // Star Data
    private List<Astronomy.StarRecord> catalog;
    public List<Astronomy.StarGPU> visibleStars;
    public boolean starsDirty = false; // Tells Window.java to re-upload the VBO

    public void init() {
        // Load stars up to magnitude 6.5
        catalog = Astronomy.loadBSC5("/data/bsc5.csv", 6.5f);
        // Start date: June 21, 2024 at 12:00 UTC
        currentJD = Astronomy.julianDate(2024, 6, 21, 12, 0, 0.0);
    }

    public void update(float dt) {
        // Advance time: 1 in-game day happens every `dayLengthSec` real seconds
        currentJD += (dt * GameConfig.dayNightSpeed) / GameConfig.dayLengthSec;
        recompute();
    }

    public void recompute() {
        double lst = Astronomy.localSiderealTime(currentJD, lonRad);

        // ── Solar ────────────────────────────────────────────────────────
        Astronomy.RADecDist sun = Astronomy.sunPosition(currentJD);
        Vector2d sunHoriz = Astronomy.equatorialToHorizontal(sun.ra, sun.dec, latRad, lst);
        sunDir.set(Astronomy.azAltToDirection(sunHoriz.x, sunHoriz.y));
        float sunElev = (float) Math.sin(sunHoriz.y);

        // ── Lunar ────────────────────────────────────────────────────────
        Astronomy.LunarInfo moon = Astronomy.moonPosition(currentJD);
        Vector2d moonHoriz = Astronomy.equatorialToHorizontal(moon.ra, moon.dec, latRad, lst);
        moonDir.set(Astronomy.azAltToDirection(moonHoriz.x, moonHoriz.y));
        moonPhaseAngle = (float) moon.phaseAngle;
        // Bright-limb angle must be in the SHADER's local plane (built from world-up +
        // moonDir), not the equatorial frame — otherwise the crescent points the wrong
        // way. It's simply the direction toward the sun, projected into that plane.
        Vector3f up    = new Vector3f(0f, 1f, 0f);
        Vector3f right = new Vector3f(moonDir).cross(up).normalize();   // cross(moonDir, up)
        Vector3f mUp   = new Vector3f(right).cross(moonDir).normalize();// cross(right, moonDir)
        moonBrightLimbAngle = (float) Math.atan2(sunDir.dot(mUp), sunDir.dot(right));

        // ── Day/Night/Sunset Factors ──────────────────────────────────────
        dayFactor    = clamp(sunElev * 8.0f + 0.5f, 0.0f, 1.0f);
        nightFactor  = 1.0f - dayFactor;

        sunsetFactor = (float) Math.exp(-sunHoriz.y * sunHoriz.y / 0.04);
        sunsetFactor = clamp(sunsetFactor * (1.0f - nightFactor * 0.5f), 0.0f, 1.0f);

        // ── Lighting / Sky Colors ─────────────────────────────────────────
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

        // ── Star Updates (Only every ~30 in-game seconds) ─────────────────
        if (Math.abs(currentJD - lastStarUpdate) > 30.0 / 86400.0) {
            lastStarUpdate = currentJD;
            visibleStars = Astronomy.buildVisibleStars(catalog, currentJD, latRad, lonRad);
            starsDirty = true;
        }
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
}