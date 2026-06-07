package com.leaf.game.core;

import com.leaf.game.util.Camera;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import imgui.ImGui;
import imgui.ImFont;

import java.util.ArrayList;
import java.util.List;

public class Telescope {

    public float fovDeg = 12.0f;
    public boolean showLabels      = true;
    public boolean showConstLines  = true;
    public boolean showAstroData   = false;
    public float labelMagLimit = 3.5f;

    private final Vector3f lookDir = new Vector3f(0, 1, 0);
    private float lookAz, lookAlt;
    private final List<TelescopeTarget> targets = new ArrayList<>();

    // Saved during update() so render2D() has access without changing method signatures
    private double currentJD = 2460000.0;

    public static class TelescopeTarget {
        public String name;
        public Vector2f screenPos;
        public float    mag;
        public boolean  isConst;
    }

    private static final float[][] NAMED_STARS = {
            {  52.27f,  47.40f,  1.79f }, {  10.90f,  56.54f,  2.07f }, {  37.95f,  89.26f,  2.02f },
            { 279.23f,  38.78f,  0.03f }, { 297.70f,   8.87f,  0.77f }, { 310.36f,  45.28f,  1.25f },
            {  88.79f,   7.41f,  0.42f }, {  78.63f,  -8.20f,  0.18f }, {  81.28f,  -0.30f,  2.23f },
            {  84.05f,  -1.20f,  1.69f }, {  85.19f,  -1.94f,  1.77f }, { 101.29f, -16.72f, -1.46f },
            { 113.65f,  31.89f,  1.58f }, { 116.33f,  28.03f,  1.15f }, { 152.09f,  11.97f,  1.36f },
            { 201.30f, -11.16f,  0.98f }, { 213.92f,  19.18f, -0.05f }, { 247.35f, -26.43f,  0.91f },
            { 165.93f,  61.75f,  1.81f }, { 200.98f,  54.93f,  1.86f }, { 206.88f,  49.31f,  1.76f },
            {  68.98f,  16.51f,  0.87f }, {  56.75f,  24.12f,  3.71f }, {  79.17f,  45.99f,  0.08f },
            { 114.83f,   5.23f,  3.14f }, { 222.68f,  74.16f,  2.07f }, {  14.18f,  60.72f,  2.27f },
            { 263.05f,  86.58f,  4.35f },
    };

    private static final String[] STAR_NAMES = {
            "Mirphak", "Schedar", "Polaris", "Vega", "Altair", "Deneb",
            "Betelgeuse", "Rigel", "Mintaka", "Alnilam", "Alnitak",
            "Sirius", "Castor", "Pollux", "Regulus", "Spica", "Arcturus",
            "Antares", "Dubhe", "Alioth", "Alkaid", "Aldebaran",
            "Alcyone (Pleiades)", "Capella", "Procyon", "Kochab", "γ Cas", "δ UMi",
    };

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

