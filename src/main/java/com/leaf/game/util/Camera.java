package com.leaf.game.util;

import com.leaf.game.core.GameConfig;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class Camera {

    // Position set externally by Player each frame
    public Vector3f position = new Vector3f(0, 0, 0);

    public float yaw;
    public float pitch;

    /**
     * Optional dynamic FOV override (degrees).
     * If >= 0, getProjectionMatrix() uses this instead of GameConfig.fov.
     * Set by Window.java each frame from FlightController.getFovBoost().
     * Reset to -1 when flight is inactive so the debug slider still works.
     */
    public float dynamicFov = -1f;

    private static final float MAX_PITCH = (float) Math.toRadians(89.0f);

    // ── GRAVITY REORIENTATION ─────────────────────────────────────────────────
    // yaw/pitch describe the look in a LOCAL Y-up frame; this quaternion then
    // rotates that frame into world space so "up" can be any direction (the
    // player walks on walls/ceilings under flipped gravity). Identity = normal.
    // gravityRot is animated toward gravityTarget so flips are smooth, not jarring.
    public  final Quaternionf gravityRot    = new Quaternionf();
    private final Quaternionf gravityTarget = new Quaternionf();

    public Camera() {
        this.yaw   = (float) Math.toRadians(-90.0f);
        this.pitch = 0.0f;
    }

    /** Re-aim the camera so local up (0,1,0) maps to {@code worldUp} (animated). */
    public void setGravityUp(Vector3f worldUp) {
        gravityTarget.rotationTo(0f, 1f, 0f, worldUp.x, worldUp.y, worldUp.z);
    }

    /** Like {@link #setGravityUp} but applies instantly (no flip animation) — for respawn. */
    public void snapGravityUp(Vector3f worldUp) {
        setGravityUp(worldUp);
        gravityRot.set(gravityTarget);
    }

    /** Ease the live orientation toward the target each frame (smooth flip). */
    public void tickGravity(float dt) {
        gravityRot.slerp(gravityTarget, Math.min(1f, dt * 9f));
        gravityRot.normalize();
    }

    /** World-space up vector under the current gravity orientation. */
    public Vector3f getUp() {
        return gravityRot.transform(new Vector3f(0f, 1f, 0f));
    }

    public void setPosition(float x, float y, float z) {
        this.position.set(x, y, z);
    }

    public Vector3f getLookDirection() {
        Vector3f local = new Vector3f(
                (float)(Math.cos(pitch) * Math.cos(yaw)),
                (float)(Math.sin(pitch)),
                (float)(Math.cos(pitch) * Math.sin(yaw))
        ).normalize();
        return gravityRot.transform(local);
    }

    /** Yaw-only forward (no pitch component) in the ground plane. Used for movement. */
    public Vector3f getForward() {
        Vector3f local = new Vector3f(
                (float) Math.cos(yaw),
                0.0f,
                (float) Math.sin(yaw)
        ).normalize();
        return gravityRot.transform(local);
    }

    public Vector3f getRight() {
        // local right = local forward × local up(0,1,0)
        Vector3f local = new Vector3f(
                -(float) Math.sin(yaw),
                0.0f,
                (float) Math.cos(yaw)
        ).normalize();
        return gravityRot.transform(local);
    }

    /** When true (gravity is flipped), the ±89° pitch limit is lifted for full look freedom. */
    public boolean freeLook = false;

    public void clampPitch() {
        // ALWAYS clamp the pitch. The gravity quaternion handles the macro-rotation,
        // so local pitch never needs to exceed 89 degrees. This prevents the
        // "neck-breaking" upside-down camera flip.
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
    }

    /**
     * Standard view matrix — no roll baked in.
     * Window.java applies a separate rotateZ(roll) matrix on top so frustum
     * extraction and up-vector remain correct.
     */
    public Matrix4f getViewMatrix() {
        Vector3f direction = getLookDirection();
        Vector3f target    = new Vector3f(position).add(direction);
        Vector3f up        = getUp();          // reorients with gravity
        return new Matrix4f().lookAt(position, target, up);
    }

    /**
     * Projection matrix.
     * Uses dynamicFov if set (>= 0), otherwise falls back to GameConfig.fov.
     * Window.java sets dynamicFov = GameConfig.fov + flightController.getFovBoost()
     * when flight is active, and resets it to -1 otherwise.
     */
    public Matrix4f getProjectionMatrix() {
        float fovDeg      = (dynamicFov >= 0f) ? dynamicFov : GameConfig.fov;
        float fovRad      = (float) Math.toRadians(fovDeg);
        float aspectRatio = 1280.0f / 720.0f;
        float near        = 0.1f;
        float far         = 1000.0f;
        return new Matrix4f().perspective(fovRad, aspectRatio, near, far);
    }
}