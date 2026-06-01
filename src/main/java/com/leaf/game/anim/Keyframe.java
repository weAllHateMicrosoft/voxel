package com.leaf.game.anim;

/**
 * One keyframe for a single part inside an AnimClip.
 *
 * Only the fields you actually set matter — unset fields default to
 * the part's rest-pose values and are not interpolated (they stay at
 * whatever the previous keyframe left them at).
 *
 * Euler angles are in DEGREES.  Easing: "linear", "ease_in", "ease_out",
 * "ease_in_out", "step".  Default is "linear".
 */
public class Keyframe {
    public float  t  = 0f;       // time in seconds
    public Float  rx = null, ry = null, rz = null;  // rotation degrees
    public Float  tx = null, ty = null, tz = null;  // translation offset
    public Float  sx = null, sy = null, sz = null;  // scale multiplier

    public String easing = "linear";

    public Keyframe() {}

    public Keyframe(float t) { this.t = t; }

    /** Blend ratio 0→1 using the easing curve of THIS keyframe (the destination). */
    public float ease(float ratio) {
        return switch (easing == null ? "linear" : easing) {
            case "ease_in"     -> ratio * ratio;
            case "ease_out"    -> ratio * (2 - ratio);
            case "ease_in_out" -> ratio < 0.5f ? 2 * ratio * ratio : -1 + (4 - 2 * ratio) * ratio;
            case "step"        -> ratio < 1f ? 0f : 1f;
            default            -> ratio;   // linear
        };
    }
}
