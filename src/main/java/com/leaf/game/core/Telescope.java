package com.leaf.game.core;

import com.leaf.game.core.Astronomy.StarGPU;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Telescope — a held item that lets the player examine the sky in detail.
 *
 * HOW IT PLUGS IN (see notes for Window.java at the bottom):
 *   1. Player holds/equips the telescope item.
 *   2. While held, call Telescope.update(lookDir, dayNight) each frame.
 *   3. Call Telescope.render2D(screenW, screenH) to draw the HUD overlay.
 *      This is a pure Java2D / immediate-mode draw — no extra shaders needed.
 *
 * The telescope does NOT change the 3D camera. It renders its own 2D overlay
 * on top of the existing sky showing:
 *   - A circular vignette (eyepiece feel)
 *   - Named bright stars near the look direction
 *   - Constellation name if the centre of a constellation is in view
 *   - Compass bearing and altitude readout
 *   - Current sidereal time and Julian Date (optional, toggleable)
 *
 * NAVIGATION INFORMATION displayed:
 *   - "N 347°  Alt +32.4°" → player learns Polaris is ~North and ~32° up
 *     (in a real telescope this tells you your latitude is 32°N)
 *   - Star names near Polaris teach the player it's the "anchor star"
 *   - Orion's belt rising/setting direction teaches East/West
 */
public class Telescope {

    // ── Configuration ──────────────────────────────────────────────────────────
    /**
     * Angular radius of the telescope's field of view, in degrees.
     * ~5° feels like a real telescope. ~15° is more game-friendly (easier to aim).
     */
    public float fovDeg = 12.0f;

    /** Toggle with a key (e.g. F or Tab while holding scope). */
    public boolean showLabels      = true;
    public boolean showConstLines  = true;
    public boolean showAstroData   = false;  // JD / LST — off by default, lore-appropriate

    /** Magnitude limit for labelling stars. 2.0 = only very bright; 4.5 = many stars. */
    public float labelMagLimit = 3.5f;

    // ── State (updated every frame) ────────────────────────────────────────────
    /** Direction the telescope is currently pointing (normalised world vector). */
    private final Vector3f lookDir = new Vector3f(0, 1, 0);

    /** Azimuth (degrees, 0=N, 90=E) and altitude (degrees) of the look direction. */
    private float lookAz, lookAlt;

    // Stars close enough to the FOV centre to display — refreshed each frame.
    private final List<TelescopeTarget> targets = new ArrayList<>();

    // ── Inner record ──────────────────────────────────────────────────────────
    public static class TelescopeTarget {
        public String name;
        public Vector2f screenPos;  // pixel position in the overlay, centred at (0,0)
        public float    mag;
        public boolean  isConst;    // true = constellation label, not a star
    }

    // ── Named bright stars (Hipparcos positions, J2000) ───────────────────────
    // Format: { name, RA_deg, Dec_deg, magnitude }
    // This list covers the 30 brightest / most navigationally useful stars.
    // For gameplay, these are the stars the player will learn to recognise.
    private static final float[][] NAMED_STARS = {
        // Name is stored separately; this array is parallel to STAR_NAMES.
        // { RA_deg, Dec_deg, mag }
        {  52.27f,  47.40f,  1.79f },  // 0  Mirphak (Alpha Per)
        {  10.90f,  56.54f,  2.07f },  // 1  Schedar (Alpha Cas)
        {  37.95f,  89.26f,  2.02f },  // 2  Polaris ★★★ navigation anchor
        { 279.23f,  38.78f,  0.03f },  // 3  Vega
        { 297.70f,   8.87f,  0.77f },  // 4  Altair
        { 310.36f,  45.28f,  1.25f },  // 5  Deneb
        {  88.79f,   7.41f,  0.42f },  // 6  Betelgeuse (Orion shoulder, red)
        {  78.63f,  -8.20f,  0.18f },  // 7  Rigel    (Orion foot, blue)
        {  81.28f,  -0.30f,  2.23f },  // 8  Mintaka  (Orion belt W)
        {  84.05f,  -1.20f,  1.69f },  // 9  Alnilam  (Orion belt C)
        {  85.19f,  -1.94f,  1.77f },  // 10 Alnitak  (Orion belt E)
        { 101.29f, -16.72f, -1.46f },  // 11 Sirius ★★★ brightest star
        { 113.65f,  31.89f,  1.58f },  // 12 Castor   (Gemini)
        { 116.33f,  28.03f,  1.15f },  // 13 Pollux   (Gemini)
        { 152.09f,  11.97f,  1.36f },  // 14 Regulus  (Leo, heart)
        { 201.30f, -11.16f,  0.98f },  // 15 Spica    (Virgo)
        { 213.92f,  19.18f, -0.05f },  // 16 Arcturus (Boötes, orange giant)
        { 247.35f, -26.43f,  0.91f },  // 17 Antares  (Scorpius, red supergiant)
        { 165.93f,  61.75f,  1.81f },  // 18 Dubhe    (Big Dipper bowl)
        { 200.98f,  54.93f,  1.86f },  // 19 Alioth   (Big Dipper handle)
        { 206.88f,  49.31f,  1.76f },  // 20 Alkaid   (Big Dipper tip)
        {  68.98f,  16.51f,  0.87f },  // 21 Aldebaran (Taurus, bull's eye)
        {  56.75f,  24.12f,  3.71f },  // 22 Alcyone  (Pleiades centre)
        {  79.17f,  45.99f,  0.08f },  // 23 Capella  (Auriga)
        { 114.83f,   5.23f,  3.14f },  // 24 Procyon  (Canis Minor)
        { 222.68f,  74.16f,  2.07f },  // 25 Kochab   (Ursa Minor bowl)
        {  14.18f,  60.72f,  2.27f },  // 26 Gamma Cas (Cassiopeia centre)
        { 263.05f,  86.58f,  4.35f },  // 27 Delta UMi (near Polaris)
    };

