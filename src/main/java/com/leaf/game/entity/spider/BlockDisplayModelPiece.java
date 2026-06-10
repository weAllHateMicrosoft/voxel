package com.leaf.game.entity.spider;

import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

public class BlockDisplayModelPiece {
    public String blockName; // e.g. "smooth_quartz" (Minecraft block ID minus namespace)
    public Matrix4f transform;
    public List<String> tags;

    public BlockDisplayModelPiece(String blockName, Matrix4f transform, List<String> tags) {
        this.blockName = blockName;
        this.transform = new Matrix4f(transform);
        this.tags = new ArrayList<>(tags);
    }

    public void scale(float sx, float sy, float sz) {
        this.transform.set(new Matrix4f().scale(sx, sy, sz).mul(this.transform));
    }

    public BlockDisplayModelPiece clone() {
        return new BlockDisplayModelPiece(this.blockName, new Matrix4f(this.transform), new ArrayList<>(this.tags));
    }
}