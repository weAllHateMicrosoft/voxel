package com.leaf.game.entity.spider;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public enum PivotMode {
    Y_AXIS,
    SPIDER_ORIENTATION,
    GROUND_ORIENTATION;

    public Quaternionf get(SpiderBody spider) {
        switch (this) {
            case Y_AXIS:
                float yaw = spider.orientation.getEulerAnglesYXZ(new Vector3f()).y;
                return new Quaternionf().rotateY(yaw);
            case GROUND_ORIENTATION:
                return new Quaternionf(spider.preferredOrientation);
            case SPIDER_ORIENTATION:
            default:
                return new Quaternionf(spider.orientation);
        }
    }
}