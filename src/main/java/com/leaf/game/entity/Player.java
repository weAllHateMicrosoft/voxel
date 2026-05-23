// --- FILE: src/main/java/com/leaf/game/entity/Player.java ---
package com.leaf.game.entity;

import com.leaf.game.util.Camera;
import com.leaf.game.util.RaycastResult;
import com.leaf.game.core.GameConfig;
import com.leaf.game.world.World;
import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.*;

public class Player {

    public boolean debugMode = false;
    public Vector3f position;

    // ── HEALTH & FALL DAMAGE ──
    public float health = 20.0f;
    public float maxHealth = 20.0f;
    public float highestY = -1000f; // Tracks peak height during a jump/fall

    private float velocityY = 0.0f;
    private boolean onGround = false;

    private static final float WIDTH      = 0.6f;
    private static final float HEIGHT     = 1.8f;
    private static final float EYE_HEIGHT = 1.6f;

    private boolean lastW = false;
    private double lastWTime = 0;
    private boolean isSprinting = false;

    private boolean lastSpace = false;
    private double lastSpaceTime = 0;

    public Player(float x, float y, float z) {
        position = new Vector3f(x, y, z);
        highestY = y;
    }

    public void update(long window, Camera camera, World world, float deltaTime) {
        double now = glfwGetTime();

        boolean currentW = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        if (currentW && !lastW) {
            if (now - lastWTime < 0.3) isSprinting = true;
            lastWTime = now;
        }
        if (!currentW) isSprinting = false;
        lastW = currentW;

        boolean currentSpace = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        if (currentSpace && !lastSpace) {
            if (now - lastSpaceTime < 0.3) {
                debugMode = !debugMode;
                velocityY = 0.0f;
            }
            lastSpaceTime = now;
        }
        lastSpace = currentSpace;

        float speed = debugMode ? GameConfig.FLY_SPEED : (isSprinting ? GameConfig.SPRINT_SPEED : GameConfig.WALK_SPEED);

        Vector3f forward = camera.getForward();
        Vector3f right   = camera.getRight();

        float dx = 0.0f, dy = 0.0f, dz = 0.0f;

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) { dx += forward.x * speed * deltaTime; dz += forward.z * speed * deltaTime; }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) { dx -= forward.x * speed * deltaTime; dz -= forward.z * speed * deltaTime; }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { dx += right.x * speed * deltaTime; dz += right.z * speed * deltaTime; }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { dx -= right.x * speed * deltaTime; dz -= right.z * speed * deltaTime; }

        if (debugMode) {
            if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) dy += speed * deltaTime;
            if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) dy -= speed * deltaTime;
            position.x += dx; position.y += dy; position.z += dz;
            highestY = position.y; // Never take fall damage in debug mode
        } else {
            velocityY -= GameConfig.GRAVITY * deltaTime;
            if (currentSpace && onGround) {
                velocityY = GameConfig.JUMP_FORCE;
                onGround = false;
            }
            dy = velocityY * deltaTime;

            int substeps = (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) * 10.0f);
            substeps = Math.max(1, substeps);

            float stepX = dx / substeps, stepY = dy / substeps, stepZ = dz / substeps;

            boolean wasOnGround = onGround;
            onGround = false;

            for (int i = 0; i < substeps; i++) {
                if (stepX != 0) { position.x += stepX; if (resolveCollisionX(world, stepX)) { stepX = 0; isSprinting = false; } }
                if (stepY != 0) { position.y += stepY; if (resolveCollisionY(world, stepY)) stepY = 0; }
                if (stepZ != 0) { position.z += stepZ; if (resolveCollisionZ(world, stepZ)) { stepZ = 0; isSprinting = false; } }
            }

            // ── FALL DAMAGE LOGIC ──
            if (!wasOnGround && onGround) { // Just landed!
                float fallDistance = highestY - position.y;
                if (fallDistance > 4.0f) {
                    health -= (fallDistance*0.5 - 4.0f); // 0.5 damage per block after 4
                    if (health <= 0) {
                        System.out.println("You died!");
                        position.set(0, 100, 0); // Respawn in the sky
                        health = maxHealth;
                    }
                }
                highestY = position.y;
            } else if (onGround) {
                highestY = position.y;
            } else {
                if (position.y > highestY) highestY = position.y; // Reaching peak of jump
            }
        }

        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
    }

    private static final float EPSILON = 0.01f;

    private boolean resolveCollisionX(World world, float dx) {
        float halfW = WIDTH / 2.0f;
        int minY = (int) Math.floor(position.y + EPSILON), maxY = (int) Math.floor(position.y + HEIGHT - EPSILON);
        int minZ = (int) Math.floor(position.z - halfW + EPSILON), maxZ = (int) Math.floor(position.z + halfW - EPSILON);

        if (dx > 0) {
            int leadingX = (int) Math.floor(position.x + halfW);
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++)
                if (world.getBlock(leadingX, y, z).isSolid()) { position.x = leadingX - halfW; return true; }
        } else if (dx < 0) {
            int trailingX = (int) Math.floor(position.x - halfW);
            for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++)
                if (world.getBlock(trailingX, y, z).isSolid()) { position.x = trailingX + 1.0f + halfW; return true; }
        }
        return false;
    }

    private boolean resolveCollisionY(World world, float dy) {
        float halfW = WIDTH / 2.0f;
        int minX = (int) Math.floor(position.x - halfW + EPSILON), maxX = (int) Math.floor(position.x + halfW - EPSILON);
        int minZ = (int) Math.floor(position.z - halfW + EPSILON), maxZ = (int) Math.floor(position.z + halfW - EPSILON);

        if (dy > 0) {
            int headY = (int) Math.floor(position.y + HEIGHT);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                if (world.getBlock(x, headY, z).isSolid()) { position.y = headY - HEIGHT; velocityY = 0.0f; return true; }
        } else if (dy < 0) {
            int feetY = (int) Math.floor(position.y);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                if (world.getBlock(x, feetY, z).isSolid()) { position.y = feetY + 1.0f; velocityY = 0.0f; onGround = true; return true; }
        }
        return false;
    }

    private boolean resolveCollisionZ(World world, float dz) {
        float halfW = WIDTH / 2.0f;
        int minX = (int) Math.floor(position.x - halfW + EPSILON), maxX = (int) Math.floor(position.x + halfW - EPSILON);
        int minY = (int) Math.floor(position.y + EPSILON), maxY = (int) Math.floor(position.y + HEIGHT - EPSILON);

        if (dz > 0) {
            int leadingZ = (int) Math.floor(position.z + halfW);
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++)
                if (world.getBlock(x, y, leadingZ).isSolid()) { position.z = leadingZ - halfW; return true; }
        } else if (dz < 0) {
            int trailingZ = (int) Math.floor(position.z - halfW);
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++)
                if (world.getBlock(x, y, trailingZ).isSolid()) { position.z = trailingZ + 1.0f + halfW; return true; }
        }
        return false;
    }

    public RaycastResult getTargetBlock(Camera camera, World world) {
        final float STEP = 0.05f, MAX_REACH = 5.0f;
        float rx = camera.position.x, ry = camera.position.y, rz = camera.position.z;
        org.joml.Vector3f dir = camera.getLookDirection();
        float dx = dir.x * STEP, dy = dir.y * STEP, dz = dir.z * STEP;

        int lastBX = (int) Math.floor(rx), lastBY = (int) Math.floor(ry), lastBZ = (int) Math.floor(rz);
        float distance = 0;

        while (distance < MAX_REACH) {
            rx += dx; ry += dy; rz += dz; distance += STEP;
            int bx = (int) Math.floor(rx), by = (int) Math.floor(ry), bz = (int) Math.floor(rz);

            if (world.getBlock(bx, by, bz).isSolid()) {
                RaycastResult res = new RaycastResult();
                res.hit = true; res.hitX = bx; res.hitY = by; res.hitZ = bz;
                res.placeX = lastBX; res.placeY = lastBY; res.placeZ = lastBZ;
                return res;
            }
            lastBX = bx; lastBY = by; lastBZ = bz;
        }
        return null;
    }
}