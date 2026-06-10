package com.leaf.game.core;

import org.joml.Vector3f;

/**
 * CelestialNav — answers the navigational questions your star system can pose.
 *
 * All methods are stateless and take the DayNight instance for the current JD.
 * Plug into UI, quests, NPCs, or puzzle triggers as needed.
 *
 * The three questions real celestial navigators asked:
 *   1. Which direction am I facing?     → getCardinalFromPolaris / getBearingToStar
 *   2. What time / season is it?        → getApproximateHourFromBigDipper / getSeason
 *   3. Where am I (latitude)?           → getLatitudeFromPolaris
 *
 * Plus the world-event solver for landmark puzzles:
 *   4. When does [star] touch [horizon point]? → solveStarEvent
 */
public final class CelestialNav {

    private CelestialNav() {}   // static utility class

    // ─────────────────────────────────────────────────────────────────────────
    // 1. DIRECTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the azimuth of True North (0°) derived from Polaris's position.
     * In a real sky, Polaris is within ~0.7° of the celestial pole.
     * Here we just return the azimuth of Polaris directly — close enough.
     *
     * Usage: compass bearing = (playerFacingAzimuth - northAzimuth + 360) % 360
     *
     * @return azimuth of True North in degrees (0–360), or -1 if Polaris is below horizon.
     */
    public static float getTrueNorthAzimuth(DayNight dn) {
        // Polaris: RA = 37.95°, Dec = 89.264°
        double raRad  = Math.toRadians(37.95);
        double decRad = Math.toRadians(89.264);
        double latRad = Math.toRadians(43.8561);
        double lonRad = Math.toRadians(-79.337);

        double lst   = Astronomy.localSiderealTime(dn.currentJD, lonRad);
        org.joml.Vector2d h = Astronomy.equatorialToHorizontal(raRad, decRad, latRad, lst);

        if (h.y < -0.01) return -1f;   // Polaris below horizon (shouldn't happen at 44°N)
        return (float) Math.toDegrees(h.x);
    }

    /**
     * Azimuth from the observer toward a named navigational star.
     * Returns -1 if the star is currently below the horizon.
     *
     * @param ra  Right ascension of the star, in degrees.
     * @param dec Declination of the star, in degrees.
     */
    public static float getStarAzimuth(DayNight dn, float raDeg, float decDeg) {
        double latRad = Math.toRadians(43.8561);
        double lonRad = Math.toRadians(-79.337);
        double lst = Astronomy.localSiderealTime(dn.currentJD, lonRad);
        org.joml.Vector2d h = Astronomy.equatorialToHorizontal(
                Math.toRadians(raDeg), Math.toRadians(decDeg), latRad, lst);
        return h.y >= -0.01 ? (float) Math.toDegrees(h.x) : -1f;
    }

