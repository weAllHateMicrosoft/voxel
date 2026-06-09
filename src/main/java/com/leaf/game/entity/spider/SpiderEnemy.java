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
    private float tickAccumulator = 0f;

    // ── CUSTOMIZATION FIELDS FOR THE COMMAND ──
    public int customLegCount = 6;
    public float customScale = 1.0f;

    public SpiderEnemy(float x, float y, float z) {
        super(x, y, z, Type.SPIDER);
    }

    public SpiderBody getBody() {
        return body;
    }

    // ── EXISTING FEATURE CALL: ACCELERATE ROTATION ON HIT ──
    @Override
    public boolean applyDamage(float amount) {
        // Since it's a friendly sandbox spider, we don't let it die.
        // Instead, we apply a satisfying rotational impulse using the built-in physics!
        if (body != null) {
            // Spin primarily around the vertical Y axis with slight diagonal variance for a organic look
            Vector3f spinAxis = new Vector3f(
                    (float) (Math.random() - 0.5) * 0.2f,
                    1.0f,
                    (float) (Math.random() - 0.5) * 0.2f
            ).normalize();

            // Rotational force scales with the hit damage (e.g. 25 damage = ~1.25 radians of spin)
            float spinForce = amount * 0.05f;
            body.accelerateRotation(spinAxis, spinForce);
        }

        // Trigger flash and hit sound
        hitFlashTimer = 0.18f;
        com.leaf.game.core.AudioManager.playAt("seal_hit", position, (Vector3f) null, 35f);

        return false; // Never dies in sandbox mode
    }

    private void scaleGaitFor120TPS(Gait g, float t) {
        g.maxSpeed *= t;
        g.moveAcceleration *= t * t;
        g.rotateAcceleration *= t * t;
        g.legMoveSpeed *= t;
        g.gravityAcceleration *= t * t;
        g.bodyHeightCorrectionAcceleration *= t * t;
        g.tridentRotationalKnockBack *= t * t;
        g.airDragCoefficient *= t;
        g.groundDragCoefficient *= t;
        g.rotationalDragCoefficient *= t;
        g.samePairCooldown *= 6;
        g.crossPairCooldown *= 6;
    }

    @Override
    public void update(float dt, World world, Vector3f playerPos, List<Enemy> allEnemies) {
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
            // Apply custom legs based on command input (defaults to 6-legged hexBot)
            SpiderOptions options;
            if (customLegCount == 4)       options = SpiderPresets.quadBot(4, customScale);
            else if (customLegCount == 8)  options = SpiderPresets.octoBot(4, customScale);
            else                           options = SpiderPresets.hexBot(4, customScale);

            // Scale the gait hover heights & stride limits to fit the physical size
            if (customScale != 1.0f) {
                options.walkGait.scale(customScale);
                options.gallopGait.scale(customScale);
            }

            // Scale physics to 120 TPS
            float t = 1.0f / 6.0f;
            scaleGaitFor120TPS(options.walkGait, t);
            scaleGaitFor120TPS(options.gallopGait, t);

            this.body = SpiderBody.fromPosition(world, this.position, 0f,
                    options.bodyPlan, options.walkGait, options.gallopGait);

            for (Leg leg : body.legs) {
                leg.setStepListener(l -> {
                    com.leaf.game.core.AudioManager.playAt("block_stone", l.endEffector, (Vector3f)null, 25f);
                });
            }
            this.initialized = true;
        }

        // ── 120 TPS FIXED TIMESTEP LOOP ──
        tickAccumulator += dt;
        float tickRate = 1.0f / 120.0f;

        while (tickAccumulator >= tickRate) {

            if (this.knockbackVelX != 0f || this.knockbackVelZ != 0f) {
                body.velocity.x += this.knockbackVelX;
                body.velocity.z += this.knockbackVelZ;
                this.knockbackVelX = 0f;
                this.knockbackVelZ = 0f;
            }

            if (attackCooldown > 0f) attackCooldown -= tickRate;

            if (mode == BehaviorMode.HOSTILE) {
                body.gallop = false;
                Vector3f direction = new Vector3f(playerPos).sub(body.position);
                direction.y = 0;
                float dist = direction.length();
                if (dist > 0.001f) direction.normalize();

                body.rotateTowards(direction);

                float currentSpeed = SpiderMath.horizontalLength(body.velocity);
                float decelerateDist = (currentSpeed * currentSpeed) / (2f * body.gait().moveAcceleration);

                if (dist > 2.5f + decelerateDist) {
                    body.walkAt(new Vector3f(direction).mul(body.gait().maxSpeed));
                } else {
                    body.walkAt(new Vector3f(0f, 0f, 0f));
                }

                if (dist <= 2.5f * 1.2f && attackCooldown <= 0f) {
                    framePlayerDamage = 25f;
                    attackCooldown = 1.2f;
                    com.leaf.game.core.AudioManager.playAt("enemy_swing", position, (Vector3f)null, 30f);
                }

            } else if (mode == BehaviorMode.IDLE) {
                body.gallop = false;
                body.walkAt(new Vector3f(0f, 0f, 0f));

            } else if (mode == BehaviorMode.FOLLOW_TARGET) {
                body.gallop = true;
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
                body.gallop = true;
                if (rideInput.lengthSquared() > 0.001f) {
                    body.rotateTowards(rideInput);
                    body.walkAt(new Vector3f(rideInput).mul(body.gait().maxSpeed));
                } else {
                    body.walkAt(new Vector3f(0f, 0f, 0f));
                }
            }

            body.update();
            tickAccumulator -= tickRate;
        }

        this.position.set(body.position);
    }
}