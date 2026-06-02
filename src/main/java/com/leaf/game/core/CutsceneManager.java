package com.leaf.game.core;

import imgui.ImFont;
import imgui.ImGui;

/**
 * CutsceneManager — in-engine letterbox text cutscenes for DESCENT.
 *
 * Cinematic-on-a-budget: black letterbox bars, a dimmed view of the world behind,
 * text that types in character-by-character, and a wind bed howling underneath.
 * No video files, no extra dependencies — and the ending can show live run stats.
 *
 * ── HOW TO EDIT THE CUTSCENES ────────────────────────────────────────────────
 *  Everything you'd want to change is right here at the top:
 *    INTRO / ENDING : the slides. Each inner array is ONE slide (advanced with
 *                     SPACE); each String in it is a line on that slide.
 *    CPS            : typewriter speed (characters per second).
 *    WIND_SOUND     : which /audios file plays underneath (wind_cemetery, wind_big…).
 *  A line that is exactly "DESCENT" is rendered as the large glowing title.
 *
 * ── CONTROLS ─────────────────────────────────────────────────────────────────
 *  SPACE / ENTER : finish the current line instantly, or advance to the next slide.
 *  ESC           : skip the whole cutscene.
 */
public class CutsceneManager {

    // ═══════════════════════════════════════════════════════════════════════
    //  EDIT YOUR CUTSCENES HERE
    // ═══════════════════════════════════════════════════════════════════════
    public static final String[][] INTRO = {
        { "You came for the Chakra Crystal.",
          "Buried beneath this mountain for three hundred years." },
        { "They were already here when you arrived.",
          "They have always been here.",
          "Waiting." },
        { "The crystal is warm in your hands.",
          "It knows you are afraid.",
          "It will teach you to survive." },
        { "DESCENT",
          "Survive.   Learn.   Escape." },
    };

    public static final String[][] ENDING = {
        { "You survived.",
          "Every wave. Every hunter. Every thing it sent." },
        { "And now —",
          "the sky opens." },
        { "FLIGHT.",
          "The last thing the crystal ever gives." },
        { "The mountain has nothing left to hold you." },
        { "DESCENT",
          "Go anywhere." },
    };

    /** Typewriter speed (characters revealed per second). */
    private static final float CPS = 30f;
    /** Wind bed that plays under the cutscene (path under /audios, no extension). */
    private static final String WIND_SOUND = "wind/wind_cemetery";
    private static final float  WIND_VOLUME = 0.6f;

    // ═══════════════════════════════════════════════════════════════════════

    private String[][] script = null;
    private int     slide      = 0;
    private float   typed      = 0f;   // characters revealed on the current slide so far
    private int     slideChars = 0;    // total characters on the current slide
    private boolean active     = false;
    private boolean ending     = false;
    private float   windFade   = 0f;   // 0→1 fade-in for the wind bed
    private float   age        = 0f;   // total seconds the cutscene has run (for the glow pulse)
    private float   promptT    = 0f;   // blink timer for the "[SPACE]" prompt

    /** Optional stat lines shown on the ENDING (set by Window before startEnding). */
    public String endingStat = null;

    public void startIntro()  { begin(INTRO,  false); }
    public void startEnding() { begin(ENDING, true);  }

    private void begin(String[][] s, boolean isEnding) {
        script = s; slide = 0; typed = 0f; active = true; ending = isEnding;
        windFade = 0f; age = 0f; promptT = 0f;
        slideChars = charCount(0);
        AudioManager.playContinuous(WIND_SOUND, 0f); // starts silent, update() fades it in
    }

    public boolean isActive()  { return active; }
    public boolean wasEnding()  { return ending; }

    // ── Per-frame update (raw dt; runs while the world is frozen) ─────────────
    public void update(float dt) {
        if (!active) return;
        age     += dt;
        promptT += dt;
        windFade = Math.min(1f, windFade + dt * 0.6f);
        AudioManager.setContinuousVolume(WIND_SOUND, WIND_VOLUME * windFade);
        if (typed < slideChars) typed = Math.min(slideChars, typed + CPS * dt);
    }

    /** SPACE/ENTER: complete the current line, or move to the next slide. */
    public void advance() {
        if (!active) return;
        if (typed < slideChars) { typed = slideChars; return; }  // reveal the rest first
        slide++;
        if (slide >= script.length) { end(); return; }
        typed = 0f;
        slideChars = charCount(slide);
        promptT = 0f;
        AudioManager.play("cystal_click", 0.4f);                 // soft page-turn
    }

