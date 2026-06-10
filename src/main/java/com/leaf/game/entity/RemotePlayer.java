package com.leaf.game.entity;

import com.leaf.game.render.Mesh;
import com.leaf.game.render.Shader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

public class RemotePlayer {

    private final Mesh mesh;
    private final Mesh barBg;   // dark backing of the floating health bar
    private final Mesh barFg;   // green fill (width = health fraction)

    public float x, y, z;
    public float yaw, pitch, roll;

    public float targetX, targetY, targetZ;
    public float targetYaw, targetPitch, targetRoll;

    public int targetState = 0; // Sync'd state
    public boolean targetHooked = false;
    public float targetHookX, targetHookY, targetHookZ;

    /** Synced teammate health (drives the floating bar). */
    public float health = 1f, maxHealth = 1f;

    // Local Caches for Bandwidth-Free Trails
    public final List<Vector3f> dashTrail = new ArrayList<>();
    public final List<Vector3f> rewindTrail = new ArrayList<>();
    private float dashTrailAge = 0f;
    private float snapshotTimer = 0f;

    public RemotePlayer() {
        mesh  = buildSteveModel();
        barBg = buildQuad(0.10f, 0.02f, 0.02f);   // dark red backing
        barFg = buildQuad(0.15f, 0.95f, 0.30f);   // bright green fill
    }

    public void update(float rawDeltaTime) {
        float lerpSpeed = 15.0f;
        x += (targetX - x) * lerpSpeed * rawDeltaTime;
        y += (targetY - y) * lerpSpeed * rawDeltaTime;
        z += (targetZ - z) * lerpSpeed * rawDeltaTime;

        yaw   += (targetYaw - yaw) * lerpSpeed * rawDeltaTime;
        pitch += (targetPitch - pitch) * lerpSpeed * rawDeltaTime;
        roll  += (targetRoll - roll) * lerpSpeed * rawDeltaTime;

        // Populate Dash Trail locally based on synced state
        if (targetState == 1) { // 1 = Dashing
            dashTrailAge = 0f;
            if (dashTrail.isEmpty() || new Vector3f(x, y, z).distance(dashTrail.get(dashTrail.size() - 1)) > 0.25f) {
                dashTrail.add(new Vector3f(x, y, z));
                if (dashTrail.size() > 10) dashTrail.remove(0);
            }
        } else {
            dashTrailAge += rawDeltaTime;
            if (dashTrailAge > 0.45f) dashTrail.clear();
        }

        // Populate Rewind Trail locally (Record at ~20Hz)
        snapshotTimer += rawDeltaTime;
        if (snapshotTimer >= 0.05f) {
            snapshotTimer = 0f;
            rewindTrail.add(new Vector3f(x, y, z));
            if (rewindTrail.size() > 28) rewindTrail.remove(0);
        }
    }

    public void render(Shader shader, Matrix4f projection, Matrix4f view) {
        // Roll & Pitch must be applied to fully reflect skimming, soaring, and cannonball tumbling
        Matrix4f model = new Matrix4f()
                .translate(x, y, z)
                .rotateY(yaw)
                .rotateX(pitch)
                .rotateZ(roll);

        Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
        shader.setUniform("mvp", mvp);
        mesh.render();

        renderHealthBar(shader, projection, view);
    }

    /** A small camera-facing health bar floating above the teammate's head. */
    private void renderHealthBar(Shader shader, Matrix4f projection, Matrix4f view) {
        float frac = (maxHealth > 0f) ? Math.max(0f, Math.min(1f, health / maxHealth)) : 0f;

        // Camera right/up/toward in WORLD space, read from the view rotation rows.
        Vector3f right   = new Vector3f(view.m00(), view.m10(), view.m20());
        Vector3f up      = new Vector3f(view.m01(), view.m11(), view.m21());
        Vector3f toward  = new Vector3f(view.m02(), view.m12(), view.m22()); // bar→camera
        Vector3f nrm     = new Vector3f(right).cross(up);

        float barW = 1.0f, barH = 0.14f, headY = 2.20f;
        // Bottom-left corner of the (centred) bar.
        Vector3f origin = new Vector3f(x, y + headY, z)
                .fma(-0.5f * barW, right).fma(-0.5f * barH, up);

        Matrix4f vp = new Matrix4f(projection).mul(view);

        // Backing (full width), then green fill (left-anchored, width = frac), nudged
        // toward the camera so it sits in front of the backing without z-fighting.
        drawBar(shader, vp, origin, right, up, nrm, barW, barH);
        Vector3f fgOrigin = new Vector3f(origin).fma(0.01f, toward);
        if (frac > 0f) drawBar(shader, vp, fgOrigin, right, up, nrm, barW * frac, barH);
    }

