package com.leaf.game.util;

import com.leaf.game.entity.Player;
import com.leaf.game.world.WorldGen;
import imgui.ImGui;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Renders a live preview of all noise fields into an ImGui window.
 *
 * Usage in Window.java:
 *   Field:   private NoiseVisualizer noiseVis;
 *   Init:    this.noiseVis = new NoiseVisualizer(worldGen);         (after worldGen is created)
 *   Frame:   if (showNoiseViewer) noiseVis.renderWindow(player);   (inside ImGui frame)
 *   Cleanup: noiseVis.cleanup();                                    (after loop ends)
 *   Toggle:  F4 key → showNoiseViewer = !showNoiseViewer;
 */
public class NoiseVisualizer {

    // Resolution of each preview texture in pixels.
    // 256×256 is fast to generate (≈65k noise samples per field) and clear enough to read.
    private static final int SIZE = 256;

    // World-units per pixel. 8 means the preview covers SIZE*8 = 2048 world blocks.
    private int unitsPerPixel = 8;

    @FunctionalInterface
    private interface Sampler { float sample(int wx, int wz); }

    private final WorldGen gen;

    // OpenGL texture IDs. -1 = not yet generated.
    private int contTex = -1;
    private int erosTex = -1;
    private int pvTex   = -1;
    private int tempTex = -1;
    private int humTex  = -1;

    // World-coordinate centre of the preview (updated to player position on request)
    private int centreX = 0;
    private int centreZ = 0;

    public NoiseVisualizer(WorldGen gen) {
        this.gen = gen;
    }

    /**
     * Call once per frame, inside the ImGui newFrame / render block.
     * Draws a floating window with grayscale previews of all five noise fields.
     */
    public void renderWindow(Player player) {
        ImGui.begin("Noise Viewer  [F4]");

        // ── CONTROLS ──────────────────────────────────────────────────────────
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
        if (ImGui.sliderInt("Zoom (u/px)", upp, 1, 32))
            unitsPerPixel = upp[0];

        ImGui.separator();
        ImGui.text(String.format("Centre: (%d, %d)   Coverage: %d x %d blocks",
                centreX, centreZ, SIZE * unitsPerPixel, SIZE * unitsPerPixel));
        ImGui.spacing();

        // ── TOP ROW: continentalness | erosion | peaks & valleys ──────────────
        float w = 200, h = 200;
        renderTex("Continentalness", contTex, w, h);
        ImGui.sameLine();
        renderTex("Erosion",         erosTex, w, h);
        ImGui.sameLine();
        renderTex("Peaks & Valleys", pvTex,   w, h);

        ImGui.spacing();

        // ── BOTTOM ROW: temperature | humidity ───────────────────────────────
        renderTex("Temperature", tempTex, w, h);
        ImGui.sameLine();
        renderTex("Humidity",    humTex,  w, h);

        ImGui.end();
    }

    private void renderTex(String label, int texId, float w, float h) {
        ImGui.beginGroup();
        ImGui.text(label);
        if (texId == -1) {
            ImGui.dummy(w, h);
            ImGui.text("(click Refresh)");
        } else {
            ImGui.image(texId, w, h);
        }
        ImGui.endGroup();
    }

    // =========================================================================
    // TEXTURE GENERATION
    // =========================================================================

    private void generateAll() {
        contTex = regen(contTex, gen::sampleContinentalness);
        erosTex = regen(erosTex, gen::sampleErosion);
        pvTex   = regen(pvTex,   gen::samplePeaksValleys);
        tempTex = regen(tempTex, gen::sampleTemperature);
        humTex  = regen(humTex,  gen::sampleHumidity);
    }

    /**
     * Deletes the old texture if it exists, generates a new one from the sampler,
     * and returns the new texture ID.
     */
    private int regen(int oldTex, Sampler sampler) {
        deleteTex(oldTex);

        byte[] rgb = new byte[SIZE * SIZE * 3];

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                // Map pixel (col, row) to world coordinates
                int wx = centreX + (col - SIZE / 2) * unitsPerPixel;
                int wz = centreZ + (row - SIZE / 2) * unitsPerPixel;

                // noise is in [-1, 1]; remap to [0, 255] for display
                float val  = sampler.sample(wx, wz);
                int   gray = Math.max(0, Math.min(255, (int)((val + 1f) * 127.5f)));

                int idx = (row * SIZE + col) * 3;
                rgb[idx]     = (byte) gray;
                rgb[idx + 1] = (byte) gray;
                rgb[idx + 2] = (byte) gray;
            }
        }

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

    /** Call this when the game shuts down to free GPU memory. */
    public void cleanup() {
        deleteTex(contTex);
        deleteTex(erosTex);
        deleteTex(pvTex);
        deleteTex(tempTex);
        deleteTex(humTex);
    }
}