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

    // ── HIT REACTION ──────────────────────────────────────────────────────────
    @Override
    public boolean applyDamage(float amount) {
        if (body != null) {
            // Spin primarily around the vertical Y axis with slight diagonal variance.
            //
            // FIX — KNOCKBACK SCALE:
            // The original spinForce = amount * 0.05f was designed for a ~20 Hz game
            // loop.  At 120 TPS the same quaternion impulse is applied per-tick rather
            // than per-frame, making it 6× more powerful than intended (the rotational
            // drag only cancels it gradually).  Scale by (1/6) to restore the felt
            // magnitude, matching how tridentRotationalKnockBack is already scaled in
            // scaleGaitFor120TPS (t * t ≈ 1/36 for angular acceleration; for an
            // instantaneous velocity impulse the correct scale is t = 1/6).
            //
            // Additionally the X/Z components of the spin axis (±0.1 range) allowed
            // strong pitch/roll impulses that could flip the body entirely.  Halving
            // those components to ±0.05 keeps the organic wobble feel while staying
            // safely within the pitch/roll clamp added in updatePreferredAngles().
            final float TPS_SCALE = 1.0f / 6.0f;   // matches scaleGaitFor120TPS t
            Vector3f spinAxis = new Vector3f(
                    (float)(Math.random() - 0.5) * 0.1f,   // was 0.2f — halved to limit pitch/roll
                    1.0f,
                    (float)(Math.random() - 0.5) * 0.1f
            ).normalize();

            float spinForce = amount * 0.05f * TPS_SCALE;
            body.accelerateRotation(spinAxis, spinForce);
        }

        hitFlashTimer = 0.18f;
        com.leaf.game.core.AudioManager.playAt("seal_hit", position, (Vector3f) null, 35f);

        return false;
    }

    private void scaleGaitFor120TPS(Gait g, float t) {
        g.maxSpeed                       *= t;
        g.moveAcceleration               *= t * t;
        g.rotateAcceleration             *= t * t;
        g.legMoveSpeed                   *= t;
        g.gravityAcceleration            *= t * t;
        g.bodyHeightCorrectionAcceleration *= t * t;
        g.tridentRotationalKnockBack     *= t * t;
        g.airDragCoefficient             *= t;
        g.groundDragCoefficient          *= t;
        g.rotationalDragCoefficient      *= t;
        g.samePairCooldown               *= 6;
        g.crossPairCooldown              *= 6;

        // FIX — LEG LIFT HEIGHT TIMESTEP SCALE:
        // legLiftHeight is the per-tick vertical step added to the foot arc peak.
        // It has the same dimension as legMoveSpeed (blocks per tick) so it must
        // scale by t, not t².  The original code omitted this, leaving legLiftHeight
        // at its design value (~0.35 blocks) regardless of tick rate.  At 120 TPS
        // that means the foot arc advances only 0.35/tick * legMoveSpeed ticks in Y,
        // which is fine for the arc shape — BUT legLiftHeight is also compared
        // directly to the step height inside Leg.updateMovement to decide when to
        // stop lifting.  Without scaling it is effectively 6× too large in world-
        // space terms relative to the body correction and move speeds, so the foot
        // hangs in the air far too long on tall steps instead of planting quickly.
        // Scaling by t aligns it with legMoveSpeed.
        g.legLiftHeight                  *= t;

        // legDropDistance governs when the foot stops arcing and starts descending.
        // Same dimension as legLiftHeight — must scale by t for the same reason.
        g.legDropDistance                *= t;
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
            SpiderOptions options;
            if (customLegCount == 4)       options = SpiderPresets.quadBot(4, customScale);
            else if (customLegCount == 8)  options = SpiderPresets.octoBot(4, customScale);
            else                           options = SpiderPresets.hexBot(4, customScale);

            if (customScale != 1.0f) {
                options.walkGait.scale(customScale);
                options.gallopGait.scale(customScale);
            }

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