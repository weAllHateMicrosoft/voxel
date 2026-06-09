package com.leaf.game.entity.spider;

import com.leaf.game.util.SpiderMath;

public class LerpGait {
    public float bodyHeight;
    public SpiderMath.SplitDistance triggerZone;

    public LerpGait(float bodyHeight, SpiderMath.SplitDistance triggerZone) {
        this.bodyHeight = bodyHeight;
        this.triggerZone = triggerZone;
    }

    public LerpGait scale(float factor) {
        bodyHeight *= factor;
        triggerZone = triggerZone.scale(factor);
        return this;
    }

    public LerpGait clone() {
        return new LerpGait(bodyHeight, triggerZone.clone());
    }

    public LerpGait lerp(LerpGait target, float factor) {
        this.bodyHeight = SpiderMath.lerp(this.bodyHeight, target.bodyHeight, factor);
        this.triggerZone = this.triggerZone.lerp(target.triggerZone, factor);
        return this;
    }
}