package com.leaf.game;

import org.joml.Vector3f;

public class DroppedItem {
    public Vector3f position;
    public Block blockType;
    public float age;
    public boolean alive = true;

    // ── PERSISTENT CHASE STATE ──
    // Once true, the item will never stop chasing the player
    private boolean isLockedOn = false;

    // Unique coordinates to identify this exact item on the network
    public final int originX, originY, originZ;

    public DroppedItem(int bx, int by, int bz, Block blockType) {
        this.originX = bx;
        this.originY = by;
        this.originZ = bz;

        // Visual floating coordinates (with tiny offsets)
        float rx = (float) (Math.random() * 0.4f - 0.2f);
        float rz = (float) (Math.random() * 0.4f - 0.2f);
        this.position = new Vector3f(bx + 0.5f + rx, by + 0.2f, bz + 0.5f + rz);
        this.blockType = blockType;
        this.age = (float) (Math.random() * 100.0f);
    }

    public void update(float deltaTime, Vector3f playerPos) {
        this.age += deltaTime;
        float dist = position.distance(playerPos);

        // ── LOCK-ON MAGNET PHYSICS ──
        // If the player ever enters the 3.5 block radius, lock on permanently!
        if (dist < 3.5f) {
            isLockedOn = true;
        }

        // If locked on, chase the player's chest relentlessly, even if they run away
        if (isLockedOn) {
            // Target the player's chest (feet position + 0.9f)
            Vector3f target = new Vector3f(playerPos.x, playerPos.y + 0.9f, playerPos.z);
            Vector3f direction = new Vector3f(target).sub(position);
            direction.normalize();

            // Exponential speed: gets faster and faster the closer it gets!
            float pullSpeed = 6.0f / Math.max(0.1f, dist);

            position.add(direction.mul(pullSpeed * deltaTime));
        }
    }
}