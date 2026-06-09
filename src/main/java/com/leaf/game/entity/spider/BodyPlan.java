package com.leaf.game.entity.spider;

import java.util.ArrayList;
import java.util.List;

public class BodyPlan {
    public float scale = 1.0f;
    public List<LegPlan> legs = new ArrayList<>();

    public void scale(float factor) {
        this.scale *= factor;
        for (LegPlan leg : legs) {
            leg.attachmentPosition.mul(factor);
            leg.restPosition.mul(factor);
            for (SegmentPlan segment : leg.segments) {
                segment.length *= factor;
            }
        }
    }
}