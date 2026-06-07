package com.leaf.game.core;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * MeteorSystem — manages sporadic meteors, annual meteor showers, fireballs,
 * and the player's "meteor ability" visual.
 *
 * RENDERING:
 *   Each active meteor uploads a small VBO of point sprites (one per trail segment)
 *   rendered with meteor_vertex.glsl / meteor_fragment.glsl using additive blending.
 *   See uploadToGPU() and the integration notes at the bottom for Window.java.
 *
 * METEOR SHOWERS (real dates, accurate radiants):
 *   Perseids   — Aug  12 peak, radiant Perseus  (RA 48°,  Dec 58°)
 *   Leonids    — Nov  17 peak, radiant Leo       (RA 152°, Dec 22°)
 *   Geminids   — Dec  14 peak, radiant Gemini    (RA 112°, Dec 33°)
 *   Orionids   — Oct  21 peak, radiant Orion     (RA 95°,  Dec 16°)
 *   Quadrantids— Jan   3 peak, radiant Boötes    (RA 230°, Dec 49°)
 *
 * PHYSICS ACCURACY (simplified for rendering):
 *   Real meteors travel at 11–72 km/s. In sky angular terms they cross
 *   10–60° in 0.5–2 seconds. We model this as an angular speed + lifetime.
 *   Shower meteors originate FROM the radiant point; sporadics are random.
 */
public class MeteorSystem {

    // ── Meteor shower definitions ─────────────────────────────────────────────
    public static class Shower {
        public final String name;
        public final int    peakMonth;   // 1-12
        public final int    peakDay;
        public final float  peakZHR;     // zenithal hourly rate at peak
        public final float  radiantRa;   // degrees
        public final float  radiantDec;  // degrees
        public final int    durationDays;// days either side of peak with notable activity

        public Shower(String n, int m, int d, float zhr, float ra, float dec, int dur) {
            name=n; peakMonth=m; peakDay=d; peakZHR=zhr; radiantRa=ra; radiantDec=dec;
            durationDays=dur;
        }
    }

    public static final Shower[] SHOWERS = {
        new Shower("Quadrantids", 1,  3,  120f, 230f,  49f, 3),
        new Shower("Lyrids",      4, 22,   18f, 272f,  34f, 3),
        new Shower("Perseids",    8, 12,  100f,  48f,  58f, 7),  // the famous one
        new Shower("Orionids",   10, 21,   23f,  95f,  16f, 5),
        new Shower("Leonids",    11, 17,   15f, 152f,  22f, 4),
        new Shower("Geminids",   12, 14,  120f, 112f,  33f, 5),
    };

    // ── Individual meteor ─────────────────────────────────────────────────────
    public static class Meteor {
        public Vector3f headDir    = new Vector3f();  // current head position (unit sky dir)
        public Vector3f velocity   = new Vector3f();  // angular velocity (radians/sec)
        public float    lifetime   = 0;               // total seconds this meteor lives
        public float    age        = 0;               // seconds elapsed
        public float    brightness = 0;               // 0=faint, 1=fireball
        public boolean  isFireball = false;
        public boolean  isPlayerAbility = false;      // spawned by player meteor ability

        // Trail: list of past head positions (oldest at end)
        public final List<Vector3f> trail = new ArrayList<>();
        public static final int TRAIL_POINTS = 18;   // number of trail segments

        // Persistent train (lingers after meteor fades) — only for fireballs
        public float trainLifetime = 0;   // if > 0, train lingers this many more seconds
        public float trainAge      = 0;

        public boolean isAlive() {
            return age < lifetime || trainAge < trainLifetime;
        }
        public float headAlpha() {
            return Math.max(0f, 1f - age / lifetime);
        }
    }

    // ── System state ──────────────────────────────────────────────────────────
    private final List<Meteor> active = new ArrayList<>();
    private final Random rng = new Random();
    private double spawnAccumulator = 0;    // fractional meteor debt

    // ── GPU data (uploaded each frame) ───────────────────────────────────────
    // Flat float buffer: [x,y,z, trailFrac, brightness, ...]
    // 5 floats per vertex × TRAIL_POINTS vertices per meteor
    private float[] gpuBuffer = new float[0];
    public  int     gpuVertexCount = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Call every frame. dt in real seconds.
     * dn provides the current Julian date and DayNight state.
     */
    public void update(float dt, DayNight dn) {
        // Only spawn meteors at night
        if (dn.nightFactor < 0.3f) {
            active.clear();
            return;
        }

        // Determine current rate
        float rate = computeRate(dn);
        spawnAccumulator += rate * dt / 3600.0;   // rate is in per-hour

        while (spawnAccumulator >= 1.0) {
            spawnAccumulator -= 1.0;
            Shower active_shower = getActiveShower(dn);
            spawnMeteor(dn, active_shower, false);
        }

        // Randomly spawn fireballs (very rare — about 1 per 2 hours in dark skies)
        if (rng.nextDouble() < dt / 7200.0) {
            spawnFireball(dn);
        }

        // Update all active meteors
        active.removeIf(m -> !m.isAlive());
        for (Meteor m : active) {
            updateMeteor(m, dt, dn);
        }

        buildGPUBuffer();
    }

