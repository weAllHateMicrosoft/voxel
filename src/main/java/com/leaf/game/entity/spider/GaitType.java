package com.leaf.game.entity.spider;

import java.util.ArrayList;
import java.util.List;

public enum GaitType {
    WALK,
    GALLOP;

    public List<Leg> getLegsInUpdateOrder(SpiderBody spider) {
        List<Leg> ordered = new ArrayList<>();
        List<Integer> diag1 = new ArrayList<>();
        List<Integer> diag2 = new ArrayList<>();

        for (int i = 0; i < spider.legs.size(); i++) {
            if (LegLookUp.isDiagonal1(i)) diag1.add(i);
            else if (LegLookUp.isDiagonal2(i)) diag2.add(i);
        }

        for (int idx : diag1) ordered.add(spider.legs.get(idx));
        for (int idx : diag2) ordered.add(spider.legs.get(idx));

        return ordered;
    }

    public boolean canMoveLeg(Leg leg, SpiderBody spider) {
        int index = spider.legs.indexOf(leg);

        if (this == GALLOP && spider.isWalking) { // Gallop gait logic
            if (!leg.target.isGrounded) return true;

            boolean onGround = spider.onGround;
            for (Leg l : spider.legs) {
                if (l.isGrounded()) { onGround = true; break; }
            }
            if (!onGround) return false;

            Leg pair = spider.legs.get(LegLookUp.horizontal(index));
            leg.isPrimary = LegLookUp.isDiagonal1(index) || pair.isDisabled || !pair.target.isGrounded;

            if (leg.isPrimary) {
                Leg front = getOrNull(spider.legs, LegLookUp.diagonalFront(index));

                if (front != null && leg.target.isGrounded && front.timeSinceBeginMove < spider.gait.crossPairCooldown) {
                    return false;
                }
                return leg.isOutsideTriggerZone() || !leg.touchingGround;
            } else {
                boolean hasCooldown = pair.target.isGrounded && (pair.timeSinceBeginMove < spider.gait.samePairCooldown);
                return pair.isMoving && !hasCooldown;
            }
        }
        else { // Walk gait logic
            if (!leg.target.isGrounded) return true;

            leg.isPrimary = true;
            List<Leg> crossPair = unIndexLeg(spider, LegLookUp.adjacent(index));
            for (Leg l : crossPair) {
                if (!l.isGrounded() && !l.isDisabled && l.target.isGrounded) return false;
            }

            for (Leg l : crossPair) {
                if (l.target.isGrounded && l.timeSinceStopMove < spider.gait.crossPairCooldown) return false;
            }

            List<Leg> samePair = unIndexLeg(spider, LegLookUp.diagonal(index));
            for (Leg l : samePair) {
                if (l.target.isGrounded && l.timeSinceBeginMove < spider.gait.samePairCooldown) return false;
            }

            boolean wantsToMove = leg.isOutsideTriggerZone() || !leg.touchingGround;
            boolean alreadyAtTarget = leg.endEffector.distanceSquared(leg.target.position) < 0.01f;

            boolean onGround = spider.onGround;
            for (Leg l : spider.legs) {
                if (l.isGrounded()) { onGround = true; break; }
            }

            return wantsToMove && !alreadyAtTarget && onGround;
        }
    }

    private List<Leg> unIndexLeg(SpiderBody spider, List<Integer> indices) {
        List<Leg> result = new ArrayList<>();
        for (int i : indices) {
            Leg l = getOrNull(spider.legs, i);
            if (l != null) result.add(l);
        }
        return result;
    }

    private Leg getOrNull(List<Leg> legs, int index) {
        if (index >= 0 && index < legs.size()) return legs.get(index);
        return null;
    }
}