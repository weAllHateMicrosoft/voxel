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

        // Find surrounding keyframes
        Keyframe prev = null, next = null;
        for (Keyframe kf : track) {
            Float v = ch.get(kf);
            if (v == null) continue;
            if (kf.t <= time) prev = kf;
            else if (next == null) { next = kf; break; }
        }
        if (prev == null && next == null) return null;
        if (prev == null) return ch.get(next);
        if (next == null) {
            if (!loop) return ch.get(prev);
            // Wrap: interpolate between last key and first key
            Keyframe first = null;
            for (Keyframe kf : track) { if (ch.get(kf) != null) { first = kf; break; } }
            if (first == null) return ch.get(prev);
            next = first;
            float span = duration - prev.t + next.t;
            if (span <= 0) return ch.get(prev);
            float ratio = next.ease((time - prev.t) / span);
            float pv = ch.get(prev), nv = ch.get(next);
            return pv + (nv - pv) * ratio;
        }
        float span = next.t - prev.t;
        if (span <= 0) return ch.get(next);
        float ratio = next.ease((time - prev.t) / span);
        Float pv = ch.get(prev), nv = ch.get(next);
        if (pv == null) return nv;
        if (nv == null) return pv;
        return pv + (nv - pv) * ratio;
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
