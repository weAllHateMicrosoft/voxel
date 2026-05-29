package com.leaf.game.anim;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.*;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.*;

/**
 * In-game animation editor.  Toggle with F8.
 *
 * ── Quick-start ───────────────────────────────────────────────────────────────
 * 1. Press F8 to open the editor.
 * 2. Pick a model from the Model dropdown (auto-loaded from resources/models/).
 * 3. Pick or create a clip from the Clip dropdown.
 * 4. Click a part in the tree on the left.
 * 5. Drag the rotation/translation sliders on the right to pose the part.
 * 6. Scrub the timeline and click "+ Add Keyframe" to record it.
 * 7. Hit Play in the 3D preview to see your animation.
 * 8. Click "Save Model" to write back to the JSON file.
 *
 * ── Integration notes ─────────────────────────────────────────────────────────
 * Use AnimPlayer + ModelRenderer at runtime:
 *
 *   // In entity constructor:
 *   AnimModel model = AnimModel.loadFromClasspath("enemy_basic");
 *   AnimPlayer ap   = new AnimPlayer(model);
 *   ap.play("walk");
 *
 *   // Each frame:
 *   ap.tick(dt);
 *   ModelRenderer.render(model, ap.getPose(), worldMatrix, view, proj);
 */
public class AnimEditor {

    // ── Visibility ───────────────────────────────────────────────────────────
    public boolean visible = false;

    // ── Loaded models ────────────────────────────────────────────────────────
    private final List<AnimModel> loadedModels = new ArrayList<>();
    private final ImInt  modelImIdx = new ImInt(0);
    private final ImInt  clipImIdx  = new ImInt(0);
    private String selectedPart = null;

    // ── Preview playback ─────────────────────────────────────────────────────
    private float   previewTime    = 0f;
    private boolean previewPlaying = false;
    private AnimPlayer previewPlayer;

    // ── Preview FBO ──────────────────────────────────────────────────────────
    private int previewFbo = 0, previewTex = 0, previewRbo = 0;
    private static final int PREV_W = 360, PREV_H = 300;

    // ── Orbit camera ─────────────────────────────────────────────────────────
    private float orbitYaw   =  30f;
    private float orbitPitch = -15f;
    private float orbitDist  =   4f;

    // ── Edit buffers (3-element for dragFloat3 / colorEdit3) ─────────────────
    private final float[] editRot    = {0f, 0f, 0f};   // rx,ry,rz degrees
    private final float[] editTrans  = {0f, 0f, 0f};   // tx,ty,tz
    private final float[] editSize   = {0.5f, 0.5f, 0.5f};
    private final float[] editPivot  = {0f, 0f, 0f};
    private final float[] editOrigin = {0f, 0f, 0f};
    private final float[] editColor  = {0.7f, 0.7f, 0.7f};

    // ── String inputs for new items ──────────────────────────────────────────
    private final ImString newModelName = new ImString(32);
    private final ImString newClipName  = new ImString(32);
    private final ImString newPartName  = new ImString(32);

    // ── Clip loop toggle ─────────────────────────────────────────────────────
    private final ImBoolean loopBool = new ImBoolean(true);

    // ── Duration edit ────────────────────────────────────────────────────────
    private final float[] editDur = {1f};

    // ── Timeline cursor ──────────────────────────────────────────────────────
    private final float[] timelineCursor = {0f};

    // ─────────────────────────────────────────────────────────────────────────
    //  Init / cleanup
    // ─────────────────────────────────────────────────────────────────────────

    public void init() {
        ModelRenderer.init();
        createPreviewFbo();
        reloadModelList();
    }

