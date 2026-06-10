package com.leaf.game.entity.spider;

import org.joml.Vector3f;

public class LegTarget {
    public Vector3f position;
    public boolean  isGrounded;
    public int      id;

    public LegTarget(Vector3f position, boolean isGrounded, int id) {
        this.position   = new Vector3f(position);
        this.isGrounded = isGrounded;
        this.id         = id;
    }
}