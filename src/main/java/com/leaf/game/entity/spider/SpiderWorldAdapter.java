package com.leaf.game.entity.spider;

import com.leaf.game.world.World;
import org.joml.Vector3f;

public class SpiderWorldAdapter {

    /**
     * Casts a ray to find the nearest solid ground.
     * Returns the exact 3D position where the foot should rest, or null if no ground is found.
     */
    public static Vector3f raycastGround(World world, Vector3f origin, Vector3f direction, double maxDist) {
        Vector3f pos = new Vector3f(origin);

        // Prevent zero-direction errors
        if (direction.lengthSquared() < 1e-6f) return null;

        Vector3f step = new Vector3f(direction).normalize().mul(0.1f);
        float travelled = 0f;

        while (travelled < maxDist) {
            pos.add(step);
            travelled += 0.1f;

            int bx = (int) Math.floor(pos.x);
            int by = (int) Math.floor(pos.y);
            int bz = (int) Math.floor(pos.z);

            // Check if the current block is solid
            if (world.getBlock(bx, by, bz).isSolid()) {
                return new Vector3f(pos.x - step.x, by + 1.0f, pos.z - step.z); // step back one
            }
        }
        return null;
    }

    /**
     * Checks if a specific point is resting on the ground.
     */
    public static boolean isOnGround(World world, Vector3f position, Vector3f downVector) {
        Vector3f hit = raycastGround(world, position, downVector, 0.15f);
        return hit != null;
    }

    /**
     * A small wrapper class to hold collision resolution data.
     */
    public static class CollisionResult {
        public final Vector3f position;
        public final Vector3f offset;

        public CollisionResult(Vector3f position, Vector3f offset) {
            this.position = position;
            this.offset = offset;
        }
    }

    /**
     * Resolves body-to-floor collision.
     * If the spider's core sinks into a block, this calculates how far up it needs to be pushed.
     */
    public static CollisionResult resolveCollision(World world, Vector3f position, Vector3f velocityDir) {
        // Mirror Kotlin exactly: start from position - direction, trace in direction for direction.length()
        // With velocityDir = (0, min(-1, -|vy|), 0), this starts 1 unit above and traces down.
        float len = velocityDir.length();
        if (len < 1e-6f) return null;

        // Start point: position minus the velocity direction vector
        Vector3f start = new Vector3f(position).sub(velocityDir);

        // Step along direction in small increments
        Vector3f normalizedDir = new Vector3f(velocityDir).normalize();
        float stepSize = 0.05f;
        float travelled = 0f;
        Vector3f pos = new Vector3f(start);

        while (travelled <= len) {
            int bx = (int) Math.floor(pos.x);
            int by = (int) Math.floor(pos.y);
            int bz = (int) Math.floor(pos.z);

            if (world.getBlock(bx, by, bz).isSolid()) {
                // Surface is the top of this block
                Vector3f hitPos = new Vector3f(position.x, by + 1.0f, position.z);
                Vector3f offset = new Vector3f(hitPos).sub(position);
                return new CollisionResult(hitPos, offset);
            }

            pos.add(new Vector3f(normalizedDir).mul(stepSize));
            travelled += stepSize;
        }
        return null;
    }

    /**
     * Checks if a point is inside water/lava (used for particle effects and sound).
     */
    public static boolean isLiquid(World world, Vector3f position) {
        int bx = (int) Math.floor(position.x);
        int by = (int) Math.floor(position.y);
        int bz = (int) Math.floor(position.z);

        return world.getBlock(bx, by, bz).isLiquid();
    }
}