package com.leaf.game.util;

import com.leaf.game.entity.Player;
import com.leaf.game.world.WorldGen;
import imgui.ImGui;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Live preview of all noise fields + the combined terrain height map.
 * Toggle with F4. Click "Refresh" after changing GameConfig sliders.
 *
 * The HEIGHT MAP is the most useful view for tuning:
 *   Deep blue  = deep ocean
 *   Light blue = shallow ocean / coast
 *   Green      = plains
 *   Brown      = highlands / foothills
 *   White      = mountain peaks
 *
 * Individual noise maps (grayscale) show each input independently.
 */
public class NoiseVisualizer {

    private static final int SIZE = 256; // texture resolution in pixels

    // World units per pixel. SIZE * unitsPerPixel = world blocks covered.
    private int unitsPerPixel = 8;       // default: 256 * 8 = 2048 blocks

    @FunctionalInterface
    private interface Sampler { float sample(int wx, int wz); }

    private final WorldGen gen;

    // Grayscale individual noise textures
    private int contTex   = -1;
    private int erosTex   = -1;
    private int pvTex     = -1;
    private int tempTex   = -1;
    private int humTex    = -1;

    // Colored combined height map texture — the most useful view
    private int heightTex = -1;

    // World coordinate of the preview centre
    private int centreX = 0;
    private int centreZ = 0;

    public NoiseVisualizer(WorldGen gen) {
        this.gen = gen;
    }

    // =========================================================================
    // MAIN RENDER — call once per ImGui frame
    // =========================================================================

    public void renderWindow(Player player) {
        ImGui.begin("Noise Viewer  [F4]");

        // ── Controls ──────────────────────────────────────────────────────────
        if (ImGui.button("Centre on Player")) {
            centreX = (int) player.position.x;
            centreZ = (int) player.position.z;
        }
        ImGui.sameLine();
        if (ImGui.button("Refresh")) {
            generateAll();
        }
        ImGui.sameLine();
        int[] upp = { unitsPerPixel };
        ImGui.setNextItemWidth(120);
        if (ImGui.sliderInt("Zoom (u/px)", upp, 1, 64))
            unitsPerPixel = upp[0];

        ImGui.separator();
        ImGui.text(String.format("Centre: (%d, %d)   Coverage: %d x %d blocks",
                centreX, centreZ, SIZE * unitsPerPixel, SIZE * unitsPerPixel));
        ImGui.spacing();

        // ── Height map (large, colored) ───────────────────────────────────────
        // This shows the COMBINED output of all three terrain noises.
        // Use this to judge overall terrain distribution. Tune here first.
        float bigW = 420, bigH = 420;
        renderTex("Height Map  (C × E × PV combined)", heightTex, bigW, bigH);
        ImGui.sameLine();

        // ── Legend (right of height map) ──────────────────────────────────────
        ImGui.beginGroup();
        ImGui.text("Legend:");
        ImGui.spacing();
        colorSwatch(0,   0,   90,  "Deep ocean     (y  9–14)");
        colorSwatch(30,  90,  160, "Shallow ocean  (y 14–22)");
        colorSwatch(210, 190, 140, "Beach / coast  (y 22–26)");
        colorSwatch(80,  140, 50,  "Plains         (y 26–33)");
        colorSwatch(110, 85,  55,  "Highlands      (y 33–44)");
        colorSwatch(200, 200, 200, "Mountain peaks (y 44+)  ");
        ImGui.spacing();
        ImGui.textDisabled("(heights assume default");
        ImGui.textDisabled(" heightBase=8, range=52)");
        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();
        ImGui.textWrapped("Tune continentalness sliders first. Ocean/land split should look like the left Minecraft reference image before touching erosion or PV.");
        ImGui.endGroup();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // ── Individual noise maps (smaller, grayscale) ────────────────────────
        float w = 170, h = 170;
        renderTex("Continentalness\n(large smooth blobs)", contTex, w, h);
        ImGui.sameLine();
        renderTex("Erosion\n(organic basins)", erosTex, w, h);
        ImGui.sameLine();
        renderTex("Peaks & Valleys\n(fine ridge network)", pvTex, w, h);
        ImGui.sameLine();
        renderTex("Temperature", tempTex, w, h);
        ImGui.sameLine();
        renderTex("Humidity", humTex, w, h);

        ImGui.spacing();
        ImGui.textDisabled("Grayscale: black = -1, white = +1.");
        ImGui.textDisabled("PV uses ridged noise — bright ridges, dark valleys.");

        ImGui.end();
    }

    // Renders a small colored square as a legend swatch
    private void colorSwatch(int r, int g, int b, String label) {
        // Use ImGui dummy + text to fake a colored box label
        // (no drawList needed — just a colored text prefix)
        ImGui.textColored(r / 255f, g / 255f, b / 255f, 1f, "██  ");
        ImGui.sameLine();
        ImGui.text(label);
    }

    private void renderTex(String label, int texId, float w, float h) {
        ImGui.beginGroup();
        ImGui.text(label);
        if (texId == -1) {
            ImGui.dummy(w, h);
            ImGui.textDisabled("(click Refresh)");
        } else {
            ImGui.image(texId, w, h);
        }
        ImGui.endGroup();
    }

