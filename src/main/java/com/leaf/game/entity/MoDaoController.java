package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;

/**
 * MoDaoController — 魔刀千刃  "Demon Blade, Thousand Shards"
 *
 * KEY SCHEME
 *   '  (apostrophe) — tap once while SHEATHED → DISPERSE shards into formation
 *                   — tap again while DISPERSED → RECALL shards early
 *   LMB while DISPERSED   → CONVERGE: all 16 shards lance toward crosshair target
 *   RMB while DISPERSED   → SWEEP:    shards fan into a horizontal scythe arc
 *
 * PHASES (clear animation intent for each)
 *   SHEATHED   – shards orbit the player in a double helix (passive visual)
 *   DISPERSING – shards fly outward to the formation ahead (0.32 s)
 *   DISPERSED  – cloud of shards floats ahead, tracking look direction
 *   CONVERGING – all shards fly toward the same point (unmistakable burst)
 *   SWEEPING   – shards fan into a horizontal line and slice sideways
 *   RECALLING  – shards fly back to player (clearly retreating)
 *
 * Window reads:
 *   moDao.shards[]           — shard world positions for rendering
 *   moDao.phase              — which animation to use
 *   moDao.shakeRequest       — pending screen-shake after impact
 *   moDao.convergeImpacted   — true for one frame after converge lands (make crater)
 *   moDao.convergeImpactPos  — crater centre
 *   moDao.sweepHitEnemies[]  — damage events this frame from sweep
 */
public class MoDaoController {

    // ═════════════════════════════════════════════════════════════════════════
    //  Shard data
    // ═════════════════════════════════════════════════════════════════════════

    public static class Shard {
        public final Vector3f pos   = new Vector3f();
        public       Vector3f vel   = new Vector3f();
        /** Per-shard phase offset (0 – 2π) for independent bobbing / orbit. */
        public final float    phase;
        /** Used in sweep — has this shard already been counted for damage? */
        public       boolean  hitUsed = false;

