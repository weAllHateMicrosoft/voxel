package com.leaf.game.entity.spider;

import com.leaf.game.entity.Enemy;
import com.leaf.game.util.SpiderMath;
import com.leaf.game.world.World;
import org.joml.Vector3f;
import java.util.List;

public class SpiderEnemy extends Enemy {

    public enum BehaviorMode { HOSTILE, IDLE, FOLLOW_TARGET, RIDDEN }

    public BehaviorMode mode = BehaviorMode.HOSTILE;
    public Vector3f laserTarget = new Vector3f();
    public Vector3f rideInput = new Vector3f();

    private SpiderBody body;
    private boolean initialized = false;
    private float attackCooldown = 0f;

    public SpiderEnemy(float x, float y, float z) {
        super(x, y, z, Type.SPIDER);
    }

    public SpiderBody getBody() {
        return body;
    }

    @Override
    public void update(float dt, World world, Vector3f playerPos, List<Enemy> allEnemies) {
        // Standard state resets
        framePlayerDamage = 0f;
        wantsToThrow = false;
        pendingGrabImpact = false;

        if (!alive) {
            if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);
            return;
        }
        if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);
        if (isGrabbed) return;
        if (isThrown) { isThrown = false; }

        if (!initialized) {
            SpiderOptions options = SpiderPresets.hexBot(4, 1.0f);

            // Standard Walk Gait
            options.walkGait.maxSpeed = 8.0f;
            options.walkGait.moveAcceleration = 20.0f;
            options.walkGait.legMoveSpeed = 26.0f;

            // Fast Gallop Gait (Used for Laser Chasing & Riding)
            options.gallopGait.maxSpeed = 16.0f;
            options.gallopGait.moveAcceleration = 35.0f;
            options.gallopGait.legMoveSpeed = 45.0f;

            this.body = SpiderBody.fromPosition(world, this.position, 0f,
                    options.bodyPlan, options.walkGait, options.gallopGait);

            for (Leg leg : body.legs) {
                leg.setStepListener(l -> {
                    com.leaf.game.core.AudioManager.playAt("block_stone", l.endEffector, (Vector3f)null, 25f);
                });
            }
            this.initialized = true;
        }

        if (this.knockbackVelX != 0f || this.knockbackVelZ != 0f) {
            body.velocity.x += this.knockbackVelX;
            body.velocity.z += this.knockbackVelZ;
            this.knockbackVelX = 0f;
            this.knockbackVelZ = 0f;
        }

        if (attackCooldown > 0f) attackCooldown -= dt;

        // ── BEHAVIOR SWITCH ──
        if (mode == BehaviorMode.HOSTILE) {
            body.gallop = false;
            Vector3f direction = new Vector3f(playerPos).sub(body.position);
            direction.y = 0;
            float dist = direction.length();
            if (dist > 0.001f) direction.normalize();

            body.rotateTowards(direction);

            float currentSpeed = SpiderMath.horizontalLength(body.velocity);
            float decelerateDist = (currentSpeed * currentSpeed) / (2f * body.gait().moveAcceleration);
            float stopDist = 2.5f;

            if (dist > stopDist + decelerateDist) {
                body.walkAt(new Vector3f(direction).mul(body.gait().maxSpeed));
            } else {
                body.walkAt(new Vector3f(0f, 0f, 0f));
            }

            if (dist <= stopDist * 1.2f && attackCooldown <= 0f) {
                framePlayerDamage = 25f;
                attackCooldown = 1.2f;
                com.leaf.game.core.AudioManager.playAt("enemy_swing", position, (Vector3f)null, 30f);
            }

        } else if (mode == BehaviorMode.IDLE) {
            body.gallop = false;
            body.walkAt(new Vector3f(0f, 0f, 0f));

        } else if (mode == BehaviorMode.FOLLOW_TARGET) {
            body.gallop = true; // Use the fast gait to chase the laser!
            Vector3f direction = new Vector3f(laserTarget).sub(body.position);
            direction.y = 0;
            float dist = direction.length();
            if (dist > 0.001f) direction.normalize();

            body.rotateTowards(direction);

            float currentSpeed = SpiderMath.horizontalLength(body.velocity);
            float decelerateDist = (currentSpeed * currentSpeed) / (2f * body.gait().moveAcceleration);

            if (dist > 1.5f + decelerateDist) {
                body.walkAt(new Vector3f(direction).mul(body.gait().maxSpeed));
            } else {
                body.walkAt(new Vector3f(0f, 0f, 0f));
            }

        } else if (mode == BehaviorMode.RIDDEN) {
            body.gallop = true; // Sprint while riding
            if (rideInput.lengthSquared() > 0.001f) {
                body.rotateTowards(rideInput);
                body.walkAt(new Vector3f(rideInput).mul(body.gait().maxSpeed));
            } else {
                body.walkAt(new Vector3f(0f, 0f, 0f));
            }
        }

        body.update();
        this.position.set(body.position);
    }
}