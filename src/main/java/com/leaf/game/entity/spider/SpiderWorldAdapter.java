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
                // Return the position, but snap Y to the top surface of the block
                return new Vector3f(pos.x, by + 1.0f, pos.z);
            }
        }
        return null;
    }

    /**
     * Checks if a specific point is resting on the ground.
     */
    public static boolean isOnGround(World world, Vector3f position, Vector3f downVector) {
        // Cast a very short ray downward (0.15 blocks) to check for a floor
        Vector3f hit = raycastGround(world, position, downVector, 0.15);
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
    public static CollisionResult resolveCollision(World world, Vector3f position, Vector3f velocityDirection) {
        int bx = (int) Math.floor(position.x);
        int by = (int) Math.floor(position.y);
        int bz = (int) Math.floor(position.z);

        if (world.getBlock(bx, by, bz).isSolid()) {
            // The body is inside a block. Push it up to the surface.
            Vector3f correctedPosition = new Vector3f(position.x, by + 1.0f, position.z);
            Vector3f offset = new Vector3f(correctedPosition).sub(position);
            return new CollisionResult(correctedPosition, offset);
        }
        return null; // No collision
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