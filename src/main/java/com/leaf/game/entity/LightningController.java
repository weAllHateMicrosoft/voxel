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
 * ── How it works ─────────────────────────────────────────────────────────────
 *   Hold U to charge (up to lightningMaxCharge seconds).
 *   Release U → pending single-target strike (waits doubleTapWindow seconds).
 *   Press U again within window → AOE burst fires instead.
 *   Double-tap U → AOE burst (first tap queues, second tap fires AOE).
 *   Longer charge = more damage on single strike.
 *
 * ── Water electrocution ───────────────────────────────────────────────────────
 *   When the primary target stands in water, electricity chains outward to ALL
 *   other enemies also standing in water within lightningWaterChainRadius blocks.
 *   Chain hits deal a fixed lightningWaterChainDamage (not charge-scaled).
 *
 * ── Sky storm ────────────────────────────────────────────────────────────────
 *   stormIntensity (0–1) rises while charging and decays after the strike.
 *   Window reads it to darken the sky.
 *
 * ── Bolt visuals ─────────────────────────────────────────────────────────────
 *   activeBolts holds world-space target data filled by this controller.
 *   Window renders them as massive multi-path zigzag bolts in the ImGui pass.
 *
 * ── Mana ─────────────────────────────────────────────────────────────────────
 *   Single strike costs manaLightningBase … manaLightningMax (charge-scaled).
 *   AOE burst costs manaLightningAoe.  Insufficient mana cancels the strike.
 */
public class LightningController {

    // ─────────────────────────────────────────────────────────────────────────
    //  Inner types
    // ─────────────────────────────────────────────────────────────────────────

