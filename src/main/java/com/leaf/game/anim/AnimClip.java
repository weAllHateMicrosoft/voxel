package com.leaf.game.anim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named animation clip — a set of keyframe tracks, one per part.
 *
 * Clips are stored inside the AnimModel and referenced by name at runtime.
 */
public class AnimClip {
    public String  name;
    public float   duration = 1f;   // seconds
    public boolean loop     = true;

    /** partId → sorted list of keyframes for that part */
    public Map<String, List<Keyframe>> keyframes = new LinkedHashMap<>();

    public AnimClip() {}
    public AnimClip(String name) { this.name = name; }

    // ── Runtime helpers ──────────────────────────────────────────────────────

    /** Sample a single float channel for a part at time t. Returns null if no track or no keys. */
    public Float sampleFloat(String partId, FloatChannel ch, float t) {
        List<Keyframe> track = keyframes.get(partId);
        if (track == null || track.isEmpty()) return null;

        // Clamp/wrap time
        float time = loop ? (t % duration) : Math.min(t, duration);
        if (time < 0) time += duration;

        // Find the four channel-valued keyframes surrounding `time` (no allocation):
        //   p1 = last key <= time, p0 = the one before it,
        //   p2 = first key  > time, p3 = the one after it.
        // p0/p3 are needed for the Catmull-Rom spline (smooth interpolation).
        Keyframe p0 = null, p1 = null, p2 = null, p3 = null;
        for (Keyframe kf : track) {
            if (ch.get(kf) == null) continue;
            if (kf.t <= time) { p0 = p1; p1 = kf; }
            else if (p2 == null) p2 = kf;
            else { p3 = kf; break; }
        }

        if (p1 == null && p2 == null) return null;
        if (p1 == null) return ch.get(p2);     // before the first key
        if (p2 == null) {                        // after the last key
            if (!loop) return ch.get(p1);
            // Wrap linearly from the last key back to the first.
            Keyframe first = null;
            for (Keyframe kf : track) { if (ch.get(kf) != null) { first = kf; break; } }
            if (first == null || first == p1) return ch.get(p1);
            float span = duration - p1.t + first.t;
            if (span <= 0) return ch.get(p1);
            float ratio = first.ease((time - p1.t) / span);
            float pv = ch.get(p1), nv = ch.get(first);
            return pv + (nv - pv) * ratio;
        }

        // Segment p1 -> p2
        float span = p2.t - p1.t;
        if (span <= 0) return ch.get(p2);
        float u  = (time - p1.t) / span;
        float v1 = ch.get(p1), v2 = ch.get(p2);

        // Catmull-Rom spline (smooth, matches Blockbench) when either end keyframe
        // requests it; otherwise the regular eased/linear lerp.
        boolean smooth = "catmullrom".equals(p1.easing) || "catmullrom".equals(p2.easing);
        if (smooth) {
            float v0 = (p0 != null) ? ch.get(p0) : v1;   // clamp ends (no wrap-around for splines)
            float v3 = (p3 != null) ? ch.get(p3) : v2;
            return catmullRom(v0, v1, v2, v3, u);
        }
        return v1 + (v2 - v1) * p2.ease(u);
    }

    /** Uniform Catmull-Rom interpolation of the segment p1..p2 (p0,p3 are the neighbours). */
    private static float catmullRom(float p0, float p1, float p2, float p3, float u) {
        float u2 = u * u, u3 = u2 * u;
        return 0.5f * ((2f * p1)
                + (-p0 + p2) * u
                + (2f * p0 - 5f * p1 + 4f * p2 - p3) * u2
                + (-p0 + 3f * p1 - 3f * p2 + p3) * u3);
    }

    public enum FloatChannel {
        RX { @Override public Float get(Keyframe kf) { return kf.rx; } },
        RY { @Override public Float get(Keyframe kf) { return kf.ry; } },
        RZ { @Override public Float get(Keyframe kf) { return kf.rz; } },
        TX { @Override public Float get(Keyframe kf) { return kf.tx; } },
        TY { @Override public Float get(Keyframe kf) { return kf.ty; } },
        TZ { @Override public Float get(Keyframe kf) { return kf.tz; } },
        SX { @Override public Float get(Keyframe kf) { return kf.sx; } },
        SY { @Override public Float get(Keyframe kf) { return kf.sy; } },
        SZ { @Override public Float get(Keyframe kf) { return kf.sz; } };
        public abstract Float get(Keyframe kf);
    }

    /** Ensure the track list for partId exists. */
    public List<Keyframe> getOrCreateTrack(String partId) {
        return keyframes.computeIfAbsent(partId, k -> new ArrayList<>());
    }

    /** Sort all tracks by keyframe time — call after any edit. */
    public void sortTracks() {
        for (List<Keyframe> track : keyframes.values())
            track.sort((a, b) -> Float.compare(a.t, b.t));
    }
}