    /** ESC: skip the whole thing. */
    public void skip() { end(); }

    private void end() {
        active = false;
        AudioManager.stopContinuous(WIND_SOUND);
    }

    private int charCount(int s) {
        int n = 0;
        for (String line : script[s]) n += line.length();
        return n;
    }

    // ── Render (call inside the ImGui frame) ─────────────────────────────────
    public void render(float w, float h) {
        if (!active) return;
        imgui.ImDrawList draw = ImGui.getForegroundDrawList();

        // Dim the world, then lay cinematic letterbox bars over the top/bottom.
        draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.80f));
        float bar = h * 0.155f;
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 1f);
        draw.addRectFilled(0, 0, w, bar, black);
        draw.addRectFilled(0, h - bar, w, h, black);

        ImFont font = ImGui.getFont();
        float base = ImGui.getFontSize();

        String[] lines = script[slide];
        boolean titleSlide = lines.length > 0 && lines[0].equals("DESCENT");

        // Pre-measure block height so the text sits centred between the bars.
        float bodySize  = base * 1.55f;
        float titleSize = base * 3.6f;
        float gap = 18f;
        float blockH = 0f;
        for (String line : lines) {
            boolean t = line.equals("DESCENT");
            blockH += (t ? titleSize : bodySize) + gap;
        }
        float y = h * 0.5f - blockH * 0.5f;

        int budget = (int) typed;   // characters allowed to show this frame
        for (String line : lines) {
            boolean t = line.equals("DESCENT");
            float size = t ? titleSize : bodySize;
            int take = Math.max(0, Math.min(line.length(), budget));
            budget -= line.length();
            String shown = line.substring(0, take);

            if (!shown.isEmpty()) {
                float ratio = size / base;
                float tw = ImGui.calcTextSize(shown).x * ratio;
                float x = (w - tw) * 0.5f;
                if (t) {
                    // Glowing title: a soft pulsing halo drawn behind the crisp text.
                    float glow = 0.45f + 0.25f * (float) Math.sin(age * 2.2f);
                    int halo = ImGui.colorConvertFloat4ToU32(0.85f, 0.55f, 1.0f, glow);
                    for (int o = 1; o <= 3; o++) {
                        draw.addText(font, size, x - o, y,     halo, shown);
                        draw.addText(font, size, x + o, y,     halo, shown);
                        draw.addText(font, size, x,     y - o, halo, shown);
                        draw.addText(font, size, x,     y + o, halo, shown);
                    }
                    draw.addText(font, size, x, y, ImGui.colorConvertFloat4ToU32(1f, 0.97f, 0.9f, 1f), shown);
                } else {
                    // Body: drop shadow + soft white for legibility over the world.
                    draw.addText(font, size, x + 2, y + 2, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.9f), shown);
                    draw.addText(font, size, x, y, ImGui.colorConvertFloat4ToU32(0.93f, 0.95f, 1f, 1f), shown);
                }
            }
            y += size + gap;
        }

        // Optional stat line on the ending (e.g. "You remember 84 faces.").
        if (ending && endingStat != null && typed >= slideChars) {
            float tw = ImGui.calcTextSize(endingStat).x;
            draw.addText(font, base, (w - tw) * 0.5f, h - bar - 40f,
                    ImGui.colorConvertFloat4ToU32(0.8f, 0.6f, 0.55f, 0.95f), endingStat);
        }

        // "[ SPACE ]" prompt, blinking, once the current slide has finished typing.
        if (typed >= slideChars) {
            float a = 0.35f + 0.35f * (float) Math.sin(promptT * 3.2f);
            boolean last = slide >= script.length - 1;
            String prompt = last ? "[ SPACE ]  begin" : "[ SPACE ]";
            float tw = ImGui.calcTextSize(prompt).x;
            draw.addText(font, base, (w - tw) * 0.5f, h - bar + 18f,
                    ImGui.colorConvertFloat4ToU32(0.7f, 0.75f, 0.85f, a), prompt);
        }

        // Unobtrusive skip hint in the corner.
        draw.addText(font, base, 16f, h - bar + 18f,
                ImGui.colorConvertFloat4ToU32(0.5f, 0.52f, 0.6f, 0.6f), "[ESC] skip");
    }
}
