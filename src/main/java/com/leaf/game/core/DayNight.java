package com.leaf.game.core;

import org.joml.Vector3f;

/**
 * DayNight — drives the whole sky + lighting mood from a single 0..1 time-of-day.
 *
 *   time: 0 = midnight, 0.25 = dawn, 0.5 = noon, 0.75 = dusk.
 *
 * Every frame {@link #update} advances time (scaled by GameConfig.dayNightSpeed — crank
 * it for the "Made in Heaven" effect) and recomputes the sun/moon direction, the active
 * light colour + strength, ambient, and the sky gradient (zenith + horizon) colours.
 *
 * The golden-hour ("sunset") window is deliberately brief and warm — orange horizon,
 * purple zenith — because the sun crosses the horizon band quickly, just like real life.
 */
public class DayNight {

    public float time = GameConfig.dayStartTime;

    // ── Outputs (recomputed each frame) ───────────────────────────────────────
    public final Vector3f sunDir   = new Vector3f(0, 1, 0);   // direction TO the sun
    public final Vector3f moonDir  = new Vector3f(0, -1, 0);
    public final Vector3f lightDir = new Vector3f(0, 1, 0);   // active luminary (sun by day, moon by night)
    public final Vector3f lightColor   = new Vector3f(1);
    public final Vector3f ambientColor = new Vector3f(1);
    public float lightStrength   = 0.8f;
    public float ambientStrength = 0.25f;
    public final Vector3f skyZenith  = new Vector3f();        // overhead sky colour
    public final Vector3f skyHorizon = new Vector3f();        // colour at the horizon
    public float sunElev;        // -1 (midnight) .. +1 (noon)
    public float dayFactor;      // 0 night .. 1 day
    public float nightFactor;    // 1 night .. 0 day
    public float sunsetFactor;   // 0..1 golden-hour glow

    public void update(float dt) {
        time += dt * GameConfig.dayNightSpeed / Math.max(1f, GameConfig.dayLengthSec);
        time -= (float) Math.floor(time);
        recompute();
    }

    public void recompute() {
        float ang = (time - 0.25f) * (float) (Math.PI * 2.0);   // 0 at dawn, π/2 at noon
        sunElev = (float) Math.sin(ang);
        sunDir.set((float) Math.cos(ang), sunElev, 0.35f).normalize();
        moonDir.set(sunDir).negate();

        dayFactor    = smooth(-0.08f, 0.22f, sunElev);
        nightFactor  = 1f - dayFactor;
        float nearHz = clamp(1f - Math.abs(sunElev) / 0.22f, 0f, 1f);   // 1 at the horizon
        sunsetFactor = nearHz * smooth(-0.22f, 0.02f, sunElev);         // only near/above horizon

        // The light source crossfades sun→moon as the sun dips below the horizon.
        float sunUp = clamp((sunElev + 0.05f) / 0.10f, 0f, 1f);
        lightDir.set(moonDir).lerp(sunDir, sunUp).normalize();
        lightStrength = sunUp * (0.30f + 0.70f * dayFactor) + (1f - sunUp) * 0.18f;

        Vector3f warm = new Vector3f(1.00f, 0.96f, 0.88f)                // warm daylight
                .lerp(new Vector3f(1.00f, 0.42f, 0.18f), sunsetFactor);  // → deep sunset orange
        lightColor.set(0.55f, 0.64f, 0.98f).lerp(warm, sunUp);          // cool moonlight at night

        ambientStrength = 0.10f + 0.22f * dayFactor + 0.05f * sunsetFactor;
        ambientColor.set(0.10f, 0.13f, 0.24f)                            // night ambient
                .lerp(new Vector3f(0.58f, 0.70f, 0.92f), dayFactor)      // → day sky-bounce
                .lerp(new Vector3f(0.55f, 0.34f, 0.40f), sunsetFactor * 0.55f);  // warm sunset lift

        skyZenith.set(0.015f, 0.02f, 0.08f)                              // deep night navy
                .lerp(new Vector3f(0.30f, 0.55f, 0.95f), dayFactor)      // → day blue
                .lerp(new Vector3f(0.16f, 0.13f, 0.42f), sunsetFactor * 0.85f); // → dusky purple
        skyHorizon.set(0.04f, 0.05f, 0.13f)
                .lerp(new Vector3f(0.66f, 0.80f, 0.96f), dayFactor)
                .lerp(new Vector3f(1.00f, 0.46f, 0.26f), sunsetFactor);  // → blazing orange band
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    private static float smooth(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