    /**
     * Spawn a meteor from the player's "meteor ability".
     * This is a much brighter, slower fireball that originates from the player's
     * cast direction and leaves a long persistent train.
     *
     * @param castDir  The world direction the player aimed at (normalised).
     * @param dn       Current DayNight for context.
     */
    public void spawnPlayerMeteor(Vector3f castDir, DayNight dn) {
        Meteor m = new Meteor();
        m.isPlayerAbility = true;
        m.isFireball = true;

        // Start slightly above the aim direction
        Vector3f start = new Vector3f(castDir).normalize();
        if (start.y < 0.1f) start.y = 0.1f;
        start.normalize();
        m.headDir.set(start);

        // Velocity: perpendicular to the aim, going downward-ish
        // Player meteors streak dramatically across the sky
        Vector3f perp = new Vector3f(start.z, 0, -start.x).normalize();
        float speed = (float)(0.6 + rng.nextDouble() * 0.4);  // radians/sec — slow for drama
        m.velocity.set(perp).mul(speed);
        m.velocity.y -= 0.3f;   // slight downward arc

        // Player meteors: bright, long-lasting
        m.brightness    = 0.92f + (float)rng.nextDouble() * 0.08f;
        m.lifetime      = 2.5f + (float)rng.nextDouble() * 1.5f;
        m.trainLifetime = 4.0f + (float)rng.nextDouble() * 3.0f;  // long persistent train

        active.add(m);
    }

    /** @return list of currently active meteors for rendering. */
    public List<Meteor> getActive() { return active; }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — SPAWNING
    // ─────────────────────────────────────────────────────────────────────────

    private float computeRate(DayNight dn) {
        // Background sporadic rate: ~10 per hour visible in dark skies
        float rate = 10f;

        Shower s = getActiveShower(dn);
        if (s != null) {
            float peakFrac = showerPeakFraction(dn, s);
            rate += s.peakZHR * peakFrac;
        }

        // Moon washes out faint meteors — reduce apparent rate
        float moonWash = 1f - dn.nightFactor * 0.5f;   // rough; nightFactor encodes moonlight
        rate *= moonWash;

        return rate;
    }

    private Shower getActiveShower(DayNight dn) {
        // Parse current date from Julian Date
        int[] ymd = jdToYMD(dn.currentJD);
        int month = ymd[1], day = ymd[2];

        for (Shower s : SHOWERS) {
            int daysFromPeak = Math.abs(dayOfYear(month, day) - dayOfYear(s.peakMonth, s.peakDay));
            if (daysFromPeak <= s.durationDays) return s;
        }
        return null;
    }

    private float showerPeakFraction(DayNight dn, Shower s) {
        int[] ymd = jdToYMD(dn.currentJD);
        int doy = dayOfYear(ymd[1], ymd[2]);
        int peak = dayOfYear(s.peakMonth, s.peakDay);
        float dist = Math.abs(doy - peak);
        return (float) Math.exp(-dist * dist / (2.0 * s.durationDays * 0.5));
    }

