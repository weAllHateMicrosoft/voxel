package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * AbilityController — four survival-mode player abilities.
 *
 *   Q   — Dash          horizontal burst, ghost trail, wall-stopped
 *   G   — Cannonball    hold to charge, release to launch, camera spin in flight
 *   Z   — State Rewind  hold to scrub back 5 s of player history (not world)
 *   E   — Blink         teleport to crosshair (up to blinkRange blocks)
 *
 * ── Integration contract ────────────────────────────────────────────────────
 *   • Player.update() calls abilities.tick() BEFORE the physics block.
 *   • If tick() returns true, Player.update() must skip physics and sync camera.
 *   • Dash/cannonball expose public dx/dz overrides read by Player's physics.
 *   • All abilities disabled while debugMode (flight) is active.
 *   • Only one ability can be active at a time; attempting to start a second
 *     while one is running is silently ignored.
 *
 * ── No renderer changes required ───────────────────────────────────────────
 *   Ghost trails / trajectory arcs are exposed as List<Vector3f> and rendered
 *   by Window.java using the existing item-mesh + alpha infrastructure.
 *   Overlay vignettes use the new overlayVignette* shader uniforms.
 */
public class AbilityController {

    private final Player player;

    // ── DASH (Q) ───────────────────────────────────────────────────────────────
    public  boolean  isDashing    = false;
    public  float    dashDirX     = 0f;   // unit vector, read by Player.update()
    public  float    dashDirZ     = 0f;
    private float    dashTimer    = 0f;
    private float    dashCooldown = 0f;
    private boolean  lastQ        = false;
    /** Ghost-trail positions (most recent last). Window renders these. */
    public  final List<Vector3f> dashTrail  = new ArrayList<>();
    /** Age of the last dash in seconds — for fading ghost trail after dash ends. */
    public  float    dashTrailAge = 999f;

    // ── CANNONBALL (hold G) ────────────────────────────────────────────────────
    public  boolean  isCannonballing = false;
    /** Horizontal velocity components injected into Player's dx/dz each frame. */
    public  float    cannonVelX      = 0f;
    public  float    cannonVelZ      = 0f;
    private boolean  isCharging_     = false;
    private float    chargeTime      = 0f;
    /** 0–1, used by Window for power-bar and FOV boost. */
    public  float    chargePower     = 0f;
    private float    cannonCooldown  = 0f;
    /** Accumulated spin angle (radians) — exposed via getCameraRoll(). */
    private float    cannonSpin      = 0f;
    private float    cannonSpinRate  = 0f;
    private boolean  lastG           = false;
    /** Trajectory preview dots for the charging arc. Window renders these. */
    public  final List<Vector3f> trajectoryArc = new ArrayList<>();

    // ── STATE REWIND (hold Z) ─────────────────────────────────────────────────
    public  boolean  isRewinding   = false;
    private float    rewindCooldown = 0f;
    private float    snapshotTimer  = 0f;
    private float    rewindAccum    = 0f;
    // Snapshot layout: [x, y, z, velocityY, cameraYaw, cameraPitch]
    private final ArrayDeque<float[]> snapshots = new ArrayDeque<>();
    /** Recent history positions for the ghost trail visualisation. */
    public  final List<Vector3f> rewindTrail = new ArrayList<>();
    private boolean  lastZ         = false;

    // ── BLINK (E) ─────────────────────────────────────────────────────────────
    /** True for exactly one frame after a blink fires — used by Window for trail. */
    public  boolean  justBlinked     = false;
    public  float    blinkFlashTimer = 0f;
    /** Origin and destination of the most recent blink, for the ghost trail. */
    public  Vector3f blinkOrigin     = new Vector3f();
    public  Vector3f blinkDest       = new Vector3f();
    private float    blinkCooldown   = 0f;
    private boolean  lastE           = false;

    // ── SMOOTH CAMERA EFFECTS ─────────────────────────────────────────────────
    // Composited into Player.getCameraRoll() and Player.getCameraFovBoost()
    // alongside FlightController's values.
    private float smoothRoll     = 0f;
    private float smoothFovBoost = 0f;

    // ── OVERLAY VIGNETTE ──────────────────────────────────────────────────────
    // Window reads these each frame and sends them to the overlayVignette* uniforms.
    private float     overlayStrength = 0f;
    private Vector3f  overlayColor    = new Vector3f();

    // ─────────────────────────────────────────────────────────────────────────
    public AbilityController(Player p) { this.player = p; }

