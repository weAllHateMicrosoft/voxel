package com.leaf.game.entity.spider;

public class LegModelApplier {

    public static void applyMechanicalLegModel(BodyPlan bodyPlan) {
        for (LegPlan leg : bodyPlan.legs) {
            for (int i = 0; i < leg.segments.size(); i++) {
                SegmentPlan segment = leg.segments.get(i);
                DisplayModel model;

                if (i == 0) {
                    model = SpiderLegModel.BASE;
                } else if (i == 1) {
                    model = SpiderLegModel.FEMUR;
                } else if (i == leg.segments.size() - 2) {
                    model = SpiderLegModel.TIBIA;
                } else if (i == leg.segments.size() - 1) {
                    model = SpiderLegModel.TIP;
                } else {
                    model = SpiderLegModel.FEMUR;
                }

                // Clone and dynamically scale segment transformations to fit physical plan lengths
                segment.model = model.clone();
                segment.model.scale(1.0f, 1.0f, segment.length);
            }
        }
    }
}