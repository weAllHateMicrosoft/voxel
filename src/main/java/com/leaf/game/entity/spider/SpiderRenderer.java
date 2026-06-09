package com.leaf.game.entity.spider;

import com.leaf.game.render.Shader;
import com.leaf.game.render.AssetManager;
import com.leaf.game.render.ModelMesh;
import com.leaf.game.render.Texture;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.List;

public class SpiderRenderer {

    public static void render(SpiderEnemy spider, Shader shader, Matrix4f projection, Matrix4f view) {
        SpiderBody body = spider.getBody();
        Matrix4f pv = new Matrix4f(projection).mul(view);

        // 1. Render Torso Structure
        Matrix4f torsoTransform = new Matrix4f().translate(body.position).rotate(body.orientation);
        renderModel(shader, pv, body.bodyPlan.bodyModel, torsoTransform);

        // 2. Render Leg Segments
        Quaternionf pivot = body.gait().legChainPivotMode.get(body);
        for (int legIdx = 0; legIdx < body.legs.size(); legIdx++) {
            Leg leg = body.legs.get(legIdx);
            KinematicChain chain = leg.chain;
            List<Quaternionf> rotations = chain.getRotations(pivot);

            for (int i = 0; i < chain.segments.size(); i++) {
                SegmentPlan segmentPlan = body.bodyPlan.legs.get(legIdx).segments.get(i);
                Vector3f startPos = (i == 0) ? chain.root : chain.segments.get(i - 1).position;
                Quaternionf rot = rotations.get(i);

                Matrix4f segmentTransform = new Matrix4f().translate(startPos).rotate(rot);
                renderModel(shader, pv, segmentPlan.model, segmentTransform);
            }
        }
    }

    private static void renderModel(Shader shader, Matrix4f pv, DisplayModel model, Matrix4f transformation) {
        for (BlockDisplayModelPiece piece : model.pieces) {
            Matrix4f pieceTransform = new Matrix4f(transformation).mul(piece.transform);
            Matrix4f mvp = new Matrix4f(pv).mul(pieceTransform);
            shader.setUniform("mvp", mvp);

            // Handle emissive elements (Glowing cyan eyes and structural blinking lights)
            if (piece.tags.contains("eye")) {
                shader.setUniform("emissiveMode", 1);
                shader.setUniform("emissiveTint", new Vector3f(0.0f, 2.5f, 2.5f)); // Searing cyan
            } else if (piece.tags.contains("blinking_lights")) {
                shader.setUniform("emissiveMode", 1);
                shader.setUniform("emissiveTint", new Vector3f(0.4f, 2.0f, 0.6f)); // Glowing green
            }

            shader.setUniform("useTexture", 1);

            // Bind the correct texture map
            Texture tex = AssetManager.get().getTexture(piece.blockName);
            if (tex != null) {
                tex.bind();
            }

            // Draw the block geometry
            ModelMesh mesh = AssetManager.get().getModel(piece.blockName);
            if (mesh != null) {
                mesh.render();
            }

            shader.setUniform("useTexture", 0);
            shader.setUniform("emissiveMode", 0);
            shader.setUniform("emissiveTint", new Vector3f(1.0f, 1.0f, 1.0f));
        }
    }

    public static void cleanup() {
        // GPU cleanups are resolved natively via AssetManager.get().cleanup() on shutdown
    }
}