    private static final String[] STAR_NAMES = {
        "Mirphak", "Schedar", "Polaris", "Vega", "Altair", "Deneb",
        "Betelgeuse", "Rigel", "Mintaka", "Alnilam", "Alnitak",
        "Sirius", "Castor", "Pollux", "Regulus", "Spica", "Arcturus",
        "Antares", "Dubhe", "Alioth", "Alkaid", "Aldebaran",
        "Alcyone (Pleiades)", "Capella", "Procyon", "Kochab", "γ Cas", "δ UMi",
    };

    // Navigation notes shown next to specific stars (teach the player what each means).
    // Key = star name.
    private static final java.util.Map<String, String> NAV_NOTES = new java.util.HashMap<>();
    static {
        NAV_NOTES.put("Polaris",     "True North — altitude = your latitude");
        NAV_NOTES.put("Sirius",      "Rises almost due East");
        NAV_NOTES.put("Betelgeuse",  "Orion — rises East, sets West");
        NAV_NOTES.put("Dubhe",       "Points to Polaris");
        NAV_NOTES.put("Alkaid",      "Tip of Big Dipper handle");
        NAV_NOTES.put("Vega",        "Summer Triangle — nearly overhead in July");
        NAV_NOTES.put("Antares",     "South in summer, near horizon");
        NAV_NOTES.put("Deneb",       "Summer Triangle — high in September");
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Call each frame while the telescope is active.
     * @param camLookDir  Normalised camera look direction in world space.
     * @param dn          The live DayNight instance.
     */
    public void update(Vector3f camLookDir, DayNight dn) {
        lookDir.set(camLookDir).normalize();

        // Convert world direction → azimuth/altitude for the HUD readout.
        // Y = sin(alt), XZ magnitude = cos(alt).
        lookAlt = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, lookDir.y))));
        float az = (float) Math.toDegrees(Math.atan2(lookDir.x, -lookDir.z));
        lookAz = ((az % 360f) + 360f) % 360f;

        // Find named stars in FOV.
        targets.clear();
        double cosHalfFov = Math.cos(Math.toRadians(fovDeg));

        if (showLabels) {
            double lst = Astronomy.localSiderealTime(dn.currentJD, Math.toRadians(-79.3370));
            double lat = Math.toRadians(43.8561);

            for (int i = 0; i < NAMED_STARS.length; i++) {
                float ra  = (float) Math.toRadians(NAMED_STARS[i][0]);
                float dec = (float) Math.toRadians(NAMED_STARS[i][1]);
                float mag = NAMED_STARS[i][2];

                if (mag > labelMagLimit) continue;

                // Convert equatorial → horizontal → world direction
                org.joml.Vector2d horiz = Astronomy.equatorialToHorizontal(ra, dec, lat, lst);
                if (horiz.y < -0.02) continue;   // below horizon
                Vector3f dir = Astronomy.azAltToDirection(horiz.x, horiz.y);

                // Is this star in the telescope's FOV?
                float dot = lookDir.dot(dir);
                if (dot < cosHalfFov) continue;

                // Project onto the telescope's 2D image plane.
                Vector2f screen = project(dir, cosHalfFov);

                TelescopeTarget t = new TelescopeTarget();
                t.name      = STAR_NAMES[i];
                t.screenPos = screen;
                t.mag       = mag;
                t.isConst   = false;
                targets.add(t);
            }

            // Constellation centres
            if (showConstLines) {
                for (ConstellationData cd : ConstellationData.ALL) {
                    float ra  = (float) Math.toRadians(cd.centerRa);
                    float dec = (float) Math.toRadians(cd.centerDec);
                    org.joml.Vector2d horiz = Astronomy.equatorialToHorizontal(ra, dec, lat, lst);
                    if (horiz.y < 0.05) continue;
                    Vector3f dir = Astronomy.azAltToDirection(horiz.x, horiz.y);
                    float dot = lookDir.dot(dir);
                    if (dot < cosHalfFov * 0.85f) continue;   // slightly wider for labels

                    Vector2f screen = project(dir, cosHalfFov);
                    TelescopeTarget t = new TelescopeTarget();
                    t.name      = cd.name;
                    t.screenPos = screen;
                    t.mag       = 0;
                    t.isConst   = true;
                    targets.add(t);
                }
            }
        }
    }

    /**
     * Project a world direction onto the telescope's 2D image plane.
     * Returns normalised coordinates where (0,0) = centre, (±1, ±1) = FOV edge.
     */
    private Vector2f project(Vector3f dir, double cosHalfFov) {
        // Build a local coordinate frame for the telescope direction.
        Vector3f up    = new Vector3f(0, 1, 0);
        Vector3f right = new Vector3f(lookDir).cross(up).normalize();
        Vector3f tUp   = new Vector3f(right).cross(lookDir).normalize();

        // Angular offset from centre, scaled to [-1,1] over the FOV.
        float scale = (float)(1.0 / Math.tan(Math.toRadians(fovDeg)));
        float x =  dir.dot(right) * scale;
        float y =  dir.dot(tUp)   * scale;
        return new Vector2f(x, y);
    }

    /**
     * Render the telescope overlay.  Call this AFTER the 3D scene is done,
     * before SwapBuffers.
     *
     * This uses a simple immediate-mode 2D draw approach. You'll need to plug
     * in whatever 2D drawing API your engine uses (NanoVG, SpriteBatch, etc).
     * See the RENDERING NOTES below for the OpenGL state required.
     *
     * @param screenW   Framebuffer width in pixels.
     * @param screenH   Framebuffer height in pixels.
     * @param renderer  Your 2D drawing interface — replace with your actual type.
     */
    public void render2D(int screenW, int screenH, Object renderer) {
        // ── REPLACE THIS METHOD BODY with your engine's 2D drawing calls ──────
        // The logic below shows WHAT to draw; plug in your actual draw API.

        float cx = screenW / 2f;
        float cy = screenH / 2f;
        float radius = Math.min(screenW, screenH) * 0.44f;   // eyepiece circle radius

        // 1. Dark vignette outside the eyepiece circle.
        //    Draw a fullscreen quad, then punch a transparent circle in the centre.
        //    With NanoVG: nvgBeginPath; nvgRect(0,0,W,H); nvgCircle(cx,cy,radius)
        //                 nvgPathWinding(NVG_HOLE); nvgFillColor(0,0,0,0.88f); nvgFill

        // 2. Eyepiece ring (thin bright circle).
        //    nvgBeginPath; nvgCircle(cx,cy,radius); nvgStrokeColor(0.9,0.9,0.9,0.6);
        //    nvgStrokeWidth(2); nvgStroke

        // 3. Crosshair at centre.
        //    Small ±8px cross, colour = rgba(1, 0.8, 0.3, 0.5)

        // 4. Star labels.
        for (TelescopeTarget t : targets) {
            // Convert normalised [-1,1] screen coords to pixel coords.
            float px = cx + t.screenPos.x * radius;
            float py = cy - t.screenPos.y * radius;   // Y is flipped (screen Y grows down)

            if (t.isConst) {
                // Constellation name: larger, dimmer text in the sky colour.
                // Draw at (px, py) with colour rgba(0.55, 0.75, 1.0, 0.35)
                // Font: bold, ~14px, centred.
            } else {
                // Star: small dot + name label.
                float dotR = Math.max(2f, 5f - t.mag);  // brighter = bigger dot
                // Draw filled circle at (px, py) radius dotR, colour rgba(1,1,0.9,0.9)
                // Draw name label at (px + dotR + 4, py), colour rgba(1,1,0.85,0.8), ~11px

                // Optional nav note below the name.
                String note = NAV_NOTES.get(t.name);
                if (note != null) {
                    // Draw note at (px + dotR + 4, py + 14), colour rgba(0.7,0.9,1,0.6), ~9px italic
                }
            }
        }

        // 5. HUD readout (bottom of eyepiece).
        String compassLetter = azToCompass(lookAz);
        String readout = String.format("%s %.0f°   Alt %+.1f°", compassLetter, lookAz, lookAlt);
        // Draw at (cx, cy + radius - 20), centred, rgba(1,1,1,0.7), ~12px

        // 6. Optional astro data (top of eyepiece).
        if (showAstroData) {
            // Show JD and LST for the hardcore player / puzzle mechanics.
            // These numbers let a player correlate star positions with calendar dates.
        }
    }

    /** Convert azimuth degrees to a compass direction string. */
    public static String azToCompass(float az) {
        String[] pts = {"N","NNE","NE","ENE","E","ESE","SE","SSE",
                        "S","SSW","SW","WSW","W","WNW","NW","NNW"};
        int idx = (int)((az + 11.25f) / 22.5f) % 16;
        return pts[idx];
    }

    /** @return list of targets in FOV (for use by Window.java if needed). */
    public List<TelescopeTarget> getTargets() { return targets; }

    public float getLookAz()  { return lookAz; }
    public float getLookAlt() { return lookAlt; }
}

