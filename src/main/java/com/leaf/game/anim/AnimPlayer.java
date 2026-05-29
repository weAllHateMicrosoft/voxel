package com.leaf.game.anim;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Runtime animation player for a single AnimModel instance.
 *
 * Usage:
 *   AnimPlayer player = new AnimPlayer(model);
 *   player.play("walk");
 *   ...
 *   // every frame:
 *   player.tick(deltaTime);
 *   Map<String, Matrix4f> pose = player.getPose();
 *   modelRenderer.render(model, pose, worldMatrix);
 *
 * Blending: crossfade(name, blendTime) starts a smooth transition.
 */
public class AnimPlayer {

    private final AnimModel model;

    private String  currentClip  = null;
    private float   currentTime  = 0f;
    private boolean playing      = true;

    // Crossfade state
    private String  blendClip    = null;
    private float   blendTime    = 0f;   // remaining blend time
    private float   blendDur     = 0f;   // total blend duration
    private float   blendClipT   = 0f;   // time in the incoming clip

    public AnimPlayer(AnimModel model) {
        this.model = model;
    }

    // ── Playback control ─────────────────────────────────────────────────────

    public void play(String clipName) {
        play(clipName, true);
    }

    public void play(String clipName, boolean loop) {
        if (!model.animations.containsKey(clipName)) return;
        model.animations.get(clipName).loop = loop;
        currentClip  = clipName;
        currentTime  = 0f;
        playing      = true;
        blendClip    = null;
    }

    /** Smoothly transition to another clip over blendSeconds. */
    public void crossfade(String clipName, float blendSeconds) {
        if (!model.animations.containsKey(clipName)) { play(clipName); return; }
        blendClip   = clipName;
        blendTime   = blendSeconds;
        blendDur    = blendSeconds;
        blendClipT  = 0f;
    }

    public void stop()   { playing = false; }
    public void resume() { playing = true; }

    public String  getCurrentClip() { return currentClip; }
    public float   getCurrentTime() { return currentTime; }
    public boolean isPlaying()      { return playing; }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(float dt) {
        if (!playing || currentClip == null) return;
        AnimClip clip = model.animations.get(currentClip);
        if (clip == null) return;
        currentTime += dt;
        if (clip.loop) {
            currentTime %= Math.max(0.001f, clip.duration);
        } else {
            currentTime = Math.min(currentTime, clip.duration);
            if (currentTime >= clip.duration) playing = false;
        }

        if (blendClip != null) {
            blendClipT += dt;
            blendTime  -= dt;
            if (blendTime <= 0f) {
                // Blend complete — switch to incoming clip
                currentClip = blendClip;
                currentTime = blendClipT;
                blendClip   = null;
            }
        }
    }

    // ── Pose computation ─────────────────────────────────────────────────────

    /**
     * Compute and return a world-space transform matrix for every part,
     * keyed by part id.  The root parts sit at their origin; child parts
     * are concatenated on top of their parent's transform.
     *
     * The returned matrices go directly into the MVP calculation:
     *   mvp = projection * view * worldMatrix * pose.get(partId)
     */
    public Map<String, Matrix4f> getPose() {
        Map<String, Matrix4f> result = new HashMap<>();
        // Build root-first (parts list must be in parent-before-child order)
        for (PartDef part : model.parts) {
            Matrix4f m = computePartMatrix(part, result);
            result.put(part.id, m);
        }
        return result;
    }

    private Matrix4f computePartMatrix(PartDef part, Map<String, Matrix4f> built) {
        // 1. Get animated offsets for this frame
        float rx = sampleOrDefault(part.id, AnimClip.FloatChannel.RX, part.defaultRx);
        float ry = sampleOrDefault(part.id, AnimClip.FloatChannel.RY, part.defaultRy);
        float rz = sampleOrDefault(part.id, AnimClip.FloatChannel.RZ, part.defaultRz);
        float tx = sampleOrDefault(part.id, AnimClip.FloatChannel.TX, 0f);
        float ty = sampleOrDefault(part.id, AnimClip.FloatChannel.TY, 0f);
        float tz = sampleOrDefault(part.id, AnimClip.FloatChannel.TZ, 0f);

        // Blend if crossfading
        if (blendClip != null && blendDur > 0) {
            float alpha = 1f - (blendTime / blendDur);  // 0→incoming, 1→outgoing? reversed: alpha=0 at start
            alpha = Math.max(0f, Math.min(1f, alpha));
            rx = lerp(rx, sampleBlend(part.id, AnimClip.FloatChannel.RX, part.defaultRx), alpha);
            ry = lerp(ry, sampleBlend(part.id, AnimClip.FloatChannel.RY, part.defaultRy), alpha);
            rz = lerp(rz, sampleBlend(part.id, AnimClip.FloatChannel.RZ, part.defaultRz), alpha);
            tx = lerp(tx, sampleBlend(part.id, AnimClip.FloatChannel.TX, 0f), alpha);
            ty = lerp(ty, sampleBlend(part.id, AnimClip.FloatChannel.TY, 0f), alpha);
            tz = lerp(tz, sampleBlend(part.id, AnimClip.FloatChannel.TZ, 0f), alpha);
        }

        // 2. Build local transform: translate to origin, rotate around pivot, translate back
        Matrix4f local = new Matrix4f()
                // Move pivot to world-space
                .translate(part.ox + tx, part.oy + ty, part.oz + tz)
                // Rotate around pivot (pivot is the joint, so we offset, rotate, un-offset)
                .translate(part.pivotX, part.pivotY, part.pivotZ)
                .rotateX((float) Math.toRadians(rx))
                .rotateY((float) Math.toRadians(ry))
                .rotateZ((float) Math.toRadians(rz))
                .translate(-part.pivotX, -part.pivotY, -part.pivotZ);

        // 3. Concatenate with parent transform (if any)
        if (part.parent != null) {
            Matrix4f parentMat = built.get(part.parent);
            if (parentMat != null) {
                Matrix4f combined = new Matrix4f(parentMat);
                combined.mul(local);
                return combined;
            }
        }
        return local;
    }

    private float sampleOrDefault(String partId, AnimClip.FloatChannel ch, float def) {
        if (currentClip == null) return def;
        AnimClip clip = model.animations.get(currentClip);
        if (clip == null) return def;
        Float v = clip.sampleFloat(partId, ch, currentTime);
        return v != null ? v : def;
    }

    private float sampleBlend(String partId, AnimClip.FloatChannel ch, float def) {
        if (blendClip == null) return def;
        AnimClip clip = model.animations.get(blendClip);
        if (clip == null) return def;
        Float v = clip.sampleFloat(partId, ch, blendClipT);
        return v != null ? v : def;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