    // ── Public accessors (read by Player + Window) ────────────────────────────
    public boolean   isCharging()         { return isCharging_; }
    public float     getCameraRoll()      { return smoothRoll; }
    public float     getCameraFovBoost()  { return smoothFovBoost; }
    public float     getOverlayStrength() { return overlayStrength; }
    public Vector3f  getOverlayColor()    { return overlayColor; }

    // Cooldown 0 = on cooldown, 1 = fully ready
    public float getDashCooldownFrac()   { return dashCooldown   <= 0 ? 1f : 1f - dashCooldown   / GameConfig.dashCooldown; }
    public float getCannonCooldownFrac() { return cannonCooldown <= 0 ? 1f : 1f - cannonCooldown / GameConfig.cannonCooldown; }
    public float getRewindCooldownFrac() { return rewindCooldown <= 0 ? 1f : 1f - rewindCooldown / GameConfig.rewindCooldown; }
    public float getBlinkCooldownFrac()  { return blinkCooldown  <= 0 ? 1f : 1f - blinkCooldown  / GameConfig.blinkCooldown; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main tick — call from Player.update() every frame, before physics block
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates all abilities.
     * @return true if this controller has full positional control this frame
     *         (Rewind active). Caller must skip normal physics and sync camera.
     */
    public boolean tick(long window, Camera camera, World world, float dt) {
        // Abilities only available in survival mode
        if (player.debugMode) {
            decayAll(dt);
            return false;
        }

        justBlinked = false;
        tickCooldowns(dt);
        recordSnapshot(camera, dt);
        updateRewindTrail();

        // ── REWIND (Z) — full takeover ────────────────────────────────────────
        boolean zHeld = glfwGetKey(window, GLFW_KEY_Z) == GLFW_PRESS;
        if (isRewinding) {
            if (!zHeld || snapshots.isEmpty()) {
                isRewinding    = false;
                rewindCooldown = GameConfig.rewindCooldown;
            } else {
                applyRewind(camera, dt);
                blendOverlay(new Vector3f(0.25f, 0.55f, 1.0f), 0.26f, dt);
                decayCameraEffects(0f, 0f, dt);
                return true; // caller must skip physics
            }
        }
        // Start rewind (need at least a few snapshots)
        if (zHeld && !lastZ && rewindCooldown <= 0f
                && !isAnyAbilityActive() && snapshots.size() >= 4) {
            isRewinding  = true;
            rewindAccum  = 0f;
        }
        lastZ = zHeld;

        // ── BLINK (E) — instant ───────────────────────────────────────────────
        boolean eHeld = glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS;
        if (eHeld && !lastE && blinkCooldown <= 0f && !isAnyAbilityActive()) {
            executeBlink(camera, world);
        }
        lastE = eHeld;
        if (blinkFlashTimer > 0f) {
            blinkFlashTimer = Math.max(0f, blinkFlashTimer - dt);
            float s = blinkFlashTimer / GameConfig.blinkFlashDecay;
            blendOverlay(new Vector3f(0.93f, 0.95f, 1.0f), s * 0.38f, dt);
        }

        // ── DASH (Q) — tap ────────────────────────────────────────────────────
        boolean qHeld = glfwGetKey(window, GLFW_KEY_Q) == GLFW_PRESS;
        if (qHeld && !lastQ && dashCooldown <= 0f && !isAnyAbilityActive()) {
            startDash(window, camera);
        }
        lastQ = qHeld;
        if (isDashing) {
            dashTimer -= dt;
            dashTrailAge = 0f;
            dashTrail.add(new Vector3f(player.position)); // record ghost position
            if (dashTrail.size() > GameConfig.dashGhostCount) dashTrail.remove(0);
            blendOverlay(new Vector3f(0.45f, 0.88f, 1.0f), 0.14f, dt);
            decayCameraEffects(0f, 5f, dt); // slight FOV boost
            if (dashTimer <= 0f) isDashing = false;
        } else {
            dashTrailAge += dt;
            if (dashTrailAge > 0.45f) dashTrail.clear();
        }

        // ── CANNONBALL (hold G) ───────────────────────────────────────────────
        boolean gHeld = glfwGetKey(window, GLFW_KEY_G) == GLFW_PRESS;
        if (isCannonballing) {
            // Apply drag to horizontal velocity each frame
            float drag = (float)Math.pow(GameConfig.cannonHorizDrag, dt * 60f);
            cannonVelX *= drag;
            cannonVelZ *= drag;
            // Camera spin
            cannonSpin += cannonSpinRate * dt;
            // Decay chargePower for HUD
            chargePower = Math.max(0f, chargePower - dt * 0.4f);
            // Cannonball ends when Player detects landing (isCannonballing = false set there)
            blendOverlay(new Vector3f(1.0f, 0.62f, 0.1f), 0.13f, dt);
            // Roll = accumulated spin; FOV = large boost
            float fovTarget = 10f + chargePower * 18f;
            smoothFovBoost += (fovTarget - smoothFovBoost) * Math.min(1f, GameConfig.cameraLerpSpeed * dt);
            // Smooth roll toward current spin (fast catch-up)
            smoothRoll += (cannonSpin - smoothRoll) * Math.min(1f, 20f * dt);
        } else if (!isAnyAbilityActive()) {
            if (gHeld && cannonCooldown <= 0f) {
                if (!isCharging_) { isCharging_ = true; chargeTime = 0f; }
                chargeTime  = Math.min(chargeTime + dt, GameConfig.cannonMaxCharge);
                chargePower = chargeTime / GameConfig.cannonMaxCharge;
                updateTrajectoryArc(camera);
                decayCameraEffects(0f, 0f, dt);
            } else if (!gHeld && isCharging_) {
                // Released G → FIRE
                fireCannonball(camera);
                isCharging_    = false;
                trajectoryArc.clear();
            } else {
                if (isCharging_) isCharging_ = false;
                trajectoryArc.clear();
            }
        }
        lastG = gHeld;

        // Decay effects when nothing is happening
        if (!isDashing && !isCannonballing && !isCharging_ && blinkFlashTimer <= 0f) {
            decayCameraEffects(0f, 0f, dt);
            blendOverlay(new Vector3f(0f, 0f, 0f), 0f, dt);
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DASH
    // ─────────────────────────────────────────────────────────────────────────

    private void startDash(long window, Camera camera) {
        isDashing    = true;
        dashTimer    = GameConfig.dashDuration;
        dashCooldown = GameConfig.dashCooldown;
        dashTrail.clear();

        // Direction: WASD input, fallback to look direction
        Vector3f fwd   = camera.getForward();
        Vector3f right = camera.getRight();
        float dx = 0, dz = 0;
        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) { dx += fwd.x; dz += fwd.z; }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) { dx -= fwd.x; dz -= fwd.z; }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) { dx += right.x; dz += right.z; }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) { dx -= right.x; dz -= right.z; }
        float len = (float)Math.sqrt(dx*dx + dz*dz);
        if (len < 0.01f) { dx = fwd.x; dz = fwd.z; len = 1f; }
        dashDirX = dx / len;
        dashDirZ = dz / len;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CANNONBALL
    // ─────────────────────────────────────────────────────────────────────────