    private void spawnMeteor(DayNight dn, Shower shower, boolean isFireball) {
        Meteor m = new Meteor();

        double latRad = Math.toRadians(43.8561);
        double lonRad = Math.toRadians(-79.337);
        double lst    = Astronomy.localSiderealTime(dn.currentJD, lonRad);

        Vector3f startDir;
        if (shower != null && rng.nextDouble() < 0.8) {
            // Shower meteor: radiates from the shower radiant with scatter
            double ra  = Math.toRadians(shower.radiantRa);
            double dec = Math.toRadians(shower.radiantDec);
            org.joml.Vector2d horiz = Astronomy.equatorialToHorizontal(ra, dec, latRad, lst);

            // Check radiant is above horizon
            if (horiz.y < 0) {
                // Radiant below horizon — no shower meteors visible from this direction
                return;
            }
            Vector3f radiant = Astronomy.azAltToDirection(horiz.x, horiz.y);

            // Start the meteor near the radiant, spread out slightly
            float scatter = (float)(rng.nextGaussian() * 0.3);
            startDir = randomPerpendicular(radiant, scatter);
        } else {
            // Sporadic: random sky position above horizon
            startDir = randomSkyDirection();
        }

        if (startDir.y < 0) return;   // below horizon, skip

        m.headDir.set(startDir);

        // Velocity: meteors travel away from the radiant
        if (shower != null) {
            double ra  = Math.toRadians(shower.radiantRa);
            double dec = Math.toRadians(shower.radiantDec);
            org.joml.Vector2d horiz = Astronomy.equatorialToHorizontal(ra, dec, latRad, lst);
            Vector3f radiant = Astronomy.azAltToDirection(horiz.x, horiz.y);
            // Velocity points AWAY from radiant
            m.velocity.set(startDir).sub(radiant).normalize();
        } else {
            m.velocity.set(randomSkyDirection()).sub(startDir).normalize();
        }

        // Speed: real meteors cover 20-60° in ~0.8s. Angular speed in rad/sec:
        float speed = (float)(0.5 + rng.nextDouble() * 1.5) + (isFireball ? -0.3f : 0f);
        m.velocity.mul(speed);
        m.velocity.y -= (float)rng.nextDouble() * 0.1f;   // slight downward bias

        m.brightness    = isFireball ? 0.85f + (float)rng.nextDouble() * 0.15f
                                     : (float)rng.nextDouble();
        m.lifetime      = isFireball ? 1.8f + (float)rng.nextFloat() * 1.2f
                                     : 0.4f + (float)rng.nextFloat() * 0.8f;
        m.isFireball    = isFireball;
        m.trainLifetime = isFireball ? 2.0f + (float)rng.nextFloat() * 3.0f : 0f;

        active.add(m);
    }

