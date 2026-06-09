package com.leaf.game.entity.spider;

import org.joml.Vector3f;

public class SegmentPlan {
    public float length;
    public Vector3f initDirection;

    public SegmentPlan(float length, Vector3f initDirection) {
        this.length = length;
        this.initDirection = new Vector3f(initDirection);
    }

    public SegmentPlan clone() {
        return new SegmentPlan(length, initDirection);
    }
}