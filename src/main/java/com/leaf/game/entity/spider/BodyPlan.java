package com.leaf.game.entity.spider;

import java.util.ArrayList;
import java.util.List;

public class BodyPlan {
    public float scale = 1.0f;
    public List<LegPlan> legs = new ArrayList<>();
    public DisplayModel bodyModel = SpiderTorsoModels.EMPTY.model.clone();

    public void scale(float factor) {
        this.scale *= factor;
        // Removed model scaling from memory. This is now handled dynamically during render!
        for (LegPlan leg : legs) {
            leg.attachmentPosition.mul(factor);
            leg.restPosition.mul(factor);
            for (SegmentPlan segment : leg.segments) {
                segment.length *= factor;
            }
        }
    }
}