/*
 * ════════════════════════════════════════════════════════════════════════════
 * INTEGRATION NOTES FOR Window.java
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 1. MOON SIZE — your moon mesh is scaled by the model matrix.  The angular
 *    diameter of the real Moon is ~0.52°.  A game moon that "looks right"
 *    typically uses 1.5–3× that (0.75–1.5°).  In your model matrix builder:
 *
 *      float moonRadius = 0.025f;  // was probably ~0.008f or similar — increase this
 *      Matrix4f moonModel = new Matrix4f()
 *          .translate(moonDir.x * 950f, moonDir.y * 950f, moonDir.z * 950f)
 *          .scale(moonRadius);
 *
 *    950f = far enough to be behind terrain but inside the far plane.
 *    Tune moonRadius until it looks right. 0.02–0.04 is a good starting range.
 *
 * 2. STAR SHADER UNIFORM RENAME — star_vertex.glsl now expects "viewProj"
 *    instead of "invViewProj".  Change your upload from:
 *
 *      starShader.setUniformMat4("invViewProj", invViewProj);
 *    to:
 *      starShader.setUniformMat4("viewProj", viewProj);   // the regular VP matrix
 *
 *    The double-inversion was accidentally correct but wasteful.
 *
 * 3. SAME RENAME for constellation_vertex.glsl — it now also uses "viewProj".
 *
 * 4. GL_PROGRAM_POINT_SIZE — make sure this is enabled once at init:
 *      glEnable(GL_PROGRAM_POINT_SIZE);
 *    Without it, gl_PointSize in the vertex shader has no effect and all stars
 *    render as 1-pixel points.
 *
 * 5. MOON PHASE UNIFORMS — moon_fragment.glsl now uses moonPhaseAngle and
 *    moonBrightLimbAngle (both already computed in DayNight.java).
 *    Add these to your moon shader upload block:
 *
 *      moonShader.setUniformFloat("moonPhaseAngle",      dayNight.moonPhaseAngle);
 *      moonShader.setUniformFloat("moonBrightLimbAngle", dayNight.moonBrightLimbAngle);
 *
 * 6. TELESCOPE INTEGRATION — call from your render loop when the item is held:
 *
 *      if (player.isHoldingTelescope()) {
 *          telescope.update(camera.getLookDirection(), dayNight);
 *          telescope.render2D(framebufferWidth, framebufferHeight, yourRenderer);
 *      }
 *
 * 7. CONSTELLATION lineAlpha uniform — add a player-accessible setting (0.0–1.0).
 *    Default 0.35 is a good starting point (visible but not garish).
 *    In your constellation render pass:
 *      constShader.setUniformFloat("lineAlpha", constellationAlpha);   // 0.35f default
 *
 * ════════════════════════════════════════════════════════════════════════════
 */
