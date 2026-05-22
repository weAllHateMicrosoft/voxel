package com.leaf.game;

public enum Block {

    // Each block type has an RGB color (0.0 to 1.0 range)
    AIR  (0.0f, 0.0f, 0.0f),
    GRASS(0.3f, 0.7f, 0.2f),
    DIRT (0.5f, 0.3f, 0.1f),
    STONE(0.5f, 0.5f, 0.5f);

    public final float r, g, b;

    Block(float r, float g, float b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public boolean isSolid() {
        return this != AIR;
    }
}