package com.leaf.game.entity.spider;

import org.joml.Vector3f;

public class SegmentPlan {
    public float length;
    public Vector3f initDirection;
    public DisplayModel model = DisplayModel.empty(); // New field!

    public SegmentPlan(float length, Vector3f initDirection) {
        this.length = length;
        this.initDirection = new Vector3f(initDirection);
    }

    public SegmentPlan clone() {
        SegmentPlan cloned = new SegmentPlan(length, new Vector3f(initDirection));
        cloned.model = this.model.clone();
        return cloned;
    }
}