package com.leaf.game.entity.spider;

import org.joml.Vector3f;

public class ChainSegment {
    public Vector3f position;
    public float length;
    public Vector3f initDirection;

    public ChainSegment(Vector3f position, float length, Vector3f initDirection) {
        this.position = new Vector3f(position);
        this.length = length;
        this.initDirection = new Vector3f(initDirection);
    }

    public ChainSegment clone() {
        return new ChainSegment(this.position, this.length, this.initDirection);
    }
}