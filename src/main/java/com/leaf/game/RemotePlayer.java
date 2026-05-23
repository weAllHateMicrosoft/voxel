package com.leaf.game;

import org.joml.Matrix4f;

/**
 * The visual representation of the other player in your world.
 *
 * It's just a colored box (0.6 wide, 1.8 tall — same as your hitbox)
 * rendered at whatever position the network says they're at.
 *
 * No physics, no collision — it's purely cosmetic for now.
 * The mesh is built once in the constructor and never rebuilt.
 * We move it by changing the model matrix each frame (translation).
 */
public class RemotePlayer {

    private final Mesh mesh;

    // Current position (updated every frame from network data)
    public float x, y, z;

    // Distinct color so you can tell who's who: a warm orange
    private static final float R = 0.9f, G = 0.5f, B = 0.1f;

    public RemotePlayer() {
        mesh = buildPlayerBox();
    }

    /**
     * Call this in the render loop.
     * Translates the box to the remote player's position and renders it.
     *
     * @param shader     your game's shader (already bound)
     * @param projection the camera's projection matrix
     * @param view       the camera's view matrix
     */
    public void render(Shader shader, Matrix4f projection, Matrix4f view) {
        // Build a model matrix that places the box at (x, y, z)
        // The box is defined at the origin — translation moves it into the world.
        Matrix4f model = new Matrix4f().translate(x, y, z);
        Matrix4f mvp   = new Matrix4f(projection).mul(view).mul(model);
        shader.setUniform("mvp", mvp);
        mesh.render();
    }

    public void cleanup() {
        mesh.cleanup();
    }

    /**
     * Builds a 0.6 × 1.8 × 0.6 box at the local origin (0, 0, 0).
     * The box represents a player body. We'll translate it at render time.
     *
     * This uses the same face-building pattern as World.buildMesh().
     */
    private Mesh buildPlayerBox() {
        float w = 0.3f;  // half-width (full width = 0.6)
        float h = 1.8f;  // full height

        // Brightness per face — same convention as the world mesh
        float top    = R * 1.0f;  float topG    = G * 1.0f;  float topB    = B * 1.0f;
        float side   = R * 0.75f; float sideG   = G * 0.75f; float sideB   = B * 0.75f;
        float bottom = R * 0.5f;  float bottomG = G * 0.5f;  float bottomB = B * 0.5f;

        // Each vertex: x, y, z, r, g, b  (6 floats)
        float[] verts = {
                // TOP face (y = h)
                -w, h, -w,  top, topG, topB,
                w, h, -w,  top, topG, topB,
                w, h,  w,  top, topG, topB,
                -w, h,  w,  top, topG, topB,

                // BOTTOM face (y = 0)
                -w, 0, -w,  bottom, bottomG, bottomB,
                w, 0, -w,  bottom, bottomG, bottomB,
                w, 0,  w,  bottom, bottomG, bottomB,
                -w, 0,  w,  bottom, bottomG, bottomB,

                // FRONT face (+Z)
                -w, 0,  w,  side, sideG, sideB,
                w, 0,  w,  side, sideG, sideB,
                w, h,  w,  side, sideG, sideB,
                -w, h,  w,  side, sideG, sideB,

                // BACK face (-Z)
                w, 0, -w,  side, sideG, sideB,
                -w, 0, -w,  side, sideG, sideB,
                -w, h, -w,  side, sideG, sideB,
                w, h, -w,  side, sideG, sideB,

                // RIGHT face (+X)
                w, 0, -w,  side, sideG, sideB,
                w, 0,  w,  side, sideG, sideB,
                w, h,  w,  side, sideG, sideB,
                w, h, -w,  side, sideG, sideB,

                // LEFT face (-X)
                -w, 0,  w,  side, sideG, sideB,
                -w, 0, -w,  side, sideG, sideB,
                -w, h, -w,  side, sideG, sideB,
                -w, h,  w,  side, sideG, sideB,
        };

        // Two triangles per face, 6 faces = 12 triangles = 36 indices
        int[] indices = {
                0,  1,  2,   2,  3,  0,   // top
                4,  6,  5,   6,  4,  7,   // bottom (flipped winding for correct facing)
                8,  9, 10,  10, 11,  8,   // front
                12, 13, 14,  14, 15, 12,   // back
                16, 17, 18,  18, 19, 16,   // right
                20, 21, 22,  22, 23, 20,   // left
        };

        return new Mesh(verts, indices);
    }
}