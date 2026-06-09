package com.leaf.game.entity.spider;

public class LegModelApplier {

    public static void applyMechanicalLegModel(BodyPlan bodyPlan) {
        for (LegPlan leg : bodyPlan.legs) {
            // Right legs are placed on the negative X side.
            // We use this to detect and perfectly mirror them!
            boolean isRightLeg = leg.attachmentPosition.x < 0f;

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

                segment.model = model.clone();

                // Mirror X by -1.0f if it's a right leg, achieving perfect symmetry!
                float mirrorX = isRightLeg ? -1.0f : 1.0f;
                segment.model.scale(mirrorX, 1.0f, segment.length);
            }
        }
    }
}