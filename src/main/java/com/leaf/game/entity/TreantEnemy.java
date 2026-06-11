package com.leaf.game.entity;

import com.leaf.game.entity.spider.*;
import com.leaf.game.util.SpiderMath;
import com.leaf.game.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class TreantEnemy extends Enemy {

    private SpiderBody body;
    private boolean initialized = false;
    private float tickAccumulator = 0f;
    private float sleepTransition = 1f; // 1 = fully asleep (tree disguise), 0 = fully awake
    private float attackCooldown  = 0f;

    // Shared with TreantRenderer
    public static final int TRUNK_HEIGHT = 4;

    public TreantEnemy(float x, float y, float z) {
        super(x, y, z, Type.TREANT);
        this.state    = State.IDLE;
        this.onGround = true;
    }

    public SpiderBody getBody()            { return body; }
    public float      getSleepTransition() { return sleepTransition; }

    // ── Helper: build a single-block DisplayModel for a named block ───────────────
    // The spider renderer expects each SegmentPlan to have a .model whose pieces
    // each contain a blockName (for texture lookup) and a transform (identity here
    // since the segment transform in the renderer handles all positioning/scaling).
    private static DisplayModel blockModel(String blockName) {
        BlockDisplayModelPiece piece = new BlockDisplayModelPiece(
                blockName,
                new Matrix4f(),          // identity — renderer applies its own transform
                new ArrayList<>()        // no special tags
        );
        return new DisplayModel(Arrays.asList(piece));
    }

    @Override
    public boolean applyDamage(float amount) {
        if (state == State.IDLE) {
            state = State.CHASE;
        }
        return super.applyDamage(amount);
    }

    @Override
    public void update(float dt, World world, Vector3f playerPos, List<Enemy> allEnemies) {
        framePlayerDamage = 0f;

        if (!alive) {
            if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);
            return;
        }
        if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);
        if (isGrabbed) return;
        if (isThrown)  { isThrown = false; }

        // ── One-time initialisation ───────────────────────────────────────────────
        if (!initialized) {
            SpiderOptions options = new SpiderOptions();

            // Oak-log model reused for every root segment
            DisplayModel logModel = blockModel("oak_log");

            for (int i = 0; i < 6; i++) {
                float angle = (float)(i * Math.PI / 3.0);
                float cx    = (float)Math.cos(angle);
                float cz    = (float)Math.sin(angle);

                Vector3f attach = new Vector3f(cx * 0.4f, 0.3f,  cz * 0.4f);
                Vector3f rest   = new Vector3f(cx * 2.2f, 0f,    cz * 2.2f);

                List<SegmentPlan> segs = new ArrayList<>();

                // Seg 0: upper root — sweeps outward and slightly down
                SegmentPlan s0 = new SegmentPlan(1.4f,
                        new Vector3f(cx * 0.5f, -0.5f, cz * 0.5f).normalize());
                s0.model = logModel.clone();
                segs.add(s0);

                // Seg 1: lower root — droops toward the ground for an organic curve
                SegmentPlan s1 = new SegmentPlan(1.4f,
                        new Vector3f(cx * 0.2f, -1.0f, cz * 0.2f).normalize());
                s1.model = logModel.clone();
                segs.add(s1);

                options.bodyPlan.legs.add(new LegPlan(attach, rest, segs));
            }

            // BodyPlan scale stays 1.0 — the treant is built in world units directly.
            // The spider renderer multiplies bodyPlan.scale into every segment transform,
            // so leaving it at 1.0 means segmentPlan.length == world-unit length exactly.

            // Heavy lumbering walk
            options.walkGait                = new Gait(0.08f, GaitType.WALK);
            options.walkGait.legMoveSpeed   = 0.20f;
            options.walkGait.legLiftHeight  = 0.9f;

            // Aggressive gallop when chasing
            options.gallopGait                   = new Gait(0.20f, GaitType.GALLOP);
            options.gallopGait.legMoveSpeed      = 0.38f;
            options.gallopGait.legLiftHeight     = 1.1f;
            options.gallopGait.moveAcceleration  = 0.05f;

            // Scale gaits for 120 TPS tick loop
            float tickScale = 1.0f / 6.0f;
            options.walkGait.scale(tickScale);
            options.gallopGait.scale(tickScale);

            // Spawn offset: place the body at y+1 so the first body.update() collision
            // resolve drops it cleanly onto the ground rather than teleporting through it.
            Vector3f spawnPos = new Vector3f(position.x, position.y + 1f, position.z);
            this.body = SpiderBody.fromPosition(
                    world, spawnPos, 0f,
                    options.bodyPlan, options.walkGait, options.gallopGait);

            // Thud on every root step
            // (listener set after first body.update below, once legs exist)

            // Run one seed tick so legs are created (initLegs fires inside body.update)
            // and the body settles onto the ground from the +1 spawn offset.
            body.gallop    = false;
            body.isWalking = false;
            body.walkAt(new Vector3f(0, 0, 0));
            body.update();

            // Now legs exist — attach step sounds
            for (Leg leg : body.legs) {
                leg.setStepListener(l ->
                        com.leaf.game.core.AudioManager.playAt(
                                "block_stone", l.endEffector, (Vector3f) null, 35f));
            }

            this.position.set(body.position);
            this.initialized = true;
        }

        float distToPlayer = new Vector3f(playerPos).sub(position).length();

        // ── AI State Machine ──────────────────────────────────────────────────────
        switch (state) {
            case IDLE:
                // Snap to block-centre and keep body in sync so it looks like a world tree
                position.x = (float) Math.floor(position.x) + 0.5f;
                position.z = (float) Math.floor(position.z) + 0.5f;
                if (body != null) {
                    body.position.x = position.x;
                    body.position.z = position.z;
                    body.velocity.x = 0f;
                    body.velocity.z = 0f;
                }
                break;

            case CHASE:
                if (distToPlayer > 45f) {
                    state  = State.IDLE;
                    health = maxHealth;
                } else if (distToPlayer <= attackRange) {
                    state = State.ATTACK;
                }
                break;

            case ATTACK:
                if (attackCooldown <= 0f) {
                    framePlayerDamage = damagePerSec * attackInterval;
                    attackCooldown    = attackInterval;
                    body.velocity.y += 0.3f;
                }
                if (distToPlayer > attackRange * 1.5f) state = State.CHASE;
                break;
        }
        if (attackCooldown > 0f) attackCooldown -= dt;

        // ── Sleep / Wake Transition ───────────────────────────────────────────────
        if (state == State.IDLE) {
            sleepTransition = Math.min(1f, sleepTransition + dt * 0.5f);
        } else {
            sleepTransition = Math.max(0f, sleepTransition - dt * 2.0f);
        }

        // ── Procedural Root Rest Positions ────────────────────────────────────────
        // sleep=0 (awake):  roots spread wide, flat on ground
        // sleep=1 (asleep): roots tucked tight up into trunk, invisible from outside
        for (int i = 0; i < 6; i++) {
            float angle  = (float)(i * Math.PI / 3.0);
            float cx     = (float)Math.cos(angle);
            float cz     = (float)Math.sin(angle);
            float spread = SpiderMath.lerp(2.2f, 0.15f, sleepTransition);
            float restY  = SpiderMath.lerp(0f,   0.4f,  sleepTransition);
            body.bodyPlan.legs.get(i).restPosition.set(cx * spread, restY, cz * spread);
        }

        // ── 120 TPS Fixed-Timestep IK Physics ────────────────────────────────────
        tickAccumulator += dt;
        final float tickRate = 1.0f / 120.0f;

        while (tickAccumulator >= tickRate) {

            if (knockbackVelX != 0f || knockbackVelZ != 0f) {
                body.velocity.x += knockbackVelX;
                body.velocity.z += knockbackVelZ;
                knockbackVelX = 0f;
                knockbackVelZ = 0f;
            }

            if (state == State.IDLE) {
                // Set gallop FIRST so gait() returns the correct gait immediately
                body.gallop    = false;
                body.isWalking = false;
                body.walkAt(new Vector3f(0, 0, 0));

            } else {
                // Set gallop FIRST so gait().maxSpeed reads from gallopGait
                body.gallop = true;

                Vector3f dir = new Vector3f(playerPos).sub(body.position);
                dir.y = 0f;
                float d = dir.length();
                if (d > 0.001f) dir.normalize();

                body.rotateTowards(dir);

                if (d > 1.5f) {
                    body.walkAt(new Vector3f(dir).mul(body.gait().maxSpeed));
                } else {
                    body.walkAt(new Vector3f(0, 0, 0));
                }
            }

            body.update();
            tickAccumulator -= tickRate;
        }

        this.position.set(body.position);
        this.onGround = body.onGround;
    }
}