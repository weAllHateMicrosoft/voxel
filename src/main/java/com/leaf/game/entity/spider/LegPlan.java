package com.leaf.game.entity.spider;

import org.joml.Vector3f;
import java.util.List;

public class LegPlan {
    public Vector3f attachmentPosition;
    public Vector3f restPosition;
    public List<SegmentPlan> segments;

    public LegPlan(Vector3f attachmentPosition, Vector3f restPosition, List<SegmentPlan> segments) {
        this.attachmentPosition = new Vector3f(attachmentPosition);
        this.restPosition = new Vector3f(restPosition);
        this.segments = segments;
    }
}