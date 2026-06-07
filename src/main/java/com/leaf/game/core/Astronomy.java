// --- FILE: src/main/java/com/leaf/game/core/Astronomy.java ---
package com.leaf.game.core;

import org.joml.Vector2d;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class Astronomy {
    public static final double PI = 3.14159265358979323846;
    public static final double TWO_PI = 2.0 * PI;
    public static final double DEG2RAD = PI / 180.0;

    public static double wrapTwoPI(double x) {
        x = x % TWO_PI;
        return x < 0.0 ? x + TWO_PI : x;
    }

    public static double wrapPI(double x) {
        x = (x + PI) % TWO_PI;
        return x < 0.0 ? x + TWO_PI : x - PI;
    }

    public static double julianDate(int year, int month, int day, int hour, int minute, double second) {
        if (month <= 2) { year--; month += 12; }
        int A = year / 100;
        int B = 2 - A + (A / 4);
        return Math.floor(365.25 * (year + 4716))
                + Math.floor(30.6001 * (month + 1))
                + day + B - 1524.5
                + (hour + minute / 60.0 + second / 3600.0) / 24.0;
    }

    public static double julianCenturies(double jd) {
        return (jd - 2451545.0) / 36525.0;
    }

    public static double localSiderealTime(double jd, double lonRad) {
        double T = julianCenturies(jd);
        double gmst = 24110.54841 + 8640184.812866 * T + 0.093104 * T * T - 6.2e-6 * T * T * T;
        double ut = ((jd + 0.5) % 1.0) * 86400.0;
        gmst += ut * 1.00273790935;
        return wrapTwoPI(gmst / 86400.0 * TWO_PI) + lonRad;
    }

    public static Vector2d equatorialToHorizontal(double ra, double dec, double lat, double lst) {
        double ha = wrapPI(lst - ra);
        double sinAlt = Math.sin(dec) * Math.sin(lat) + Math.cos(dec) * Math.cos(lat) * Math.cos(ha);
        double alt = Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt)));

        double cosAz = (Math.sin(dec) - Math.sin(alt) * Math.sin(lat)) / (Math.cos(alt) * Math.cos(lat) + 1e-10);
        double az = Math.acos(Math.max(-1.0, Math.min(1.0, cosAz)));
        if (Math.sin(ha) > 0.0) az = TWO_PI - az;

        return new Vector2d(az, alt);
    }

    public static Vector3f azAltToDirection(double az, double alt) {
        float cosAlt = (float) Math.cos(alt);
        return new Vector3f(
                cosAlt * (float) Math.sin(az),
                (float) Math.sin(alt),
                -cosAlt * (float) Math.cos(az)
        );
    }

    public static class StarRecord {
        public float ra, dec, mag, bv;
    }

    public static class StarGPU {
        public Vector3f dir;
        public float mag, bv;
    }

    public static List<StarRecord> loadBSC5(String csvPath, float magLimit) {
        List<StarRecord> catalog = new ArrayList<>();
        InputStream is = Astronomy.class.getResourceAsStream(csvPath);

        try {
            // Fallback for IDEs (like IntelliJ) where resources aren't always rebuilt immediately
            if (is == null) {
                java.io.File diskFile = new java.io.File("src/main/resources" + csvPath);
                if (diskFile.exists()) {
                    is = new java.io.FileInputStream(diskFile);
                } else {
                    System.err.println("Could not find stars file at: " + diskFile.getAbsolutePath());
                    return catalog;
                }
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split(",");
                    float vmag = Float.parseFloat(parts[2]);
                    if (vmag > magLimit) continue;
                    StarRecord s = new StarRecord();
                    s.ra = (float) (Float.parseFloat(parts[0]) * DEG2RAD);
                    s.dec = (float) (Float.parseFloat(parts[1]) * DEG2RAD);
                    s.mag = vmag;
                    s.bv = Float.parseFloat(parts[3]);
                    catalog.add(s);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load stars: " + e.getMessage());
        }

        // If the real catalogue is sparse (the bundled CSV is just a sample), fill the
        // sky with a dense field of faint procedural background stars so it looks alive.
        // The real bright stars stay as the recognisable ones; this is the Milky-Way dust.
        // (Drop in the full ~9000-star BSC5 via bsc5_converter.py and this fill switches off.)
        if (catalog.size() < 1500) {
            java.util.Random rng = new java.util.Random(0xC0FFEEL);
            int fill = 2600 - catalog.size();
            for (int i = 0; i < fill; i++) {
                StarRecord s = new StarRecord();
                s.ra  = (float) (rng.nextDouble() * TWO_PI);
                s.dec = (float) Math.asin(2.0 * rng.nextDouble() - 1.0);   // uniform on the sphere
                s.mag = 3.8f + rng.nextFloat() * 2.6f;                      // faint (3.8–6.4)
                s.bv  = -0.25f + rng.nextFloat() * 1.9f;                    // blue → red mix
                catalog.add(s);
            }
        }
        return catalog;
    }

    public static List<StarGPU> buildVisibleStars(List<StarRecord> catalog, double jd, double latRad, double lonRad) {
        double lst = localSiderealTime(jd, lonRad);
        List<StarGPU> out = new ArrayList<>();
        for (StarRecord s : catalog) {
            Vector2d horiz = equatorialToHorizontal(s.ra, s.dec, latRad, lst);
            if (horiz.y < -0.01) continue;
            StarGPU g = new StarGPU();
            g.dir = azAltToDirection(horiz.x, horiz.y);
            g.mag = s.mag;
            g.bv = s.bv;
            out.add(g);
        }
        return out;
    }

    public static class RADecDist { public double ra, dec, dist; }

    public static RADecDist sunPosition(double jd) {
        double T = julianCenturies(jd);
        double L0 = DEG2RAD * ((280.46646 + 36000.76983 * T) % 360.0);
        double M = DEG2RAD * ((357.52911 + 35999.05029 * T - 0.0001537 * T * T) % 360.0);
        double e = 0.016708634 - 0.000042037 * T;

        double C = DEG2RAD * ((1.914602 - 0.004817 * T - 0.000014 * T * T) * Math.sin(M)
                + (0.019993 - 0.000101 * T) * Math.sin(2 * M)
                + 0.000289 * Math.sin(3 * M));
        double sunLon = L0 + C;

        double eps = DEG2RAD * (23.439291 - 0.013004 * T);
        RADecDist r = new RADecDist();
        r.ra = wrapTwoPI(Math.atan2(Math.cos(eps) * Math.sin(sunLon), Math.cos(sunLon)));
        r.dec = Math.asin(Math.sin(eps) * Math.sin(sunLon));
        r.dist = 1.000001018 * (1.0 - e * e) / (1.0 + e * Math.cos(M + C - L0));
        return r;
    }

    public static class LunarInfo {
        public double ra, dec, phaseAngle, brightLimbAngle, distance_km;
    }

    public static LunarInfo moonPosition(double jd) {
        double T = julianCenturies(jd);
        double Lp = (218.3164477 + 481267.88123421 * T) % 360.0;
        double D = (297.8501921 + 445267.1114034 * T) % 360.0;
        double M = (357.5291092 + 35999.0502909 * T) % 360.0;
        double Mp = (134.9633964 + 477198.8675055 * T) % 360.0;
        double F = (93.2720950 + 483202.0175233 * T) % 360.0;

        double Lr = Lp * DEG2RAD, Dr = D * DEG2RAD, Mr = M * DEG2RAD, Mpr = Mp * DEG2RAD, Fr = F * DEG2RAD;

        double dLon = 6.288774 * Math.sin(Mpr) + 1.274027 * Math.sin(2 * Dr - Mpr) + 0.658314 * Math.sin(2 * Dr);
        double dLat = 5.128122 * Math.sin(Fr) + 0.280602 * Math.sin(Mpr + Fr) + 0.277693 * Math.sin(Mpr - Fr);

        double moonLon = (Lp + dLon) * DEG2RAD;
        double moonLat = dLat * DEG2RAD;

        double eps = DEG2RAD * (23.439291 - 0.013004 * T);
        double x = Math.cos(moonLat) * Math.cos(moonLon);
        double y = Math.cos(eps) * Math.cos(moonLat) * Math.sin(moonLon) - Math.sin(eps) * Math.sin(moonLat);
        double z = Math.sin(eps) * Math.cos(moonLat) * Math.sin(moonLon) + Math.cos(eps) * Math.sin(moonLat);

        LunarInfo info = new LunarInfo();
        info.ra = wrapTwoPI(Math.atan2(y, x));
        info.dec = Math.asin(Math.max(-1.0, Math.min(1.0, z)));

        RADecDist sun = sunPosition(jd);
        double cosi = Math.sin(sun.dec) * Math.sin(info.dec) + Math.cos(sun.dec) * Math.cos(info.dec) * Math.cos(sun.ra - info.ra);
        info.phaseAngle = Math.acos(Math.max(-1.0, Math.min(1.0, cosi)));

        double num = Math.cos(sun.dec) * Math.sin(sun.ra - info.ra);
        double denom = Math.sin(sun.dec) * Math.cos(info.dec) - Math.cos(sun.dec) * Math.sin(info.dec) * Math.cos(sun.ra - info.ra);
        info.brightLimbAngle = Math.atan2(num, denom);

        return info;
    }
}