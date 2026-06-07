package com.leaf.game.core;

import com.leaf.game.entity.*;
import com.leaf.game.net.NetworkSession;
import com.leaf.game.render.Shader;
import com.leaf.game.util.Camera;
import com.leaf.game.util.RaycastResult;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import imgui.ImFont;
import imgui.ImGui;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * WindowHud  -  all ImGui HUD / menu rendering extracted from Window.java.
 *
 * Every method reads game state via the {@code win} back-reference.
 * Fields accessed as {@code win.fieldName} are package-private in Window.java
 * (no access modifier  -  same-package visibility is sufficient).
 */
class WindowHud {

    final Window win;

    WindowHud(Window win) {
        this.win = win;
    }

    /**
     * Flip to true to expose the developer options (load game, multiplayer,
     * pre-gen radius). The shipping start screen shows only "Play Game".
     * The code below is kept intact so nothing is lost  -  just hidden.
     */
    private static final boolean DEV_MENU = false;

    void renderConnectionMenu(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 170.0f, h / 2.0f - 150.0f);
        ImGui.setNextWindowSize(340.0f, DEV_MENU ? 430.0f : 320.0f);
        ImGui.begin("Start Screen",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.spacing();
        ImGui.textColored(1.0f, 0.85f, 0.4f, 1.0f, "        D E S C E N T");
        ImGui.spacing();
        ImGui.textDisabled("        Survive. Learn. Escape.");
        ImGui.spacing(); ImGui.separator(); ImGui.spacing(); ImGui.spacing();

        // ── The one shipping button ───────────────────────────────────────────
        if (ImGui.button("PLAY", 320, 44)) {
            win.network = null;
            win.playIntroOnSpawn = true;
            win.gameEnded = false;
            win.player.progression.reset();   // fresh run: start with only the starting kit
            RunRecords.INSTANCE.newRun((float) org.lwjgl.glfw.GLFW.glfwGetTime());
            win.startPreload();
        }
        ImGui.spacing();

        // ── MULTIPLAYER (host / join over LAN) — both players get the full kit ─
        ImGui.separator(); ImGui.spacing();
        ImGui.textDisabled("        Multiplayer  (all abilities)");
        ImGui.spacing();
        if (ImGui.button("Host Game", 320, 28)) startMultiplayer(true);
        ImGui.spacing();
        ImGui.inputText("Host IP", win.ipInput);
        if (ImGui.button("Join Game", 320, 28)) startMultiplayer(false);
        ImGui.spacing();

        // ── Developer options (hidden unless DEV_MENU)  -  kept in code ─────────
        if (DEV_MENU) {
            ImGui.separator(); ImGui.spacing();
            ImGui.text("Pre-generate Radius:");
            int[] rad = {win.preloadRadius};
            if (ImGui.sliderInt("##rad", rad, 0, 100)) win.preloadRadius = rad[0];
            ImGui.spacing();

            if (ImGui.button("Single Player (no intro)", 320, 28)) {
                win.network = null;
                win.startPreload();
            }
            ImGui.spacing();
            if (SaveManager.saveExists() && ImGui.button("Load Saved Game", 320, 28)) {
                java.util.Arrays.fill(win.hotbar, Block.AIR);
                SaveManager.loadGame(win.world, win.player, win.inventory);
                for (Block b : Block.values()) {
                    if (win.inventory.getCount(b) > 0) win.addBlockToHotbar(b);
                }
                win.worldGen.resetSeed(GameConfig.seed);
                win.world.clearAllChunks();
                win.network = null;
                win.startPreload();
            }
            ImGui.spacing();
            if (ImGui.button("Host Multiplayer Game", 320, 28)) {
                win.network = new NetworkSession(true, null);
                win.network.start();
                win.remotePlayer = new RemotePlayer();
                win.startPreload();
            }
            ImGui.spacing();
            ImGui.inputText("Host IP", win.ipInput);
            if (ImGui.button("Join Multiplayer Game", 320, 28)) {
                win.network = new NetworkSession(false, win.ipInput.get().trim());
                win.network.start();
                win.remotePlayer = new RemotePlayer();
                win.startPreload();
            }
        }
        ImGui.end();
    }

    /** Shared host/join setup: connect, unlock everything, and start the world. */
    private void startMultiplayer(boolean host) {
        win.network = host ? new NetworkSession(true, null)
                           : new NetworkSession(false, win.ipInput.get().trim());
        win.network.start();
        win.remotePlayer     = new RemotePlayer();
        win.playIntroOnSpawn = false;            // skip the intro cutscene in MP
        win.gameEnded        = false;
        win.player.progression.unlockAll();      // PvP: both players fully armed
        RunRecords.INSTANCE.newRun((float) org.lwjgl.glfw.GLFW.glfwGetTime());
        win.startPreload();
    }

