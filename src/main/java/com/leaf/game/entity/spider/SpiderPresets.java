package com.leaf.game.entity.spider;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

public class SpiderPresets {

    private SpiderPresets() {}

    private static void addLegPair(BodyPlan plan, Vector3f root, Vector3f rest, List<SegmentPlan> segments) {
        plan.legs.add(new LegPlan(
                new Vector3f(root.x, root.y, root.z),
                new Vector3f(rest.x, rest.y, rest.z),
                segments));

        List<SegmentPlan> mirroredSegs = new ArrayList<>();
        for (SegmentPlan s : segments) mirroredSegs.add(s.clone());
        plan.legs.add(new LegPlan(
                new Vector3f(-root.x, root.y, root.z),
                new Vector3f(-rest.x, rest.y, rest.z),
                mirroredSegs));
    }

    private static List<SegmentPlan> equalLength(int count, float length) {
        List<SegmentPlan> segs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            segs.add(new SegmentPlan(length, new Vector3f(0f, 0f, 1f)));
        }
        return segs;
    }

    private static List<SegmentPlan> robotSegments(int count, float lengthScale) {
        List<SegmentPlan> segs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float length = lengthScale;
            Vector3f initDir = new Vector3f(0f, 0f, 1f);

            if (i == 0) {
                length *= 0.5f;
                float angle = (float)(Math.PI / 3.0);
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
                initDir = new Vector3f(0f, -sin, cos);
            }
            if (i == 1) {
                length *= 0.8f;
            }

            segs.add(new SegmentPlan(length, initDir));
        }
        return segs;
    }

    public static SpiderOptions biped(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f, 0f), new Vector3f(1.0f, 0f, 0f), equalLength(segmentCount, 1.0f * segmentLength));
        return options;
    }

    public static SpiderOptions quadruped(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f, 0f), new Vector3f(0.9f, 0f, 0.9f), equalLength(segmentCount, 0.9f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f, 0f), new Vector3f(1.0f, 0f, -1.1f), equalLength(segmentCount, 1.2f * segmentLength));
        return options;
    }

    public static SpiderOptions hexapod(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f,  0.1f), new Vector3f(1.0f, 0f,  1.1f), equalLength(segmentCount, 1.1f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f,  0.0f), new Vector3f(1.3f, 0f, -0.3f), equalLength(segmentCount, 1.1f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f, -0.1f), new Vector3f(1.2f, 0f, -2.0f), equalLength(segmentCount, 1.6f * segmentLength));
        return options;
    }

    public static SpiderOptions octopod(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f,  0.1f), new Vector3f(1.0f, 0f,  1.6f), equalLength(segmentCount, 1.1f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f,  0.0f), new Vector3f(1.3f, 0f,  0.4f), equalLength(segmentCount, 1.0f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f, -0.1f), new Vector3f(1.3f, 0f, -0.9f), equalLength(segmentCount, 1.1f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0f, 0f, -0.2f), new Vector3f(1.1f, 0f, -2.5f), equalLength(segmentCount, 1.6f * segmentLength));
        return options;
    }

    public static SpiderOptions quadBot(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        options.bodyPlan.bodyModel = SpiderTorsoModels.FLAT.model.clone();
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f,  0.2f), new Vector3f(1.3f,  0f,  1.0f), robotSegments(segmentCount, 0.9f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f, -0.2f), new Vector3f(1.43f, 0f, -1.2f), robotSegments(segmentCount, 1.2f * 0.7f * segmentLength));
        LegModelApplier.applyMechanicalLegModel(options.bodyPlan);
        return options;
    }

    public static SpiderOptions hexBot(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        options.bodyPlan.bodyModel = SpiderTorsoModels.FLAT.model.clone();
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f,  0.2f), new Vector3f(1.3f,  0f,  1.3f), robotSegments(segmentCount, 1.1f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f,  0.0f), new Vector3f(1.56f, 0f, -0.1f), robotSegments(segmentCount, 1.1f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f, -0.2f), new Vector3f(1.43f, 0f, -1.6f), robotSegments(segmentCount, 1.3f * 0.7f * segmentLength));
        LegModelApplier.applyMechanicalLegModel(options.bodyPlan);
        return options;
    }

    public static SpiderOptions octoBot(int segmentCount, float segmentLength) {
        SpiderOptions options = new SpiderOptions();
        options.bodyPlan.bodyModel = SpiderTorsoModels.FLAT.model.clone();
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f,  0.3f), new Vector3f(1.3f,  0f,  1.3f), robotSegments(segmentCount, 1.1f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f,  0.1f), new Vector3f(1.56f, 0f,  0.5f), robotSegments(segmentCount, 1.0f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f,  0.1f), new Vector3f(1.56f, 0f, -0.7f), robotSegments(segmentCount, 1.1f * 0.7f * segmentLength));
        addLegPair(options.bodyPlan, new Vector3f(0.2f, -0.35f, -0.3f), new Vector3f(1.43f, 0f, -1.6f), robotSegments(segmentCount, 1.3f * 0.7f * segmentLength));
        LegModelApplier.applyMechanicalLegModel(options.bodyPlan);
        return options;
    }
}