    private void fireCannonball(Camera camera) {
        isCannonballing = true;
        cannonCooldown  = GameConfig.cannonCooldown;

        float speed = GameConfig.cannonMinPower
                + chargePower * (GameConfig.cannonMaxPower - GameConfig.cannonMinPower);
        Vector3f dir = camera.getLookDirection().normalize();

        cannonVelX = dir.x * speed;
        cannonVelZ = dir.z * speed;
        // Vertical launch: set as velocityY in Player (package-private accessor)
        player.setVelocityY(dir.y * speed);
        // Prevent fall damage from the launch height
        player.highestY = player.position.y;

        cannonSpinRate = speed * 0.065f; // faster launch = faster spin
        cannonSpin     = 0f;
    }

    private void updateTrajectoryArc(Camera camera) {
        trajectoryArc.clear();
        float speed = GameConfig.cannonMinPower
                + chargePower * (GameConfig.cannonMaxPower - GameConfig.cannonMinPower);
        Vector3f dir = camera.getLookDirection().normalize();
        float vx = dir.x * speed, vy = dir.y * speed, vz = dir.z * speed;
        float px = player.position.x, py = player.position.y + 0.9f, pz = player.position.z;
        float simDt = 0.075f;
        for (int i = 0; i < GameConfig.cannonArcPoints; i++) {
            trajectoryArc.add(new Vector3f(px, py, pz));
            vy -= GameConfig.GRAVITY * simDt;
            px += vx * simDt; py += vy * simDt; pz += vz * simDt;
            if (py < 0f) break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  STATE REWIND
    // ─────────────────────────────────────────────────────────────────────────

    private void recordSnapshot(Camera camera, float dt) {
        if (isRewinding) return; // don't record during rewind
        snapshotTimer += dt;
        if (snapshotTimer < 1f / GameConfig.rewindSnapshotHz) return;
        snapshotTimer = 0f;

        snapshots.addLast(new float[]{
                player.position.x, player.position.y, player.position.z,
                player.getVelocityY(), camera.yaw, camera.pitch
        });
        int maxSnaps = (int)(GameConfig.rewindBufferSecs * GameConfig.rewindSnapshotHz);
        while (snapshots.size() > maxSnaps) snapshots.pollFirst();
    }

    private void applyRewind(Camera camera, float dt) {
        float rewindInterval = 1f / (GameConfig.rewindSnapshotHz * GameConfig.rewindSpeed);
        rewindAccum += dt;
        while (rewindAccum >= rewindInterval && !snapshots.isEmpty()) {
            rewindAccum -= rewindInterval;
            float[] snap = snapshots.pollLast();
            player.position.set(snap[0], snap[1], snap[2]);
            player.setVelocityY(snap[3]);
            camera.yaw    = snap[4];
            camera.pitch  = snap[5];
            player.highestY = snap[1]; // prevent fall-damage surprise when rewind ends
        }
        if (snapshots.isEmpty()) {
            isRewinding    = false;
            rewindCooldown = GameConfig.rewindCooldown;
        }
    }

    private void updateRewindTrail() {
        rewindTrail.clear();
        // Show the most-recent 28 snapshots as a ghost trail
        float[][] arr = snapshots.toArray(new float[0][]);
        int start = Math.max(0, arr.length - 28);
        for (int i = start; i < arr.length; i++) {
            rewindTrail.add(new Vector3f(arr[i][0], arr[i][1], arr[i][2]));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BLINK
    // ─────────────────────────────────────────────────────────────────────────

    private void executeBlink(Camera camera, World world) {
        Vector3f dir  = camera.getLookDirection();
        float    step = 0.45f;
        float    rx   = camera.position.x, ry = camera.position.y, rz = camera.position.z;
        float    lastFX = rx, lastFY = ry - 1.6f, lastFZ = rz; // feet

        for (float dist = 0f; dist < GameConfig.blinkRange; dist += step) {
            rx += dir.x * step;
            ry += dir.y * step;
            rz += dir.z * step;
            if (world.getBlock((int)Math.floor(rx), (int)Math.floor(ry), (int)Math.floor(rz)).isSolid()) {
                break;
            }
            lastFX = rx;
            lastFY = ry - 1.6f;
            lastFZ = rz;
        }

        blinkOrigin.set(player.position);
        blinkDest.set(lastFX, lastFY, lastFZ);
        player.position.set(lastFX, lastFY, lastFZ);
        player.highestY  = lastFY; // suppress fall-damage at destination
        justBlinked      = true;
        blinkFlashTimer  = GameConfig.blinkFlashDecay;
        blinkCooldown    = GameConfig.blinkCooldown;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** True when any ability is running (dash, cannonball charging/firing, rewind). */
    public boolean isAnyAbilityActive() {
        return isDashing || isCannonballing || isCharging_ || isRewinding;
    }

    private void tickCooldowns(float dt) {
        if (dashCooldown   > 0f) dashCooldown   -= dt;
        if (cannonCooldown > 0f) cannonCooldown -= dt;
        if (rewindCooldown > 0f) rewindCooldown -= dt;
        if (blinkCooldown  > 0f) blinkCooldown  -= dt;
    }

    /**
     * Smooth-lerps roll and FOV boost toward target values.
     * When isCannonballing, roll tracks cannonSpin directly.
     */
    private void decayCameraEffects(float targetRoll, float targetFov, float dt) {
        if (isCannonballing) {
            // Cannonball spin: direct tracking (no lerp lag)
            smoothRoll += (cannonSpin - smoothRoll) * Math.min(1f, 20f * dt);
        } else {
            smoothRoll     += (targetRoll - smoothRoll)     * Math.min(1f, GameConfig.rollLerpSpeed * dt);
        }
        smoothFovBoost += (targetFov - smoothFovBoost) * Math.min(1f, GameConfig.cameraLerpSpeed * dt);
    }

    private void blendOverlay(Vector3f color, float strength, float dt) {
        overlayColor.lerp(color, Math.min(1f, 14f * dt));
        overlayStrength += (strength - overlayStrength) * Math.min(1f, 14f * dt);
    }

    private void decayAll(float dt) {
        decayCameraEffects(0f, 0f, dt);
        blendOverlay(new Vector3f(0f, 0f, 0f), 0f, dt);
    }
}