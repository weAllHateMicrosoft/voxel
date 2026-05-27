package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Block;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * LightningController — "Storm Caller" ability (U key).
 *
 * ── Two modes ────────────────────────────────────────────────────────────────
 *   Aimed strike : Hold U to charge (up to lightningMaxCharge seconds).
 *                  Release → lightning slams the aimed enemy (or nearest in range).
 *                  Longer charge = more damage.  Water multiplier applies.
 *
 *   AOE burst    : Double-tap U quickly (within lightningDoubleTapWindow).
 *                  Instant weaker strike that hits ALL enemies in lightningAoeRadius.
 *                  Longer cooldown.
 *
 * ── Sky storm ────────────────────────────────────────────────────────────────
 *   stormIntensity (0–1) rises while charging and decays after the strike.
 *   Window reads it to darken the sky / apply a rolling-cloud overlay.
 *
 * ── Bolt visuals ─────────────────────────────────────────────────────────────
 *   activeBolts holds screen-space data (filled by Window during the 3-D pass,
 *   rendered in the ImGui pass as bright zigzag lines from sky to target).
 */
public class LightningController {

    // ─────────────────────────────────────────────────────────────────────────
    //  Inner types
    // ─────────────────────────────────────────────────────────────────────────

    public static class LightningBolt {
        /** Screen-space X of the strike target (set by Window each render). */
        public float screenX, screenY;
        /** World-space hit position (used by Window to project). */
        public final Vector3f worldTarget = new Vector3f();
        public float life, maxLife;
        public boolean isAoe;
        /** 0–1 brightness fade driven by remaining life. */
        public float brightness() { return life / maxLife; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  State
    // ─────────────────────────────────────────────────────────────────────────

    private final Player player;
    private EnemyManager enemyManager;

    // Charge
    private float   chargeTimer   = 0f;
    private boolean isCharging    = false;

    // Cooldown
    private float   cooldown      = 0f;

    // Double-tap detection
    private float   lastUPressTime = -999f;
    private boolean lastU          = false;

    // Storm intensity (0 = clear, 1 = full storm)
    public  float stormIntensity   = 0f;

    // Bolts awaiting render (Window reads + renders these, then clears them)
    public final List<LightningBolt> activeBolts = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    public LightningController(Player p) { this.player = p; }

    public void setEnemyManager(EnemyManager em) { this.enemyManager = em; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public accessors
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isCharging()     { return isCharging; }
    public float   getChargeFrac()  { return Math.min(1f, chargeTimer / GameConfig.lightningMaxCharge); }
    public float   getCooldownFrac(){ return cooldown <= 0 ? 1f : 1f - cooldown / GameConfig.lightningCooldown; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main tick — called from Player.update()
    // ─────────────────────────────────────────────────────────────────────────

    public void tick(long window, Camera camera, World world, float dt, float gameTime) {
        // Decay cooldown
        if (cooldown > 0f) cooldown -= dt;

        // Decay bolt visuals
        for (Iterator<LightningBolt> it = activeBolts.iterator(); it.hasNext(); ) {
            LightningBolt b = it.next();
            b.life -= dt;
            if (b.life <= 0f) it.remove();
        }

        // Decay storm when not charging
        if (!isCharging) {
            stormIntensity = Math.max(0f, stormIntensity - dt * 0.4f);
        }

        // Cannot use lightning while in Kamui, flight, or debug mode
        if (player.debugMode || player.abilities.isKamui || enemyManager == null) {
            if (isCharging) { isCharging = false; chargeTimer = 0f; }
            lastU = false;
            return;
        }

        boolean uHeld = glfwGetKey(window, GLFW_KEY_U) == GLFW_PRESS;

        // ── Double-tap detection (AOE burst) ─────────────────────────────────
        if (uHeld && !lastU) {
            float timeSinceLast = gameTime - lastUPressTime;
            if (timeSinceLast <= GameConfig.lightningDoubleTapWindow && cooldown <= 0f) {
                // Double-tap detected — fire AOE burst
                fireAoeBurst(world);
                cooldown      = GameConfig.lightningAoeCooldown;
                isCharging    = false;
                chargeTimer   = 0f;
                lastUPressTime = -999f; // reset so next press starts fresh
                lastU = uHeld;
                return;
            }
            lastUPressTime = gameTime;
        }

        // ── Charge buildup (hold U) ───────────────────────────────────────────
        if (uHeld && cooldown <= 0f) {
            if (!isCharging) isCharging = true;
            chargeTimer   = Math.min(GameConfig.lightningMaxCharge, chargeTimer + dt);
            stormIntensity = Math.min(1f, stormIntensity + dt * 0.6f);
        }

        // ── Release → fire aimed strike ───────────────────────────────────────
        if (!uHeld && lastU && isCharging) {
            if (cooldown <= 0f) {
                fireAimedStrike(camera, world);
                cooldown    = GameConfig.lightningCooldown;
            }
            isCharging  = false;
            chargeTimer = 0f;
        }

        // Cancel if key still up without charge
        if (!uHeld && !isCharging) {
            chargeTimer = 0f;
        }

        lastU = uHeld;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Strike helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void fireAimedStrike(Camera camera, World world) {
        if (enemyManager == null) return;

        Vector3f eyePos = camera.position;
        // Try aimed enemy first, fall back to closest visible
        Enemy target = enemyManager.findMostAligned(world, eyePos,
                camera.getLookDirection(), GameConfig.lightningRange);
        if (target == null) {
            target = enemyManager.findClosestVisible(world, eyePos, GameConfig.lightningRange);
        }
        if (target == null) {
            stormIntensity = 0.5f; // partial storm release with no target
            return;
        }

        float t      = getChargeFrac();
        float damage = GameConfig.lightningBaseDamage
                + t * (GameConfig.lightningMaxDamage - GameConfig.lightningBaseDamage);

        // Water bonus
        Vector3f targetFeet = target.position;
        int fx = (int) Math.floor(targetFeet.x);
        int fy = (int) Math.floor(targetFeet.y - 0.1f);
        int fz = (int) Math.floor(targetFeet.z);
        boolean inWater = world.getBlock(fx, fy, fz) == Block.WATER
                       || world.getBlock(fx, fy + 1, fz) == Block.WATER;
        if (inWater) damage *= GameConfig.lightningWaterMult;

        target.applyDamage(damage);
        target.applyKnockback(0f, 6f, 0f); // jolt upward on hit

        spawnBolt(target.getCentre(), false);
        stormIntensity = 1.0f;
    }

    private void fireAoeBurst(World world) {
        if (enemyManager == null) return;

        Vector3f origin = new Vector3f(player.position.x,
                player.position.y + 1f, player.position.z);
        boolean anyHit = false;

        for (Enemy e : enemyManager.getEnemies()) {
            if (!e.alive) continue;
            float dx = e.position.x - origin.x;
            float dz = e.position.z - origin.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > GameConfig.lightningAoeRadius) continue;

            float damage = GameConfig.lightningAoeDamage * (1f - dist / GameConfig.lightningAoeRadius * 0.5f);

            int fx = (int) Math.floor(e.position.x);
            int fy = (int) Math.floor(e.position.y - 0.1f);
            int fz = (int) Math.floor(e.position.z);
            boolean inWater = world.getBlock(fx, fy, fz)     == Block.WATER
                           || world.getBlock(fx, fy + 1, fz) == Block.WATER;
            if (inWater) damage *= GameConfig.lightningWaterMult;

            e.applyDamage(damage);
            e.applyKnockback(0f, 5f, 0f);
            spawnBolt(e.getCentre(), true);
            anyHit = true;
        }
        if (anyHit) stormIntensity = 1.0f;
    }

    private void spawnBolt(Vector3f worldTarget, boolean aoe) {
        LightningBolt bolt = new LightningBolt();
        bolt.worldTarget.set(worldTarget);
        bolt.maxLife = GameConfig.lightningBoltLife;
        bolt.life    = bolt.maxLife;
        bolt.isAoe   = aoe;
        activeBolts.add(bolt);
    }
}