    void renderPreloadProgress(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 160.0f, h / 2.0f - 65.0f);
        ImGui.setNextWindowSize(320.0f, 130.0f);
        ImGui.begin("Pre-generating Terrain",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);
        ImGui.text("Generating win.world in background...");
        ImGui.text("Please wait a moment while the spawn");
        ImGui.text("area finishes compiling...");
        ImGui.spacing();
        float progress = (float) (glfwGetTime() % 2.0) / 2.0f;
        ImGui.progressBar(progress, 300, 24);
        ImGui.end();
    }

    // Slider value holders (imgui-java sliderFloat needs a float[1]); synced from AudioManager.
    private final float[] volMaster = {1f}, volSfx = {1f}, volMob = {1f}, volMusic = {1f};

    void renderPauseMenu(float w, float h) {
        ImGui.setNextWindowPos(w / 2.0f - 130.0f, h / 2.0f - 175.0f);
        ImGui.setNextWindowSize(260.0f, 350.0f);
        ImGui.begin("Paused",
                imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove);
        ImGui.text("Game Paused");
        ImGui.separator();
        ImGui.spacing();
        if (ImGui.button("Resume", 240, 28)) {
            win.isPaused = false;
            glfwSetInputMode(win.window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        }
        ImGui.spacing();
        if (ImGui.button("Controls  [F1]", 240, 28)) {
            // Open help without closing pause  -  win.player can read then ESC back
            win.showHelp = true;
        }
        ImGui.spacing();
        if (ImGui.button("Save Game", 240, 28)) SaveManager.saveGame(win.world, win.player, win.inventory);
        ImGui.spacing();
        if (ImGui.button("Save & Quit", 240, 28)) {
            SaveManager.saveGame(win.world, win.player, win.inventory);
            glfwSetWindowShouldClose(win.window, true);
        }

        // ── VOLUME MIXER ──────────────────────────────────────────────────────
        ImGui.spacing();
        ImGui.separator();
        ImGui.text("Volume");
        ImGui.pushItemWidth(150f);
        volMaster[0] = AudioManager.getMasterVolume();
        if (ImGui.sliderFloat("Master", volMaster, 0f, 1f)) AudioManager.setMasterVolume(volMaster[0]);
        volSfx[0] = AudioManager.getSfxVolume();
        if (ImGui.sliderFloat("Effects", volSfx, 0f, 1f)) AudioManager.setSfxVolume(volSfx[0]);
        volMob[0] = AudioManager.getMobVolume();
        if (ImGui.sliderFloat("Mobs", volMob, 0f, 1f)) AudioManager.setMobVolume(volMob[0]);
        volMusic[0] = AudioManager.getMusicVolume();
        if (ImGui.sliderFloat("Music", volMusic, 0f, 1f)) AudioManager.setMusicVolume(volMusic[0]);
        ImGui.popItemWidth();

        ImGui.end();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ABILITY UNLOCK CARD  (shown between waves)
    // ─────────────────────────────────────────────────────────────────────────
    void renderUnlockCard(float w, float h) {
        int n = win.unlockCardAbilities.size();
        float cardW = 500f;
        float cardH = 210f + n * 78f;
        ImGui.setNextWindowPos(w / 2f - cardW / 2f, h / 2f - cardH / 2f);
        ImGui.setNextWindowSize(cardW, cardH);
        ImGui.setNextWindowBgAlpha(0.93f);
        ImGui.begin("AbilityUnlock",
                imgui.flag.ImGuiWindowFlags.NoDecoration
              | imgui.flag.ImGuiWindowFlags.NoMove
              | imgui.flag.ImGuiWindowFlags.NoResize);

        ImGui.spacing(); ImGui.spacing();
        cardCenter("ABILITY UNLOCKED", 1.0f, 0.85f, 0.35f);
        ImGui.spacing();
        cardCenter(win.player.progression.flavorFor(win.unlockCardWave), 0.85f, 0.9f, 1.0f);
        ImGui.spacing(); ImGui.separator(); ImGui.spacing();

        for (Progression.Ability a : win.unlockCardAbilities) {
            cardCenter(a.label + "    " + a.key, 0.45f, 0.95f, 1.0f);
            ImGui.spacing();
            ImGui.pushTextWrapPos(ImGui.getWindowWidth() - 24f);
            ImGui.setCursorPosX(22f);
            ImGui.text(a.desc);
            ImGui.popTextWrapPos();
            ImGui.spacing();
        }

        ImGui.spacing();
        ImGui.pushTextWrapPos(ImGui.getWindowWidth() - 24f);
        ImGui.setCursorPosX(22f);
        ImGui.textColored(0.55f, 0.75f, 1.0f, 0.95f, Progression.MANA_NOTE);
        ImGui.popTextWrapPos();
        ImGui.spacing(); ImGui.separator(); ImGui.spacing();

        cardCenter("[ ENTER ]   begin wave " + (win.unlockCardWave + 1), 1.0f, 0.9f, 0.4f);
        ImGui.end();
    }

    /** Centre a single line of text horizontally in the current ImGui window. */
    private void cardCenter(String text, float r, float g, float b) {
        float tw = ImGui.calcTextSize(text).x;
        ImGui.setCursorPosX(Math.max(8f, (ImGui.getWindowWidth() - tw) * 0.5f));
        ImGui.textColored(r, g, b, 1.0f, text);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PRACTICE OVERLAY  — multi-step hands-on ability tutorial
    // ─────────────────────────────────────────────────────────────────────────
    void renderPractice(float w, float h) {
        if (win.practiceAbility == null || win.practiceSteps == null) return;
        AbilityPractice.Step step = win.practiceSteps.get(win.practiceStepIndex);
        int totalSteps = win.practiceSteps.size();
        boolean celebrating = win.practiceStepDone;

        imgui.ImDrawList draw = ImGui.getForegroundDrawList();
        ImFont font = ImGui.getFont();
        float base = ImGui.getFontSize();

        // Dim background — world stays visible so the player can act
        draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.50f));

        // Card sits at the bottom quarter of the screen
        float cardW = Math.min(640f, w - 40f);
        float cx = w * 0.5f;
        float lineH = base * 1.45f;
        // Count instruction lines (split on \n)
        String[] instrLines = step.instruction.split("\n");
        float cardH = 18f + lineH              // title row
                    + instrLines.length * lineH + 8f // instructions
                    + lineH + 12f;             // goal/skip row
        float cardY = h - cardH - 28f;

        draw.addRectFilled(cx - cardW/2, cardY, cx + cardW/2, cardY + cardH,
                ImGui.colorConvertFloat4ToU32(0.03f, 0.04f, 0.10f, 0.93f), 8f);
        int borderCol = celebrating
                ? ImGui.colorConvertFloat4ToU32(0.35f, 1.0f, 0.5f, 0.9f)   // green when done
                : ImGui.colorConvertFloat4ToU32(0.4f,  0.55f, 0.9f, 0.6f);
        draw.addRect(cx - cardW/2, cardY, cx + cardW/2, cardY + cardH, borderCol, 8f, 0, 1.8f);

        float ty = cardY + 10f;

        // Title + step counter
        String abilName = win.practiceAbility.label;
        String progress = totalSteps > 1
                ? "  (" + (win.practiceStepIndex + 1) + " / " + totalSteps + ")"
                : "";
        String header = "PRACTICE  -  " + abilName + progress;
        if (step.keyHint != null) header += "    [ " + step.keyHint + " ]";
        float hw = ImGui.calcTextSize(header).x;
        draw.addText(cx - hw/2, ty, ImGui.colorConvertFloat4ToU32(0.45f, 0.95f, 1.0f, 1f), header);
        ty += lineH + 2f;

        // Instruction lines
        for (String line : instrLines) {
            float lw = ImGui.calcTextSize(line).x;
            draw.addText(font, base, cx - lw/2, ty,
                    ImGui.colorConvertFloat4ToU32(0.88f, 0.90f, 0.95f, 0.95f), line);
            ty += lineH;
        }
        ty += 8f;

        // Progress counter (e.g. "2 / 3") if required > 1
        if (step.required > 1) {
            int done = Math.min(win.practiceCtx.counter, step.required);
            String prog = done + " / " + step.required;
            float progW = ImGui.calcTextSize(prog).x;
            int progCol = done >= step.required
                    ? ImGui.colorConvertFloat4ToU32(0.3f, 1.0f, 0.45f, 1f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.3f,  1f);
            draw.addText(cx - progW/2, ty, progCol, prog);
            ty += lineH;
        }

        // Warning text (e.g. "HOLD it! Don't click!") — shown in red above skip row
        if (win.practiceWarnText != null && win.practiceWarnTimer > 0f) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(glfwGetTime() * 10f);
            float ww = ImGui.calcTextSize(win.practiceWarnText).x;
            draw.addText(font, base * 1.05f, cx - ww/2, ty,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.25f, pulse), win.practiceWarnText);
            ty += lineH;
        }

        // Celebration OR skip/prompt row
        if (celebrating && step.doneText != null) {
            float pulse = 0.7f + 0.3f * (float) Math.sin(glfwGetTime() * 8f);
            float dw = ImGui.calcTextSize(step.doneText).x;
            draw.addText(cx - dw/2, ty,
                    ImGui.colorConvertFloat4ToU32(0.3f, 1.0f, 0.45f, pulse), step.doneText);
        } else if (!celebrating) {
            String skipTxt = step.allowSkip && win.practiceStepAge > 2f
                    ? "[ ENTER ]  skip" : "";
            if (!skipTxt.isEmpty()) {
                float sw = ImGui.calcTextSize(skipTxt).x;
                draw.addText(cx - sw/2, ty,
                        ImGui.colorConvertFloat4ToU32(0.5f, 0.52f, 0.6f, 0.6f), skipTxt);
            }
        }

        // Dummy HP bar above the card
        com.leaf.game.entity.Enemy dummy = win.practiceCtx.dummy();
        if (dummy != null && dummy.alive) {
            float barW = 180f, barH = 12f;
            float bx = cx - barW/2, by = cardY - barH - 22f;
            float frac = Math.max(0f, dummy.health / dummy.maxHealth);
            draw.addRectFilled(bx, by, bx + barW, by + barH,
                    ImGui.colorConvertFloat4ToU32(0.15f, 0.05f, 0.05f, 0.85f), 4f);
            draw.addRectFilled(bx, by, bx + barW * frac, by + barH,
                    ImGui.colorConvertFloat4ToU32(0.9f, 0.25f, 0.25f, 0.9f), 4f);
            draw.addRect(bx, by, bx + barW, by + barH,
                    ImGui.colorConvertFloat4ToU32(0.5f, 0.2f, 0.2f, 0.6f), 4f, 0, 1.2f);
            String dLabel = "TARGET  HP";
            draw.addText(cx - ImGui.calcTextSize(dLabel).x/2, by - 16f,
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.55f, 0.55f, 0.7f), dLabel);
        }

        // ── HUD arrows: draw a pointer to the mana bar and/or cooldown icon ──
        if (step.showManaArrow || step.showCooldownArrow) {
            renderHudAnnotations(draw, font, base, w, h, step.showManaArrow, step.showCooldownArrow);
        }
    }

    /** Draw animated arrows pointing at the mana bar and/or the cooldown icon strip. */
    private void renderHudAnnotations(imgui.ImDrawList draw, ImFont font, float base,
                                      float w, float h,
                                      boolean showMana, boolean showCooldown) {
        float pulse = 0.6f + 0.4f * (float)Math.abs(Math.sin(glfwGetTime() * 4f));
        int arrowCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.92f, 0.3f, pulse);
        int labelCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.95f, 0.5f, pulse);

        if (showMana) {
            // Mana bar sits at h-80 + 14+3 = h-63  (hpHeight=14, gap=3)
            float mpY   = h - 63f;
            float mpCx  = w * 0.5f;
            // Arrow pointing right  (from left of bar)
            float ax = mpCx - 120f, ay = mpY + 5f;
            draw.addLine(ax - 28f, ay, ax, ay, arrowCol, 2.5f);
            draw.addTriangleFilled(ax, ay - 5f, ax, ay + 5f, ax + 8f, ay, arrowCol);
            draw.addText(font, base * 0.9f, ax - 80f, ay - 8f, labelCol, "MANA");
        }

        if (showCooldown) {
            // Cooldown icon row 1 is bottom-right.  Point at that area.
            float iconY = h - 90f;   // approximate top of the icon strip
            float iconX = w - 180f;
            float ax = iconX - 10f, ay = iconY + 15f;
            draw.addLine(ax - 32f, ay, ax, ay, arrowCol, 2.5f);
            draw.addTriangleFilled(ax, ay - 5f, ax, ay + 5f, ax + 8f, ay, arrowCol);
            draw.addText(font, base * 0.9f, ax - 105f, ay - 8f, labelCol, "COOLDOWN");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DEATH SCREEN
    // ─────────────────────────────────────────────────────────────────────────
    void renderDeathScreen(float w, float h) {
        imgui.ImDrawList draw = ImGui.getForegroundDrawList();
        // Heavy dark overlay  -  the world is barely visible
        draw.addRectFilled(0, 0, w, h, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.82f));

        float cy = h * 0.5f - 90f;
        // Title
        String title = "YOU FELL";
        float tw = ImGui.calcTextSize(title).x * 2.4f;
        float tx = (w - tw) * 0.5f;
        draw.addText(ImGui.getFont(), ImGui.getFontSize() * 2.4f, tx + 2, cy + 2,
                ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 1f), title);
        draw.addText(ImGui.getFont(), ImGui.getFontSize() * 2.4f, tx, cy,
                ImGui.colorConvertFloat4ToU32(0.95f, 0.25f, 0.15f, 1f), title);
        cy += 60f;

        // Stat lines
        if (win.deathScreenLines != null) {
            int bestIdx = win.deathScreenLines.length - 1;
            for (int i = 0; i < win.deathScreenLines.length; i++) {
                String line = win.deathScreenLines[i];
                float lw = ImGui.calcTextSize(line).x;
                float lx = (w - lw) * 0.5f;
                boolean isBest = i == bestIdx;
                int col = isBest
                        ? ImGui.colorConvertFloat4ToU32(1f, 0.85f, 0.3f, 1f)
                        : ImGui.colorConvertFloat4ToU32(0.88f, 0.88f, 0.95f, 0.95f);
                draw.addText(lx + 1, cy + 1, ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f), line);
                draw.addText(lx, cy, col, line);
                cy += 28f;
            }
        }

        cy += 16f;
        // Prompt  -  blink
        String prompt = "[ ENTER ]  Try again";
        float pa = 0.5f + 0.5f * (float) Math.abs(Math.sin(glfwGetTime() * 2.8f));
        float pw = ImGui.calcTextSize(prompt).x;
        draw.addText((w - pw) * 0.5f, cy,
                ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.9f, pa), prompt);
    }

    void renderChatBox(int screenHeight) {
        ImGui.setNextWindowPos(10, screenHeight - 280);
        ImGui.setNextWindowSize(400, 200);
        int flags = imgui.flag.ImGuiWindowFlags.NoDecoration | imgui.flag.ImGuiWindowFlags.NoMove;
        if (!win.showChat) flags |= imgui.flag.ImGuiWindowFlags.NoBackground;
        ImGui.begin("Chat", flags);
        for (int i = Math.max(0, win.chatHistory.size() - 10); i < win.chatHistory.size(); i++)
            ImGui.text(win.chatHistory.get(i));
        if (win.showChat) {
            ImGui.setKeyboardFocusHere();
            if (ImGui.inputText("##chat", win.chatInput,
                    imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
                String msg = win.chatInput.get().trim();
                if (!msg.isEmpty()) {
                    win.chatHistory.add("[You]: " + msg);
                    if (win.network != null && win.network.connected) win.network.sendChat(msg);
                    win.chatInput.set("");
                }
                win.showChat = false;
                glfwSetInputMode(win.window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
        }
        ImGui.end();
    }

    void renderTargetCracks(Camera camera, float w, float h) {
        if (win.lastTarget == null || !win.lastTarget.hit || !win.breakingActive || win.breakProgress <= 0) return;
        Matrix4f viewProj = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
        int bx = win.lastTarget.hitX, by = win.lastTarget.hitY, bz = win.lastTarget.hitZ;
        float e = 0.005f;
        Vector3f[] corners = {
                new Vector3f(bx - e, by - e, bz - e), new Vector3f(bx + 1 + e, by - e, bz - e),
                new Vector3f(bx + 1 + e, by + 1 + e, bz - e), new Vector3f(bx - e, by + 1 + e, bz - e),
                new Vector3f(bx - e, by - e, bz + 1 + e), new Vector3f(bx + 1 + e, by - e, bz + 1 + e),
                new Vector3f(bx + 1 + e, by + 1 + e, bz + 1 + e), new Vector3f(bx - e, by + 1 + e, bz + 1 + e)
        };
        org.joml.Vector4f[] proj = new org.joml.Vector4f[8];
        for (int i = 0; i < 8; i++) {
            proj[i] = new org.joml.Vector4f(corners[i].x, corners[i].y, corners[i].z, 1.0f).mul(viewProj);
            if (proj[i].w > 0) {
                proj[i].x /= proj[i].w;
                proj[i].y /= proj[i].w;
            }
        }
        int finalColor = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.5f + win.breakProgress * 0.4f);
        float finalThickness = 2.0f + win.breakProgress * 3.0f;
        var draw = ImGui.getBackgroundDrawList();
        java.util.function.BiConsumer<Integer, Integer> drawCrack = (i, j) -> {
            if (proj[i].w > 0 && proj[j].w > 0) {
                float x1 = (proj[i].x + 1) * 0.5f * w, y1 = (1 - proj[i].y) * 0.5f * h;
                float x2 = (proj[j].x + 1) * 0.5f * w, y2 = (1 - proj[j].y) * 0.5f * h;
                draw.addLine(x1, y1, x2, y2, finalColor, finalThickness);
            }
        };
        if (win.breakProgress > 0.1f) {
            drawCrack.accept(0, 6);
            drawCrack.accept(3, 5);
        }
        if (win.breakProgress > 0.4f) {
            drawCrack.accept(1, 7);
            drawCrack.accept(2, 4);
        }
        if (win.breakProgress > 0.7f) {
            drawCrack.accept(0, 2);
            drawCrack.accept(5, 7);
        }
    }

    /** Simple gatling-gun glyph for the hotbar (body + barrel cluster + grip). */
    private void drawGunIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0;
        int metal = ImGui.colorConvertFloat4ToU32(0.34f, 0.34f, 0.40f, 1f);
        int dark  = ImGui.colorConvertFloat4ToU32(0.12f, 0.12f, 0.16f, 1f);
        draw.addRectFilled(x0 + w*0.04f, y0 + h*0.40f, x0 + w*0.62f, y0 + h*0.72f, metal, 2f);  // body
        draw.addRectFilled(x0 + w*0.14f, y0 + h*0.66f, x0 + w*0.30f, y1,            dark, 1.5f); // grip
        for (int k = 0; k < 3; k++) {                                                            // barrels
            float yy = y0 + h * (0.46f + 0.10f * k);
            draw.addLine(x0 + w*0.55f, yy, x1, yy, dark, 2.4f);
        }
    }

    /** Simple torch glyph for the hotbar (wooden stick + glowing flame). */
    private void drawTorchIcon(imgui.ImDrawList draw, float x0, float y0, float x1, float y1) {
        float w = x1 - x0, h = y1 - y0, cx = (x0 + x1) * 0.5f;
        int wood    = ImGui.colorConvertFloat4ToU32(0.45f, 0.30f, 0.14f, 1f);
        int flameO  = ImGui.colorConvertFloat4ToU32(1.0f, 0.50f, 0.10f, 1f);
        int flameY  = ImGui.colorConvertFloat4ToU32(1.0f, 0.90f, 0.45f, 1f);
        draw.addRectFilled(cx - w*0.08f, y0 + h*0.42f, cx + w*0.08f, y1, wood, 1.5f);  // stick
        draw.addCircleFilled(cx, y0 + h*0.36f, w*0.24f, flameO);                       // flame
        draw.addCircleFilled(cx, y0 + h*0.32f, w*0.13f, flameY);                       // hot core
    }

    void renderHUD(Camera camera, float screenW, float screenH) {
        var draw = ImGui.getForegroundDrawList();
        float cx = screenW / 2.0f, cy = screenH / 2.0f;

        // ── MULTIPLAYER STATUS — confirm sync at a glance ─────────────────────
        // Shows the live link + the peer's synced HP (drops when you damage them) +
        // troop counts, so it's obvious whether syncing actually works.
        if (win.network != null) {
            boolean up = win.network.connected;
            int foeTroops = win.network.remoteSummons.length / 3;
            String line = up
                ? String.format("MP LINK OK   Foe HP %.0f/%.0f   You troops %d  Foe troops %d  ([ ] ] spawn)",
                        win.remotePlayer != null ? win.remotePlayer.health : 0f,
                        win.remotePlayer != null ? win.remotePlayer.maxHealth : 0f,
                        win.mySummons.size(), foeTroops)
                : "MP LINK DOWN — check Host IP / firewall, or a desync dropped it (see console)";
            int col = up ? ImGui.colorConvertFloat4ToU32(0.4f, 1f, 0.5f, 0.95f)
                         : ImGui.colorConvertFloat4ToU32(1f, 0.4f, 0.3f, 0.95f);
            draw.addText(14f, 40f, col, line);

            // ── KILL BANNER — big and clear when you eliminate the opponent ──
            if (win.killBannerTimer > 0f) {
                float a = Math.min(1f, win.killBannerTimer / 1.5f);
                String txt = "ENEMY ELIMINATED";
                ImFont font = ImGui.getFont();
                float scale = 2.6f;
                float sz = font.getFontSize() * scale;
                float tw = ImGui.calcTextSize(txt).x * scale;
                int gold = ImGui.colorConvertFloat4ToU32(1f, 0.85f, 0.25f, a);
                int shad = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, a * 0.7f);
                draw.addText(font, sz, cx - tw / 2f + 2f, screenH * 0.26f + 2f, shad, txt);
                draw.addText(font, sz, cx - tw / 2f,      screenH * 0.26f,      gold, txt);
            }
        }

        // ── STARGAZING ─────────────────────────────────────────────────────────
        if (win.showConstellations) {
            int starC = ImGui.colorConvertFloat4ToU32(0.7f, 0.85f, 1.0f, 0.85f);
            draw.addText(14f, screenH - 120f, starC,
                    "Constellations ON  [F2] toggle   [=] snow toggle");
        }
        if (win.constellName != null) {
            String txt = win.constellName;
            int gold  = ImGui.colorConvertFloat4ToU32(1.0f, 0.92f, 0.60f, 0.95f);
            int shad  = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.7f);
            float tw  = ImGui.calcTextSize(txt).x;
            draw.addText(cx - tw/2 + 1, screenH * 0.18f + 1, shad, txt);
            draw.addText(cx - tw/2,     screenH * 0.18f,      gold, txt);
        }

        // ── LIGHTNING BOLT RENDERING ──────────────────────────────────────────
        // Multiple parallel zigzag paths per bolt for a thick, impressive look.
        if (!win.player.lightning.activeBolts.isEmpty()) {
            Matrix4f vp = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
            for (com.leaf.game.entity.LightningController.LightningBolt bolt : win.player.lightning.activeBolts) {
                float bright = bolt.brightness();
                if (bright < 0.01f) continue;

                // Project the target win.world position to screen
                org.joml.Vector4f cp = new org.joml.Vector4f(
                        bolt.worldTarget.x, bolt.worldTarget.y, bolt.worldTarget.z, 1.0f).mul(vp);
                if (cp.w <= 0f) continue; // behind camera
                float ndcX = cp.x / cp.w;
                float ndcY = cp.y / cp.w;
                if (Math.abs(ndcX) > 1.5f || Math.abs(ndcY) > 1.5f) continue; // far off screen

                float tScrX = (ndcX + 1f) * 0.5f * screenW;
                float tScrY = (1f - ndcY) * 0.5f * screenH;

                // Chain bolts use blue-white; primary bolts use white-gold
                boolean chain = bolt.isChain;

                // ── Wide ambient glow ────────────────────────────────────────
                int glowA = ImGui.colorConvertFloat4ToU32(
                        chain ? 0.3f : 0.8f, chain ? 0.6f : 0.85f, 1.0f, 0.06f * bright);
                draw.addCircleFilled(tScrX, tScrY, 120f * bright, glowA);
                draw.addCircleFilled(tScrX, tScrY, 60f * bright, ImGui.colorConvertFloat4ToU32(
                        chain ? 0.4f : 0.9f, chain ? 0.7f : 0.90f, 1.0f, 0.12f * bright));

                // ── Draw 5 parallel zigzag paths ─────────────────────────────
                // Each path has its own seeded offsets, producing a dense bolt cluster.
                int segments = 14;
                for (int path = 0; path < 5; path++) {
                    java.util.Random boltRng = new java.util.Random(bolt.hashCode() ^ (path * 0x9e3779b9));
                    // Each path originates slightly offset from the impact point
                    float originX = tScrX + (boltRng.nextFloat() - 0.5f) * 80f;
                    float originY = -30f; // above screen top

                    float[] bx = new float[segments + 1];
                    float[] by = new float[segments + 1];
                    bx[0] = originX;
                    by[0] = originY;
                    bx[segments] = tScrX + (boltRng.nextFloat() - 0.5f) * 8f;
                    by[segments] = tScrY;

                    float jitterScale = (path == 0) ? 100f : 60f + path * 10f;
                    for (int s = 1; s < segments; s++) {
                        float t = (float) s / segments;
                        float midX = bx[0] + (tScrX - bx[0]) * t;
                        float midY = by[0] + (tScrY - by[0]) * t;
                        bx[s] = midX + (boltRng.nextFloat() - 0.5f) * jitterScale * (1f - t * 0.6f);
                        by[s] = midY + (boltRng.nextFloat() - 0.5f) * 6f;
                    }

                    // Layer stack: wide outer glow -> medium fill -> thin bright core
                    float[][] layers = path == 0
                            // Primary path: massive, juicy
                            ? new float[][]{{38f, 0.04f}, {22f, 0.12f}, {12f, 0.30f}, {5f, 0.70f}, {2f, 1.00f}}
                            // Secondary paths: thinner, supporting glow
                            : new float[][]{{20f, 0.03f}, {10f, 0.09f}, {4f, 0.22f}, {1.5f, 0.60f}};

                    for (float[] layer : layers) {
                        float th = layer[0];
                        float a = Math.min(1f, layer[1] * bright);
                        int col = chain
                                ? ImGui.colorConvertFloat4ToU32(0.3f, 0.6f, 1.0f, a)
                                : ImGui.colorConvertFloat4ToU32(0.92f, 0.96f, 1.0f, a);
                        for (int s = 0; s < segments; s++) {
                            draw.addLine(bx[s], by[s], bx[s + 1], by[s + 1], col, th);
                        }
                    }
                }

                // ── Impact burst at target ────────────────────────────────────
                if (bright > 0.3f) {
                    float r1 = 55f * bright;
                    float r2 = 28f * bright;
                    draw.addCircleFilled(tScrX, tScrY, r1,
                            ImGui.colorConvertFloat4ToU32(chain ? 0.4f : 1.0f, chain ? 0.7f : 0.95f, 1.0f, 0.20f * bright));
                    draw.addCircleFilled(tScrX, tScrY, r2,
                            ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 0.9f, bright * 0.90f));
                    // Radiating sparks
                    java.util.Random sparkRng = new java.util.Random(bolt.hashCode() ^ 0xdeadbeef);
                    int numSparks = chain ? 6 : 12;
                    for (int sk = 0; sk < numSparks; sk++) {
                        float ang = sparkRng.nextFloat() * (float) (2 * Math.PI);
                        float len = (20f + sparkRng.nextFloat() * 40f) * bright;
                        float sa = 0.6f * bright;
                        int sparkCol = chain
                                ? ImGui.colorConvertFloat4ToU32(0.4f, 0.7f, 1.0f, sa)
                                : ImGui.colorConvertFloat4ToU32(1.0f, 0.95f, 0.6f, sa);
                        draw.addLine(tScrX, tScrY,
                                tScrX + (float) Math.cos(ang) * len,
                                tScrY + (float) Math.sin(ang) * len,
                                sparkCol, 2.5f * bright);
                    }
                }
            }
        }

        // ── SCREEN FLASH on fresh strike ──────────────────────────────────────
        // When a new bolt is at peak brightness, flash the whole screen white briefly
        float peakBright = 0f;
        for (com.leaf.game.entity.LightningController.LightningBolt b : win.player.lightning.activeBolts) {
            if (b.brightness() > 0.85f) peakBright = Math.max(peakBright, b.brightness());
        }
        if (peakBright > 0.85f) {
            float flashA = (peakBright - 0.85f) / 0.15f * 0.45f;
            draw.addRectFilled(0, 0, screenW, screenH,
                    ImGui.colorConvertFloat4ToU32(0.85f, 0.90f, 1.0f, flashA));
        }

        // ── GRAB IMPACT / THROW FLASH ────────────────────────────────────────
        // throwFlash > 0 on the frame an enemy is thrown/slammed, and on impact.
        // Gives the hit a bright orange punch flash across the screen.
        float grabFlash = win.player.grab.throwFlash;
        if (grabFlash > 0.01f) {
            // Edge burst: bright orange, very brief
            float edgeA = grabFlash * 0.65f;
            float innerA = grabFlash * 0.20f;
            // Full-screen orange tint (very subtle  -  just a wash of colour)
            draw.addRectFilled(0, 0, screenW, screenH,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.45f, 0.05f, innerA));
            // Strong orange flash on all four edges (like a punch frame)
            float edgeW = 28f * grabFlash;
            int edgeCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.35f, 0.0f, edgeA);
            draw.addRectFilled(0, 0, screenW, edgeW, edgeCol);
            draw.addRectFilled(0, screenH - edgeW, screenW, screenH, edgeCol);
            draw.addRectFilled(0, edgeW, edgeW, screenH - edgeW, edgeCol);
            draw.addRectFilled(screenW - edgeW, edgeW, screenW, screenH - edgeW, edgeCol);
        }

        // ── KAMUI PHASE BORDER EFFECT ─────────────────────────────────────────
        // Shimmering purple frame around the screen edges while Kamui is active.
        if (win.player.abilities.isKamui) {
            float urgency = 1f - win.player.abilities.kamuiTimer / GameConfig.kamuiMaxDuration;
            float pulse = 0.5f + 0.5f * (float) Math.sin(glfwGetTime() * 6.0 + urgency * 4.0);
            float edgeAlpha = 0.4f + urgency * 0.25f + pulse * 0.15f;
            int edgeColor = ImGui.colorConvertFloat4ToU32(0.6f, 0.0f, 1.0f, edgeAlpha);
            float edgeW = 18f + pulse * 8f;
            draw.addRectFilled(0, 0, screenW, edgeW, edgeColor);
            draw.addRectFilled(0, screenH - edgeW, screenW, screenH, edgeColor);
            draw.addRectFilled(0, edgeW, edgeW, screenH - edgeW, edgeColor);
            draw.addRectFilled(screenW - edgeW, edgeW, screenW, screenH - edgeW, edgeColor);

            // ── KAMUI ABSORPTION VORTEX ──────────────────────────────────────
            // When the win.player holds LMB while in Kamui, a spiraling distortion
            // vortex builds up around the targeted position, mimicking Obito's
            // Kamui eye technique from Naruto.
            if (win.player.abilities.isAbsorbing || win.player.abilities.absorptionCharge > 0f) {
                float charge = win.player.abilities.absorptionCharge;
                float vx = win.player.abilities.absorptionScrX;
                float vy = win.player.abilities.absorptionScrY;
                double t = glfwGetTime();

                // Outer pulsing glow around the vortex origin
                float glowR = 30f + charge * 90f;
                draw.addCircleFilled(vx, vy, glowR,
                        ImGui.colorConvertFloat4ToU32(0.55f, 0.0f, 1.0f, 0.20f * charge));

                // Spinning concentric rings that tighten as charge builds
                int numRings = 4;
                for (int ri = 0; ri < numRings; ri++) {
                    float ringPhase = (float) (t * (2.5f + ri * 0.8f)) + ri * (float) (Math.PI / numRings);
                    float ringRadius = glowR * (0.3f + ri * 0.22f) * (1f - charge * 0.4f);
                    float ringAlpha = 0.5f * charge;
                    int ringCol = ImGui.colorConvertFloat4ToU32(0.7f, 0.1f, 1.0f, ringAlpha);
                    draw.addCircle(vx, vy, ringRadius, ringCol, 48, 2.5f);
                }

                // Spiral arms  -  lines drawn from vortex outward along rotating angles
                int numArms = 8;
                for (int ai = 0; ai < numArms; ai++) {
                    float baseAngle = (float) (t * 3.5 + ai * Math.PI * 2.0 / numArms);
                    float armLen = (50f + charge * 130f);
                    // Each arm has segments that taper inward (spiral toward centre)
                    int armSegs = 10;
                    for (int seg = 0; seg < armSegs; seg++) {
                        float tSeg = (float) seg / armSegs;
                        float tSeg1 = (float) (seg + 1) / armSegs;
                        float angle0 = baseAngle - tSeg * 1.8f * charge;
                        float angle1 = baseAngle - tSeg1 * 1.8f * charge;
                        float r0 = armLen * (1f - tSeg);
                        float r1a = armLen * (1f - tSeg1);
                        float sx0 = vx + (float) Math.cos(angle0) * r0;
                        float sy0 = vy + (float) Math.sin(angle0) * r0;
                        float sx1 = vx + (float) Math.cos(angle1) * r1a;
                        float sy1 = vy + (float) Math.sin(angle1) * r1a;
                        float aa = (1f - tSeg) * charge * 0.80f;
                        int armCol = ImGui.colorConvertFloat4ToU32(0.65f, 0.0f, 1.0f, aa);
                        draw.addLine(sx0, sy0, sx1, sy1, armCol, 2.5f + charge * 2.5f);
                    }
                }

                // Bright core that pulses faster as it approaches full charge
                float corePulse = 0.5f + 0.5f * (float) Math.sin(t * (8.0 + charge * 14.0));
                float coreR = 6f + corePulse * 10f * charge;
                draw.addCircleFilled(vx, vy, coreR,
                        ImGui.colorConvertFloat4ToU32(0.9f, 0.5f, 1.0f, 0.85f * charge));

                // Near-complete: charge bar flashes bright white
                if (charge > 0.85f) {
                    float flashA = (charge - 0.85f) / 0.15f * 0.55f;
                    draw.addCircleFilled(vx, vy, 28f * charge,
                            ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, flashA));
                }
            }
        }

        // ── MANHATTAN TRANSFER 2D OFF-SCREEN INDICATOR ────────────────────────
        if (win.player.stand.isDeployed() && !win.player.stand.isInStandPerspective()) {

            // 1. Use the Matrix only to check if the drone is visible on-screen
            Matrix4f viewProj = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
            Vector3f dronePos = win.player.stand.standPos;

            org.joml.Vector4f clipPos = new org.joml.Vector4f(dronePos.x, dronePos.y, dronePos.z, 1.0f).mul(viewProj);
            boolean inFront = clipPos.w > 0.0f;
            float ndcX = clipPos.x / Math.abs(clipPos.w);
            float ndcY = clipPos.y / Math.abs(clipPos.w);
            boolean onScreen = inFront && Math.abs(ndcX) <= 1.0f && Math.abs(ndcY) <= 1.0f;

            // 2. Draw the edge arrow ONLY if it is off-screen
            if (!onScreen) {
                // To avoid matrix flipping bugs, we use simple Dot Products with
                // the camera's local axes to find the true, stable direction.
                Vector3f toDrone = new Vector3f(dronePos).sub(camera.position).normalize();
                Vector3f right = camera.getRight();
                // Cross product: Right x Forward = Local Up Vector
                Vector3f up = new Vector3f(right).cross(camera.getLookDirection()).normalize();

                // Screen X grows right. Screen Y grows DOWN (so we invert Y).
                float dirX = toDrone.dot(right);
                float dirY = -toDrone.dot(up);

                float angle = (float) Math.atan2(dirY, dirX);
                float radius = Math.min(cx, cy) * 0.85f;

                float indicatorX = cx + (float) Math.cos(angle) * radius;
                float indicatorY = cy + (float) Math.sin(angle) * radius;

                // 3. Draw Custom "Tag" Shape (Made slightly larger)
                float L = 46f;      // Total length of the shape
                float W = 32f;      // Total width
                float tipL = 16f;   // Length of the pointy triangle tip

                float[][] pts = {
                        {L / 2, 0},               // 0: Tip
                        {L / 2 - tipL, W / 2},      // 1: Top Right corner
                        {-L / 2, W / 2},            // 2: Top Left corner
                        {-L / 2, -W / 2},           // 3: Bottom Left corner
                        {L / 2 - tipL, -W / 2}      // 4: Bottom Right corner
                };

                // Rotate and translate vertices to the edge of the screen
                float cosA = (float) Math.cos(angle);
                float sinA = (float) Math.sin(angle);
                for (int i = 0; i < 5; i++) {
                    float px = pts[i][0];
                    float py = pts[i][1];
                    float rotX = px * cosA - py * sinA;
                    float rotY = px * sinA + py * cosA;
                    pts[i][0] = indicatorX + rotX;
                    pts[i][1] = indicatorY + rotY;
                }

                // Colors
                int goldFill = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.15f, 0.95f);
                int outline = ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 1.0f);
                int redAlert = ImGui.colorConvertFloat4ToU32(1.0f, 0.15f, 0.15f, 1.0f);

                // Fill the shape
                draw.addQuadFilled(pts[1][0], pts[1][1], pts[2][0], pts[2][1], pts[3][0], pts[3][1], pts[4][0], pts[4][1], goldFill);
                draw.addTriangleFilled(pts[0][0], pts[0][1], pts[1][0], pts[1][1], pts[4][0], pts[4][1], goldFill);

                // Draw thick outline
                float thick = 2.5f;
                draw.addLine(pts[0][0], pts[0][1], pts[1][0], pts[1][1], outline, thick);
                draw.addLine(pts[1][0], pts[1][1], pts[2][0], pts[2][1], outline, thick);
                draw.addLine(pts[2][0], pts[2][1], pts[3][0], pts[3][1], outline, thick);
                draw.addLine(pts[3][0], pts[3][1], pts[4][0], pts[4][1], outline, thick);
                draw.addLine(pts[4][0], pts[4][1], pts[0][0], pts[0][1], outline, thick);

                // 4. Draw large, bright RED "!"
                float textOffsetX = -tipL / 2.0f;
                float centerIconX = indicatorX + textOffsetX * cosA;
                float centerIconY = indicatorY + textOffsetX * sinA;

                // Draw exclamation mark using filled rectangles (bypassing text limits so it's massive)
                // Top bar
                draw.addRectFilled(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, redAlert);
                // Bottom dot
                draw.addRectFilled(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, redAlert);

                // Outline the exclamation mark so it pops beautifully inside the gold box
                draw.addRect(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, outline, 0f, 0, 1.5f);
                draw.addRect(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, outline, 0f, 0, 1.5f);
                // Outline the exclamation mark so it pops beautifully inside the gold box
                draw.addRect(centerIconX - 2.5f, centerIconY - 9f, centerIconX + 2.5f, centerIconY + 3f, outline, 0f, 0, 1.5f);
                draw.addRect(centerIconX - 2.5f, centerIconY + 6f, centerIconX + 2.5f, centerIconY + 11f, outline, 0f, 0, 1.5f);
            } else {
                // ── ON-SCREEN 2D DRONE OUTLINE (Always bright, always visible!) ──
                float dScrX = (ndcX * 0.5f + 0.5f) * screenW;
                float dScrY = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                float dist = new Vector3f(dronePos).sub(camera.position).length();
                // Scale the 2D UI box so it shrinks slightly at a distance, but never gets too tiny
                float s = Math.max(14f, Math.min(45f, 400f / dist));

                boolean aimingAtDrone = win.player.attacks.isAimingAtStand(camera);

                int yellow = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 1.0f);
                int dimYellow = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 0.45f); // 45% opacity when idle

                int brightRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.0f, 0.0f, 1.0f); // 100% opacity bright red
                int dimRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.0f, 0.0f, 0.45f); // 45% opacity dim red

                int color = aimingAtDrone ? brightRed : dimRed;
                float thick = aimingAtDrone ? 3.0f : 1.5f;

                // Draw a 2D Diamond Outline exactly over the drone
                draw.addQuad(
                        dScrX, dScrY - s,
                        dScrX + s, dScrY,
                        dScrX, dScrY + s,
                        dScrX - s, dScrY, color, thick);

                if (aimingAtDrone) {
                    // Draw a striking inner crosshair when locked on
                    float in = s * 0.5f;
                    draw.addLine(dScrX - in, dScrY, dScrX + in, dScrY, yellow, 2.0f);
                    draw.addLine(dScrX, dScrY - in, dScrX, dScrY + in, yellow, 2.0f);
                }
            }
        }
        // Crosshair
        int black = ImGui.colorConvertFloat4ToU32(0, 0, 0, 0.6f);
        int white = ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f);

        float chargeF = win.player.attacks.getChargeFrac();

        if (chargeF > 0f) {
            // ── SNIPER / CHARGING MODE  -  red shrinking circle crosshair ──────────
            int red = ImGui.colorConvertFloat4ToU32(1.0f, 0.12f, 0.12f, 0.95f);
            float ring = Math.max(4f, 18f - 13f * chargeF); // shrinks from 18 -> 5 px as charge builds

            draw.addCircle(cx, cy, ring, red, 32, 1.8f);
            draw.addCircleFilled(cx, cy, 2.2f, red, 8);

            // Four radiating lines  -  sniper style
            float gap = ring + 2f, ext = gap + 9f;
            draw.addLine(cx - ext, cy, cx - gap, cy, red, 1.5f);
            draw.addLine(cx + gap, cy, cx + ext, cy, red, 1.5f);
            draw.addLine(cx, cy - ext, cx, cy - gap, red, 1.5f);
            draw.addLine(cx, cy + gap, cx, cy + ext, red, 1.5f);

            // Charge % below crosshair
            String pct = String.format("%.0f%%", chargeF * 100f);
            draw.addText(cx - 10f, cy + ring + 8f, black, pct);
            draw.addText(cx - 11f, cy + ring + 7f, red, pct);

        } else {
            // ── NORMAL  -  plain white crosshair ────────────────────────────────────
            draw.addLine(cx - 11, cy, cx + 11, cy, black, 3.0f);
            draw.addLine(cx, cy - 11, cx, cy + 11, black, 3.0f);
            draw.addLine(cx - 10, cy, cx + 10, cy, white, 1.5f);
            draw.addLine(cx, cy - 10, cx, cy + 10, white, 1.5f);

            // ── KAMUI ABSORPTION RING  -  shows charge building around crosshair ──
            if (win.player.abilities.isKamui && win.player.abilities.absorptionCharge > 0f) {
                float ch = win.player.abilities.absorptionCharge;
                double now = glfwGetTime();
                float pulse = 0.7f + 0.3f * (float) Math.sin(now * 12.0);
                int ringCol = ImGui.colorConvertFloat4ToU32(0.8f, 0.2f, 1.0f, 0.85f * ch * pulse);
                // Clockwise fill arc  -  approximate with many small lines around the ring
                int absRing = ImGui.colorConvertFloat4ToU32(0.7f, 0.0f, 1.0f, 0.9f * ch);
                draw.addCircle(cx, cy, 22f, absRing, 48, 3.0f * ch);
                // Filling arc (simplified: full circle but alpha scaled by charge)
                if (ch < 1.0f) {
                    draw.addCircle(cx, cy, 26f,
                            ImGui.colorConvertFloat4ToU32(0.55f, 0.0f, 1.0f, 0.35f * ch), 32, 1.5f);
                }
                // Label below crosshair
                if (ch > 0.05f) {
                    String absPct = String.format("%.0f%%", ch * 100f);
                    draw.addText(cx - 12f, cy + 32f, black, absPct);
                    draw.addText(cx - 13f, cy + 31f,
                            ImGui.colorConvertFloat4ToU32(0.85f, 0.4f, 1.0f, 1f), absPct);
                }
            }
        }

        // ── REDIRECT INDICATOR ──
        boolean aimingAtDrone = !win.player.stand.isInStandPerspective() && win.player.attacks.isAimingAtStand(camera);
        boolean redirectReady = aimingAtDrone && win.player.attacks.isRedirectAvailable(win.world);

        if (aimingAtDrone) {
            // Both states are now high-contrast Yellow/Gold
            int yellow = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.10f, 1.0f);
            int grey = ImGui.colorConvertFloat4ToU32(0.5f, 0.5f, 0.5f, 1.0f);

            int blackBg = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.85f);

            float circleRadius = 12.0f; // Shorter, tighter radius (down from 18)

            if (redirectReady) {
                draw.addRectFilled(cx + 12f, cy - 20f, cx + 115f, cy + 4f, blackBg, 4f);
                draw.addText(cx + 18f, cy - 12f, yellow, "REDIRECT [C]");
                draw.addCircle(cx, cy, circleRadius, yellow, 32, 2.5f);
            } else {
                draw.addRectFilled(cx + 12f, cy - 20f, cx + 110f, cy + 4f, blackBg, 4f);
                draw.addText(cx + 18f, cy - 12f, grey, "REFLECT [C]"); // Yellow indicator
                draw.addCircle(cx, cy, circleRadius, grey, 32, 2.0f); // Yellow circle
            }
        }
        // Health bar and win.hotbar  -  hidden while piloting the drone
        if (!win.player.stand.isInStandPerspective()) {

            // Revival immunity indicator — pulsing golden border around the whole screen.
            if (win.immunityTimer > 0f) {
                float t = win.immunityTimer / win.REVIVAL_IMMUNITY_SECS;
                float flicker = 0.3f + 0.55f * (float) Math.abs(Math.sin(glfwGetTime() * 10f));
                float alpha = t * flicker;  // fades out as immunity expires
                int immCol = ImGui.colorConvertFloat4ToU32(1f, 0.88f, 0.3f, alpha);
                float bw = 8f;
                draw.addRect(bw, bw, screenW - bw, screenH - bw, immCol, 0f, 0, bw);
                // Small label
                String immLabel = "PROTECTED  " + (int)Math.ceil(win.immunityTimer) + "s";
                float lw = ImGui.calcTextSize(immLabel).x;
                draw.addText(cx - lw/2, screenH - 140f,
                        ImGui.colorConvertFloat4ToU32(1f, 0.95f, 0.5f, alpha * 1.5f), immLabel);
            }

            // Health bar
            float hpWidth = 200f, hpHeight = 14f;
            float hpX = cx - hpWidth / 2.0f, hpY = screenH - 80f;
            // Recent-damage pulse: bright red wash + flashing outline behind the bar.
            float dmgT = Math.max(0f, win.damageFlashTimer);
            draw.addRectFilled(hpX, hpY, hpX + hpWidth, hpY + hpHeight,
                    ImGui.colorConvertFloat4ToU32(0.2f, 0, 0, 0.8f));
            float fillW = hpWidth * (Math.max(0, win.player.health) / win.player.maxHealth);
            float flashLift = dmgT > 0f ? (0.4f * (float) Math.abs(Math.sin(glfwGetTime() * 18f))) : 0f;
            draw.addRectFilled(hpX, hpY, hpX + fillW, hpY + hpHeight,
                    ImGui.colorConvertFloat4ToU32(0.8f + flashLift, 0.1f + flashLift, 0.1f + flashLift, 1.0f));
            // Low-health warning: the whole bar gently pulses when under 30%.
            float hpFracP = Math.max(0f, win.player.health) / win.player.maxHealth;
            if (hpFracP < 0.30f) {
                float warn = 0.3f + 0.4f * (float) Math.abs(Math.sin(glfwGetTime() * 5f));
                draw.addRect(hpX - 2, hpY - 2, hpX + hpWidth + 2, hpY + hpHeight + 2,
                        ImGui.colorConvertFloat4ToU32(1f, 0.2f, 0.2f, warn), 0f, 0, 2.5f);
            }
            int hpBorder = dmgT > 0f
                    ? ImGui.colorConvertFloat4ToU32(1f, 0.9f, 0.9f, Math.min(1f, dmgT * 2f))
                    : black;
            draw.addRect(hpX, hpY, hpX + hpWidth, hpY + hpHeight, hpBorder, 0f, 0, dmgT > 0f ? 3.0f : 2.0f);
            draw.addText(hpX - 26f, hpY, ImGui.colorConvertFloat4ToU32(1f, 0.7f, 0.7f, 0.9f), "HP");

            // Mana bar (blue, below health bar)
            float mpHeight = 9f;
            float mpY = hpY + hpHeight + 3f;
            draw.addRectFilled(hpX, mpY, hpX + hpWidth, mpY + mpHeight,
                    ImGui.colorConvertFloat4ToU32(0.03f, 0.04f, 0.22f, 0.8f));
            float mpFill = hpWidth * Math.max(0f, win.player.mana / win.player.maxMana);
            draw.addRectFilled(hpX, mpY, hpX + mpFill, mpY + mpHeight,
                    ImGui.colorConvertFloat4ToU32(0.22f, 0.45f, 1.0f, 1.0f));
            draw.addRect(hpX, mpY, hpX + hpWidth, mpY + mpHeight, black, 0f, 0, 1.5f);
            draw.addText(hpX - 26f, mpY - 2f, ImGui.colorConvertFloat4ToU32(0.55f, 0.7f, 1f, 0.9f), "MP");
            // Mana text label (compact, right-aligned inside bar)
            String mpLabel = String.format("%.0f / %.0f  MP", win.player.mana, win.player.maxMana);
            draw.addText(hpX + hpWidth - 78f, mpY - 0.5f, black, mpLabel);
            draw.addText(hpX + hpWidth - 79f, mpY - 1.5f,
                    ImGui.colorConvertFloat4ToU32(0.55f, 0.75f, 1.0f, 0.9f), mpLabel);

            // Hotbar
            float slotSize = 40.0f, spacing = 5.0f;
            int numSlots = 9;
            float startX = cx - ((numSlots * slotSize + (numSlots - 1) * spacing) / 2.0f);
            float startY = screenH - slotSize - 10.0f;
            win.selectedBlock = win.hotbar[win.selectedSlot];

            for (int i = 0; i < numSlots; i++) {
                float x = startX + i * (slotSize + spacing);
                int bgCol = (i == win.selectedSlot)
                        ? ImGui.colorConvertFloat4ToU32(0.6f, 0.6f, 0.6f, 0.8f)
                        : ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);
                draw.addRectFilled(x, startY, x + slotSize, startY + slotSize, bgCol, 4.0f);
                int outCol = (i == win.selectedSlot)
                        ? ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, 1.0f)
                        : ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.8f);
                draw.addRect(x, startY, x + slotSize, startY + slotSize, outCol, 4.0f, 0,
                        (i == win.selectedSlot) ? 3.0f : 1.5f);
                Block b = win.hotbar[i];
                int count = win.inventory.getCount(b);
                boolean tool = (b == Block.GATLING_GUN || b == Block.TORCH);  // items, not consumable blocks
                if (b != Block.AIR && (tool || count > 0)) {
                    float s = 8.0f;
                    float ix0 = x + s, iy0 = startY + s, ix1 = x + slotSize - s, iy1 = startY + slotSize - s;
                    if (b == Block.GATLING_GUN) {
                        drawGunIcon(draw, ix0, iy0, ix1, iy1);
                    } else if (b == Block.TORCH) {
                        drawTorchIcon(draw, ix0, iy0, ix1, iy1);
                    } else {
                        int blockCol = ImGui.colorConvertFloat4ToU32(b.r, b.g, b.b, 1.0f);
                        draw.addRectFilled(ix0, iy0, ix1, iy1, blockCol, 2.0f);
                        draw.addRect(ix0, iy0, ix1, iy1, black, 2.0f, 0, 1.5f);
                        String countStr = String.valueOf(count);
                        draw.addText(x + slotSize - 14, startY + slotSize - 18, black, countStr);
                        draw.addText(x + slotSize - 15, startY + slotSize - 19, white, countStr);
                    }
                }
            }
        } // end !isInStandPerspective (health bar + win.hotbar guard)

        // ── FLIGHT MODE INDICATOR ─────────────────────────────────────────────
        if (win.player.debugMode) {
            FlightController.FlightMode mode = win.player.flightController.getMode();
            String modeLabel = switch (mode) {
                case SKIM -> "✦ SKIM";
                case SOAR -> "✦ SOAR";
                case GRAPPLE -> "✦ GRAPPLE";
            };
            int modeColor = switch (mode) {
                case SKIM -> ImGui.colorConvertFloat4ToU32(0.4f, 0.9f, 0.4f, 0.9f);
                case SOAR -> ImGui.colorConvertFloat4ToU32(0.4f, 0.7f, 1.0f, 0.9f);
                case GRAPPLE -> ImGui.colorConvertFloat4ToU32(1.0f, 0.75f, 0.2f, 0.9f);
            };
            draw.addText(cx - 30, cy - 60, black, modeLabel);
            draw.addText(cx - 31, cy - 61, modeColor, modeLabel);
            draw.addText(cx - 55, cy - 44, black, "[V] cycle mode");
            draw.addText(cx - 56, cy - 45,
                    ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), "[V] cycle mode");

            // ── GRAPPLE FEEDBACK ─────────────────────────────────────────────
            // Gives the win.player clear state feedback so they always know whether
            // they are hooked, where the anchor is, and how to use it.
            if (mode == FlightController.FlightMode.GRAPPLE) {
                FlightController fc = win.player.flightController;
                int grappleGold = ImGui.colorConvertFloat4ToU32(1.0f, 0.78f, 0.1f, 1.0f);
                int grappleRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.35f, 0.1f, 0.9f);
                int grappleGrey = ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.7f);

                if (fc.isHooked()) {
                    // Show hook distance
                    float hookDist = new Vector3f(fc.getHookPoint()).sub(win.player.position).length();
                    String hookedStr = String.format("⦿ ZIPPING  %.0fm  Release to launch", hookDist);
                    draw.addText(cx - 72, cy + 28, black, hookedStr);
                    draw.addText(cx - 73, cy + 27, grappleGold, hookedStr);

                    // Draw a small "anchor" diamond at crosshair center to distinguish state
                    float d = 7f;
                    draw.addLine(cx, cy - d, cx + d, cy, grappleGold, 2.0f);
                    draw.addLine(cx + d, cy, cx, cy + d, grappleGold, 2.0f);
                    draw.addLine(cx, cy + d, cx - d, cy, grappleGold, 2.0f);
                    draw.addLine(cx - d, cy, cx, cy - d, grappleGold, 2.0f);
                } else {
                    // Unhooked: show targeting guide
                    String aimStr = "[ ] AIM + Hold [RIGHT-CLICK] or [F]";
                    draw.addText(cx - 88, cy + 28, black, aimStr);
                    draw.addText(cx - 89, cy + 27, grappleGrey, aimStr);

                    // Draw a square targeting reticle around the crosshair
                    float s = 12f;
                    // Four corner brackets
                    draw.addLine(cx - s, cy - s, cx - s + 5, cy - s, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy - s, cx - s, cy - s + 5, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy - s, cx + s - 5, cy - s, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy - s, cx + s, cy - s + 5, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy + s, cx - s + 5, cy + s, grappleRed, 1.5f);
                    draw.addLine(cx - s, cy + s, cx - s, cy + s - 5, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy + s, cx + s - 5, cy + s, grappleRed, 1.5f);
                    draw.addLine(cx + s, cy + s, cx + s, cy + s - 5, grappleRed, 1.5f);
                }
            }
        }

        // ── CANNONBALL CHARGE BAR + READINESS ────────────────────────────────
        if (win.player.abilities.isCharging()) {
            float barW = 180f, barH = 12f;
            float barX = cx - barW / 2f, barY = cy + 42f;

            // Power bar (gold -> red-orange)
            draw.addRectFilled(barX, barY, barX + barW, barY + barH,
                    ImGui.colorConvertFloat4ToU32(0.1f, 0.1f, 0.1f, 0.7f), 4f);
            float fill = win.player.abilities.chargePower;
            int barColor = fill >= 0.99f
                    ? ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.05f, 1.0f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.75f, 0.1f, 1.0f);
            draw.addRectFilled(barX, barY, barX + barW * fill, barY + barH, barColor, 4f);
            draw.addRect(barX, barY, barX + barW, barY + barH, black, 4f, 0, 1.5f);

            String powerLabel = fill >= 0.99f ? "FULL POWER" : String.format("CHARGING %.0f%%", fill * 100f);
            draw.addText(cx - 43, barY + 16f, black, powerLabel);
            draw.addText(cx - 44, barY + 15f, barColor, powerLabel);

            // Readiness bar (blue  -  shows chunk generation progress)
            float rdyBarY = barY + 30f;
            draw.addRectFilled(barX, rdyBarY, barX + barW, rdyBarY + barH,
                    ImGui.colorConvertFloat4ToU32(0.05f, 0.05f, 0.15f, 0.7f), 4f);
            int rdyColor = win.pathReadiness >= 0.95f
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 1.0f)   // ready = green
                    : ImGui.colorConvertFloat4ToU32(0.3f, 0.6f, 1.0f, 1.0f);  // loading = blue
            draw.addRectFilled(barX, rdyBarY, barX + barW * win.pathReadiness, rdyBarY + barH, rdyColor, 4f);
            draw.addRect(barX, rdyBarY, barX + barW, rdyBarY + barH, black, 4f, 0, 1.5f);

            String rdyLabel = win.pathReadiness >= 0.95f
                    ? "PATH CLEAR  -  release [G] to fire!"
                    : String.format("Loading terrain... %.0f%%", win.pathReadiness * 100f);
            int rdyTextColor = win.pathReadiness >= 0.95f
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 0.95f)
                    : ImGui.colorConvertFloat4ToU32(0.5f, 0.75f, 1.0f, 0.85f);
            draw.addText(cx - 70, rdyBarY + 16f, black, rdyLabel);
            draw.addText(cx - 71, rdyBarY + 15f, rdyTextColor, rdyLabel);

            // Crosshair lock indicator (camera is frozen to aim direction)
            draw.addText(cx - 55, cy - 22, black, "[ AIM LOCKED ]");
            draw.addText(cx - 56, cy - 23,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.3f, 0.85f), "[ AIM LOCKED ]");
        }

        // ── REWIND TRAIL INDICATOR ────────────────────────────────────────────
        if (win.player.abilities.isRewinding) {
            draw.addText(cx - 42, cy - 90, black, "⟲ REWINDING ⟲");
            draw.addText(cx - 43, cy - 91,
                    ImGui.colorConvertFloat4ToU32(0.3f, 0.6f, 1.0f, 1.0f), "⟲ REWINDING ⟲");
        }

        // ── SMASH INDICATOR ───────────────────────────────────────────────────
        if (win.player.isSmashing()) {
            draw.addText(cx - 35, cy - 90, black, "▼ SMASHING ▼");
            draw.addText(cx - 36, cy - 91,
                    ImGui.colorConvertFloat4ToU32(1.0f, 0.3f, 0.1f, 1.0f), "▼ SMASHING ▼");
        }

        // ── STAND PERSPECTIVE OVERLAY ────────────────────────────────────────
        // When piloting the drone: replace most HUD with a clean drone cockpit view.
        if (win.player.stand.isInStandPerspective()) {
            // Dim border vignette to indicate we are in drone mode
            int vigCol = ImGui.colorConvertFloat4ToU32(0.05f, 0.1f, 0.35f, 0.55f);
            float vEdge = 80f;
            draw.addRectFilled(0f, screenH - vEdge, screenW, screenH, vigCol);
            draw.addRectFilled(0f, 0f, screenW, vEdge, vigCol);
            draw.addRectFilled(0f, 0f, vEdge, screenH, vigCol);
            draw.addRectFilled(screenW - vEdge, 0f, screenW, screenH, vigCol);

            // ── Stand health bar ──────────────────────────────────────────────
            float shW = 160f, shH = 12f;
            float shX = cx - shW / 2f, shY = 18f;
            int shBg = ImGui.colorConvertFloat4ToU32(0.05f, 0.05f, 0.2f, 0.85f);
            int shFg = ImGui.colorConvertFloat4ToU32(0.25f, 0.65f, 1.0f, 1.0f);
            draw.addRectFilled(shX, shY, shX + shW, shY + shH, shBg, 4f);
            float shFill = (win.player.stand.standHealth / GameConfig.standMaxHealth) * shW;
            draw.addRectFilled(shX, shY, shX + shFill, shY + shH, shFg, 4f);
            draw.addRect(shX, shY, shX + shW, shY + shH, black, 4f, 0, 1.5f);
            String shLabel = String.format("STAND HP  %.0f / %.0f",
                    win.player.stand.standHealth, GameConfig.standMaxHealth);
            draw.addText(cx - 50f, shY + shH + 4f, black, shLabel);
            draw.addText(cx - 51f, shY + shH + 3f, ImGui.colorConvertFloat4ToU32(0.6f, 0.85f, 1.0f, 0.9f), shLabel);

            // ── LOS status indicators ─────────────────────────────────────────
            // Two dots: owner->stand (left), stand->target (right).
            // Green = clear, red = blocked.
            float dotY = shY + shH + 26f;
            float dotR = 5f;
            int losGreen = ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.35f, 1.0f);
            int losRed = ImGui.colorConvertFloat4ToU32(1.0f, 0.2f, 0.15f, 1.0f);
            int losDim = ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.4f, 0.6f);

            // Owner->Stand
            float d1X = cx - 28f;
            draw.addCircleFilled(d1X, dotY, dotR,
                    win.player.stand.losOwnerToStand ? losGreen : losRed, 16);
            draw.addCircle(d1X, dotY, dotR, black, 16, 1.2f);
            draw.addText(d1X - 14f, dotY + 8f,
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.8f), "OWNER");

            // Connecting line
            draw.addLine(cx - 22f, dotY, cx + 22f, dotY, losDim, 1.2f);

            // Stand->Target
            float d2X = cx + 28f;
            draw.addCircleFilled(d2X, dotY, dotR,
                    win.player.stand.losStandToTarget ? losGreen : losRed, 16);
            draw.addCircle(d2X, dotY, dotR, black, 16, 1.2f);
            draw.addText(d2X - 12f, dotY + 8f,
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.75f, 0.75f, 0.8f), "TGT");

            // Fire-ready composite state
            boolean readyToFire = win.player.stand.losOwnerToStand && win.player.stand.losStandToTarget;
            String fireHint = readyToFire ? "[LMB] Fire redirect shot" : "No clear line of fire";
            int fireColor = readyToFire
                    ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 0.4f, 0.95f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.4f, 0.2f, 0.75f);
            draw.addText(cx - 65f, dotY + 20f, black, fireHint);
            draw.addText(cx - 66f, dotY + 19f, fireColor, fireHint);

            // ── Master position marker (glowing dot + distance) ───────────────
            // Project win.player body position to screen so pilot can see where master is.
            Camera sc = win.player.stand.standCamera;
            Matrix4f svp = new Matrix4f(sc.getProjectionMatrix()).mul(sc.getViewMatrix());
            org.joml.Vector4f masterClip = new org.joml.Vector4f(
                    win.player.position.x, win.player.position.y + 0.9f, win.player.position.z, 1f).mul(svp);
            boolean masterInFront = masterClip.w > 0f;
            float masterDist = new Vector3f(win.player.stand.standPos).sub(win.player.position).length();
            String distLabel = String.format("%.0fm", masterDist);

            if (masterInFront) {
                float mNdcX = masterClip.x / masterClip.w;
                float mNdcY = masterClip.y / masterClip.w;
                boolean mOnScreen = Math.abs(mNdcX) <= 0.92f && Math.abs(mNdcY) <= 0.92f;
                float mScrX = (mNdcX * 0.5f + 0.5f) * screenW;
                float mScrY = (1f - (mNdcY * 0.5f + 0.5f)) * screenH;

                if (mOnScreen) {
                    // Pulsing gold ring  -  master is visible from drone camera
                    int masterGold = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.15f, 0.9f);
                    draw.addCircle(mScrX, mScrY, 11f, masterGold, 24, 2.5f);
                    draw.addCircle(mScrX, mScrY, 7f, masterGold, 24, 1.5f);
                    draw.addText(mScrX - 12f, mScrY + 14f, black, distLabel);
                    draw.addText(mScrX - 13f, mScrY + 13f, masterGold, distLabel);
                }
            }

            // ── Return hint ───────────────────────────────────────────────────
            String returnHint = "[TAB]  Return to body";
            draw.addText(cx - 52f, screenH - 30f, black, returnHint);
            draw.addText(cx - 53f, screenH - 31f,
                    ImGui.colorConvertFloat4ToU32(0.7f, 0.82f, 1.0f, 0.85f), returnHint);
        }

        // ── SEAL COUNT DISPLAY ────────────────────────────────────────────────
        // Show placed seal count + place cooldown tick only when NOT in drone view.
        if (!win.player.stand.isInStandPerspective() && !win.player.debugMode) {
            int placed = win.player.seals.getSealCount();
            int maxSeals = GameConfig.sealMaxCount;
            // Pip row: filled diamond = placed, hollow = available slot
            float pipY = screenH - 120f;
            float pipGap = 14f;
            float pipStartX = cx - (maxSeals - 1) * pipGap / 2f;
            int sealCyan = ImGui.colorConvertFloat4ToU32(0.2f, 0.95f, 0.95f, 1.0f);
            int sealDim = ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.3f, 0.55f);

            for (int i = 0; i < maxSeals; i++) {
                float px = pipStartX + i * pipGap;
                float half = 5f;
                if (i < placed) {
                    // Filled diamond
                    draw.addQuadFilled(px, pipY - half,
                            px + half, pipY,
                            px, pipY + half,
                            px - half, pipY, sealCyan);
                    draw.addQuad(px, pipY - half,
                            px + half, pipY,
                            px, pipY + half,
                            px - half, pipY, black, 1.2f);
                } else {
                    // Empty diamond outline
                    draw.addQuad(px, pipY - half,
                            px + half, pipY,
                            px, pipY + half,
                            px - half, pipY, sealDim, 1.2f);
                }
            }

            // Label: seal key hints on first and last slot
            float placeF = win.player.seals.getPlaceCooldownFrac();
            float teleportF = win.player.seals.getTeleportCooldownFrac();
            String sealLabel = String.format("[H] Place  [B] Teleport  [N] Reclaim   %d/%d",
                    placed, maxSeals);
            int labelCol = (placeF >= 1f)
                    ? ImGui.colorConvertFloat4ToU32(0.6f, 0.95f, 0.95f, 0.75f)
                    : ImGui.colorConvertFloat4ToU32(0.45f, 0.6f, 0.6f, 0.55f);
            draw.addText(cx - 95f, pipY + 10f, black, sealLabel);
            draw.addText(cx - 96f, pipY + 9f, labelCol, sealLabel);
        }

        // ── SEAL HUD MARKERS ─────────────────────────────────────────────────
        // Always-visible indicators so seals are spottable at any distance:
        //   • On-screen seal  -> pulsing cyan ring + distance label
        //   • Off-screen seal -> small cyan arrow on screen edge
        if (!win.player.stand.isInStandPerspective()
                && !win.player.seals.placedSeals.isEmpty()) {

            Matrix4f sealVP = new Matrix4f(camera.getProjectionMatrix())
                    .mul(camera.getViewMatrix());

            for (SealController.SealEntry seal : win.player.seals.placedSeals) {
                org.joml.Vector4f clip = new org.joml.Vector4f(
                        seal.position.x, seal.position.y, seal.position.z, 1f)
                        .mul(sealVP);

                boolean inFront = clip.w > 0f;
                float absW = Math.abs(clip.w);
                float ndcX = clip.x / absW;
                float ndcY = clip.y / absW;
                boolean onScreen = inFront
                        && Math.abs(ndcX) <= 1.0f && Math.abs(ndcY) <= 1.0f;

                float dist = new Vector3f(seal.position).sub(camera.position).length();
                String distLabel = String.format("%.0fm", dist);

                int sealRingColor = seal.targeted
                        ? ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 1.0f, 1.0f)
                        : ImGui.colorConvertFloat4ToU32(0.1f, 0.8f, 0.8f, 0.85f);
                int sealDimText = ImGui.colorConvertFloat4ToU32(0.2f, 0.9f, 0.9f, 0.75f);

                if (onScreen) {
                    // Project to pixel coords
                    float sx = (ndcX * 0.5f + 0.5f) * screenW;
                    float sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                    // Pulse: ring radius grows/shrinks slightly
                    float pulse = 1f + 0.12f * (float) Math.sin(seal.pulsePhase * 2f);
                    float outerR = (seal.targeted ? 18f : 11f) * pulse;
                    float innerR = outerR * 0.55f;

                    // Outer ring
                    draw.addCircle(sx, sy, outerR, sealRingColor, 20, seal.targeted ? 2.5f : 1.8f);
                    // Inner dot for targeted seal
                    if (seal.targeted) {
                        draw.addCircleFilled(sx, sy, innerR,
                                ImGui.colorConvertFloat4ToU32(0.2f, 1.0f, 1.0f, 0.35f), 16);
                    }
                    // Distance label just below the ring
                    float labelY = sy + outerR + 3f;
                    draw.addText(sx - 12f, labelY + 1f, black, distLabel);
                    draw.addText(sx - 13f, labelY, sealDimText, distLabel);

                } else {
                    // Off-screen arrow  -  same dot-product technique as the stand indicator
                    Vector3f toSeal = new Vector3f(seal.position)
                            .sub(camera.position).normalize();
                    Vector3f right = camera.getRight();
                    Vector3f up = new Vector3f(right)
                            .cross(camera.getLookDirection()).normalize();

                    float dirX = toSeal.dot(right);
                    float dirY = -toSeal.dot(up);
                    float angle = (float) Math.atan2(dirY, dirX);
                    float edgeR = Math.min(cx, cy) * 0.80f;

                    float ax = cx + (float) Math.cos(angle) * edgeR;
                    float ay = cy + (float) Math.sin(angle) * edgeR;

                    // Small triangle arrow
                    float cosA = (float) Math.cos(angle);
                    float sinA = (float) Math.sin(angle);
                    float tl = 12f, tw = 7f;
                    // tip, left-base, right-base
                    float t1x = ax + cosA * tl * 0.5f, t1y = ay + sinA * tl * 0.5f;
                    float t2x = ax - cosA * tl * 0.5f + sinA * tw * 0.5f;
                    float t2y = ay - sinA * tl * 0.5f - cosA * tw * 0.5f;
                    float t3x = ax - cosA * tl * 0.5f - sinA * tw * 0.5f;
                    float t3y = ay - sinA * tl * 0.5f + cosA * tw * 0.5f;
                    draw.addTriangleFilled(t1x, t1y, t2x, t2y, t3x, t3y, sealRingColor);
                    draw.addTriangle(t1x, t1y, t2x, t2y, t3x, t3y, black, 1.2f);

                    // Distance label beside arrow
                    draw.addText(ax + cosA * (tl + 3f) - 10f,
                            ay + sinA * (tl + 3f) - 6f, black, distLabel);
                    draw.addText(ax + cosA * (tl + 3f) - 11f,
                            ay + sinA * (tl + 3f) - 7f, sealDimText, distLabel);
                }
            }
        }

        // ── ENEMY HP BARS ─────────────────────────────────────────────────────
        // World-space health bar projected above each enemy's head.
        // Use the active camera: stand camera in drone mode, win.player camera otherwise.
        if (!win.enemyManager.getEnemies().isEmpty() && !win.player.stand.isInStandPerspective()) {
            // Build VP from the actual win.player eye position/orientation
            float aspect = screenW / screenH;
            float fovRad = (float) Math.toRadians(
                    (camera.dynamicFov >= 0f) ? camera.dynamicFov : GameConfig.fov);
            Matrix4f hpProj = new Matrix4f().perspective(fovRad, aspect, 0.1f, 1000f);
            Matrix4f hpView = camera.getViewMatrix();
            Matrix4f enemyVP = new Matrix4f(hpProj).mul(hpView);

            // Camera eye position and look direction for in-front culling
            Vector3f eyePos = new Vector3f(camera.position);
            Vector3f lookDir = camera.getLookDirection();
            Vector3f right = camera.getRight();

            // Off-screen enemies within this range get an edge arrow so the player
            // can always locate threats (fixes "enemies are hard to find / hit me
            // from nowhere"). Each entry: {screenDirX, screenDirY, distance}.
            java.util.List<float[]> offscreen = new java.util.ArrayList<>();
            float ecx = screenW * 0.5f, ecy = screenH * 0.5f;
            float marker = (float) glfwGetTime();

            for (Enemy enemy : win.enemyManager.getEnemies()) {
                if (!enemy.alive) continue;
                if (enemy == win.mpProxy) continue;   // invisible PvP hitbox — never show its 999 HP bar

                Vector3f headPos = new Vector3f(
                        enemy.position.x,
                        enemy.position.y + 2.3f,    // just above model top
                        enemy.position.z);
                Vector3f toEnemy = new Vector3f(headPos).sub(eyePos);
                float dist = toEnemy.length();
                if (dist > 70f) continue;           // too far to matter

                org.joml.Vector4f clip = new org.joml.Vector4f(
                        headPos.x, headPos.y, headPos.z, 1f).mul(enemyVP);
                float ndcX = clip.w != 0f ? clip.x / clip.w : 2f;
                float ndcY = clip.w != 0f ? clip.y / clip.w : 2f;
                boolean onScreen = clip.w > 0.001f
                        && ndcX >= -1.0f && ndcX <= 1.0f && ndcY >= -1.0f && ndcY <= 1.0f;

                if (!onScreen) {
                    // Collect nearby off-screen threats for an edge arrow.
                    if (dist <= 50f && dist > 0.001f) {
                        Vector3f dir = new Vector3f(headPos).sub(eyePos).div(dist);
                        Vector3f up  = new Vector3f(right).cross(lookDir).normalize();
                        offscreen.add(new float[]{ dir.dot(right), -dir.dot(up), dist });
                    }
                    continue;
                }

                float sx = (ndcX * 0.5f + 0.5f) * screenW;
                float sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH;

                float hpFrac = Math.max(0f, enemy.health / enemy.maxHealth);
                float barW = 44f, barH = 6f;
                float barX = sx - barW / 2f, barY = sy - barH;

                // Downward chevron above the bar  -  makes enemies pop against terrain.
                float hurt = enemy.hitFlashTimer > 0f ? 1f : 0f;
                int chevCol = ImGui.colorConvertFloat4ToU32(1f, 0.25f + 0.6f * hurt, 0.2f, 0.95f);
                float chTop = barY - 16f, chMid = barY - 7f;
                draw.addTriangleFilled(sx - 7f, chTop, sx + 7f, chTop, sx, chMid, chevCol);

                // Background
                draw.addRectFilled(barX, barY, barX + barW, barY + barH,
                        ImGui.colorConvertFloat4ToU32(0.1f, 0f, 0f, 0.8f), 2f);
                // HP fill  -  green -> red as health drops
                float r = 0.15f + 0.85f * (1f - hpFrac);
                float g = 0.9f * hpFrac;
                draw.addRectFilled(barX, barY, barX + barW * hpFrac, barY + barH,
                        ImGui.colorConvertFloat4ToU32(r, g, 0.05f, 1.0f), 2f);
                draw.addRect(barX, barY, barX + barW, barY + barH,
                        ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.9f), 2f, 0, 1.2f);

                // HP numbers
                String hpStr = String.format("%.0f", enemy.health);
                draw.addText(sx - 7f, barY - 30f, black, hpStr);
                draw.addText(sx - 8f, barY - 31f,
                        ImGui.colorConvertFloat4ToU32(1f, 0.85f, 0.85f, 0.85f), hpStr);
            }

            // ── OFF-SCREEN ENEMY ARROWS (nearest few, pulsing red) ────────────
            offscreen.sort((a, b) -> Float.compare(a[2], b[2]));
            int arrows = Math.min(6, offscreen.size());
            float pulse = 0.55f + 0.45f * (float) Math.sin(marker * 4f);
            for (int i = 0; i < arrows; i++) {
                float[] o = offscreen.get(i);
                float angle  = (float) Math.atan2(o[1], o[0]);
                float radius = Math.min(ecx, ecy) * 0.80f;
                float ix = ecx + (float) Math.cos(angle) * radius;
                float iy = ecy + (float) Math.sin(angle) * radius;
                float closeness = 1f - Math.min(1f, o[2] / 50f);   // bigger when closer
                float s = 11f + closeness * 10f;
                float cosA = (float) Math.cos(angle), sinA = (float) Math.sin(angle);
                // Triangle pointing outward toward the threat.
                float[][] tri = {{ s, 0 }, { -s * 0.7f, s * 0.7f }, { -s * 0.7f, -s * 0.7f }};
                for (float[] p : tri) {
                    float rx = p[0] * cosA - p[1] * sinA, ry = p[0] * sinA + p[1] * cosA;
                    p[0] = ix + rx; p[1] = iy + ry;
                }
                int fill = ImGui.colorConvertFloat4ToU32(1f, 0.2f, 0.15f, 0.55f + 0.4f * pulse * closeness);
                draw.addTriangleFilled(tri[0][0], tri[0][1], tri[1][0], tri[1][1], tri[2][0], tri[2][1], fill);
                draw.addTriangle(tri[0][0], tri[0][1], tri[1][0], tri[1][1], tri[2][0], tri[2][1],
                        ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f), 1.5f);
            }
        }

        // ── ABILITY COOLDOWN ICONS ────────────────────────────────────────────
        if (!win.player.debugMode) {
            renderAbilityHUD(draw, screenW, screenH);
        }

        // ── WELCOME BANNER ───────────────────────────────────────────────────
        // Shown for ~6 s after the win.world first loads. Guides new players to F1.
        if (win.welcomeTimer > 0f && !win.showHelp) {
            renderWelcomeBanner(draw, screenW, screenH, win.welcomeTimer);
        }

        // Onboarding objective banner (top-centre)  -  guides new players.
        // Waits for the welcome banner to fade so they don't overlap.
        if (!win.showHelp && win.welcomeTimer <= 0f) {
            renderObjectiveBanner(draw, screenW, screenH);
        }

        // ── CONTEXTUAL HINT BANNER ────────────────────────────────────────────
        // One-liners that fire on first stand deploy / first seal placement.
        if (win.hintTimer > 0f && win.hintText != null && !win.showHelp) {
            renderHintBanner(draw, screenW, screenH, win.hintTimer, win.hintText);
        }

        // ── WAVE COUNTER ──────────────────────────────────────────────────────
        // Top-left: "WAVE N  -  next in Xs" or "MAX ENEMIES REACHED"
        if (!win.player.stand.isInStandPerspective() && !win.player.debugMode) {
            int waveNum = win.enemyManager.getWaveNumber();
            float waveTimer = win.enemyManager.getWaveTimer();
            int liveCount = (int) win.enemyManager.getEnemies().stream().filter(e -> e.alive).count();
            int maxCount = GameConfig.spawnMaxEnemies;

            String waveLabel;
            int waveColor;
            if (liveCount >= maxCount) {
                waveLabel = String.format("WAVE %d  ■ MAX ENEMIES (%d/%d)", waveNum, liveCount, maxCount);
                waveColor = ImGui.colorConvertFloat4ToU32(1.0f, 0.4f, 0.15f, 0.95f);
            } else if (waveNum == 0) {
                waveLabel = String.format("Next wave in %.0fs", waveTimer);
                waveColor = ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.65f);
            } else {
                waveLabel = String.format("WAVE %d   -  next in %.0fs  [%d alive]",
                        waveNum, waveTimer, liveCount);
                // Pulse red as the next wave approaches (< 8 s)
                float urgency = Math.max(0f, 1f - waveTimer / 8f);
                waveColor = ImGui.colorConvertFloat4ToU32(
                        0.75f + 0.25f * urgency,
                        0.85f - 0.50f * urgency,
                        0.85f - 0.75f * urgency,
                        0.85f);
            }
            draw.addText(12f, 13f, black, waveLabel);
            draw.addText(11f, 12f, waveColor, waveLabel);
        }

        // ── TIME SCALE INDICATOR ──────────────────────────────────────────────
        TimeController tc = TimeController.getInstance();
        if (tc.getSlownessFactor() > 0.05f || tc.getFastnessFactor() > 0.05f) {
            String timeLabel = tc.getSlownessFactor() > 0.05f
                    ? String.format("⧗ %.2fx", tc.getScale())
                    : String.format("⚡ %.1fx", tc.getScale());
            int timeColor = tc.getSlownessFactor() > 0.05f
                    ? ImGui.colorConvertFloat4ToU32(0.6f, 0.7f, 1.0f, 0.9f)
                    : ImGui.colorConvertFloat4ToU32(1.0f, 0.65f, 0.2f, 0.9f);
            draw.addText(screenW - 80f, 12f, black, timeLabel);
            draw.addText(screenW - 81f, 11f, timeColor, timeLabel);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ABILITY VISUAL RENDERING
    // ─────────────────────────────────────────────────────────────────────────

    void renderAbilityHUD(imgui.ImDrawList draw, float screenW, float screenH) {
        // ── Ability icon layout: two rows, bottom-right ───────────────────────
        // Row 1 (top): Q  E  F  G  Z  K  J    -  combat abilities + swap
        // Row 2 (bot): X  H  B  V              -  Stand + Seal + Substitute
        float iconSize = 28f;
        float spacing = 6f;
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.8f);
        int grey = ImGui.colorConvertFloat4ToU32(0.2f, 0.2f, 0.2f, 0.8f);

        // Row 1  -  only show abilities the player has unlocked
        {
            Progression prog = win.player.progression;
            String[] labels   = {"Q",    "F",     "M",        "O",    "L"   };
            String[] tooltips = {"Dash", "Slash", "Quagmire", "Grab", "Heal"};
            Progression.Ability[] gates = {
                Progression.Ability.DASH, Progression.Ability.SLASH,
                Progression.Ability.QUAGMIRE, Progression.Ability.GRAB,
                Progression.Ability.HEAL,
            };
            float quagFrac = (GameConfig.quagmireCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, 1f - win.quagmireCooldown / GameConfig.quagmireCooldown))
                    : 1f;
            float[] fracs = {
                    win.player.abilities.getDashCooldownFrac(),
                    win.player.attacks.getMeleeCooldownFrac(),
                    quagFrac,
                    win.player.grab.getCooldownFrac(),
                    win.player.abilities.getHealCooldownFrac(),
            };
            int[] colors = {
                    ImGui.colorConvertFloat4ToU32(0.45f, 0.88f, 1.0f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.55f, 0.06f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.55f, 0.82f, 0.20f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.20f, 0.15f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.15f, 0.85f, 0.35f, 1.0f),
            };

            // Build the visible subset
            java.util.List<Integer> vis = new java.util.ArrayList<>();
            for (int i = 0; i < labels.length; i++) if (prog.isUnlocked(gates[i])) vis.add(i);
            if (!vis.isEmpty()) {
                float totalW = vis.size() * iconSize + (vis.size() - 1) * spacing;
                float startX = screenW - totalW - 14f;
                float startY = screenH - iconSize * 3f - spacing * 2f - 14f;
                for (int slot = 0; slot < vis.size(); slot++) {
                    int i = vis.get(slot);
                    float x = startX + slot * (iconSize + spacing);
                    draw.addRectFilled(x, startY, x + iconSize, startY + iconSize, grey, 5f);
                    float fillH = iconSize * fracs[i];
                    if (fillH > 0.5f)
                        draw.addRectFilled(x, startY + iconSize - fillH, x + iconSize, startY + iconSize, colors[i], 5f);
                    draw.addRect(x, startY, x + iconSize, startY + iconSize, black, 5f, 0, 1.5f);
                    draw.addText(x + 9f, startY + 7f, black, labels[i]);
                    draw.addText(x + 9f, startY + 7f, ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.9f), labels[i]);
                    draw.addText(x, startY + iconSize + 2f, ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), tooltips[i]);
                }
            }
        }

        // ── ROW 2 (middle): RANGED ATTACKS  -  only unlocked ───────────────────
        {
            Progression prog = win.player.progression;
            boolean standDeployed = win.player.stand.isDeployed();
            float stoneFrac = win.isChargingStoneCanon
                    ? Math.min(1f, win.stoneCanonCharge / GameConfig.stoneCanonMaxCharge)
                    : ((GameConfig.stoneCanonCooldown > 0f)
                       ? Math.max(0f, Math.min(1f, 1f - win.stoneCanonCooldownTimer / GameConfig.stoneCanonCooldown))
                       : 1f);
            float lightFrac = win.player.lightning.isCharging()
                    ? win.player.lightning.getChargeFrac()
                    : win.player.lightning.getCooldownFrac();
            float lightA = win.player.lightning.isCharging()
                    ? 0.6f + (float) Math.sin(glfwGetTime() * 12) * 0.4f : 1.0f;

            String[] labelsR   = {"C",     "I",            "U",         "X"     };
            String[] tooltipsR = {"Snipe", "Stone Canon",  "Lightning", "Drone" };
            Progression.Ability[] gatesR = {
                Progression.Ability.SNIPE, Progression.Ability.STONE_CANON,
                Progression.Ability.LIGHTNING, Progression.Ability.STAND,
            };
            float[]  fracsR    = {
                    win.player.attacks.getSnipeIconFrac(), stoneFrac, lightFrac,
                    standDeployed ? 1f : win.player.stand.getRedeployCooldownFrac(),
            };
            int[] colorsR = {
                    ImGui.colorConvertFloat4ToU32(0.75f, 0.45f, 1.0f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.70f, 0.55f, 0.35f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.85f, 0.92f, 1.0f, lightA),
                    ImGui.colorConvertFloat4ToU32(1.0f,  0.82f, 0.15f, 1.0f),
            };
            boolean[] glowR = { false, win.isChargingStoneCanon, win.player.lightning.isCharging(), standDeployed };

            java.util.List<Integer> visR = new java.util.ArrayList<>();
            for (int i = 0; i < labelsR.length; i++) if (prog.isUnlocked(gatesR[i])) visR.add(i);
            if (!visR.isEmpty()) {
                float totalW = visR.size() * iconSize + (visR.size() - 1) * spacing;
                float startX = screenW - totalW - 14f;
                float startY = screenH - iconSize * 2f - spacing - 14f;
                for (int slot = 0; slot < visR.size(); slot++) {
                    int i = visR.get(slot);
                    float x = startX + slot * (iconSize + spacing);
                    boolean g = glowR[i];
                    draw.addRectFilled(x, startY, x + iconSize, startY + iconSize, grey, 5f);
                    float fillH = iconSize * Math.max(0f, Math.min(1f, fracsR[i]));
                    if (fillH > 0.5f) draw.addRectFilled(x, startY + iconSize - fillH, x + iconSize, startY + iconSize, colorsR[i], 5f);
                    draw.addRect(x, startY, x + iconSize, startY + iconSize, g ? colorsR[i] : black, 5f, 0, g ? 2.5f : 1.5f);
                    draw.addText(x + 9f, startY + 7f, black, labelsR[i]);
                    draw.addText(x + 9f, startY + 7f, ImGui.colorConvertFloat4ToU32(1f,1f,1f,0.9f), labelsR[i]);
                    draw.addText(x, startY + iconSize + 2f, ImGui.colorConvertFloat4ToU32(0.8f,0.8f,0.8f,0.7f), tooltipsR[i]);
                }
            }
        }

        // ── ROW 3 (bottom): SPATIAL / SPECIAL  -  only unlocked ────────────────
        {
            Progression prog = win.player.progression;
            float kamuiFrac = win.player.abilities.isKamui
                    ? win.player.abilities.kamuiTimer / GameConfig.kamuiMaxDuration
                    : win.player.abilities.getKamuiCooldownFrac();
            float subFrac = (GameConfig.substituteCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, win.substituteCooldown / GameConfig.substituteCooldown)) : 0f;
            float swapFrac = (GameConfig.todoCooldown > 0f)
                    ? Math.max(0f, Math.min(1f, 1f - win.todoSwapCooldown / GameConfig.todoCooldown)) : 1f;

            String[] labelsS   = {"Z",     "K",      "V",    "B",      "J"   };
            String[] tooltipsS = {"Kamui", "Pillar", "Sub",  "Seal",   "Swap" };
            Progression.Ability[] gatesS = {
                Progression.Ability.KAMUI, Progression.Ability.PILLAR,
                Progression.Ability.SUBSTITUTE, Progression.Ability.SEAL,
                Progression.Ability.SWAP,
            };
            float[]  fracsS    = {
                    kamuiFrac, win.player.abilities.getPillarCooldownFrac(),
                    win.substitutePrimed ? 1f : (1f - subFrac),
                    win.player.seals.getTeleportCooldownFrac(), swapFrac,
            };
            int[] colorsS = {
                    ImGui.colorConvertFloat4ToU32(0.85f, 0.0f,  0.9f,  1.0f),
                    ImGui.colorConvertFloat4ToU32(0.6f,  0.6f,  0.65f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.9f,  0.92f, 1.0f,  1.0f),
                    ImGui.colorConvertFloat4ToU32(0.1f,  0.75f, 0.65f, 1.0f),
                    ImGui.colorConvertFloat4ToU32(0.95f, 0.4f,  1.0f,  1.0f),
            };
            boolean[] glowS = { win.player.abilities.isKamui, false, win.substitutePrimed, false, false };

            java.util.List<Integer> visS = new java.util.ArrayList<>();
            for (int i = 0; i < labelsS.length; i++) if (prog.isUnlocked(gatesS[i])) visS.add(i);
            if (!visS.isEmpty()) {
                float totalW = visS.size() * iconSize + (visS.size() - 1) * spacing;
                float startX = screenW - totalW - 14f;
                float startY = screenH - iconSize - 14f;
                for (int slot = 0; slot < visS.size(); slot++) {
                    int i = visS.get(slot);
                    float x = startX + slot * (iconSize + spacing);
                    boolean g = glowS[i];
                    draw.addRectFilled(x, startY, x + iconSize, startY + iconSize, grey, 5f);
                    float fillH = iconSize * Math.max(0f, Math.min(1f, fracsS[i]));
                    if (fillH > 0.5f) draw.addRectFilled(x, startY + iconSize - fillH, x + iconSize, startY + iconSize, colorsS[i], 5f);
                    draw.addRect(x, startY, x + iconSize, startY + iconSize, g ? colorsS[i] : black, 5f, 0, g ? 2.5f : 1.5f);
                    draw.addText(x + 9f, startY + 7f, black, labelsS[i]);
                    draw.addText(x + 9f, startY + 7f, ImGui.colorConvertFloat4ToU32(1f,1f,1f,0.9f), labelsS[i]);
                    draw.addText(x, startY + iconSize + 2f, ImGui.colorConvertFloat4ToU32(0.8f,0.8f,0.8f,0.7f), tooltipsS[i]);
                }
            }
        }
    }

    /** Draws one horizontal row of ability cooldown icons. glow[i] = bright outline (active state). */
    private void drawIconRow(imgui.ImDrawList draw, String[] labels, String[] tooltips,
                             float[] fracs, int[] colors, boolean[] glow,
                             float startX, float startY, float iconSize, float spacing,
                             int black, int grey) {
        for (int i = 0; i < labels.length; i++) {
            float x = startX + i * (iconSize + spacing);
            draw.addRectFilled(x, startY, x + iconSize, startY + iconSize, grey, 5f);
            float fillH = iconSize * Math.max(0f, Math.min(1f, fracs[i]));
            if (fillH > 0.5f) {
                draw.addRectFilled(x, startY + iconSize - fillH,
                        x + iconSize, startY + iconSize, colors[i], 5f);
            }
            boolean g = glow != null && i < glow.length && glow[i];
            draw.addRect(x, startY, x + iconSize, startY + iconSize,
                    g ? colors[i] : black, 5f, 0, g ? 2.5f : 1.5f);
            draw.addText(x + 9f, startY + 7f, black, labels[i]);
            draw.addText(x + 9f, startY + 7f,
                    ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.9f), labels[i]);
            draw.addText(x, startY + iconSize + 2f,
                    ImGui.colorConvertFloat4ToU32(0.8f, 0.8f, 0.8f, 0.7f), tooltips[i]);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELP SCREEN  (F1)
    // ─────────────────────────────────────────────────────────────────────────

    void renderHelpScreen(float screenW, float screenH) {
        float winW = Math.min(720f, screenW - 40f);
        float winH = Math.min(screenH - 40f, screenH - 40f);
        ImGui.setNextWindowPos(screenW / 2f - winW / 2f, screenH / 2f - winH / 2f);
        ImGui.setNextWindowSize(winW, winH);
        ImGui.setNextWindowBgAlpha(0.94f);
        ImGui.begin("Controls & Abilities",
                imgui.flag.ImGuiWindowFlags.NoResize |
                        imgui.flag.ImGuiWindowFlags.NoMove);

        ImGui.textDisabled("  [F1] or [ESC] to close.   Scroll down for all sections.");
        ImGui.separator();
        ImGui.spacing();

        // ── YOUR ABILITIES (unlocked vs. still locked) ────────────────────────
        ImGui.textColored(1.0f, 0.85f, 0.25f, 1.0f, "YOUR ABILITIES");
        ImGui.separator();
        Progression prog = win.player.progression;
        for (Progression.Ability a : prog.allAbilities()) {
            if (prog.isUnlocked(a)) {
                helpRow(a.key, a.label + "  -  " + a.desc);
            } else {
                int wv = prog.unlockWaveOf(a);
                ImGui.textDisabled("  [ ? ]");
                ImGui.sameLine(230f);
                ImGui.pushTextWrapPos(0f);
                ImGui.textDisabled("Locked  -  survive wave " + wv + " to unlock.");
                ImGui.popTextWrapPos();
            }
        }
        ImGui.spacing();

        // ── MOVEMENT ──────────────────────────────────────────────────────────
        ImGui.textColored(0.5f, 0.9f, 1.0f, 1.0f, "MOVEMENT");
        ImGui.separator();
        helpRow("WASD", "Move. Double-tap W to sprint.");
        helpRow("Space", "Jump. Double-tap Space to toggle flight mode (must unlock FLIGHT first).");
        helpRow("[Shift] in air", "GROUND SLAM  -  hold Shift while falling, just before you land. Craters the terrain and damages nearby enemies. The bigger the fall, the bigger the crater.");
        helpRow("Shift (in air)", "While falling from height: slam into the ground. Craters the terrain and damages nearby enemies. Bigger fall = bigger crater.");
        ImGui.spacing();

        // ── FLIGHT ────────────────────────────────────────────────────────────
        ImGui.textColored(0.4f, 0.75f, 1.0f, 1.0f, "FLIGHT  (double-tap Space to enter / exit)");
        ImGui.separator();
        helpRow("[V]  Cycle mode", "Switch between SKIM -> SOAR -> GRAPPLE flight modes.");
        helpRow("SKIM", "Glide forward with gravity. Pitch your camera to angle up or down. Good for fast horizontal travel.");
        helpRow("SOAR", "Full 3D free-flight. WASD moves relative to your look direction. Space = up, Shift = down. No gravity.");
        helpRow("GRAPPLE", "Swing on a grapple line attached to the block you're looking at.");
        ImGui.spacing();

        // ── COMBAT ────────────────────────────────────────────────────────────
        ImGui.textColored(1.0f, 0.35f, 0.2f, 1.0f, "COMBAT");
        ImGui.separator();
        helpRow("[F]  Slash", "Wide melee swing. Hits every enemy in a cone in front of you.");
        helpRow("[C]  Snipe", "Hold to charge a crystal bolt, release to fire. Longer charge = bigger explosion on impact.");
        helpRow("[G]  Cannonball", "Hold to charge, release to launch yourself as a cannonball. Explodes on landing. A dotted arc previews your trajectory while charging.");
        helpRow("[U]  Lightning", "Strike the enemy you're aiming at with a lightning bolt. Double-tap [U] quickly for an area burst that hits all nearby enemies.");
        helpRow("[O]  Grab & Slam", "Grab the enemy in your crosshair (up to ~4.5 blocks away), hoist them overhead, then smash them into the ground. Creates a crater. Big damage.");
        ImGui.spacing();

        // ── MOVEMENT ABILITIES ────────────────────────────────────────────────
        ImGui.textColored(1.0f, 0.78f, 0.15f, 1.0f, "MOVEMENT ABILITIES");
        ImGui.separator();
        helpRow("[Q]  Dash", "Instant burst in the direction you're moving. Very short cooldown. Leaves a ghost trail behind you.");
        helpRow("[E]  Blink", "Teleport to the point you're looking at (up to ~22 blocks). Short cooldown.");
        helpRow("[K]  Pillar", "A stone spire erupts beneath your feet and launches you into the air.");
        ImGui.spacing();

        // ── SPECIAL ABILITIES ─────────────────────────────────────────────────
        ImGui.textColored(0.85f, 0.35f, 1.0f, 1.0f, "SPECIAL ABILITIES");
        ImGui.separator();
        helpRow("[Z]  Kamui", "Activate a space-time vortex around yourself. Distorts the whole screen. While active: hold LMB to charge an absorption ring that sucks in nearby enemies. Drains mana continuously  -  ends when mana runs out or you press Z again. Cooldown after use.");
        helpRow("[J]  Position Swap", "Instantly teleport-swap with the nearest enemy in range. Great for escaping a bad spot or dropping enemies off cliffs.");
        helpRow("[V]  Substitute", "(On foot, not in flight) Hold [V] to prime. The next hit you take is completely absorbed  -  you blink backward, a paper dummy appears at your old position, then explodes a moment later damaging nearby enemies.");
        helpRow("[M]  Quagmire", "Fire a mud wave at the enemy you're aiming at. It travels along the ground and traps them on contact  -  they cannot move for several seconds.");
        helpRow("[I]  Stone Canon", "Stand near stone blocks and hold [I] to charge. Nearby stone is absorbed into a massive projectile (you can't move while charging). Release to fire. The longer you charge, the more stone consumed and the bigger the explosion.");
        helpRow("[L]  Heal", "Hold [L] to channel healing energy. Restores health over time while held. You cannot move while channeling.");
        ImGui.spacing();

        // ── MANHATTAN TRANSFER (Stand / Drone) ────────────────────────────────
        ImGui.textColored(1.0f, 0.85f, 0.15f, 1.0f, "MANHATTAN TRANSFER  (Stand / Drone)");
        ImGui.separator();
        helpRow("[X]", "Deploy your drone directly above you. Press [X] again to recall it.");
        helpRow("[TAB]", "Enter the drone's perspective to pilot it manually. Press [TAB] again to return to your body.");
        helpRow("Piloting  -  WASD", "Fly the drone. Space = up, Shift = down.");
        helpRow("Piloting  -  click", "Fire a shot in whatever direction the drone is facing.");
        helpRow("Not piloting  -  click", "The drone auto-targets and fires at the nearest visible enemy.");
        helpRow("Two dots (top-right)", "Indicate line-of-sight: yours (left dot) and the drone's (right dot). Both green = clear shot.");
        helpRow("Gold diamond", "Shows your drone's position on screen. If it's off-screen, an arrow on the edge points toward it.");
        ImGui.spacing();

        // ── MINATO'S SEAL ─────────────────────────────────────────────────────
        ImGui.textColored(0.2f, 0.95f, 0.95f, 1.0f, "MINATO'S SEAL  (Teleport Anchors)");
        ImGui.separator();
        helpRow("[H]", "Throw a seal marker. It sticks to the first surface it touches.");
        helpRow("[B]", "Instantly teleport to the seal nearest your crosshair. The targeted seal glows to indicate the destination.");
        helpRow("[N]", "Pull the targeted seal back to your hand without teleporting.");
        helpRow("Tip", "Up to 5 seals active at once. Use them for escape routes, high-ground setup, and rapid repositioning. Off-screen seals show as arrows on the screen edge.");
        ImGui.spacing();

        // ── TIME CONTROL ──────────────────────────────────────────────────────
        ImGui.textColored(0.7f, 1.0f, 0.6f, 1.0f, "TIME DILATION");
        ImGui.separator();
        helpRow("[R]  Slow time", "Everything moves in slow motion (including enemies). Useful for dodging or lining up a shot.");
        helpRow("[Y]  Fast time", "Speeds up time  -  enemies and projectiles move faster. Use carefully.");
        ImGui.spacing();

        // ── WORLD & UI ────────────────────────────────────────────────────────
        ImGui.textColored(0.75f, 0.75f, 0.75f, 1.0f, "WORLD & BUILDING");
        ImGui.separator();
        helpRow("LMB (hold)", "Break the block you're looking at.");
        helpRow("RMB", "Place the block selected in your hotbar.");
        helpRow("1 – 9", "Select hotbar slot.");
        helpRow("[P]", "Spawn a test enemy at your crosshair. Enemies also spawn automatically in waves.");
        helpRow("[ESC]", "Open the pause menu.");
        helpRow("[F1]", "This screen.");
        helpRow("[F3]", "Debug overlay (position, FPS, time scale, render distance).");
        helpRow("[,]",  "'Quantum Bullet' (testing). Fire a bullet that PHASES through every wall, block and mountain — it doesn't stop. As it enters and leaves each surface it ripples the wall (expanding rings perpendicular to its flight) and it pierces enemies even through cover. A searing cyan-violet orb with a light trail.");
        helpRow("[']",  "'Deprivation Domain' — Water God Stance (testing). Tap ['] to lock yourself in place in a silent standoff. The world inside the " + (int)GameConfig.depRadius + "-block domain tints gold and hyper-real; outside turns cool and distant. The instant ANY enemy or projectile moves inside, a chaotic 'slash storm' of anime sword-crescents erupts around you in every direction (a 360° dome of light-speed swings) and the victim bursts apart into white-hot voxel chunks that char as they fall. Stand perfectly still and let the horde shred itself. Lasts " + (int)GameConfig.depDuration + " s; press ['] again to exit. Tune via GameConfig (dep*). Inspired by Mushoku Tensei's Deprivation Field + Soul Knight's AoE sweeps.");
        helpRow("[K]",  "'Chocolate Disco' grid (testing). Aim at the ground and press K to spawn a 9×9 glowing geometry grid. LMB marks/unmarks a cell (the cell becomes a gold wireframe box). Press K again to DETONATE all marked cells — white-hot light sears from the wireframe edges, a column of light shoots skyward, and all blocks/enemies inside are destroyed. Press K with nothing marked to dismiss the grid. Grid is horizontal and always visible through terrain.");
        helpRow("[F5]", "Warp to the 'Canyon' (testing). Teleports you to a warm mesa region (banded terracotta cliffs, arches, turquoise pools). Fly ~450 blocks north for the BLUE version (snow biome — blue-grey rock, white tops). Press [F5] again to return. Press [V] mid-flight to cycle SKIM → SOAR → GRAPPLE.");
        helpRow("[F6]", "Non-Euclidean 'Layered Rooms' (testing). Teleports you into a 2x2 building with a central pillar. Walk CLOCKWISE around the pillar and you pass through endless distinct rooms (1,2,3,4,5,6...) instead of looping after four. Press [F6] again to leave.");
        helpRow("[F10]", "'Radar Sweep' (testing). Your world becomes a 3D radar scope from where you stand — range rings + bearing spokes on the terrain, a rotating sweep arm with an opaque→transparent afterglow that pings the landscape, and a wireframe box around every enemy that flares as the arm passes it. One ~8s scan, then back to normal.");
        helpRow("[F8]", "'The World' (testing). A sphere of photographic-negative reality bursts from your feet — everything inside inverts and shifts to electric blue, enemies and projectiles freeze in place while you keep moving, then the domain collapses back. DIO's time stop.");
        helpRow("[F7]", "'Orbital Annihilation' (testing). Aim at a block and press F7 for a ~12s fully-3D cinematic: a spinning gyroscope of energy rings + pulsing core, accelerating implosion rings that detonate, a blackout with the terrain glowing as a wireframe scan + floating embers, a CARVED crater, then a volumetric orbital laser with helix beams, shockwave, and debris. Stand back.");
        ImGui.spacing();

        // ── MANA & COOLDOWNS ──────────────────────────────────────────────────
        ImGui.textColored(0.4f, 0.6f, 1.0f, 1.0f, "MANA & RESOURCES");
        ImGui.separator();
        helpRow("Mana bar", "Blue bar below your health. Most abilities cost mana. Regenerates slowly over time.");
        helpRow("Cooldown icons", "The ability bar at the bottom shows each ability's cooldown as a filling overlay. Grayed-out = on cooldown or not enough mana.");
        ImGui.spacing();

        // ── ENEMY TYPES ───────────────────────────────────────────────────────
        ImGui.textColored(0.9f, 0.3f, 0.1f, 1.0f, "ENEMIES");
        ImGui.separator();
        helpRow("Green tint", "ZOMBIE  -  slow melee chaser. Closes in and bites. Low damage per hit but relentless. Appears in large numbers early on.");
        helpRow("Bone-white", "THROWER  -  skeleton archer. Keeps its distance and pelts you with projectiles. Kill it before it repositions.");
        helpRow("Blue-grey (large)", "GOLEM  -  heavily armored tank. Slow but extremely tanky. Slams the ground for AoE damage and throws boulders. Appears from wave 3.");
        helpRow("Health bars", "Float above every enemy. Green = healthy, yellow = damaged, red = near death.");
        helpRow("Waves", "Enemies spawn in escalating waves. The wave number shows in the top-left. Later waves bring more Golems.");

        ImGui.end();
    }

    /**
     * Single row in the help screen: yellow key label on the left,
     * wrapped description on the right. Fixed column split at 230px.
     */
    private void helpRow(String key, String desc) {
        ImGui.textColored(1.0f, 0.88f, 0.25f, 1.0f, "  " + key);
        ImGui.sameLine(230f);
        ImGui.pushTextWrapPos(0f);   // wrap at window right edge
        ImGui.text(desc);
        ImGui.popTextWrapPos();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TUTORIAL BANNERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "Press F1 for controls" banner shown for the first few seconds of play.
     * Fades in over 0.4 s and out over 1 s.
     */
    // ── Onboarding objective banner (top-centre) ──────────────────────────────
    void renderObjectiveBanner(imgui.ImDrawList draw, float screenW, float screenH) {
        com.leaf.game.core.TutorialManager t = win.tutorial;

        float cx = screenW * 0.5f;
        float y  = 50f;
        int shadow   = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, 0.85f);
        int titleCol = ImGui.colorConvertFloat4ToU32(1.0f, 0.85f, 0.4f, 1.0f);
        int bodyCol  = ImGui.colorConvertFloat4ToU32(0.92f, 0.95f, 1.0f, 0.97f);
        int dimCol   = ImGui.colorConvertFloat4ToU32(0.7f, 0.75f, 0.85f, 0.8f);
        int keyCol   = ImGui.colorConvertFloat4ToU32(0.45f, 0.9f, 1.0f, 1.0f);
        int panelBg  = ImGui.colorConvertFloat4ToU32(0.05f, 0.07f, 0.12f, 0.62f);

        // ── TUTORIAL ACTIVE: staged lesson banner ────────────────────────────
        if (t != null && t.isActive()) {
            String prog  = "STEP " + t.stepNumber() + " / " + t.stepCount();
            String title = t.title();
            String instr = t.instruction();
            String key   = t.keyHint();

            // Measure the widest line so the panel fits all of them.
            imgui.ImVec2 ps = ImGui.calcTextSize(prog);
            imgui.ImVec2 ts = ImGui.calcTextSize(title);
            imgui.ImVec2 is = ImGui.calcTextSize(instr);
            float keyW = (key != null) ? ImGui.calcTextSize("[ " + key + " ]").x : 0f;
            float maxW = Math.max(Math.max(ps.x, ts.x), Math.max(is.x, keyW));
            float pad  = 18f;
            float top  = y - 8f;
            float lineH = ts.y;
            int   lines = 3 + (key != null ? 1 : 0);
            float bot  = top + lines * (lineH + 4f) + 12f;
            draw.addRectFilled(cx - maxW / 2 - pad, top, cx + maxW / 2 + pad, bot, panelBg, 8f);

            // progress line
            draw.addText(cx - ps.x / 2 + 1, y + 1, shadow, prog);
            draw.addText(cx - ps.x / 2,     y,     dimCol, prog);
            y += lineH + 4f;
            // title
            draw.addText(cx - ts.x / 2 + 1, y + 1, shadow, title);
            draw.addText(cx - ts.x / 2,     y,     titleCol, title);
            y += lineH + 4f;
            // instruction
            draw.addText(cx - is.x / 2 + 1, y + 1, shadow, instr);
            draw.addText(cx - is.x / 2,     y,     bodyCol, instr);
            y += lineH + 4f;
            // key hint
            if (key != null) {
                String kk = "[ " + key + " ]";
                imgui.ImVec2 ks = ImGui.calcTextSize(kk);
                draw.addText(cx - ks.x / 2 + 1, y + 1, shadow, kk);
                draw.addText(cx - ks.x / 2,     y,     keyCol, kk);
            }

            // skip hint (bottom-centre, unobtrusive)
            String skip = "[F2] skip tutorial";
            imgui.ImVec2 ss = ImGui.calcTextSize(skip);
            draw.addText(cx - ss.x / 2 + 1, bot + 5f, shadow, skip);
            draw.addText(cx - ss.x / 2,     bot + 4f,
                    ImGui.colorConvertFloat4ToU32(0.6f, 0.62f, 0.7f, 0.6f), skip);
            return;
        }

        // ── TUTORIAL DONE: minimal survival banner ───────────────────────────
        int km = win.enemyManager != null ? win.enemyManager.totalKills : 0;
        String hint = "Survive the waves    -    [F1] for all controls";
        imgui.ImVec2 hs = ImGui.calcTextSize(hint);
        draw.addText(cx - hs.x / 2 + 1, y + 1, shadow, hint);
        draw.addText(cx - hs.x / 2,     y,     bodyCol, hint);
        String kills = "Defeated: " + km;
        imgui.ImVec2 ks = ImGui.calcTextSize(kills);
        draw.addText(cx - ks.x / 2 + 1, y + hs.y + 7f, shadow, kills);
        draw.addText(cx - ks.x / 2,     y + hs.y + 6f,
                ImGui.colorConvertFloat4ToU32(1.0f, 0.6f, 0.5f, 0.9f), kills);
    }

    void renderWelcomeBanner(imgui.ImDrawList draw,
                             float screenW, float screenH, float timer) {
        float alpha;
        if (timer > 5.6f) alpha = (6f - timer) / 0.4f;   // fade-in 0->0.4 s
        else if (timer < 1.0f) alpha = timer;                   // fade-out last 1 s
        else alpha = 1f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        int bg = ImGui.colorConvertFloat4ToU32(0.04f, 0.04f, 0.14f, 0.90f * alpha);
        int border = ImGui.colorConvertFloat4ToU32(0.4f, 0.85f, 1.0f, 0.55f * alpha);
        int white = ImGui.colorConvertFloat4ToU32(1.0f, 1.0f, 1.0f, alpha);
        int cyan = ImGui.colorConvertFloat4ToU32(0.4f, 0.9f, 1.0f, alpha);
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, alpha);

        float bW = 400f, bH = 56f;
        float bX = screenW / 2f - bW / 2f;
        float bY = screenH * 0.19f;

        draw.addRectFilled(bX, bY, bX + bW, bY + bH, bg, 8f);
        draw.addRect(bX, bY, bX + bW, bY + bH, border, 8f, 0, 1.8f);

        String line1 = "You have lots of abilities!";
        String line2 = "Press  [F1]  for the full controls & ability guide.";

        // Approximate centering (default font ~7 px/char)
        draw.addText(bX + bW / 2f - line1.length() * 3.6f, bY + 8f, black, line1);
        draw.addText(bX + bW / 2f - line1.length() * 3.6f - 1, bY + 7f, white, line1);
        draw.addText(bX + bW / 2f - line2.length() * 3.6f, bY + 29f, black, line2);
        draw.addText(bX + bW / 2f - line2.length() * 3.6f - 1, bY + 28f, cyan, line2);
    }

    /**
     * One-liner contextual hint (e.g. first stand deploy or first seal placed).
     * Fades out over the last 0.6 s of its duration.
     */
    void renderHintBanner(imgui.ImDrawList draw,
                          float screenW, float screenH,
                          float timer, String text) {
        float alpha = (timer < 0.6f) ? timer / 0.6f : 1f;
        alpha = Math.max(0f, Math.min(1f, alpha));

        int bg = ImGui.colorConvertFloat4ToU32(0.0f, 0.0f, 0.0f, 0.72f * alpha);
        int fg = ImGui.colorConvertFloat4ToU32(1.0f, 0.88f, 0.3f, alpha);
        int black = ImGui.colorConvertFloat4ToU32(0f, 0f, 0f, alpha);

        // Rough text width: 7 px/char
        float tw = text.length() * 7.0f;
        float bX = screenW / 2f - tw / 2f - 12f;
        float bY = 36f; // just below the wave counter line

        draw.addRectFilled(bX, bY, bX + tw + 24f, bY + 22f, bg, 4f);
        draw.addText(bX + 13f, bY + 5f, black, text);
        draw.addText(bX + 12f, bY + 4f, fg, text);
    }


    void renderDebugMenu() {
        ImGui.begin("Debug");
        ImGui.text(String.format("XYZ: %.3f / %.3f / %.3f",
                win.player.position.x, win.player.position.y, win.player.position.z));
        ImGui.text(String.format("FPS: %.1f", ImGui.getIO().getFramerate()));
        ImGui.text(String.format("Seed: %d", GameConfig.seed));
        ImGui.text(String.format("DeltaTime: %.4fs", ImGui.getIO().getDeltaTime()));
        ImGui.text(String.format("TimeScale: %.3f", TimeController.getInstance().getScale()));
        if (win.player.debugMode) {
            ImGui.text("Flight mode: " + win.player.flightController.getMode().name());
        }
        ImGui.separator();
        float[] fov = {GameConfig.fov};
        if (ImGui.sliderFloat("FOV", fov, 30f, 120f)) GameConfig.fov = fov[0];
        int[] rd = {GameConfig.renderDistance};
        if (ImGui.sliderInt("Render Distance", rd, 2, 16)) GameConfig.renderDistance = rd[0];
        ImGui.separator();
        // ── Seed editor ───────────────────────────────────────────────────────
        ImGui.text("World Seed:");
        ImGui.setNextItemWidth(140);
        // Pre-fill with current seed on first open
        if (win.seedInput.get().isEmpty()) win.seedInput.set(String.valueOf(GameConfig.seed));
        ImGui.inputText("##seedEdit", win.seedInput);
        ImGui.sameLine();
        if (ImGui.button("New World")) {
            try {
                long newSeed = Long.parseLong(win.seedInput.get().trim());
                GameConfig.seed = newSeed;
                win.worldGen.resetSeed(newSeed);
                win.world.clearAllChunks();
                win.world.meshingQueue.clear();
                win.player.position.set(2000f, 250f, 2000f);
                win.isPreloading = true;
                // Close debug menu so the preload screen shows
                win.showDebug = false;
                glfwSetInputMode(win.window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            } catch (NumberFormatException ignored) {
                // Bad input  -  do nothing
            }
        }
        ImGui.end();
    }

    // --- FILE: ./main/java/com/leaf/game/core/WindowHud.java ---
// (Replace the updateBreaking method with this updated version)

    void updateBreaking(float deltaTime) {
        if (!win.breakingActive || win.lastTarget == null || !win.lastTarget.hit) {
            win.breakProgress = 0.0f;
            win.digParticleTimer = 0.0f; // Reset particle timer when not digging
            return;
        }
        int tx = win.lastTarget.hitX, ty = win.lastTarget.hitY, tz = win.lastTarget.hitZ;
        if (tx != win.breakX || ty != win.breakY || tz != win.breakZ) {
            win.breakProgress = 0.0f;
            win.breakX = tx;
            win.breakY = ty;
            win.breakZ = tz;
            win.digSoundTimer = 0f;
            win.digPreDelay = 0f;
            win.digParticleTimer = 0.0f; // Reset particle timer on target change
        }
        Block target = win.world.getBlock(tx, ty, tz);
        if (!target.isSolid()) {
            win.breakProgress = 0.0f;
            return;
        }

        win.breakProgress += deltaTime / target.hardness;

        // ── CONTINUOUS DIG PARTICLES (Debris Spray) ──────────────────────────
        // Spawns small block fragments flying out from the digging location
        // to provide solid, responsive visual feedback without drawing textures.
        final float DIG_START_DELAY = 0.18f;
        win.digPreDelay += deltaTime;
        if (win.digPreDelay >= DIG_START_DELAY) {
            win.digParticleTimer -= deltaTime;
            if (win.digParticleTimer <= 0f) {
                // Spawn a particle at a random offset on the block's face
                float px = tx + 0.5f + (win.shakeRng.nextFloat() - 0.5f) * 0.4f;
                float py = ty + 0.5f + (win.shakeRng.nextFloat() - 0.5f) * 0.4f;
                float pz = tz + 0.5f + (win.shakeRng.nextFloat() - 0.5f) * 0.4f;

                // Launch velocity: fly upward and slightly outward
                Vector3f vel = new Vector3f(
                        (win.shakeRng.nextFloat() - 0.5f) * 2.5f,
                        1.5f + win.shakeRng.nextFloat() * 2.5f,
                        (win.shakeRng.nextFloat() - 0.5f) * 2.5f
                );

                win.droppedItems.add(new DroppedItem(tx, ty, tz, target, vel));
                // Fire a new particle every 0.15 seconds
                win.digParticleTimer = 0.15f;
            }

            // ── DIG SOUND ────────────────────────────────────────────────────
            win.digSoundTimer -= deltaTime;
            if (win.digSoundTimer <= 0f) {
                String digSnd = Window.blockDigSound(target);
                if ("crystal_clank_seq".equals(digSnd)) {
                    digSnd = win.nextCrystalClank();
                }
                if (digSnd != null) AudioManager.playVaried(digSnd, 0.65f, 0.08f);
                win.digSoundTimer = 0.30f + Math.min(0.20f, target.hardness * 0.08f);
            }
        }

        if (win.breakProgress >= 1.0f) {
            win.droppedItems.add(new DroppedItem(tx, ty, tz, target));
            win.world.setBlock(tx, ty, tz, Block.AIR);
            win.world.rebuildChunkAt(tx, ty, tz);
            String breakSnd = Window.blockBreakSound(target);
            if (breakSnd != null) AudioManager.playVaried(breakSnd, 0.85f, 0.07f);
            win.breakProgress = 0.0f;
            win.digSoundTimer = 0f;
            win.digPreDelay = 0f;
            win.digParticleTimer = 0.0f;
        }
    }

    // ── SNOW PARTICLE OVERLAY ─────────────────────────────────────────────────
    // Screen-space procedural snowfall drawn with ImGui foreground draw list.
    // Uses a deterministic hash (no per-flake Java state)  -  N seeds produce N
    // stable screen-position trajectories that drift downward + windward over time.
    //
    //  intensity  0..1  driven by altitude above snowAltitude
    //  gustStrength 0..1  tilts snow sideways when wind gusts
    //  gustAngle  radians  direction of horizontal tilt
    // ─────────────────────────────────────────────────────────────────────────
    public void renderSnowEffect(float screenW, float screenH,
                                  float timeAccum, float intensity,
                                  float gustStrength, float gustAngle) {
        if (intensity < 0.02f) return;

        var draw = ImGui.getForegroundDrawList();

        // 3 depth layers: {sizeMin, sizeMax, fallSpeed px/s, timeScale, count}
        // Far = tiny/slow (background haze), near = large/fast (immersive)
        float[][] layers = {
            { 0.8f, 1.4f,  22f, 0.60f, 60 },
            { 1.3f, 2.2f,  42f, 1.00f, 55 },
            { 2.0f, 3.5f,  72f, 1.55f, 35 }
        };

        // Wind drift: how far horizontally per second of accumulated time
        float driftX = (float)Math.cos(gustAngle) * gustStrength * 55f;
        float driftY = (float)Math.sin(gustAngle) * gustStrength * 18f;

        for (int li = 0; li < layers.length; li++) {
            float sMin  = layers[li][0];
            float sMax  = layers[li][1];
            float speed = layers[li][2];
            float ts    = layers[li][3];
            int   count = (int)(layers[li][4] * intensity);

            float t = timeAccum * ts;

            for (int i = 0; i < count; i++) {
                int seed = i + li * 1000;

                // Deterministic base position + motion parameters from seed
                float bx    = fhash(seed * 7)     * screenW;
                float by    = fhash(seed * 7 + 1) * screenH;
                float spd   = speed  + fhash(seed * 7 + 2) * speed * 0.35f;
                float phase = fhash(seed * 7 + 3) * 6.2832f;  // twinkle phase
                float size  = sMin   + fhash(seed * 7 + 4) * (sMax - sMin);
                float sway  = fhash(seed * 7 + 5) * 28f - 14f; // subtle horizontal sway

                // Current screen position (wraps around both axes)
                float cy = (by + spd * t) % (screenH + size * 4);
                if (cy < 0) cy += screenH + size * 4;
                float cx = bx + (driftX + sway) * t * 0.055f;
                cx = ((cx % screenW) + screenW) % screenW;

                // Slight shimmer
                float twinkle = 0.72f + 0.28f * (float)Math.sin(timeAccum * 2.4f + phase);
                // Near-edge fade so flakes don't pop in/out at screen border
                float edgeFade = edgeFade(cx, cy, screenW, screenH, size * 6);
                float a = intensity * twinkle * edgeFade;

                int col = ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, a);
                draw.addCircleFilled(cx, cy, size, col);
            }
        }
    }

    /** Deterministic float hash in [0,1] from an integer seed. */
    private float fhash(int n) {
        n = (n ^ 0xB5297A4D) ^ (n >> 14);
        n *= 0x68E31DA4;
        n ^= n >> 14;
        n *= 0x1B56C4E9;
        n ^= n >> 16;
        return ((n >>> 8) & 0xFFFF) / 65535f;
    }

    /** Returns 0..1 fade that is 1 in the interior and 0 near the screen edge. */
    private float edgeFade(float cx, float cy, float w, float h, float margin) {
        float ex = Math.min(cx, w - cx) / margin;
        float ey = Math.min(cy, h - cy) / margin;
        float t  = Math.min(1f, Math.min(ex, ey));
        return t * t * (3f - 2f * t); // smoothstep
    }

    void renderChocolateDiscoConsole() {
        if (!win.showDiscoUI) return;

        // Position on the left side of the screen
        ImGui.setNextWindowPos(30, 100, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.begin("Chocolate Disco Datapad", imgui.flag.ImGuiWindowFlags.AlwaysAutoResize | imgui.flag.ImGuiWindowFlags.NoCollapse);

        ImGui.textColored(1.0f, 0.8f, 0.1f, 1.0f, "MODE: ANNIHILATE");
        ImGui.separator();
        ImGui.spacing();

        // X-Axis Headers
        ImGui.text("   "); ImGui.sameLine();
        for (int c = 0; c < 9; c++) {
            ImGui.text(" " + (c + 1) + " "); ImGui.sameLine();
        }
        ImGui.newLine();

        // Reset hover state (updated only if hovered this frame)
        win.cdHoverR = -1;
        win.cdHoverC = -1;

        // 9x9 Grid of ImGui Buttons
        for (int r = 0; r < 9; r++) {
            // Y-Axis Headers
            ImGui.text("" + (char)('A' + r) + " "); ImGui.sameLine();

            for (int c = 0; c < 9; c++) {
                if (c > 0) ImGui.sameLine();
                ImGui.pushID(r * 9 + c);

                boolean marked = win.cdMarked[r][c];
                boolean det    = win.cdDetT[r][c] > 0;

                // Color the buttons based on their state
                if (det) {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 1.0f, 0.2f, 0.2f, 1.0f);
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 1.0f, 0.4f, 0.4f, 1.0f);
                } else if (marked) {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 1.0f, 0.7f, 0.1f, 1.0f);
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 1.0f, 0.85f, 0.3f, 1.0f);
                } else {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.15f, 0.15f, 0.2f, 1.0f);
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.2f, 0.8f, 0.9f, 1.0f);
                }

                if (ImGui.button("##cell", 28f, 28f)) {
                    if (!det) {
                        win.cdMarked[r][c] = !marked;
                        win.cdMeshDirty = true;
                    }
                }

                if (ImGui.isItemHovered()) {
                    win.cdHoverR = r;
                    win.cdHoverC = c;
                    win.cdMeshDirty = true;
                }

                ImGui.popStyleColor(2);
                ImGui.popID();
            }
            ImGui.newLine();
        }

        ImGui.spacing(); ImGui.separator(); ImGui.spacing();

        if (ImGui.button("EXECUTE", 280f, 40f)) {
            win.detonateDiscoGrid(win.world);
        }
        ImGui.spacing();
        if (ImGui.button("DISMISS", 280f, 30f)) {
            win.dismissDiscoGrid();
        }

        ImGui.end();
    }
    // ═══════════════════════════════════════════════════════════════════════════
    //  CHOCOLATE DISCO: IMGUI WRIST-CONSOLE
    // ═══════════════════════════════════════════════════════════════════════════

    enum DiscoMode { ANNIHILATE, DROP, REDIRECT }
    DiscoMode currentDiscoMode = DiscoMode.ANNIHILATE;

    private void handleDiscoClick(int r, int c) {
        if (currentDiscoMode == DiscoMode.ANNIHILATE) {
            // Mode 1: Toggle the Mark for Detonation
            if (win.cdDetT[r][c] <= 0f) {
                win.cdMarked[r][c] = !win.cdMarked[r][c];
                win.cdMeshDirty = true;
            }
        }
        else if (currentDiscoMode == DiscoMode.DROP) {
            // Mode 2: Call down an orbital strike of your currently selected block!
            float wx = win.cdGridX - Window.CD_HALF + c * Window.CD_CELL + Window.CD_CELL * 0.5f;
            float wz = win.cdGridZ - Window.CD_HALF + r * Window.CD_CELL + Window.CD_CELL * 0.5f;
            float wy = win.cdGridY + 40f; // Drop from the sky

            com.leaf.game.entity.DroppedItem item = new com.leaf.game.entity.DroppedItem(
                    (int)wx, (int)wy, (int)wz, win.selectedBlock, new org.joml.Vector3f(0f, -40f, 0f) // Heavy downward slam velocity
            );
            win.droppedItems.add(item);
            com.leaf.game.core.AudioManager.play("fall_hit", 0.8f);
        }
        else if (currentDiscoMode == DiscoMode.REDIRECT) {
            // Mode 3: Instantly warp all active projectiles to this cell
            float wx = win.cdGridX - Window.CD_HALF + c * Window.CD_CELL + Window.CD_CELL * 0.5f;
            float wz = win.cdGridZ - Window.CD_HALF + r * Window.CD_CELL + Window.CD_CELL * 0.5f;

            boolean redirected = false;

            // Redirect player Void Shards
            for (com.leaf.game.entity.AttackController.ActiveBolt bolt : win.player.attacks.activeBolts) {
                bolt.pos.set(wx, win.cdGridY + 1.5f, wz);
                redirected = true;
            }
            // Redirect enemy boulders/projectiles
            for (com.leaf.game.entity.EnemyManager.EnemyProjectile proj : win.enemyManager.projectiles) {
                proj.pos.set(wx, win.cdGridY + 1.5f, wz);
                redirected = true;
            }

            if (redirected) {
                com.leaf.game.core.AudioManager.play("snipe_redirect", 1.0f);
                com.leaf.game.core.ScreenEffectManager.INSTANCE.flash(0f, 0.8f, 1.0f, 0.4f, 0.1f);
            }
        }
    }
}