    private void drawBar(Shader shader, Matrix4f vp, Vector3f o,
                         Vector3f right, Vector3f up, Vector3f nrm, float sx, float sy) {
        // Columns = (right*sx, up*sy, normal, origin) — maps the unit quad [0,1]² into world.
        Matrix4f model = new Matrix4f(
                right.x * sx, right.y * sx, right.z * sx, 0f,
                up.x    * sy, up.y    * sy, up.z    * sy, 0f,
                nrm.x,        nrm.y,        nrm.z,        0f,
                o.x,          o.y,          o.z,          1f);
        shader.setUniform("mvp", new Matrix4f(vp).mul(model));
        (sx < 0.999f ? barFg : barBg).render();
    }

    public void cleanup() {
        mesh.cleanup();
        barBg.cleanup();
        barFg.cleanup();
    }

    /** A unit quad in local [0,1]² (z=0), single colour, matching the 10-float vertex layout. */
    private Mesh buildQuad(float r, float g, float b) {
        float[] v = {
            // x, y, z,   r, g, b, a,   nx, ny, nz
            0f, 0f, 0f,   r, g, b, 1f,  0f, 0f, 1f,
            1f, 0f, 0f,   r, g, b, 1f,  0f, 0f, 1f,
            1f, 1f, 0f,   r, g, b, 1f,  0f, 0f, 1f,
            0f, 1f, 0f,   r, g, b, 1f,  0f, 0f, 1f,
        };
        int[] idx = { 0, 1, 2, 2, 3, 0 };
        return new Mesh(v, idx);
    }

    // --- Builds a hierarchical "Steve" out of colored boxes ---
    private Mesh buildSteveModel() {
        List<Float> verts = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        int[] vIndex = {0};

        // Colors
        float[] skin  = {0.90f, 0.67f, 0.52f}; // Peach
        float[] shirt = {0.00f, 0.66f, 0.66f}; // Cyan
        float[] pants = {0.17f, 0.17f, 0.58f}; // Blue
        float[] shoes = {0.25f, 0.25f, 0.25f}; // Grey

        // Legs (Y: 0 to 0.75)
        addBox(verts, idx, vIndex, -0.2f, 0.0f, -0.1f, 0.2f, 0.75f, 0.1f, pants);
        // Torso (Y: 0.75 to 1.5)
        addBox(verts, idx, vIndex, -0.2f, 0.75f, -0.1f, 0.2f, 1.5f, 0.1f, shirt);
        // Head (Y: 1.5 to 1.9)
        addBox(verts, idx, vIndex, -0.2f, 1.5f, -0.2f, 0.2f, 1.9f, 0.2f, skin);
        // Arms (Attached to torso sides)
        addBox(verts, idx, vIndex, -0.3f, 0.75f, -0.1f, -0.2f, 1.5f, 0.1f, shirt); // Left arm
        addBox(verts, idx, vIndex,  0.2f, 0.75f, -0.1f,  0.3f, 1.5f, 0.1f, shirt); // Right arm

        // Convert lists to arrays
        float[] vArr = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) vArr[i] = verts.get(i);
        int[] iArr = new int[idx.size()];
        for (int i = 0; i < idx.size(); i++) iArr[i] = idx.get(i);

        return new Mesh(vArr, iArr);
    }

    // Helper to generate a 3D box and append it to our Mesh lists
    private void addBox(List<Float> verts, List<Integer> idx, int[] vIndex,
                        float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float[] col) {

        float[][] corners = {
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}, // Front
                {maxX, minY, minZ}, {minX, minY, minZ}, {minX, maxY, minZ}, {maxX, maxY, minZ}, // Back
                {minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}, // Top
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, minY, minZ}, {minX, minY, minZ}, // Bottom
                {maxX, minY, maxZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, // Right
                {minX, minY, minZ}, {minX, minY, maxZ}, {minX, maxY, maxZ}, {minX, maxY, minZ}  // Left
        };

        for (int face = 0; face < 6; face++) {
            float shade = (face == 2) ? 1.0f : (face == 3 ? 0.5f : 0.8f);
            for (int i = 0; i < 4; i++) {
                float[] corner = corners[face * 4 + i];
                verts.add(corner[0]); verts.add(corner[1]); verts.add(corner[2]);
                verts.add(col[0]*shade); verts.add(col[1]*shade); verts.add(col[2]*shade);
                verts.add(1.0f); // <--- THE MISSING ALPHA CHANNEL
                verts.add(0f); verts.add(1f); verts.add(0f); // Dummy normals
            }
            int b = vIndex[0];
            idx.add(b); idx.add(b+1); idx.add(b+2); idx.add(b+2); idx.add(b+3); idx.add(b);
            vIndex[0] += 4;
        }
    }
}