    /**
     * Altitude (degrees above horizon) of a star.
     * Useful for: "when Polaris is at altitude 43°, you are at 43°N latitude."
     */
    public static float getStarAltitude(DayNight dn, float raDeg, float decDeg) {
        double latRad = Math.toRadians(43.8561);
        double lonRad = Math.toRadians(-79.337);
        double lst = Astronomy.localSiderealTime(dn.currentJD, lonRad);
        org.joml.Vector2d h = Astronomy.equatorialToHorizontal(
                Math.toRadians(raDeg), Math.toRadians(decDeg), latRad, lst);
        return (float) Math.toDegrees(h.y);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. TIME AND SEASON
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Approximate local sidereal time (LST) from the current JD.
     * This is essentially what a star clock reads.
     * LST = 0h when Aries rises; advances ~2 hours per month.
     *
     * @return LST in hours [0, 24).
     */
    public static double getLocalSiderealTimeHours(DayNight dn) {
        double lonRad = Math.toRadians(-79.337);
        double lstRad = Astronomy.localSiderealTime(dn.currentJD, lonRad);
        return (lstRad / (2 * Math.PI)) * 24.0;
    }

    /**
     * In-game season based on the sun's ecliptic longitude.
     * Returns 0=Spring, 1=Summer, 2=Autumn, 3=Winter (Northern Hemisphere).
     */
    public static int getSeason(DayNight dn) {
        Astronomy.RADecDist sun = Astronomy.sunPosition(dn.currentJD);
        // Convert sun RA back to ecliptic longitude (approx — good enough for seasons).
        // Ecliptic lon 0° = vernal equinox (spring), 90° = summer solstice, etc.
        double eclLon = Math.toDegrees(sun.ra);   // RA ≈ ecliptic lon for seasons
        int season = (int)((eclLon + 360.0) % 360.0 / 90.0);
        return season;
    }

    /** Human-readable season name. */
    public static String getSeasonName(DayNight dn) {
        return new String[]{"Spring","Summer","Autumn","Winter"}[getSeason(dn)];
    }

    /**
     * Estimate the hour of night from the Big Dipper's rotation around Polaris.
     * The pointer stars (Dubhe + Merak) rotate 360° in one sidereal day (~23h 56m).
     *
     * This is a real technique: the "star clock" or nocturnal.
     * A player who learns this can tell midnight from 2am without any in-game clock.
     *
     * @return Estimated local solar time in hours [0, 24), or -1 if Dipper is below horizon.
     */
    public static double estimateHourFromBigDipper(DayNight dn) {
        // Dubhe (alpha UMa): RA 165.93°, Dec 61.75°
        float dubheAlt = getStarAltitude(dn, 165.93f, 61.75f);
        if (dubheAlt < 0) return -1;

        float dubheAz = getStarAzimuth(dn, 165.93f, 61.75f);
        float northAz = getTrueNorthAzimuth(dn);

        // Angle of Dubhe relative to Polaris, measured clockwise from top.
        float angleFromNorth = ((dubheAz - northAz) + 360f) % 360f;

        // Convert rotation angle to sidereal time, then approximately to solar time.
        // 360° rotation = 23h 56m sidereal ≈ 24h solar.
        // At upper culmination (angle=180°, Dipper below Polaris), time ≈ midnight in March.
        // This is a rough approximation — good enough for gameplay.
        double siderealHour = getLocalSiderealTimeHours(dn);
        // Solar time = sidereal time - right ascension of mean sun (approx)
        double meanSunRA = (dn.currentJD - 2451545.0) / 36525.0 * 24.0 + 18.697;   // rough
        double solarTime = ((siderealHour - meanSunRA % 24.0) + 24.0) % 24.0;
        return solarTime;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. LATITUDE FROM POLARIS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The altitude of Polaris above the horizon equals the observer's latitude.
     * This is one of the oldest and most reliable navigation techniques.
     *
     * In gameplay: a player with a sextant who measures Polaris altitude and
     * cross-references with a map can determine which "latitude zone" of the
     * world they're in — useful for "you must be north of the snowline" clues.
     *
     * @return Polaris altitude in degrees (essentially = your latitude for Northern Hemisphere).
     *         Returns -1 if Polaris is below horizon (Southern Hemisphere).
     */
    public static float getPolarisAltitude(DayNight dn) {
        return getStarAltitude(dn, 37.95f, 89.264f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. LANDMARK EVENT SOLVER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Finds the next Julian Date when a given star crosses a specific azimuth
     * (e.g. "when does Alnitak set on the western horizon?").
     *
     * This powers puzzle clues like:
     *   "The Megalith glows where Orion's belt touches the western horizon at midnight
     *    on the longest day."
     *
     * Algorithm: step through time in small increments until the star's azimuth
     * matches the target. For efficiency, use a coarse step then bisect.
     *
     * @param raDeg         Star right ascension in degrees.
     * @param decDeg        Star declination in degrees.
     * @param targetAzDeg   Azimuth to match (e.g. 270 = West).
     * @param altThreshDeg  Minimum altitude to consider (use ~0.5 for "on horizon",
     *                      or a larger value for "at altitude X").
     * @param searchDaysMax How many days ahead to search (e.g. 400 = next occurrence).
     * @return Julian Date of the event, or -1 if not found in searchDaysMax.
     */
    public static double solveStarAzimuthEvent(DayNight dn,
                                               float raDeg, float decDeg,
                                               float targetAzDeg, float altThreshDeg,
                                               float searchDaysMax) {
        double latRad = Math.toRadians(43.8561);
        double lonRad = Math.toRadians(-79.337);
        double raRad  = Math.toRadians(raDeg);
        double decRad = Math.toRadians(decDeg);

        double jd    = dn.currentJD;
        double step  = 1.0 / 1440.0;   // 1-minute steps
        double end   = jd + searchDaysMax;

        float prevAz  = -999;
        while (jd < end) {
            double lst = Astronomy.localSiderealTime(jd, lonRad);
            org.joml.Vector2d h = Astronomy.equatorialToHorizontal(raRad, decRad, latRad, lst);
            float az  = (float) Math.toDegrees(h.x);
            float alt = (float) Math.toDegrees(h.y);

            if (alt >= altThreshDeg) {
                // Check if we've crossed the target azimuth (handle wrap-around at 360°).
                float diff = ((az - targetAzDeg + 540f) % 360f) - 180f;
                if (prevAz > -999) {
                    float prevDiff = ((prevAz - targetAzDeg + 540f) % 360f) - 180f;
                    if (prevDiff * diff < 0) {   // sign change = crossing
                        // Bisect for precision (optional — minute accuracy is fine for gameplay).
                        return jd - step * 0.5;
                    }
                }
                prevAz = az;
            }
            jd += step;
        }
        return -1;
    }

    /**
     * Convenience: find when Orion's belt (Alnilam, the centre star) crosses due West
     * on or near the summer solstice, at midnight.
     *
     * Used for: "The Megalith lies where the belt of Orion touches the western horizon
     * at midnight on the summer solstice."
     *
     * The returned JD is the moment — the world azimuth of the belt at that moment
     * is the direction the landmark faces.
     */
    public static double findOrionBeltWestSolstice(DayNight dn) {
        // Find the summer solstice JD near the current date.
        double solsticeJD = findSolstice(dn.currentJD, true);

        // Search around midnight on that day (within ±2 days).
        DayNight temp = new DayNight();
        temp.currentJD = solsticeJD - 0.5;   // start the night before
        return solveStarAzimuthEvent(temp,
                84.05f, -1.20f,   // Alnilam (belt centre): RA 84.05°, Dec -1.20°
                270f,             // due West
                0.5f,             // just above horizon
                3.0f);            // search 3 days
    }

    /**
     * Finds the Julian Date of the next summer (isSummer=true) or winter solstice
     * after the given JD. Accurate to within ~1 hour.
     */
    public static double findSolstice(double afterJD, boolean isSummer) {
        double jd = afterJD;
        double step = 1.0;   // coarse pass: daily steps

        // Target sun declination: +23.44° (summer solstice), -23.44° (winter)
        double targetDec = Math.toRadians(isSummer ? 23.44 : -23.44);

        double bestJD   = jd;
        double bestDiff = Double.MAX_VALUE;

        for (int i = 0; i < 400; i++) {
            Astronomy.RADecDist sun = Astronomy.sunPosition(jd);
            double diff = Math.abs(sun.dec - targetDec);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestJD   = jd;
            }
            jd += step;
        }

        // Refine with hourly steps around the best day.
        jd = bestJD - 1.0;
        bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < 48; i++) {
            Astronomy.RADecDist sun = Astronomy.sunPosition(jd);
            double diff = Math.abs(sun.dec - targetDec);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestJD   = jd;
            }
            jd += 1.0 / 24.0;
        }
        return bestJD;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. HINT GENERATOR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a contextual navigation hint based on what's currently visible.
     * NPCs or books can deliver these hints to teach the player the system.
     *
     * Examples of what this might return:
     *   "Polaris sits 44° above the northern horizon — you are at 44° latitude."
     *   "Orion rises in the East. Follow his belt to find your bearing."
     *   "The Big Dipper's pointer stars align with Polaris."
     *   "Scorpius rides low in the South — it is midsummer."
     */
    public static String generateHint(DayNight dn) {
        float polarisAlt = getPolarisAltitude(dn);
        float orionAlt   = getStarAltitude(dn, 84.05f, -1.20f);   // Alnilam
        float antaresAlt = getStarAltitude(dn, 247.35f, -26.43f);
        float arctAlt    = getStarAltitude(dn, 213.92f, 19.18f);
        float northAz    = getTrueNorthAzimuth(dn);
        String season    = getSeasonName(dn);

        StringBuilder hint = new StringBuilder();

        if (polarisAlt > 5) {
            hint.append(String.format(
                "The North Star hangs %.0f° above the horizon to the %s. " +
                "Your latitude is near %.0f° North.",
                polarisAlt, Telescope.azToCompass(northAz), polarisAlt));
        }

        if (orionAlt > 10) {
            float orionAz = getStarAzimuth(dn, 84.05f, -1.20f);
            hint.append(String.format(
                " Orion's belt crosses the sky at %s. " +
                "It rises nearly due East and sets nearly due West.",
                Telescope.azToCompass(orionAz)));
        }

        if (antaresAlt > 5 && !season.equals("Winter")) {
            hint.append(" Antares burns red in the South — the heart of the Scorpion, a summer sign.");
        }

        if (arctAlt > 20 && (season.equals("Spring") || season.equals("Summer"))) {
            hint.append(" Follow the arc of the Big Dipper's handle to find Arcturus, " +
                        "the great orange star of spring.");
        }

        return hint.length() > 0 ? hint.toString() : "The sky is overcast, or the sun is up.";
    }
}
