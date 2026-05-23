package com.leaf.game.util;

import com.leaf.game.core.GameConfig;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {

    // Position is now set externally by Player each frame
    public Vector3f position = new Vector3f(0, 0, 0);

    public float yaw;
    public float pitch;

    private static final float MAX_PITCH = (float) Math.toRadians(89.0f);

    public Camera() {
        this.yaw   = (float) Math.toRadians(-90.0f);
        this.pitch = 0.0f;
    }

    // Called by Player.update() every frame
    public void setPosition(float x, float y, float z) {
        this.position.set(x, y, z);
    }

    public Vector3f getLookDirection() {
        return new Vector3f(
                (float)(Math.cos(pitch) * Math.cos(yaw)),
                (float)(Math.sin(pitch)),
                (float)(Math.cos(pitch) * Math.sin(yaw))
        ).normalize();
    }

    public Vector3f getForward() {
        return new Vector3f(
                (float) Math.cos(yaw),
                0.0f,
                (float) Math.sin(yaw)
        ).normalize();
    }

    public Vector3f getRight() {
        return new Vector3f(getForward())
                .cross(new Vector3f(0.0f, 1.0f, 0.0f))
                .normalize();
    }

    public void clampPitch() {
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
    }

    public Matrix4f getViewMatrix() {
        Vector3f direction = getLookDirection();
        Vector3f target    = new Vector3f(position).add(direction);
        Vector3f up        = new Vector3f(0.0f, 1.0f, 0.0f);
        return new Matrix4f().lookAt(position, target, up);
    }

    public Matrix4f getProjectionMatrix() {
        float fov         = (float) Math.toRadians(GameConfig.fov);
        float aspectRatio = 1280.0f / 720.0f;
        float near        = 0.1f;
        float far         = 1000.0f;
        return new Matrix4f().perspective(fov, aspectRatio, near, far);
    }
}