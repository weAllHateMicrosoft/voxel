package com.leaf.game.entity.spider;

import java.util.ArrayList;
import java.util.List;

public class DisplayModel {
    public List<BlockDisplayModelPiece> pieces;

    public DisplayModel(List<BlockDisplayModelPiece> pieces) {
        this.pieces = pieces;
    }

    public void scale(float scale) {
        scale(scale, scale, scale);
    }

    public void scale(float x, float y, float z) {
        for (BlockDisplayModelPiece piece : pieces) {
            piece.scale(x, y, z);
        }
    }

    public DisplayModel clone() {
        List<BlockDisplayModelPiece> clonedPieces = new ArrayList<>();
        for (BlockDisplayModelPiece p : pieces) {
            clonedPieces.add(p.clone());
        }
        return new DisplayModel(clonedPieces);
    }

    public static DisplayModel empty() {
        return new DisplayModel(new ArrayList<>());
    }
}