    public void update(Camera camera, DayNight dn) {
        this.currentJD = dn.currentJD;
        lookDir.set(camera.getLookDirection()).normalize();
        lookAlt = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, lookDir.y))));
        float az = (float) Math.toDegrees(Math.atan2(lookDir.x, -lookDir.z));
        lookAz = ((az % 360f) + 360f) % 360f;

        targets.clear();
        Matrix4f vp = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());

        // Always update tracking/compass, but only calculate stellar labels if it's night
        if (showLabels && dn.nightFactor > 0.1f) {
            double lst = Astronomy.localSiderealTime(dn.currentJD, Math.toRadians(GameConfig.observerLonDeg));
            double lat = Math.toRadians(GameConfig.observerLatDeg);

            for (int i = 0; i < NAMED_STARS.length; i++) {
                float ra  = (float) Math.toRadians(NAMED_STARS[i][0]);
                float dec = (float) Math.toRadians(NAMED_STARS[i][1]);
                float mag = NAMED_STARS[i][2];

                if (mag > labelMagLimit) continue;

                org.joml.Vector2d horiz = Astronomy.equatorialToHorizontal(ra, dec, lat, lst);
                if (horiz.y < -0.02) continue;
                Vector3f dir = Astronomy.azAltToDirection(horiz.x, horiz.y);

                Vector4f clip = new Vector4f(dir.x, dir.y, dir.z, 0.0f);
                vp.transform(clip);

                if (clip.w <= 0.0001f) continue;

                float ndcX = clip.x / clip.w;
                float ndcY = clip.y / clip.w;

                if (Math.abs(ndcX) > 1.5f || Math.abs(ndcY) > 1.5f) continue;

                TelescopeTarget t = new TelescopeTarget();
                t.name      = STAR_NAMES[i];
                t.screenPos = new Vector2f(ndcX, ndcY);
                t.mag       = mag;
                t.isConst   = false;
                targets.add(t);
            }

            if (showConstLines) {
                for (ConstellationData cd : ConstellationData.ALL) {
                    float ra  = (float) Math.toRadians(cd.centerRa);
                    float dec = (float) Math.toRadians(cd.centerDec);
                    org.joml.Vector2d horiz = Astronomy.equatorialToHorizontal(ra, dec, lat, lst);
                    if (horiz.y < 0.05) continue;

                    Vector3f dir = Astronomy.azAltToDirection(horiz.x, horiz.y);
                    Vector4f clip = new Vector4f(dir.x, dir.y, dir.z, 0.0f);
                    vp.transform(clip);

                    if (clip.w <= 0.0001f) continue;

                    float ndcX = clip.x / clip.w;
                    float ndcY = clip.y / clip.w;

                    if (Math.abs(ndcX) > 1.5f || Math.abs(ndcY) > 1.5f) continue;

                    TelescopeTarget t = new TelescopeTarget();
                    t.name      = cd.name;
                    t.screenPos = new Vector2f(ndcX, ndcY);
                    t.mag       = 0;
                    t.isConst   = true;
                    targets.add(t);
                }
            }
        }
    }

    private void drawTextWithShadow(imgui.ImDrawList draw, ImFont font, float size, float x, float y, int col, String text) {
        int shadow = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.85f);
        draw.addText(font, size, x + 1f, y + 1f, shadow, text);
        draw.addText(font, size, x, y, col, text);
    }

    public void render2D(int screenW, int screenH, Object renderer) {
        imgui.ImDrawList draw = (imgui.ImDrawList) renderer;
        float cx = screenW / 2f;
        float cy = screenH / 2f;
        float radius = Math.min(screenW, screenH) * 0.44f;

        // 1. Vignette: 4 solid rectangles + 1 thick smoothing ring
        int dark = ImGui.colorConvertFloat4ToU32(0.01f, 0.01f, 0.02f, 0.98f);
        draw.addRectFilled(0, 0, cx - radius, screenH, dark);
        draw.addRectFilled(cx + radius, 0, screenW, screenH, dark);
        draw.addRectFilled(cx - radius, 0, cx + radius, cy - radius, dark);
        draw.addRectFilled(cx - radius, cy + radius, cx + radius, screenH, dark);
        draw.addCircle(cx, cy, radius + 15f, dark, 64, 32f);

        // 2. Eyepiece ring
        int ringCol = ImGui.colorConvertFloat4ToU32(0.9f, 0.9f, 0.9f, 0.6f);
        draw.addCircle(cx, cy, radius, ringCol, 64, 2f);

        // 3. Crosshair at centre
        int crossCol = ImGui.colorConvertFloat4ToU32(1f, 0.3f, 0.1f, 0.6f);
        draw.addLine(cx - 10, cy, cx + 10, cy, crossCol, 1.5f);
        draw.addLine(cx, cy - 10, cx, cy + 10, crossCol, 1.5f);

        // 4. Star & Constellation labels
        ImFont font = ImGui.getFont();
        float base = ImGui.getFontSize();

        for (TelescopeTarget t : targets) {
            float px = cx + t.screenPos.x * cx;
            float py = cy - t.screenPos.y * cy;

            if (Math.hypot(px - cx, py - cy) > radius - 10f) continue;

            if (t.isConst) {
                int col = ImGui.colorConvertFloat4ToU32(0.55f, 0.75f, 1.0f, 0.35f);
                float tw = ImGui.calcTextSize(t.name).x;
                drawTextWithShadow(draw, font, base * 1.2f, px - tw / 2f, py, col, t.name);
            } else {
                float dotR = Math.max(1.5f, 4.5f - t.mag);
                int starCol = ImGui.colorConvertFloat4ToU32(1f, 1f, 0.9f, 0.9f);
                draw.addCircleFilled(px, py, dotR, starCol, 8);

                int textCol = ImGui.colorConvertFloat4ToU32(1f, 1f, 0.85f, 0.8f);
                drawTextWithShadow(draw, font, base * 0.9f, px + dotR + 4f, py - 6f, textCol, t.name);

                String note = NAV_NOTES.get(t.name);
                if (note != null) {
                    int noteCol = ImGui.colorConvertFloat4ToU32(0.6f, 0.8f, 1f, 0.75f);
                    drawTextWithShadow(draw, font, base * 0.75f, px + dotR + 4f, py + 6f, noteCol, note);
                }
            }
        }

        // 5. HUD readout (Relocated slightly lower to cleanly clear the top-center wave objectives & kills tracker)
        String compassName = azToCompassFull(lookAz);
        // Using \u00B0 Unicode escape sequence for the degree (°) symbol so it is completely safe from compiler encoding bugs
        String readout = String.format("Heading: %s (%.0f\u00B0)  |  Alt: %+.1f\u00B0", compassName, lookAz, lookAlt);
        float rw = ImGui.calcTextSize(readout).x;
        float rx = cx - rw / 2f;
        float ry = cy - radius + 85f; // Shifted from +35f to +85f to drop safely below the top-center HUD overlays
        drawTextWithShadow(draw, font, base * 1.05f, rx, ry, ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.85f), readout);
        // 6. Optional astro data
        if (showAstroData) {
            String astro = String.format("LST: %.2fh  |  JD: %.1f", Astronomy.localSiderealTime(currentJD, Math.toRadians(GameConfig.observerLonDeg)) * 12.0 / Math.PI, currentJD);
            float aw = ImGui.calcTextSize(astro).x;
            drawTextWithShadow(draw, font, base * 0.9f, cx - aw / 2f, ry + 22f, ImGui.colorConvertFloat4ToU32(0.5f, 0.8f, 1f, 0.7f), astro);
        }
    }

    public static String azToCompass(float az) {
        String[] pts = {"N","NNE","NE","ENE","E","ESE","SE","SSE",
                "S","SSW","SW","WSW","W","WNW","NW","NNW"};
        int idx = (int)((az + 11.25f) / 22.5f) % 16;
        return pts[idx];
    }

    /** Translates degree azimuths to full cardinal/ordinal strings */
    public static String azToCompassFull(float az) {
        String[] pts = {
                "North", "North-North-East", "North-East", "East-North-East",
                "East", "East-South-East", "South-East", "South-South-East",
                "South", "South-South-West", "South-West", "West-South-West",
                "West", "West-North-West", "North-West", "North-North-West"
        };
        int idx = (int)((az + 11.25f) / 22.5f) % 16;
        return pts[idx];
    }

    public List<TelescopeTarget> getTargets() { return targets; }

    public float getLookAz()  { return lookAz; }
    public float getLookAlt() { return lookAlt; }
}