    private void spawnFireball(DayNight dn) {
        spawnMeteor(dn, getActiveShower(dn), true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — UPDATE
    // ─────────────────────────────────────────────────────────────────────────

    private void updateMeteor(Meteor m, float dt, DayNight dn) {
        if (m.age < m.lifetime) {
            // Save current head to trail
            if (m.trail.size() >= Meteor.TRAIL_POINTS) {
                m.trail.remove(m.trail.size() - 1);
            }
            m.trail.add(0, new Vector3f(m.headDir));

            // Move head along velocity (angular motion on unit sphere)
            Vector3f delta = new Vector3f(m.velocity).mul(dt);
            m.headDir.add(delta).normalize();

            m.age += dt;

            // Below horizon? Kill it
            if (m.headDir.y < -0.05f) {
                m.age = m.lifetime;
            }
        } else {
            // Meteor faded — persistent train lingers for fireballs
            m.trainAge += dt;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — GPU BUFFER
    // ─────────────────────────────────────────────────────────────────────────

    private void buildGPUBuffer() {
        // 5 floats per vertex: [x, y, z, trailFrac, brightness]
        int needed = active.size() * Meteor.TRAIL_POINTS * 5;
        if (gpuBuffer.length < needed) gpuBuffer = new float[needed * 2];

        int idx = 0;
        for (Meteor m : active) {
            if (m.trail.isEmpty()) continue;
            float headAlpha = m.headAlpha();
            int n = Math.min(m.trail.size(), Meteor.TRAIL_POINTS);

            for (int i = 0; i < n; i++) {
                Vector3f pos  = m.trail.get(i);
                float frac    = (float) i / (n - 1 + 0.001f);  // 0=head, 1=tail
                float bright  = m.brightness * headAlpha;

                // Persistent train: use remaining train points, fading
                if (m.age >= m.lifetime) {
                    float trainFade = 1f - m.trainAge / Math.max(m.trainLifetime, 0.01f);
                    bright = m.brightness * trainFade * (1f - frac * 0.5f);
                }

                gpuBuffer[idx++] = pos.x;
                gpuBuffer[idx++] = pos.y;
                gpuBuffer[idx++] = pos.z;
                gpuBuffer[idx++] = frac;
                gpuBuffer[idx++] = bright;
            }
        }
        gpuVertexCount = idx / 5;
    }

    /**
     * Returns the raw float buffer for VBO upload.
     * Layout: [x, y, z, trailFrac, brightness] per vertex.
     * Upload as GL_POINTS with meteorShader bound.
     */
    public float[] getGPUBuffer()  { return gpuBuffer; }
    public int     getVertexCount(){ return gpuVertexCount; }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    private Vector3f randomSkyDirection() {
        while (true) {
            float x = (float)(rng.nextGaussian());
            float y = (float)(rng.nextGaussian());
            float z = (float)(rng.nextGaussian());
            Vector3f v = new Vector3f(x, Math.abs(y), z).normalize();
            if (v.y > 0.05f) return v;
        }
    }

    private Vector3f randomPerpendicular(Vector3f base, float spread) {
        Vector3f perp = new Vector3f(base.z, 0, -base.x);
        if (perp.lengthSquared() < 0.001f) perp.set(0, 0, 1);
        perp.normalize();
        Vector3f perp2 = new Vector3f(base).cross(perp).normalize();
        float a = (float)(rng.nextGaussian() * spread);
        float b = (float)(rng.nextGaussian() * spread);
        return new Vector3f(base)
            .add(new Vector3f(perp).mul(a))
            .add(new Vector3f(perp2).mul(b))
            .normalize();
    }

    private static int dayOfYear(int month, int day) {
        int[] days = {0,31,59,90,120,151,181,212,243,273,304,334};
        return days[month-1] + day;
    }

    /** Rough Julian Date → calendar date. Returns [year, month, day]. */
    private static int[] jdToYMD(double jd) {
        int z = (int)(jd + 0.5);
        int a = (int)((z - 1867216.25) / 36524.25);
        a = z + 1 + a - a/4;
        int b = a + 1524;
        int c = (int)((b - 122.1) / 365.25);
        int d = (int)(365.25 * c);
        int e = (int)((b - d) / 30.6001);
        int day   = b - d - (int)(30.6001 * e);
        int month = e < 14 ? e - 1 : e - 13;
        int year  = month > 2 ? c - 4716 : c - 4715;
        return new int[]{year, month, day};
    }
}

/*
 * ════════════════════════════════════════════════════════════════════════════
 * WINDOW.JAVA INTEGRATION — METEORS
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 1. DECLARE in your Window / Renderer class:
 *        private final MeteorSystem meteorSystem = new MeteorSystem();
 *        private int meteorVao, meteorVbo;
 *        private ShaderProgram meteorShader;
 *
 * 2. INIT (once, after GL context is created):
 *        meteorShader = new ShaderProgram("meteor_vertex.glsl", "meteor_fragment.glsl");
 *        meteorVao = glGenVertexArrays();
 *        meteorVbo = glGenBuffers();
 *        glBindVertexArray(meteorVao);
 *        glBindBuffer(GL_ARRAY_BUFFER, meteorVbo);
 *        // Layout: [x,y,z, trailFrac, brightness] = 5 floats = 20 bytes
 *        glVertexAttribPointer(0, 3, GL_FLOAT, false, 20, 0);   // aPos
 *        glEnableVertexAttribArray(0);
 *        glVertexAttribPointer(1, 1, GL_FLOAT, false, 20, 12);  // aTrailFrac
 *        glEnableVertexAttribArray(1);
 *        glVertexAttribPointer(2, 1, GL_FLOAT, false, 20, 16);  // aBrightness
 *        glEnableVertexAttribArray(2);
 *        glBindVertexArray(0);
 *
 * 3. UPDATE (each game loop tick, before render):
 *        meteorSystem.update(dt, dayNight);
 *
 * 4. RENDER (in your sky pass, after stars, before terrain):
 *        if (meteorSystem.getVertexCount() > 0) {
 *            // Upload buffer
 *            glBindVertexArray(meteorVao);
 *            glBindBuffer(GL_ARRAY_BUFFER, meteorVbo);
 *            float[] buf = meteorSystem.getGPUBuffer();
 *            glBufferData(GL_ARRAY_BUFFER, buf, GL_DYNAMIC_DRAW);  // or glBufferSubData
 *
 *            // Render
 *            meteorShader.use();
 *            meteorShader.setMat4("viewProj", viewProjMatrix);
 *            glEnable(GL_PROGRAM_POINT_SIZE);
 *            glBlendFunc(GL_SRC_ALPHA, GL_ONE);     // additive
 *            glDisable(GL_DEPTH_TEST);
 *            glDepthMask(false);
 *            glDrawArrays(GL_POINTS, 0, meteorSystem.getVertexCount());
 *            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
 *            glEnable(GL_DEPTH_TEST);
 *            glDepthMask(true);
 *            glBindVertexArray(0);
 *        }
 *
 * 5. PLAYER ABILITY:
 *        // When the player activates their meteor ability:
 *        meteorSystem.spawnPlayerMeteor(camera.getLookDirection(), dayNight);
 *        // Spawn 3-7 at once for a "shower" feel:
 *        for (int i = 0; i < 5; i++) {
 *            Vector3f scattered = slightlyScattered(camera.getLookDir(), 0.2f);
 *            meteorSystem.spawnPlayerMeteor(scattered, dayNight);
 *        }
 *
 * ════════════════════════════════════════════════════════════════════════════
 */