        Shard(float phase) {
            this.phase = phase;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Phase enum
    // ═════════════════════════════════════════════════════════════════════════

    public enum Phase {
        SHEATHED,   // orbiting player
        DISPERSING, // flying out to formation
        DISPERSED,  // floating formation ahead
        CONVERGING, // all shards flying toward one point
        SWEEPING,   // horizontal scythe sweep
        RECALLING   // shards flying back to player
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  State
    // ═════════════════════════════════════════════════════════════════════════

    public  Phase           phase          = Phase.SHEATHED;
    public  final Shard[]   shards;

    // ── Formation tracking ─────────────────────────────────────────────────
    /** Current formation-centre (lerps toward target each frame). */
    public  final Vector3f  formCenter     = new Vector3f();
    /** Current formation normal (lerps toward camera look). */
    private final Vector3f  formNormal     = new Vector3f(0, 0, 1);
    /** Camera right vector cached for formation basis. */
    private final Vector3f  formRight      = new Vector3f(1, 0, 0);

    // ── Converge ──────────────────────────────────────────────────────────
    private       Vector3f  convergeTarget = null;
    private       int       shardsArrived  = 0;

    /** True for exactly one frame when converge lands. Window reads this. */
    public  boolean         convergeImpacted  = false;
    public  final Vector3f  convergeImpactPos = new Vector3f();

    // ── Sweep ─────────────────────────────────────────────────────────────
    /** Direction shards travel during sweep (camera right at sweep start). */
    private final Vector3f  sweepDir       = new Vector3f();
    /** Origin baseline for sweep (camera position at sweep start). */
    private final Vector3f  sweepOrigin    = new Vector3f();
    private       float     sweepProgress  = 0f;  // 0 → moDaoSweepDist

    // ── Timing & cooldown ──────────────────────────────────────────────────
    private float phaseTimer = 0f;   // seconds remaining in current timed phase
    private float cooldown   = 0f;

    // ── Stored camera state for callback use ──────────────────────────────
    /** Camera look direction captured each tick — used by RMB callback. */
    private final Vector3f storedLook  = new Vector3f(0, 0, 1);
    private final Vector3f storedRight = new Vector3f(1, 0, 0);
    private final Vector3f storedCamPos= new Vector3f();

    // ── Key edge detection ─────────────────────────────────────────────────
    private boolean lastApostrophe = false;
    private boolean lastLMB        = false;

    // ── Screen shake ──────────────────────────────────────────────────────
    /** Non-zero this frame when an impact just happened. Window reads it. */
    public  float shakeRequest = 0f;

    // ── Dependencies ──────────────────────────────────────────────────────
    private final Player       player;
    private       EnemyManager enemyManager;
    private final Random       rng = new Random();

    // ═════════════════════════════════════════════════════════════════════════
    //  Construction
    // ═════════════════════════════════════════════════════════════════════════

    public MoDaoController(Player player) {
        this.player = player;
        int n = GameConfig.moDaoShardCount;
        shards = new Shard[n];
        for (int i = 0; i < n; i++) {
            shards[i] = new Shard((float)(i * 2.0 * Math.PI / n));
            // Start at world origin — player.position is null during field init.
            // tick() will move them to the real player position on the first frame.
            shards[i].pos.set(0f, 0f, 0f);
        }
    }

    public void setEnemyManager(EnemyManager em) { this.enemyManager = em; }

    // ═════════════════════════════════════════════════════════════════════════
    //  Public accessors
    // ═════════════════════════════════════════════════════════════════════════

    public boolean isDispersed()       { return phase == Phase.DISPERSED; }
    public boolean isActive()          { return phase != Phase.SHEATHED; }
    public float   getCooldownFrac()   { return cooldown <= 0f ? 1f : 1f - cooldown / GameConfig.moDaoCooldown; }

    /**
     * Called by the Window mouse-button callback when RMB is pressed.
     * Returns true if the blade consumes the click (sweep triggered).
     */
    public boolean onRmbPress() {
        if (phase != Phase.DISPERSED) return false;
        if (player.mana < GameConfig.moDaoSweepCost) return false;
        player.mana -= GameConfig.moDaoSweepCost;
        enterSweeping();
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main tick — called from Player.update()
    // ═════════════════════════════════════════════════════════════════════════

    public void tick(long window, Camera camera, World world, float dt) {
        shakeRequest     = 0f;
        convergeImpacted = false;

        // Cache camera state (used by onRmbPress callback and formation logic)
        storedLook.set(camera.getLookDirection());
        storedRight.set(camera.getRight());
        storedCamPos.set(camera.position);

        if (cooldown > 0f) cooldown -= dt;

        boolean apostropheHeld = glfwGetKey(window, GLFW_KEY_APOSTROPHE) == GLFW_PRESS;
        boolean lmbHeld        = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;

        // ── Per-phase logic ───────────────────────────────────────────────────
        switch (phase) {

            case SHEATHED -> {
                tickOrbitShards(camera, dt);
                if (apostropheHeld && !lastApostrophe
                        && !player.debugMode && cooldown <= 0f) {
                    enterDispersing(camera);
                }
            }

            case DISPERSING -> {
                phaseTimer -= dt;
                updateFormation(camera, dt);
                float prog = 1f - Math.max(0f, phaseTimer / GameConfig.moDaoDisperseTime);
                flyToFormation(prog, dt);
                if (phaseTimer <= 0f) phase = Phase.DISPERSED;
            }

            case DISPERSED -> {
                // Drain mana; auto-recall if empty
                player.mana = Math.max(0f, player.mana - GameConfig.moDaoDrainPerSec * dt);
                if (player.mana <= 0f) { enterRecalling(camera); break; }

                updateFormation(camera, dt);
                floatInFormation(camera, dt);

                // Recall on tap
                if (apostropheHeld && !lastApostrophe) {
                    enterRecalling(camera);
                    break;
                }

                // LMB → Converge
                if (lmbHeld && !lastLMB
                        && player.mana >= GameConfig.maDaoConvergeCost) {
                    player.mana -= GameConfig.maDaoConvergeCost;
                    enterConverging(camera, world);
                }
            }

            case CONVERGING -> tickConverging(world, dt);

            case SWEEPING   -> tickSweeping(world, dt);

            case RECALLING  -> {
                phaseTimer -= dt;
                float recallProg = 1f - Math.max(0f, phaseTimer / GameConfig.moDaoRecallTime);
                flyToPlayer(camera, recallProg, dt);
                if (phaseTimer <= 0f) {
                    phase    = Phase.SHEATHED;
                    cooldown = GameConfig.moDaoCooldown;
                }
            }
        }

        lastApostrophe = apostropheHeld;
        lastLMB        = lmbHeld;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Phase transitions
    // ═════════════════════════════════════════════════════════════════════════

    private void enterDispersing(Camera camera) {
        phase      = Phase.DISPERSING;
        phaseTimer = GameConfig.moDaoDisperseTime;
        // Initialise formation so shards have a target to fly toward
        updateFormation(camera, 0f);
        // Start all shards from player position
        float ey = player.position.y + 1.6f;
        for (Shard s : shards) {
            s.pos.set(player.position.x, ey, player.position.z);
        }
    }

    private void enterConverging(Camera camera, World world) {
        phase          = Phase.CONVERGING;
        shardsArrived  = 0;
        convergeTarget = findConvergeTarget(camera, world);
        // Give every shard a velocity pointing toward the target
        for (Shard s : shards) {
            Vector3f dir = new Vector3f(convergeTarget).sub(s.pos).normalize();
            // Slight random spread so they don't arrive as a perfect point (more dramatic)
            dir.x += (rng.nextFloat() - 0.5f) * 0.08f;
            dir.y += (rng.nextFloat() - 0.5f) * 0.08f;
            dir.z += (rng.nextFloat() - 0.5f) * 0.08f;
            dir.normalize();
            s.vel = new Vector3f(dir).mul(GameConfig.moDaoConvergeSpeed);
        }
    }

    private void enterSweeping() {
        phase         = Phase.SWEEPING;
        sweepProgress = 0f;
        sweepDir.set(storedRight);
        sweepOrigin.set(storedCamPos).add(new Vector3f(storedLook).mul(2.5f));
        // Reset per-shard hit flags
        for (Shard s : shards) s.hitUsed = false;

        // Arrange shards in a vertical fan centred on the sweep origin
        int n = shards.length;
        for (int i = 0; i < n; i++) {
            float t = (float)(i) / (n - 1) - 0.5f;   // -0.5 → +0.5
            // Spread up/down and slightly in/out of the sweep plane
            Vector3f up = new Vector3f(0, 1, 0);
            shards[i].pos.set(sweepOrigin)
                    .add(new Vector3f(up).mul(t * 2.2f))
                    .add(new Vector3f(storedLook).mul((float)(Math.sin(t * Math.PI)) * 0.4f));
        }
    }

    private void enterRecalling(Camera camera) {
        phase      = Phase.RECALLING;
        phaseTimer = GameConfig.moDaoRecallTime;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Per-phase shard movement
    // ═════════════════════════════════════════════════════════════════════════

    /** Sheathed: double-helix orbit around the player. */
    private void tickOrbitShards(Camera camera, float dt) {
        float time = (float) glfwGetTime();
        float ey   = player.position.y + 1.6f;
        int n      = shards.length;
        int half   = n / 2;

        for (int i = 0; i < n; i++) {
            Shard s = shards[i];
            float angle;
            float radius, height;
            if (i < half) {
                // Inner ring — tighter, faster, clockwise
                angle  = s.phase + time * 1.4f;
                radius = 0.85f;
                height = ey + 0.18f * (float) Math.sin(s.phase + time * 2.1f);
            } else {
                // Outer ring — wider, slower, counter-clockwise
                angle  = s.phase - time * 0.95f;
                radius = 1.25f;
                height = ey - 0.15f + 0.15f * (float) Math.sin(s.phase + time * 1.6f);
            }
            s.pos.set(
                player.position.x + (float) Math.cos(angle) * radius,
                height,
                player.position.z + (float) Math.sin(angle) * radius
            );
        }
    }

    /** Recompute the formation centre and basis vectors. */
    private void updateFormation(Camera camera, float dt) {
        Vector3f look  = camera.getLookDirection();
        Vector3f right = camera.getRight();
        // True up in camera-space
        Vector3f up = new Vector3f(right).cross(look).normalize();

        Vector3f targetCenter = new Vector3f(camera.position)
                .add(new Vector3f(look).mul(GameConfig.moDaoFormDist));

        // Smooth lerp so the formation follows the look direction with satisfying lag
        float lerpT = Math.min(1f, 7f * dt);
        formCenter.lerp(targetCenter, lerpT);
        formNormal.lerp(look, lerpT).normalize();
        formRight.lerp(right, lerpT).normalize();
    }

    /** Return the world-space position of formation slot i. */
    private Vector3f formationSlot(int i) {
        int n    = shards.length;
        int half = n / 2;

        // True-up from stored basis
        Vector3f up = new Vector3f(formRight).cross(formNormal).normalize();
        float time  = (float) glfwGetTime();

        float radius = (i < half) ? 0.60f : 1.10f;
        float angle  = shards[i].phase + time * (i < half ? 0.55f : -0.40f);

        // Position in formation plane (right/up basis vectors)
        float bx = (float) Math.cos(angle) * radius;
        float by = (float) Math.sin(angle) * radius;

        // Slight depth variation for volume
        float depth = 0.15f * (float) Math.sin(shards[i].phase + time * 1.2f);

        return new Vector3f(formCenter)
                .add(new Vector3f(formRight).mul(bx))
                .add(new Vector3f(up).mul(by))
                .add(new Vector3f(formNormal).mul(depth));
    }

    /** Dispersing: lerp shards toward their formation slots. */
    private void flyToFormation(float prog, float dt) {
        for (int i = 0; i < shards.length; i++) {
            Vector3f target = formationSlot(i);
            shards[i].pos.lerp(target, Math.min(1f, prog + dt * 8f));
        }
    }

    /** Dispersed: hold shards in formation (they track with smooth lag). */
    private void floatInFormation(Camera camera, float dt) {
        for (int i = 0; i < shards.length; i++) {
            Vector3f target = formationSlot(i);
            shards[i].pos.lerp(target, Math.min(1f, dt * 9f));
        }
    }

    /** Converging: move each shard toward convergeTarget. */
    private void tickConverging(World world, float dt) {
        if (convergeTarget == null) { enterRecalling(null); return; }

        boolean allArrived = true;
        for (Shard s : shards) {
            float dist = new Vector3f(convergeTarget).sub(s.pos).length();
            if (dist > 0.6f) {
                allArrived = false;
                s.pos.add(new Vector3f(s.vel).mul(dt));
            }
        }

        if (allArrived || ++shardsArrived >= shards.length) {
            // ── IMPACT ────────────────────────────────────────────────────
            convergeImpacted = true;
            convergeImpactPos.set(convergeTarget);
            shakeRequest = 0.65f;

            // Snap all shards to impact point so Window renders them there
            for (Shard s : shards) s.pos.set(convergeTarget);

            // Brief pause at impact, then recall
            enterRecalling(null);
        }
    }

    /** Sweeping: move shards sideways and apply damage. */
    private void tickSweeping(World world, float dt) {
        float stepDist = GameConfig.moDaoSweepSpeed * dt;
        sweepProgress += stepDist;

        // Move all shards sideways
        Vector3f move = new Vector3f(sweepDir).mul(stepDist);
        for (Shard s : shards) {
            s.pos.add(move);
        }

        // Damage enemies whose centre is within 0.9 blocks of any shard
        if (enemyManager != null) {
            for (Enemy e : enemyManager.getEnemies()) {
                if (!e.alive) continue;
                Vector3f ec = e.getCentre();
                for (Shard s : shards) {
                    if (s.hitUsed) continue;
                    float dist = new Vector3f(s.pos).sub(ec).length();
                    if (dist < 1.0f) {
                        e.applyDamage(GameConfig.moDaoSweepShardDmg);
                        e.applyKnockback(sweepDir.x * 4f, 1.5f, sweepDir.z * 4f);
                        s.hitUsed = true; // one hit per shard per sweep pass
                        break;
                    }
                }
            }
        }

        if (sweepProgress >= GameConfig.moDaoSweepDist) {
            enterRecalling(null);
        }
    }

    /** Recalling: lerp shards back toward player eye position. */
    private void flyToPlayer(Camera camera, float prog, float dt) {
        // eyePos may be null-camera during late recall — use player position directly
        float ex = player.position.x;
        float ey = player.position.y + 1.6f;
        float ez = player.position.z;
        Vector3f eye = new Vector3f(ex, ey, ez);
        for (Shard s : shards) {
            s.pos.lerp(eye, Math.min(1f, dt * 14f + prog * 0.5f));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Target acquisition
    // ═════════════════════════════════════════════════════════════════════════

    private Vector3f findConvergeTarget(Camera camera, World world) {
        // 1. Nearest aligned alive enemy
        if (enemyManager != null) {
            Enemy e = enemyManager.findMostAligned(
                    world, camera.position, camera.getLookDirection(),
                    GameConfig.moDaoConvergeRange);
            if (e != null) return new Vector3f(e.getCentre());
        }

        // 2. Raycast for solid surface
        Vector3f dir  = camera.getLookDirection();
        float    step = 0.3f;
        float    rx   = camera.position.x;
        float    ry   = camera.position.y;
        float    rz   = camera.position.z;
        for (float dist = 0f; dist < GameConfig.moDaoConvergeRange; dist += step) {
            rx += dir.x * step;  ry += dir.y * step;  rz += dir.z * step;
            int bx = (int) Math.floor(rx);
            int by = (int) Math.floor(ry);
            int bz = (int) Math.floor(rz);
            if (by >= 0 && by < Chunk.HEIGHT && world.getBlock(bx, by, bz).isSolid()) {
                return new Vector3f(rx, ry, rz);
            }
        }

        // 3. Far point
        return new Vector3f(camera.position)
                .add(new Vector3f(dir).mul(GameConfig.moDaoConvergeRange * 0.5f));
    }
}
