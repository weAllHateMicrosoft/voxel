package com.leaf.game.entity.spider;

import com.leaf.game.render.Mesh;
import com.leaf.game.render.Shader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.List;

public class SpiderRenderer {

    private static Mesh spiderBodyMesh;
    private static Mesh spiderLegMesh;

    public static void render(SpiderEnemy spider, Shader shader, Matrix4f projection, Matrix4f view) {
        if (spiderBodyMesh == null) spiderBodyMesh = buildColorCube(0.12f, 0.12f, 0.14f); // Dark exoskeleton
        if (spiderLegMesh == null) spiderLegMesh   = buildColorCube(0.18f, 0.18f, 0.20f); // Lighter gray joints

        SpiderBody body = spider.getBody();
        Matrix4f pv = new Matrix4f(projection).mul(view);

        // 1. Render Main Body (Thorax/Abdomen)
        // Stretched and flattened to look like a mechanical spider torso
        Matrix4f bodyMat = new Matrix4f()
                .translate(body.position)
                .rotate(body.orientation)
                .scale(1.3f, 0.6f, 1.8f);
        shader.setUniform("mvp", new Matrix4f(pv).mul(bodyMat));
        spiderBodyMesh.render();

        // 2. Render Eyes (Glowing Red)
        Matrix4f eyeL = new Matrix4f().translate(body.position).rotate(body.orientation)
                .translate(-0.3f, 0.1f, 0.9f).scale(0.15f);
        Matrix4f eyeR = new Matrix4f().translate(body.position).rotate(body.orientation)
                .translate(0.3f, 0.1f, 0.9f).scale(0.15f);

        shader.setUniform("emissiveMode", 1);
        shader.setUniform("emissiveTint", new Vector3f(2.0f, 0.2f, 0.2f)); // Intense red glow
        shader.setUniform("mvp", new Matrix4f(pv).mul(eyeL));
        spiderBodyMesh.render(); // Reuse mesh, color overridden by emissive tint
        shader.setUniform("mvp", new Matrix4f(pv).mul(eyeR));
        spiderBodyMesh.render();
        shader.setUniform("emissiveMode", 0);
        shader.setUniform("emissiveTint", new Vector3f(1f, 1f, 1f));

        // 3. Render Legs via IK Chain
        Quaternionf pivot = body.gait().legChainPivotMode.get(body);

        for (Leg leg : body.legs) {
            KinematicChain chain = leg.chain;
            List<Quaternionf> rotations = chain.getRotations(pivot);

            for (int i = 0; i < chain.segments.size(); i++) {
                ChainSegment seg = chain.segments.get(i);
                Vector3f startPos = (i == 0) ? chain.root : chain.segments.get(i - 1).position;
                Quaternionf rot = rotations.get(i);

                // Taper the legs: thicker at the base (coxa/femur), thinner at the tips
                float thickness = 0.22f - (i * 0.04f);

                Matrix4f segMat = new Matrix4f()
                        .translate(startPos)
                        .rotate(rot)
                        .scale(thickness, thickness, seg.length)
                        // Translate Z by +0.5 to shift the unit cube so its base is exactly at startPos
                        // and it stretches forward to end exactly at segment.length
                        .translate(0f, 0f, 0.5f);

                shader.setUniform("mvp", new Matrix4f(pv).mul(segMat));
                spiderLegMesh.render();
            }
        }
    }

    /**
     * Generates a simple colored unit cube. Duplicated here to keep SpiderRenderer
     * completely self-contained and decoupled from Window.java.
     */
    private static Mesh buildColorCube(float r, float g, float b) {
        float[][] c = {
                {-.5f,-.5f, .5f},{ .5f,-.5f, .5f},{ .5f, .5f, .5f},{-.5f, .5f, .5f}, // front
                { .5f,-.5f,-.5f},{-.5f,-.5f,-.5f},{-.5f, .5f,-.5f},{ .5f, .5f,-.5f}, // back
                {-.5f, .5f,-.5f},{ .5f, .5f,-.5f},{ .5f, .5f, .5f},{-.5f, .5f, .5f}, // top
                {-.5f,-.5f, .5f},{ .5f,-.5f, .5f},{ .5f,-.5f,-.5f},{-.5f,-.5f,-.5f}, // bottom
                { .5f,-.5f, .5f},{ .5f,-.5f,-.5f},{ .5f, .5f,-.5f},{ .5f, .5f, .5f}, // right
                {-.5f,-.5f,-.5f},{-.5f,-.5f, .5f},{-.5f, .5f, .5f},{-.5f, .5f,-.5f}, // left
        };
        float[] v = new float[24 * 10];
        int[]   idx = new int[36];
        int vo = 0, io = 0;
        for (int face = 0; face < 6; face++) {
            float shade = (face == 2) ? 1.0f : (face == 3 ? 0.6f : 0.82f);
            for (int i = 0; i < 4; i++) {
                float[] p = c[face * 4 + i];
                v[vo++] = p[0]; v[vo++] = p[1]; v[vo++] = p[2];
                v[vo++] = r * shade; v[vo++] = g * shade; v[vo++] = b * shade; v[vo++] = 1f;
                v[vo++] = 0f; v[vo++] = 1f; v[vo++] = 0f;
            }
            int base = face * 4;
            idx[io++] = base; idx[io++] = base + 1; idx[io++] = base + 2;
            idx[io++] = base + 2; idx[io++] = base + 3; idx[io++] = base;
        }
        return new Mesh(v, idx);
    }

    public static void cleanup() {
        if (spiderBodyMesh != null) { spiderBodyMesh.cleanup(); spiderBodyMesh = null; }
        if (spiderLegMesh != null)  { spiderLegMesh.cleanup();  spiderLegMesh = null; }
    }
}