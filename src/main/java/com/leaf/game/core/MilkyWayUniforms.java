package com.leaf.game.core;

import org.joml.Vector3f;

/**
 * MilkyWayUniforms — computes the two vec3 uniforms the sky shader needs
 * to draw the Milky Way in the correct orientation for any world-space convention.
 *
 * HOW IT WORKS:
 *   Instead of hardcoding a rotation matrix (which broke because I guessed the
 *   wrong world-space axis convention), we compute the galactic pole and galactic
 *   centre directions using your EXISTING Astronomy.equatorialToHorizontal() +
 *   Astronomy.azAltToDirection() pipeline. Those functions already know your
 *   coordinate system — so the output is always correct regardless of which axis
 *   is north, east, or up in your game.
 *
 * WHAT TO UPLOAD TO THE SKY SHADER:
 *   skyShader.setVec3("milkyWayAxis",   milkyWayUniforms.axis);    // galactic north pole
 *   skyShader.setVec3("milkyWayCentre", milkyWayUniforms.centre);  // galactic centre dir
 *
 * UPDATE FREQUENCY:
 *   These change slowly (once per in-game hour is plenty).
 *   Call update() in the same block as your starsDirty check in DayNight.
 *   Or just call it every frame — it's cheap (two equatorialToHorizontal calls).
 */
public class MilkyWayUniforms {

    /** Direction toward the galactic north pole, in world space. */
    public final Vector3f axis   = new Vector3f(0, 1, 0);

    /** Direction toward the galactic centre, in world space. */
    public final Vector3f centre = new Vector3f(0, 0, -1);

    // ── Galactic reference points (J2000, fixed forever) ─────────────────────

    // Galactic North Pole in equatorial coordinates
    private static final double GNP_RA_DEG  = 192.859508;
    private static final double GNP_DEC_DEG = 27.128336;

    // Galactic Centre in equatorial coordinates
    private static final double GC_RA_DEG   = 266.405100;
    private static final double GC_DEC_DEG  = -28.936175;

    // ── Observer location (same as DayNight) ─────────────────────────────────
    private static final double LAT_RAD = Math.toRadians(43.8561);
    private static final double LON_RAD = Math.toRadians(-79.337);

    /**
     * Recompute both vectors for the current Julian Date.
     * Call once per frame (or throttle to once per in-game minute — it's slow-moving).
     *
     * @param currentJD  The current Julian Date from DayNight.currentJD
     */
    public void update(double currentJD) {
        double lst = Astronomy.localSiderealTime(currentJD, LON_RAD);

        // Galactic North Pole → world direction
        org.joml.Vector2d gnpHoriz = Astronomy.equatorialToHorizontal(
                Math.toRadians(GNP_RA_DEG), Math.toRadians(GNP_DEC_DEG), LAT_RAD, lst);
        axis.set(Astronomy.azAltToDirection(gnpHoriz.x, gnpHoriz.y));

        // Galactic Centre → world direction
        org.joml.Vector2d gcHoriz = Astronomy.equatorialToHorizontal(
                Math.toRadians(GC_RA_DEG), Math.toRadians(GC_DEC_DEG), LAT_RAD, lst);
        centre.set(Astronomy.azAltToDirection(gcHoriz.x, gcHoriz.y));

        // Note: when these objects are below the horizon, their Y component is negative.
        // The shader uses them as mathematical axes (not positions), so this is fine —
        // the galactic band is still correctly oriented even when the GC is below horizon.
        // The band will simply be positioned correctly relative to the visible sky.
    }
}
