package com.leaf.game.entity.spider;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Faithful port of presets.kt.
 *
 * Provides factory methods for all spider body plan geometries.
 * The "Bot" variants (hexBot, octoBot, quadBot) use the mechanical robot
 * segment shape: first segment is short and angled upward, second is 80%
 * length, remaining are full length.  This produces the characteristic
 * raised-knee look of the original spider.
 *
 * The plain variants (hexapod, octopod, quadruped, biped) use equal-length
 * segments all pointing straight forward — simpler, more organic silhouette.
 *
 * Recommended starting point for your game's spider enemy: hexBot(4, 1.0)
 * This matches the default used in AppState.
 */
public class SpiderPresets {

    private SpiderPresets() {}

    // ── Plain variants (equal-length segments, forward-pointing) ─────────────

    public static SpiderOptions biped(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f, 0f),
                new Vector3f(1.0f, 0f, 0f),
                equalLength(segmentCount, 1.0f * segmentLength));
        return options;
    }

    public static SpiderOptions quadruped(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f, 0f),
                new Vector3f(0.9f, 0f, 0.9f),
                equalLength(segmentCount, 0.9f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f, 0f),
                new Vector3f(1.0f, 0f, -1.1f),
                equalLength(segmentCount, 1.2f * segmentLength));
        return options;
    }

    public static SpiderOptions hexapod(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f,  0.1f),
                new Vector3f(1.0f, 0f,  1.1f),
                equalLength(segmentCount, 1.1f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f,  0.0f),
                new Vector3f(1.3f, 0f, -0.3f),
                equalLength(segmentCount, 1.1f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f, -0.1f),
                new Vector3f(1.2f, 0f, -2.0f),
                equalLength(segmentCount, 1.6f * segmentLength));
        return options;
    }

    public static SpiderOptions octopod(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f,  0.1f),
                new Vector3f(1.0f, 0f,  1.6f),
                equalLength(segmentCount, 1.1f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f,  0.0f),
                new Vector3f(1.3f, 0f,  0.4f),
                equalLength(segmentCount, 1.0f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f, -0.1f),
                new Vector3f(1.3f, 0f, -0.9f),
                equalLength(segmentCount, 1.1f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0f, 0f, -0.2f),
                new Vector3f(1.1f, 0f, -2.5f),
                equalLength(segmentCount, 1.6f * segmentLength));
        return options;
    }

    // ── Robot/Bot variants (mechanical segment shape, raised-knee look) ───────

    /**
     * 4-legged robot spider.
     * segmentCount: number of IK segments per leg (3–4 recommended).
     * segmentLength: base length scale (1.0 = default).
     */
    public static SpiderOptions quadBot(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f,  0.2f),
                new Vector3f(1.3f,  0f,  1.0f),
                robotSegments(segmentCount, 0.9f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f, -0.2f),
                new Vector3f(1.43f, 0f, -1.2f),
                robotSegments(segmentCount, 1.2f * 0.7f * segmentLength));
        return options;
    }

    /**
     * 6-legged robot spider — the default used in the original plugin (AppState).
     * Recommended for your game's spider enemy.
     * segmentCount: 4 is the default.
     * segmentLength: 1.0 is the default.
     */
    public static SpiderOptions hexBot(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f,  0.2f),
                new Vector3f(1.3f,  0f,  1.3f),
                robotSegments(segmentCount, 1.1f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f,  0.0f),
                new Vector3f(1.56f, 0f, -0.1f),
                robotSegments(segmentCount, 1.1f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f, -0.2f),
                new Vector3f(1.43f, 0f, -1.6f),
                robotSegments(segmentCount, 1.3f * 0.7f * segmentLength));
        return options;
    }

    /**
     * 8-legged robot spider.
     */
    public static SpiderOptions octoBot(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f,  0.3f),
                new Vector3f(1.3f,  0f,  1.3f),
                robotSegments(segmentCount, 1.1f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f,  0.1f),
                new Vector3f(1.56f, 0f,  0.5f),
                robotSegments(segmentCount, 1.0f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f,  0.1f),
                new Vector3f(1.56f, 0f, -0.7f),
                robotSegments(segmentCount, 1.1f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan,
                new Vector3f(0.2f, -0.35f, -0.3f),
                new Vector3f(1.43f, 0f, -1.6f),
                robotSegments(segmentCount, 1.3f * 0.7f * segmentLength));
        return options;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Adds a mirrored left/right leg pair to the body plan.
     * The left leg uses +x for root and rest; the right leg mirrors to -x.
     * Segments are deep-cloned for the right leg so each leg has independent state.
     */
    private static void addLegPair(BodyPlan plan, Vector3f root, Vector3f rest,
                                   List<SegmentPlan> segments) {
        // Left leg (positive X side)
        plan.legs.add(new LegPlan(
                new Vector3f( root.x, root.y, root.z),
                new Vector3f( rest.x, rest.y, rest.z),
                segments));

        // Right leg (mirrored on X) — deep clone segments so state is independent
        List<SegmentPlan> mirroredSegs = new ArrayList<>();
        for (SegmentPlan s : segments) mirroredSegs.add(s.clone());
        plan.legs.add(new LegPlan(
                new Vector3f(-root.x, root.y, root.z),
                new Vector3f(-rest.x, rest.y, rest.z),
                mirroredSegs));
    }

    /**
     * Creates N segments of equal length, all pointing straight forward (0,0,1).
     */
    private static List<SegmentPlan> equalLength(int count, float length) {
        List<SegmentPlan> segs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            segs.add(new SegmentPlan(length, new Vector3f(0f, 0f, 1f)));
        }
        return segs;
    }

    /**
     * Creates the mechanical robot segment shape used by all *Bot presets.
     *
     * Segment 0 (coxa):   half length, angled 60° upward — the "hip" that lifts up
     * Segment 1 (femur):  80% length — the upper leg
     * Remaining (tibia+): full length — lower leg and tip
     *
     * This is what produces the characteristic raised-knee silhouette.
     */
    private static List<SegmentPlan> robotSegments(int count, float lengthScale) {
        List<SegmentPlan> segs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float length = lengthScale;
            Vector3f initDir = new Vector3f(0f, 0f, 1f);

            if (i == 0) {
                length *= 0.5f;
                // Rotate 60° around X axis: tilts the segment upward
                float angle = (float)(Math.PI / 3.0);
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
                // rotateAroundX on (0,0,1): y' = y*cos - z*sin, z' = y*sin + z*cos
                initDir = new Vector3f(0f, -sin, cos);
            }
            if (i == 1) {
                length *= 0.8f;
            }

            segs.add(new SegmentPlan(length, initDir));
        }
        return segs;
    }
}