    public static class LightningBolt {
        /** World-space hit position (used by Window to project to screen). */
        public final Vector3f worldTarget = new Vector3f();
        public float life, maxLife;
        /** True for chain-water bolts (blue tint vs. white primary). */
        public boolean isChain;
        /** Set by Window once the 3-D strike VFX have been spawned for this bolt. */
        public boolean fxSpawned = false;
        /** 0–1 brightness fade driven by remaining life. */
        public float brightness() { return life / maxLife; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  State
    // ─────────────────────────────────────────────────────────────────────────

    private final Player player;
    private EnemyManager enemyManager;

    // Charge
    private float   chargeTimer = 0f;
    private boolean isCharging  = false;

    // Cooldown
    private float cooldown = 0f;

    // Edge-detect for U key
    private boolean lastU = false;

    // Pending single-strike — queued when U is released after charging.
    // If U is pressed again within pendingStrikeDelay → AOE fires instead.
    private boolean pendingSingleStrike = false;
    private float   pendingStrikeDelay  = 0f;
    private float   savedChargeFrac     = 0f;

    // Storm intensity (0 = clear, 1 = full storm) — read by Window each frame
    public float stormIntensity = 0f;

    // Bolts awaiting render (Window reads + renders these)
    public final List<LightningBolt> activeBolts = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    public LightningController(Player p) { this.player = p; }

    public void setEnemyManager(EnemyManager em) { this.enemyManager = em; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public accessors
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isCharging()      { return isCharging; }
    public float   getChargeFrac()   { return Math.min(1f, chargeTimer / GameConfig.lightningMaxCharge); }
    public float   getCooldownFrac() { return cooldown <= 0 ? 1f : 1f - cooldown / GameConfig.lightningCooldown; }

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

        // Storm decays when not charging
        if (!isCharging) {
            stormIntensity = Math.max(0f, stormIntensity - dt * 0.35f);
        }

        // Cannot use lightning in debug mode
        if (player.debugMode || enemyManager == null) {
            if (isCharging) { isCharging = false; chargeTimer = 0f; }
            pendingSingleStrike = false;
            lastU = false;
            return;
        }

        boolean uHeld = glfwGetKey(window, GLFW_KEY_U) == GLFW_PRESS
                && player.can(com.leaf.game.core.Progression.Ability.LIGHTNING);

        // ── Tick pending single strike ────────────────────────────────────────
        // When U is released after charging, we queue the strike for doubleTapWindow
        // seconds rather than firing immediately.  If U is pressed again in that
        // window, we cancel the single and fire AOE instead.
        if (pendingSingleStrike) {
            if (uHeld && !lastU) {
                // Second tap within window → AOE burst, cancel the queued single
                if (cooldown <= 0f) {
                    if (player.mana >= GameConfig.manaLightningAoe) {
                        player.mana -= GameConfig.manaLightningAoe;
                        fireAoeBurst(world);
                        cooldown = GameConfig.lightningAoeCooldown;
                        stormIntensity = 1.0f;
                    }
                }
                pendingSingleStrike = false;
                lastU = uHeld;
                return;
            }
            pendingStrikeDelay -= dt;
            if (pendingStrikeDelay <= 0f) {
                // Double-tap window expired — fire the queued single strike
                if (cooldown <= 0f) {
                    float manaCost = GameConfig.manaLightningBase
                            + savedChargeFrac * (GameConfig.manaLightningMax - GameConfig.manaLightningBase);
                    if (player.mana >= manaCost) {
                        player.mana -= manaCost;
                        fireAimedStrike(camera, world, savedChargeFrac);
                        cooldown = GameConfig.lightningCooldown;
                    }
                }
                pendingSingleStrike = false;
            }
            // Don't start a new charge while a strike is pending
            lastU = uHeld;
            return;
        }

        // ── Charge buildup (hold U) ───────────────────────────────────────────
        if (uHeld && cooldown <= 0f) {
            if (!isCharging) isCharging = true;
            com.leaf.game.core.AudioManager.playContinuous("lightening_charging");
            chargeTimer    = Math.min(GameConfig.lightningMaxCharge, chargeTimer + dt);
            stormIntensity = Math.min(1f, stormIntensity + dt * 0.8f);
        }

        // ── Release → queue single strike (don't fire immediately) ───────────
        // The brief hold lets a second tap cancel it in favour of AOE.
        if (!uHeld && lastU && isCharging) {
            com.leaf.game.core.AudioManager.stopContinuous("lightening_charging");
            savedChargeFrac     = getChargeFrac();
            pendingSingleStrike = true;
            pendingStrikeDelay  = GameConfig.lightningDoubleTapWindow;
            isCharging          = false;
            chargeTimer         = 0f;
        }

        // Cancel charge counter if key is not held
        if (!uHeld && !isCharging) chargeTimer = 0f;
        com.leaf.game.core.AudioManager.stopContinuous("lightening_charging");

        lastU = uHeld;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Strike
    // ─────────────────────────────────────────────────────────────────────────

    private void fireAimedStrike(Camera camera, World world, float chargeFrac) {
        com.leaf.game.core.AudioManager.play(Math.random() > 0.5 ? "lightening1" : "lightening2");
        if (enemyManager == null) return;

        Vector3f eyePos = camera.position;

        // Find target: prefer the enemy most aligned with look direction
        Enemy target = enemyManager.findMostAligned(world, eyePos,
                camera.getLookDirection(), GameConfig.lightningRange);
        if (target == null) {
            target = enemyManager.findClosestVisible(world, eyePos, GameConfig.lightningRange);
        }
        if (target == null) {
            stormIntensity = 0.4f; // faint storm even without a hit
            return;
        }

        // Scale damage by charge fraction
        float damage = GameConfig.lightningBaseDamage
                + chargeFrac * (GameConfig.lightningMaxDamage - GameConfig.lightningBaseDamage);

        // Check if primary target is standing in or on water
        boolean primaryInWater = isInWater(world, target.position);

        target.applyDamage(damage);
        target.applyKnockback(0f, 6f, 0f); // jolt upward
        spawnBolt(target.getCentre(), false);
        stormIntensity = 1.0f;

        // ── Water chain electrocution ─────────────────────────────────────────
        if (primaryInWater) {
            Vector3f origin = target.position;
            for (Enemy e : enemyManager.getEnemies()) {
                if (e == target || !e.alive) continue;
                float dx = e.position.x - origin.x;
                float dz = e.position.z - origin.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > GameConfig.lightningWaterChainRadius) continue;
                if (!isInWater(world, e.position)) continue;

                // Falloff: full damage up to 50% radius, then linear drop
                float falloffStart = GameConfig.lightningWaterChainRadius * 0.5f;
                float chainDmg = GameConfig.lightningWaterChainDamage;
                if (dist > falloffStart) {
                    chainDmg *= 1f - (dist - falloffStart)
                            / (GameConfig.lightningWaterChainRadius - falloffStart);
                }
                e.applyDamage(chainDmg);
                e.applyKnockback(0f, 4f, 0f);
                spawnBolt(e.getCentre(), true); // blue chain bolt
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AOE burst (double-tap U) — hits all enemies in lightningAoeRadius
    // ─────────────────────────────────────────────────────────────────────────

    private void fireAoeBurst(World world) {
        com.leaf.game.core.AudioManager.play("lightening_crowd");
        if (enemyManager == null) return;
        Vector3f origin = new Vector3f(player.position.x, player.position.y + 1f, player.position.z);
        boolean anyHit = false;
        for (Enemy e : enemyManager.getEnemies()) {
            if (!e.alive) continue;
            float dx = e.position.x - origin.x;
            float dz = e.position.z - origin.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > GameConfig.lightningAoeRadius) continue;
            // Falloff: full damage in inner 50%, then linear drop
            float falloffStart = GameConfig.lightningAoeRadius * 0.5f;
            float dmg = GameConfig.lightningAoeDamage;
            if (dist > falloffStart)
                dmg *= 1f - (dist - falloffStart) / (GameConfig.lightningAoeRadius - falloffStart);
            boolean inWater = isInWater(world, e.position);
            if (inWater) dmg *= 1.8f;
            e.applyDamage(dmg);
            e.applyKnockback(0f, 5f, 0f);
            spawnBolt(e.getCentre(), true); // blue chain bolt for AOE
            anyHit = true;
        }
        if (anyHit) stormIntensity = 1.0f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** True if the enemy's feet or body are touching a water block. */
    private boolean isInWater(World world, Vector3f pos) {
        int fx = (int) Math.floor(pos.x);
        int fy = (int) Math.floor(pos.y - 0.1f);
        int fz = (int) Math.floor(pos.z);
        return world.getBlock(fx, fy,     fz) == Block.WATER
            || world.getBlock(fx, fy + 1, fz) == Block.WATER;
    }

    private void spawnBolt(Vector3f worldTarget, boolean chain) {
        LightningBolt bolt = new LightningBolt();
        bolt.worldTarget.set(worldTarget);
        bolt.maxLife = GameConfig.lightningBoltLife;
        bolt.life    = bolt.maxLife;
        bolt.isChain = chain;
        activeBolts.add(bolt);
    }
}