    public void cleanup() {
        destroyPreviewFbo();
        ModelRenderer.cleanup();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main render (called every frame when visible)
    // ─────────────────────────────────────────────────────────────────────────

    public void render(float dt) {
        if (!visible) return;

        if (previewPlaying && previewPlayer != null) {
            previewPlayer.tick(dt);
            previewTime = previewPlayer.getCurrentTime();
            timelineCursor[0] = previewTime;
        }

        renderPreviewFbo();

        ImGui.setNextWindowSize(1020, 660, ImGuiCond.Once);
        ImGui.setNextWindowPos(20, 20, ImGuiCond.Once);
        ImGui.begin("Animation Editor  [F8]", ImGuiWindowFlags.NoCollapse);

        drawToolbar();
        ImGui.separator();

        float totalW = ImGui.getContentRegionAvailX();
        float leftW  = 195f;
        float midW   = PREV_W + 16f;
        float rightW = Math.max(50f, totalW - leftW - midW - 24f);

        ImGui.beginChild("##left",  leftW,  0, true);
        drawLeftPanel();
        ImGui.endChild();

        ImGui.sameLine();

        ImGui.beginChild("##mid",   midW,   0, true);
        drawPreviewPanel();
        ImGui.endChild();

        ImGui.sameLine();

        ImGui.beginChild("##right", rightW, 0, true);
        drawRightPanel();
        ImGui.endChild();

        ImGui.end();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Toolbar
    // ─────────────────────────────────────────────────────────────────────────

    private void drawToolbar() {
        if (ImGui.button("Save")) saveCurrentModel();
        if (ImGui.isItemHovered()) ImGui.setTooltip("Save model to src/main/resources/models/<name>.json");
        ImGui.sameLine();
        if (ImGui.button("Reload")) reloadModelList();
        ImGui.sameLine();
        if (ImGui.button("+ Model")) ImGui.openPopup("##newmodel");

        if (ImGui.beginPopup("##newmodel")) {
            ImGui.inputText("Name", newModelName);
            if (ImGui.button("Create") && newModelName.getLength() > 0) {
                AnimModel m = AnimEditor.createDefaultEnemyModel();
                m.name = newModelName.get();
                loadedModels.add(m);
                modelImIdx.set(loadedModels.size() - 1);
                onModelChanged();
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        ImGui.sameLine();
        ImGui.textDisabled("  F8 = close  |  Drag sliders to pose  |  Add KF to record  |  Save to write JSON");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Left panel: model/clip selectors, part tree
    // ─────────────────────────────────────────────────────────────────────────

    private void drawLeftPanel() {
        // Model selector
        ImGui.text("Model");
        String[] mNames = loadedModels.stream().map(m -> m.name).toArray(String[]::new);
        if (mNames.length == 0) mNames = new String[]{"(none)"};
        int prevM = modelImIdx.get();
        if (ImGui.combo("##m", modelImIdx, mNames) && modelImIdx.get() != prevM) onModelChanged();

        ImGui.spacing();

        AnimModel model = currentModel();
        if (model == null) { ImGui.textDisabled("No model loaded"); return; }

        // Clip selector
        ImGui.text("Clip");
        String[] cNames = model.animations.keySet().toArray(new String[0]);
        if (cNames.length == 0) cNames = new String[]{"(none)"};
        int prevC = clipImIdx.get();
        if (ImGui.combo("##c", clipImIdx, cNames) && clipImIdx.get() != prevC) onClipChanged();

        ImGui.sameLine();
        if (ImGui.smallButton("+##nc")) ImGui.openPopup("##newclip");
        if (ImGui.beginPopup("##newclip")) {
            ImGui.inputText("Name##cn", newClipName);
            if (ImGui.button("Add") && newClipName.getLength() > 0) {
                String cn = newClipName.get();
                if (!model.animations.containsKey(cn)) {
                    model.animations.put(cn, new AnimClip(cn));
                    List<String> keys = new ArrayList<>(model.animations.keySet());
                    clipImIdx.set(keys.indexOf(cn));
                }
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        // Clip settings
        AnimClip clip = currentClip();
        if (clip != null) {
            editDur[0] = clip.duration;
            if (ImGui.dragFloat("Duration##d", editDur, 0.01f, 0.05f, 120f)) clip.duration = editDur[0];
            loopBool.set(clip.loop);
            if (ImGui.checkbox("Loop##l", loopBool)) clip.loop = loopBool.get();
        }

        ImGui.separator();
        ImGui.text("Parts");

        for (PartDef p : model.parts) {
            if (p.parent != null) continue;
            drawPartNode(model, p);
        }

        ImGui.spacing();
        if (ImGui.smallButton("+##np")) ImGui.openPopup("##newpart");
        ImGui.sameLine();
        if (ImGui.smallButton("-##dp") && selectedPart != null) {
            model.parts.removeIf(p -> p.id.equals(selectedPart));
            ModelRenderer.invalidateModel(model);
            selectedPart = null;
        }
        if (ImGui.beginPopup("##newpart")) {
            ImGui.inputText("ID##pid", newPartName);
            if (ImGui.button("Add") && newPartName.getLength() > 0) {
                PartDef p = new PartDef();
                p.id     = newPartName.get();
                p.parent = selectedPart;
                model.parts.add(p);
                selectedPart = p.id;
                syncSliders(p);
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void drawPartNode(AnimModel model, PartDef part) {
        boolean sel = part.id.equals(selectedPart);
        List<PartDef> kids = model.childrenOf(part.id);

        int flags = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.SpanFullWidth;
        if (sel)          flags |= ImGuiTreeNodeFlags.Selected;
        if (kids.isEmpty()) flags |= ImGuiTreeNodeFlags.Leaf | ImGuiTreeNodeFlags.NoTreePushOnOpen;

        boolean open = ImGui.treeNodeEx(part.id, flags, part.id);
        if (ImGui.isItemClicked()) {
            selectedPart = part.id;
            syncSliders(part);
        }
        if (open && !kids.isEmpty()) {
            for (PartDef kid : kids) drawPartNode(model, kid);
            ImGui.treePop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Middle panel: 3D preview
    // ─────────────────────────────────────────────────────────────────────────

    private void drawPreviewPanel() {
        ImGui.text("Preview  (drag=orbit  scroll=zoom)");
        // Flip V so OpenGL texture appears right-side-up
        ImGui.image(previewTex, PREV_W, PREV_H, 0, 1, 1, 0);

        if (ImGui.isItemHovered()) {
            // Orbit drag
            ImVec2 d = ImGui.getMouseDragDelta(0, 1f);
            if (d.x != 0 || d.y != 0) {
                orbitYaw   += d.x * 0.5f;
                orbitPitch += d.y * 0.5f;
                orbitPitch  = Math.max(-89f, Math.min(89f, orbitPitch));
                ImGui.resetMouseDragDelta(0);
            }
            float scroll = ImGui.getIO().getMouseWheel();
            if (scroll != 0) orbitDist = Math.max(1f, Math.min(14f, orbitDist - scroll * 0.35f));
        }

        ImGui.spacing();
        if (ImGui.button(previewPlaying ? " Pause " : " Play  ")) {
            previewPlaying = !previewPlaying;
            if (previewPlaying) rebuildPreviewPlayer();
        }
        ImGui.sameLine();
        if (ImGui.button("Reset")) {
            previewTime = 0f; timelineCursor[0] = 0f;
            rebuildPreviewPlayer();
        }
        ImGui.sameLine();
        ImGui.text(String.format("t=%.2f", previewTime));

        // View presets
        if (ImGui.button("Front"))  { orbitYaw = 180f; orbitPitch = 0f; }
        ImGui.sameLine();
        if (ImGui.button("Side"))   { orbitYaw = 90f;  orbitPitch = 0f; }
        ImGui.sameLine();
        if (ImGui.button("Top"))    { orbitYaw = 0f;   orbitPitch = -89f; }
        ImGui.sameLine();
        if (ImGui.button("3/4"))    { orbitYaw = 30f;  orbitPitch = -18f; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Right panel: part inspector + timeline
    // ─────────────────────────────────────────────────────────────────────────

    private void drawRightPanel() {
        AnimModel model = currentModel();
        AnimClip  clip  = currentClip();
        PartDef   part  = (selectedPart != null && model != null) ? model.getPart(selectedPart) : null;

        // ── Part inspector ────────────────────────────────────────────────────
        ImGui.text(part != null ? "Part: " + part.id : "Part: (none)");
        ImGui.separator();

        if (part != null) {
            ImGui.text("Pose (add keyframe to record):");
            ImGui.dragFloat3("Rotation°", editRot,   1f, -180f, 180f);
            ImGui.dragFloat3("Translate", editTrans, 0.01f, -5f,  5f);

            ImGui.spacing();
            ImGui.text("Geometry (rebuilt live):");
            boolean sizeChanged = ImGui.dragFloat3("Size w h d", editSize, 0.01f, 0.01f, 8f);
            if (sizeChanged) {
                part.w = editSize[0]; part.h = editSize[1]; part.d = editSize[2];
                ModelRenderer.invalidateMesh(model, part.id);
            }
            boolean pivotChanged = ImGui.dragFloat3("Pivot x y z", editPivot, 0.01f, -4f, 4f);
            if (pivotChanged) {
                part.pivotX = editPivot[0]; part.pivotY = editPivot[1]; part.pivotZ = editPivot[2];
                ModelRenderer.invalidateMesh(model, part.id);
            }
            boolean origChanged = ImGui.dragFloat3("Origin x y z", editOrigin, 0.01f, -8f, 8f);
            if (origChanged) {
                part.ox = editOrigin[0]; part.oy = editOrigin[1]; part.oz = editOrigin[2];
            }

            ImGui.spacing();
            boolean colChanged = ImGui.colorEdit3("Color", editColor);
            if (colChanged) {
                part.cr = editColor[0]; part.cg = editColor[1]; part.cb = editColor[2];
                ModelRenderer.invalidateMesh(model, part.id);
            }

            ImGui.spacing();
            if (ImGui.button("Set as Rest Pose")) {
                part.defaultRx = editRot[0]; part.defaultRy = editRot[1]; part.defaultRz = editRot[2];
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("This pose will show when no animation is playing");
        }

        ImGui.spacing();
        ImGui.separator();

        // ── Timeline ─────────────────────────────────────────────────────────
        ImGui.text("Timeline");
        if (clip != null) {
            float dur = Math.max(0.01f, clip.duration);
            if (ImGui.sliderFloat("t##tl", timelineCursor, 0f, dur)) {
                previewTime = timelineCursor[0];
                rebuildPreviewPlayerAt(previewTime);
            }

            if (part != null) {
                if (ImGui.button("+ Add KF")) addKeyframe(clip, part);
                if (ImGui.isItemHovered()) ImGui.setTooltip(
                        "Record the current slider values as a keyframe at t=" + String.format("%.2f", previewTime));
                ImGui.sameLine();
                if (ImGui.button("- Del KF")) removeKeyframe(clip, part.id);
                if (ImGui.isItemHovered()) ImGui.setTooltip("Remove nearest keyframe to cursor");

                ImGui.spacing();
                drawKeyframeStrip(clip, part.id);
            }

            ImGui.spacing();
            ImGui.separator();
            ImGui.text("All tracks:");
            for (Map.Entry<String, List<Keyframe>> e : clip.keyframes.entrySet()) {
                boolean hi = e.getKey().equals(selectedPart);
                if (hi) ImGui.pushStyleColor(ImGuiCol.Text, 1f, 1f, 0.3f, 1f);
                String label = "  " + e.getKey() + "  (" + e.getValue().size() + " kf)";
                ImGui.text(label);
                if (ImGui.isItemClicked() && model != null) {
                    PartDef p = model.getPart(e.getKey());
                    if (p != null) { selectedPart = e.getKey(); syncSliders(p); }
                }
                if (hi) ImGui.popStyleColor();
            }
        } else {
            ImGui.textDisabled("No clip selected");
        }
    }

    // ── Keyframe strip ────────────────────────────────────────────────────────

    private void drawKeyframeStrip(AnimClip clip, String partId) {
        List<Keyframe> track = clip.keyframes.get(partId);
        float stripW = ImGui.getContentRegionAvailX();
        float stripH = 20f;
        ImVec2 pos = ImGui.getCursorScreenPos();
        var draw = ImGui.getForegroundDrawList();

        draw.addRectFilled(pos.x, pos.y, pos.x + stripW, pos.y + stripH,
                ImGui.colorConvertFloat4ToU32(0.12f, 0.12f, 0.12f, 1f));
        draw.addRect(pos.x, pos.y, pos.x + stripW, pos.y + stripH,
                ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.4f, 1f), 0, 0, 1f);

        float dur = Math.max(0.01f, clip.duration);
        if (track != null) {
            for (Keyframe kf : track) {
                float x = pos.x + (kf.t / dur) * stripW;
                float y = pos.y + stripH * 0.5f;
                boolean near = Math.abs(kf.t - previewTime) < dur * 0.025f;
                int col = near
                        ? ImGui.colorConvertFloat4ToU32(1f, 1f, 0.3f, 1f)
                        : ImGui.colorConvertFloat4ToU32(0.65f, 0.45f, 1f, 1f);
                draw.addCircleFilled(x, y, 5f, col);
                draw.addCircle(x, y, 5.5f, ImGui.colorConvertFloat4ToU32(1f, 1f, 1f, 0.35f), 12, 1f);
            }
        }

        // Playhead
        float ph = pos.x + (previewTime / dur) * stripW;
        draw.addLine(ph, pos.y, ph, pos.y + stripH,
                ImGui.colorConvertFloat4ToU32(1f, 0.35f, 0.35f, 1f), 2f);

        // Invisible button to capture clicks
        ImGui.setCursorScreenPos(pos.x, pos.y);
        ImGui.invisibleButton("##strip", stripW, stripH);
        if (ImGui.isItemActive()) {
            float rx = ImGui.getMousePos().x - pos.x;
            previewTime = Math.max(0, Math.min(dur, (rx / stripW) * dur));
            timelineCursor[0] = previewTime;
        }

        ImGui.setCursorScreenPos(pos.x, pos.y + stripH + 4f);
    }

    // ── Keyframe add/remove ───────────────────────────────────────────────────

    private void addKeyframe(AnimClip clip, PartDef part) {
        List<Keyframe> track = clip.getOrCreateTrack(part.id);
        track.removeIf(kf -> Math.abs(kf.t - previewTime) < 0.015f);
        Keyframe kf = new Keyframe(previewTime);
        if (editRot[0]   != 0f) kf.rx = editRot[0];
        if (editRot[1]   != 0f) kf.ry = editRot[1];
        if (editRot[2]   != 0f) kf.rz = editRot[2];
        if (editTrans[0] != 0f) kf.tx = editTrans[0];
        if (editTrans[1] != 0f) kf.ty = editTrans[1];
        if (editTrans[2] != 0f) kf.tz = editTrans[2];
        // If everything is zero, still add a "hold at rest" keyframe
        if (kf.rx==null && kf.ry==null && kf.rz==null &&
                kf.tx==null && kf.ty==null && kf.tz==null) kf.ty = 0f;
        track.add(kf);
        clip.sortTracks();
    }

    private void removeKeyframe(AnimClip clip, String partId) {
        List<Keyframe> track = clip.keyframes.get(partId);
        if (track == null) return;
        float dur = Math.max(0.01f, clip.duration);
        track.removeIf(kf -> Math.abs(kf.t - previewTime) < dur * 0.04f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3D Preview FBO rendering
    // ─────────────────────────────────────────────────────────────────────────

    private void renderPreviewFbo() {
        AnimModel model = currentModel();
        if (model == null || !ModelRenderer.isInitialised()) return;

        glBindFramebuffer(GL_FRAMEBUFFER, previewFbo);
        glViewport(0, 0, PREV_W, PREV_H);
        glClearColor(0.11f, 0.11f, 0.17f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);

        Map<String, Matrix4f> pose = buildPreviewPose(model);

        // Orbit camera setup
        float yawR   = (float) Math.toRadians(orbitYaw);
        float pitchR = (float) Math.toRadians(orbitPitch);
        float eyeX = (float)(orbitDist * Math.sin(yawR) * Math.cos(pitchR));
        float eyeY = (float)(orbitDist * Math.sin(pitchR));
        float eyeZ = (float)(orbitDist * Math.cos(yawR) * Math.cos(pitchR));

        Vector3f eye    = new Vector3f(eyeX, eyeY + 0.5f, eyeZ);
        Vector3f target = new Vector3f(0f, 0.5f, 0f);
        Matrix4f view   = new Matrix4f().lookAt(eye, target, new Vector3f(0,1,0));
        Matrix4f proj   = new Matrix4f().perspective(
                (float) Math.toRadians(55f), (float) PREV_W / PREV_H, 0.05f, 50f);

        ModelRenderer.render(model, pose, new Matrix4f(), view, proj);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private Map<String, Matrix4f> buildPreviewPose(AnimModel model) {
        AnimClip clip = currentClip();
        Map<String, Matrix4f> pose;

        if (clip != null) {
            // Sample the clip at previewTime
            AnimPlayer sp = new AnimPlayer(model);
            sp.play(clip.name, clip.loop);
            if (previewPlaying && previewPlayer != null) {
                // Use live player's pose
                pose = previewPlayer.getPose();
            } else {
                // Scrub: advance to previewTime in one step
                sp.tick(previewTime);
                pose = sp.getPose();
            }
        } else {
            // No clip — rest pose
            pose = defaultPose(model);
        }

        // Override the selected part with the live slider values so the user
        // sees immediate feedback when dragging sliders
        if (selectedPart != null) {
            PartDef p = model.getPart(selectedPart);
            if (p != null) {
                Matrix4f parentMat = (p.parent != null)
                        ? pose.getOrDefault(p.parent, new Matrix4f())
                        : new Matrix4f();
                Matrix4f local = new Matrix4f()
                        .translate(p.ox + editTrans[0], p.oy + editTrans[1], p.oz + editTrans[2])
                        .translate(p.pivotX, p.pivotY, p.pivotZ)
                        .rotateX((float) Math.toRadians(editRot[0]))
                        .rotateY((float) Math.toRadians(editRot[1]))
                        .rotateZ((float) Math.toRadians(editRot[2]))
                        .translate(-p.pivotX, -p.pivotY, -p.pivotZ);
                pose.put(selectedPart, new Matrix4f(parentMat).mul(local));
            }
        }
        return pose;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private AnimModel currentModel() {
        if (loadedModels.isEmpty()) return null;
        int idx = Math.min(modelImIdx.get(), loadedModels.size() - 1);
        return loadedModels.get(idx);
    }

    private AnimClip currentClip() {
        AnimModel m = currentModel();
        if (m == null || m.animations.isEmpty()) return null;
        List<String> keys = new ArrayList<>(m.animations.keySet());
        int idx = Math.min(clipImIdx.get(), keys.size() - 1);
        return m.animations.get(keys.get(idx));
    }

    private void onModelChanged() {
        selectedPart = null;
        clipImIdx.set(0);
        previewPlayer = null;
        previewTime = 0f;
        timelineCursor[0] = 0f;
    }

    private void onClipChanged() {
        previewTime = 0f;
        timelineCursor[0] = 0f;
        previewPlayer = null;
    }

    private void syncSliders(PartDef p) {
        editRot[0]    = p.defaultRx; editRot[1]    = p.defaultRy; editRot[2]    = p.defaultRz;
        editTrans[0]  = 0f;          editTrans[1]  = 0f;          editTrans[2]  = 0f;
        editSize[0]   = p.w;         editSize[1]   = p.h;         editSize[2]   = p.d;
        editPivot[0]  = p.pivotX;    editPivot[1]  = p.pivotY;    editPivot[2]  = p.pivotZ;
        editOrigin[0] = p.ox;        editOrigin[1] = p.oy;        editOrigin[2] = p.oz;
        editColor[0]  = p.cr;        editColor[1]  = p.cg;        editColor[2]  = p.cb;
    }

    private void rebuildPreviewPlayer() {
        AnimModel m = currentModel();
        AnimClip  c = currentClip();
        if (m == null || c == null) return;
        previewPlayer = new AnimPlayer(m);
        previewPlayer.play(c.name, c.loop);
        if (previewTime > 0) previewPlayer.tick(previewTime);
    }

    private void rebuildPreviewPlayerAt(float t) {
        previewPlaying = false;
        previewTime    = t;
        AnimModel m = currentModel();
        AnimClip  c = currentClip();
        if (m == null || c == null) return;
        previewPlayer = new AnimPlayer(m);
        previewPlayer.play(c.name, c.loop);
        previewPlayer.tick(t);
    }

    private Map<String, Matrix4f> defaultPose(AnimModel model) {
        Map<String, Matrix4f> pose = new LinkedHashMap<>();
        for (PartDef p : model.parts) {
            Matrix4f par = (p.parent != null) ? pose.getOrDefault(p.parent, new Matrix4f()) : new Matrix4f();
            Matrix4f loc = new Matrix4f()
                    .translate(p.ox, p.oy, p.oz)
                    .translate(p.pivotX, p.pivotY, p.pivotZ)
                    .rotateX((float) Math.toRadians(p.defaultRx))
                    .rotateY((float) Math.toRadians(p.defaultRy))
                    .rotateZ((float) Math.toRadians(p.defaultRz))
                    .translate(-p.pivotX, -p.pivotY, -p.pivotZ);
            pose.put(p.id, new Matrix4f(par).mul(loc));
        }
        return pose;
    }

    private void saveCurrentModel() {
        AnimModel m = currentModel();
        if (m == null) return;
        String path = "src/main/resources/models/" + m.name + ".json";
        m.saveToFile(path);
        System.out.println("[AnimEditor] Saved → " + path);
    }

    private void reloadModelList() {
        loadedModels.clear();
        for (String name : AnimModel.listModelFiles()) {
            AnimModel m = AnimModel.loadFromFile("src/main/resources/models/" + name + ".json");
            if (m != null) loadedModels.add(m);
        }
        if (loadedModels.isEmpty()) loadedModels.add(createDefaultEnemyModel());
        modelImIdx.set(0);
        onModelChanged();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FBO management
    // ─────────────────────────────────────────────────────────────────────────

    private void createPreviewFbo() {
        previewFbo = glGenFramebuffers();
        previewTex = glGenTextures();
        previewRbo = glGenRenderbuffers();

        glBindFramebuffer(GL_FRAMEBUFFER, previewFbo);
        glBindTexture(GL_TEXTURE_2D, previewTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, PREV_W, PREV_H, 0, GL_RGB, GL_UNSIGNED_BYTE, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, previewTex, 0);
        glBindRenderbuffer(GL_RENDERBUFFER, previewRbo);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, PREV_W, PREV_H);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, previewRbo);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void destroyPreviewFbo() {
        if (previewFbo != 0) glDeleteFramebuffers(previewFbo);
        if (previewTex != 0) glDeleteTextures(previewTex);
        if (previewRbo != 0) glDeleteRenderbuffers(previewRbo);
        previewFbo = previewTex = previewRbo = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Default humanoid model template (used when no JSON files exist yet)
    // ─────────────────────────────────────────────────────────────────────────

    public static AnimModel createDefaultEnemyModel() {
        AnimModel m = new AnimModel("enemy_basic");
        m.parts.addAll(List.of(
            mkPart("body",  null,   0.8f,1.0f,0.5f, 0f,0.5f,0f, 0f,0f,0f,   0.40f,0.70f,0.40f),
            mkPart("head",  "body", 0.7f,0.7f,0.7f, 0f,0f,0f,   0f,0.85f,0f,0.90f,0.72f,0.55f),
            mkPart("arm_l", "body", 0.3f,0.9f,0.3f, 0f,0.45f,0f,-0.55f,0.25f,0f,0.40f,0.70f,0.40f),
            mkPart("arm_r", "body", 0.3f,0.9f,0.3f, 0f,0.45f,0f, 0.55f,0.25f,0f,0.40f,0.70f,0.40f),
            mkPart("leg_l", "body", 0.35f,0.9f,0.35f,0f,0.45f,0f,-0.22f,-0.5f,0f,0.35f,0.55f,0.35f),
            mkPart("leg_r", "body", 0.35f,0.9f,0.35f,0f,0.45f,0f, 0.22f,-0.5f,0f,0.35f,0.55f,0.35f)
        ));

        // idle
        AnimClip idle = new AnimClip("idle"); idle.duration = 2f; idle.loop = true;
        kf(idle,"body","ty", 0f,0f, 1f,-0.03f, 2f,0f);
        m.animations.put("idle", idle);

        // walk
        AnimClip walk = new AnimClip("walk"); walk.duration = 0.6f; walk.loop = true;
        kf(walk,"arm_l","rx", 0f,30f, 0.3f,-30f, 0.6f,30f);
        kf(walk,"arm_r","rx", 0f,-30f,0.3f,30f,  0.6f,-30f);
        kf(walk,"leg_l","rx", 0f,-30f,0.3f,30f,  0.6f,-30f);
        kf(walk,"leg_r","rx", 0f,30f, 0.3f,-30f, 0.6f,30f);
        m.animations.put("walk", walk);

        // attack
        AnimClip atk = new AnimClip("attack"); atk.duration = 0.4f; atk.loop = false;
        kf(atk,"arm_r","rx", 0f,0f, 0.1f,-90f, 0.3f,30f, 0.4f,0f);
        kf(atk,"body","ry",  0f,0f, 0.1f,-20f, 0.4f,0f);
        m.animations.put("attack", atk);

        // die
        AnimClip die = new AnimClip("die"); die.duration = 0.8f; die.loop = false;
        kf(die,"body","rx", 0f,0f, 0.8f,-85f);
        kf(die,"body","ty", 0f,0f, 0.8f,-0.5f);
        kf(die,"arm_l","rx",0f,0f, 0.4f,60f);
        kf(die,"arm_r","rx",0f,0f, 0.4f,-40f);
        m.animations.put("die", die);

        return m;
    }

    private static PartDef mkPart(String id, String par,
            float w, float h, float d,
            float pvX, float pvY, float pvZ,
            float ox, float oy, float oz,
            float cr, float cg, float cb) {
        PartDef p = new PartDef();
        p.id=id; p.parent=par; p.w=w; p.h=h; p.d=d;
        p.pivotX=pvX; p.pivotY=pvY; p.pivotZ=pvZ;
        p.ox=ox; p.oy=oy; p.oz=oz;
        p.cr=cr; p.cg=cg; p.cb=cb;
        return p;
    }

    /** Add keyframes: varargs = t0,v0, t1,v1, ... */
    private static void kf(AnimClip clip, String partId, String ch, float... tv) {
        List<Keyframe> track = clip.getOrCreateTrack(partId);
        for (int i = 0; i < tv.length - 1; i += 2) {
            float t = tv[i], v = tv[i+1];
            Keyframe kfr = null;
            for (Keyframe x : track) if (Math.abs(x.t - t) < 0.001f) { kfr = x; break; }
            if (kfr == null) { kfr = new Keyframe(t); track.add(kfr); }
            switch (ch) {
                case "rx"->kfr.rx=v; case "ry"->kfr.ry=v; case "rz"->kfr.rz=v;
                case "tx"->kfr.tx=v; case "ty"->kfr.ty=v; case "tz"->kfr.tz=v;
            }
        }
        clip.sortTracks();
    }
}
