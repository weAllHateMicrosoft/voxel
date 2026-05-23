package com.leaf.game;

import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.*;

public class Player {

    public boolean debugMode = false;
    public Vector3f position;

    private float velocityY = 0.0f;
    private boolean onGround = false;

    // Player box dimensions
    private static final float WIDTH      = 0.6f;
    private static final float HEIGHT     = 1.8f;
    private static final float EYE_HEIGHT = 1.6f;

    // Physics constants
    private static final float GRAVITY      = 35.0f; // Tweaked for a better fall feel
    private static final float JUMP_FORCE   = GameConfig.JUMP_FORCE;
    private static final float WALK_SPEED   = GameConfig.WALK_SPEED;
    private static final float SPRINT_SPEED = GameConfig.SPRINT_SPEED;
    private static final float FLY_SPEED    = GameConfig.FLY_SPEED;

    // Double-Click Tracking
    private boolean lastW = false;
    private double lastWTime = 0;
    private boolean isSprinting = false;

    private boolean lastSpace = false;
    private double lastSpaceTime = 0;

    public Player(float x, float y, float z) {
        position = new Vector3f(x, y, z);
    }

    public void update(long window, Camera camera, World world, float deltaTime) {
        double now = glfwGetTime();

        // ── 1. DOUBLE CLICK TRACKING (W to Sprint) ──
        boolean currentW = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        if (currentW && !lastW) { // Key was just pressed
            if (now - lastWTime < 0.3) {
                isSprinting = true;
            }
            lastWTime = now;
        }
        if (!currentW) { // Cancel sprint if W is released
            isSprinting = false;
        }
        lastW = currentW;

        // ── 2. DOUBLE CLICK TRACKING (Space to toggle Fly/Debug) ──
        boolean currentSpace = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (currentSpace && !lastSpace) {
            if (now - lastSpaceTime < 0.3) {
                debugMode = !debugMode; // Toggle mode
                velocityY = 0.0f;       // Stop falling instantly
            }
            lastSpaceTime = now;
        }
        lastSpace = currentSpace;

        // ── 3. CALCULATE MOVEMENT DELTAS ──
        float speed = debugMode ? FLY_SPEED : (isSprinting ? SPRINT_SPEED : WALK_SPEED);

        // We use getForward() so pitch NEVER affects horizontal movement!
        Vector3f forward = camera.getForward();
        Vector3f right   = camera.getRight();

        float dx = 0.0f;
        float dy = 0.0f;
        float dz = 0.0f;

        // WASD Horizontal Input
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            dx += forward.x * speed * deltaTime;
            dz += forward.z * speed * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            dx -= forward.x * speed * deltaTime;
            dz -= forward.z * speed * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            dx += right.x * speed * deltaTime;
            dz += right.z * speed * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            dx -= right.x * speed * deltaTime;
            dz -= right.z * speed * deltaTime;
        }

        // ── 4. APPLY MOVEMENT (Axis-By-Axis for perfect collisions) ──
        if (debugMode) {
            // Debug Mode: No collisions, direct Y control
            if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
                dy += speed * deltaTime;
            }
            if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
                dy -= speed * deltaTime;
            }
            position.x += dx;
            position.y += dy;
            position.z += dz;
        } else {
            // Survival Mode: Apply Gravity
            velocityY -= GRAVITY * deltaTime;

            // Jump
            if (currentSpace && onGround) {
                velocityY = JUMP_FORCE;
                onGround = false;
            }
            dy = velocityY * deltaTime;

            // X AXIS
            if (dx != 0) {
                position.x += dx;
                resolveCollisionX(world, dx);
            }

            // Y AXIS
            onGround = false; // Reset every frame
            if (dy != 0) {
                position.y += dy;
                resolveCollisionY(world, dy);
            }

            // Z AXIS
            if (dz != 0) {
                position.z += dz;
                resolveCollisionZ(world, dz);
            }
        }

        // ── 5. UPDATE CAMERA ──
        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
    }

    // ────────────────────────────────────────────────────────────────────────
    // AXIS-BY-AXIS COLLISION RESOLUTION
    // ────────────────────────────────────────────────────────────────────────

    private void resolveCollisionX(World world, float dx) {
        float halfW = WIDTH / 2.0f;
        int minX = (int) Math.floor(position.x - halfW);
        int maxX = (int) Math.floor(position.x + halfW);
        int minY = (int) Math.floor(position.y);
        int maxY = (int) Math.floor(position.y + HEIGHT - 0.01f);
        int minZ = (int) Math.floor(position.z - halfW);
        int maxZ = (int) Math.floor(position.z + halfW);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(x, y, z).isSolid()) {
                        // Push player out based on the direction they were moving
                        if (dx > 0) position.x = x - halfW;
                        else if (dx < 0) position.x = x + 1.0f + halfW;
                        return; // Stop checking once we resolve
                    }
                }
            }
        }
    }

    private void resolveCollisionY(World world, float dy) {
        float halfW = WIDTH / 2.0f;
        int minX = (int) Math.floor(position.x - halfW);
        int maxX = (int) Math.floor(position.x + halfW);
        int minY = (int) Math.floor(position.y);
        int maxY = (int) Math.floor(position.y + HEIGHT - 0.01f);
        int minZ = (int) Math.floor(position.z - halfW);
        int maxZ = (int) Math.floor(position.z + halfW);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(x, y, z).isSolid()) {
                        if (dy > 0) { // Hit ceiling
                            position.y = y - HEIGHT;
                            velocityY = 0;
                        } else if (dy < 0) { // Hit floor
                            position.y = y + 1.0f;
                            velocityY = 0;
                            onGround = true;
                        }
                        return;
                    }
                }
            }
        }
    }

    private void resolveCollisionZ(World world, float dz) {
        float halfW = WIDTH / 2.0f;
        int minX = (int) Math.floor(position.x - halfW);
        int maxX = (int) Math.floor(position.x + halfW);
        int minY = (int) Math.floor(position.y);
        int maxY = (int) Math.floor(position.y + HEIGHT - 0.01f);
        int minZ = (int) Math.floor(position.z - halfW);
        int maxZ = (int) Math.floor(position.z + halfW);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(x, y, z).isSolid()) {
                        if (dz > 0) position.z = z - halfW;
                        else if (dz < 0) position.z = z + 1.0f + halfW;
                        return;
                    }
                }
            }
        }
    }
}