    // =========================================================================
    // TEXTURE GENERATION
    // =========================================================================

    private void generateAll() {
        // Grayscale individual noise maps
        contTex   = regenGray(contTex,   gen::sampleContinentalness);
        erosTex   = regenGray(erosTex,   gen::sampleErosion);
        pvTex     = regenGray(pvTex,     gen::samplePeaksValleys);
        tempTex   = regenGray(tempTex,   gen::sampleTemperature);
        humTex    = regenGray(humTex,    gen::sampleHumidity);

        // Colored height map — the combined terrain output
        heightTex = regenHeightMap(heightTex);
    }

    /**
     * Generates a grayscale texture from a noise sampler.
     * noise in [-1, 1] → gray in [0, 255].
     */
    private int regenGray(int oldTex, Sampler sampler) {
        deleteTex(oldTex);

        byte[] rgb = new byte[SIZE * SIZE * 3];
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                int wx = centreX + (col - SIZE / 2) * unitsPerPixel;
                int wz = centreZ + (row - SIZE / 2) * unitsPerPixel;

                float val  = sampler.sample(wx, wz);
                int   gray = clamp255((int)((val + 1f) * 127.5f));

                int i = (row * SIZE + col) * 3;
                rgb[i] = rgb[i+1] = rgb[i+2] = (byte) gray;
            }
        }

        return uploadTexture(rgb);
    }

    /**
     * Generates a COLORED height map showing the combined C×E×PV terrain output.
     *
     * Height [0, 1] is mapped to distinct biome colors matching the legend:
     *   0.00–0.14  deep ocean   (very dark blue)
     *   0.14–0.30  shallow sea  (medium blue)
     *   0.30–0.38  beach/coast  (sandy tan)
     *   0.38–0.52  plains       (green)
     *   0.52–0.68  highlands    (brown)
     *   0.68–1.00  mountains    (gray → white)
     *
     * These thresholds match the continentalnessSpline output ranges,
     * so the colors directly correspond to the spline zones.
     */
    private int regenHeightMap(int oldTex) {
        deleteTex(oldTex);

        byte[] rgb = new byte[SIZE * SIZE * 3];
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                int wx = centreX + (col - SIZE / 2) * unitsPerPixel;
                int wz = centreZ + (row - SIZE / 2) * unitsPerPixel;

                float h = gen.sampleHeight(wx, wz);  // [0, 1]

                // Assign color based on height zone
                int r, g, b;
                if (h < 0.14f) {
                    // Deep ocean — dark blue
                    float t = h / 0.14f;
                    r = lerp255(0,  0,  t);
                    g = lerp255(0,  30, t);
                    b = lerp255(80, 100, t);
                } else if (h < 0.30f) {
                    // Shallow ocean — blue
                    float t = (h - 0.14f) / 0.16f;
                    r = lerp255(0,   30,  t);
                    g = lerp255(30,  100, t);
                    b = lerp255(100, 180, t);
                } else if (h < 0.38f) {
                    // Beach / coast — sandy
                    float t = (h - 0.30f) / 0.08f;
                    r = lerp255(200, 210, t);
                    g = lerp255(180, 190, t);
                    b = lerp255(120, 140, t);
                } else if (h < 0.52f) {
                    // Plains — green
                    float t = (h - 0.38f) / 0.14f;
                    r = lerp255(70,  90,  t);
                    g = lerp255(140, 155, t);
                    b = lerp255(40,  55,  t);
                } else if (h < 0.68f) {
                    // Highlands — brown
                    float t = (h - 0.52f) / 0.16f;
                    r = lerp255(100, 130, t);
                    g = lerp255(80,  100, t);
                    b = lerp255(45,  55,  t);
                } else {
                    // Mountains — gray to white
                    float t = Math.min(1f, (h - 0.68f) / 0.32f);
                    r = lerp255(140, 255, t);
                    g = lerp255(140, 255, t);
                    b = lerp255(145, 255, t);
                }

                int i = (row * SIZE + col) * 3;
                rgb[i]   = (byte) r;
                rgb[i+1] = (byte) g;
                rgb[i+2] = (byte) b;
            }
        }

        return uploadTexture(rgb);
    }

    private int uploadTexture(byte[] rgb) {
        ByteBuffer buf = MemoryUtil.memAlloc(rgb.length);
        buf.put(rgb).flip();

        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, SIZE, SIZE, 0, GL_RGB, GL_UNSIGNED_BYTE, buf);
        glBindTexture(GL_TEXTURE_2D, 0);

        MemoryUtil.memFree(buf);
        return id;
    }

    private void deleteTex(int id) {
        if (id != -1) glDeleteTextures(id);
    }

    private int clamp255(int v) { return Math.max(0, Math.min(255, v)); }
    private int lerp255(int a, int b, float t) { return clamp255((int)(a + t * (b - a))); }

    public void cleanup() {
        deleteTex(contTex);
        deleteTex(erosTex);
        deleteTex(pvTex);
        deleteTex(tempTex);
        deleteTex(humTex);
        deleteTex(heightTex);
    }
}