package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class TestMovementController {

    // ── TUNABLE CONSTANTS ──────────────────────────────────────────────────
    public float WALK_SPEED            = GameConfig.WALK_SPEED;
    public float SPRINT_MULTIPLIER     = 1.8f;     // Cranked up for parkour feel
    public float GRAPPLE_Y_SCALE       = 0.4f;     // Less vertical pull, more horizontal zip
    public float GRAPPLE_MAX_DURATION  = 0.6f;
    public float WALLRUN_GRAVITY_SCALE = 0.08f;    // 8% gravity while on a wall
    public float WALLRUN_CAMERA_ROLL   = 8.0f;     // degrees
    public int   STEP_UP_MAX_BLOCKS    = 2;
    public float WALL_ENTRY_DOT_MAX    = 0.6f;     // More forgiving wall approach angle
    public float WALL_ENTRY_MIN_SPEED  = 1.2f;     // Must be moving faster than walking to wallrun
    public float SLIDE_SPEED_SCALE     = 1.4f;
    public float SLIDE_MIN_SPEED       = 8.0f;
    public float SLIDE_MAX_SPEED       = 35.0f;
    public float JUMP_FORCE            = GameConfig.JUMP_FORCE * 1.1f;
    public float GRAVITY               = GameConfig.GRAVITY;

    // ── STATE MACHINE ──────────────────────────────────────────────────────
    public enum State { GROUNDED, AIRBORNE, WALLRUN, GRAPPLING, SURFACE_SLIDE }
    public State state = State.AIRBORNE;

    // ── PHYSICS STATE ──────────────────────────────────────────────────────
    public Vector3f velocity = new Vector3f(0, 0, 0);
    private int jumpsRemaining = 2;

    // Sprint tracking (isolated from Player.java)
    private boolean isSprinting = false;
    private boolean lastW       = false;
    private double  lastWTime   = 0;

    // Jump edge-detection
    private boolean lastSpace = false;

    // Grapple state
    private float grappleTimer = 0f;
    private Vector3f hookPoint = new Vector3f();
    private Enemy hookedEnemy = null;
    private boolean lastRightClick = false;

    // Wallrun state
    private Vector3f wallNormal = new Vector3f();
    private float cameraRoll = 0f;

    // Reference
    private final Player player;
    private EnemyManager enemyManager;

    public TestMovementController(Player player) {
        this.player = player;
    }

    public void setEnemyManager(EnemyManager em) {
        this.enemyManager = em;
    }

    public float getCameraRoll() {
        return cameraRoll;
    }

    // ── MAIN TICK ─────────────────────────────────────────────────────────
    public void tick(long window, Camera camera, World world, float dt) {
        boolean wHeld = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        boolean sHeld = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
        boolean aHeld = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
        boolean dHeld = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
        boolean spacePressed = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        boolean shiftHeld = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
        boolean rightClick = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        // [BUG 3 FIX] Local sprint double-tap logic
        double now = glfwGetTime();
        if (wHeld && !lastW) {
            if (now - lastWTime < 0.3) isSprinting = true;
            lastWTime = now;
        }
        if (!wHeld) isSprinting = false;
        lastW = wHeld;

        // [BUG 4 FIX] Edge detect jump
        boolean spaceJustPressed = spacePressed && !lastSpace;
        lastSpace = spacePressed;

        // 1. Process Grapple Hook
        handleGrapple(camera, world, rightClick, dt);

        // 2. Input to Acceleration
        Vector3f inputDir = new Vector3f();
        Vector3f fwd = camera.getForward();
        Vector3f right = camera.getRight();

        if (wHeld) inputDir.add(fwd);
        if (sHeld) inputDir.sub(fwd);
        if (dHeld) inputDir.add(right);
        if (aHeld) inputDir.sub(right);

        // [BUG 1 FIX] Explicitly zero Y to prevent flying, then normalize
        inputDir.y = 0f;
        if (inputDir.lengthSquared() > 0.01f) {
            inputDir.normalize();
        }

        float targetSpeed = WALK_SPEED;
        if (isSprinting) targetSpeed *= SPRINT_MULTIPLIER;

        // 3. State Processing & Forces
        switch (state) {
            case GROUNDED -> {
                jumpsRemaining = 2;
                cameraRoll += (0f - cameraRoll) * 10f * dt;

                // Slide Entry
                if (shiftHeld && isSprinting && velocity.lengthSquared() > 10f) {
                    state = State.SURFACE_SLIDE;
                    break;
                }

                // [BUG 2 FIX] Use horizontal friction
                applyHorizontalFriction(dt, 10.0f);
                velocity.add(new Vector3f(inputDir).mul(targetSpeed * 10f * dt));

                if (spaceJustPressed) performJump(1);
            }
            case AIRBORNE -> {
                cameraRoll += (0f - cameraRoll) * 10f * dt;

                // Air Strafing (Preserves momentum, allows steering)
                if (inputDir.lengthSquared() > 0) {
                    velocity.add(new Vector3f(inputDir).mul(targetSpeed * 2.5f * dt)); // Air control
                }
                velocity.y -= GRAVITY * dt;

                detectWallRun(world, targetSpeed);

                if (spaceJustPressed) performJump(2);
            }
            case SURFACE_SLIDE -> {
                cameraRoll += (0f - cameraRoll) * 10f * dt;

                // Very low friction for sliding
                applyHorizontalFriction(dt, 0.8f);

                if (spaceJustPressed) performJump(1);

                // Exit slide if we release shift or get too slow
                float horizSpeedSq = velocity.x * velocity.x + velocity.z * velocity.z;
                if (!shiftHeld || horizSpeedSq < SLIDE_MIN_SPEED * SLIDE_MIN_SPEED * 0.25f) {
                    state = State.AIRBORNE; // Will resolve to grounded naturally in collision loop
                }
            }
            case WALLRUN -> {
                jumpsRemaining = 1;
                velocity.y -= (GRAVITY * WALLRUN_GRAVITY_SCALE) * dt;

                // Project velocity onto wall (v = v - dot(v, n)*n)
                float dotN = velocity.dot(wallNormal);
                velocity.sub(new Vector3f(wallNormal).mul(dotN));

                // Stick to wall slightly so we don't peel off accidentally
                velocity.add(new Vector3f(wallNormal).mul(-4.0f * dt));

                // Camera Roll - lean away from the wall
                Vector3f look = camera.getLookDirection();
                float crossY = new Vector3f(wallNormal).cross(look).y;
                float rollTarget = (float) Math.toRadians(crossY > 0 ? WALLRUN_CAMERA_ROLL : -WALLRUN_CAMERA_ROLL);
                cameraRoll += (rollTarget - cameraRoll) * 8f * dt;

                if (spaceJustPressed) {
                    // Wall Jump - push off the wall normal and upwards
                    velocity.add(new Vector3f(wallNormal).mul(JUMP_FORCE * 0.8f));
                    velocity.y = JUMP_FORCE;
                    state = State.AIRBORNE;
                } else if (!checkCollision(new Vector3f(player.position).add(new Vector3f(wallNormal).mul(-0.5f)), world)) {
                    // Wall ended, we ran off the edge
                    state = State.AIRBORNE;
                }
            }
            case GRAPPLING -> {
                cameraRoll += (0f - cameraRoll) * 10f * dt;

                if (hookedEnemy != null) {
                    if (!hookedEnemy.alive) {
                        state = State.AIRBORNE;
                    } else {
                        hookPoint.set(hookedEnemy.getCentre()).add(0, 0.5f, 0);
                    }
                }

                Vector3f toHook = new Vector3f(hookPoint).sub(player.position);
                float dist = toHook.length();
                if (dist > 1.5f && grappleTimer < GRAPPLE_MAX_DURATION) {
                    Vector3f dir = toHook.normalize();
                    float pullStrength = 65f;

                    // Reduce vertical pull as requested
                    float yPull = dir.y * pullStrength * GRAPPLE_Y_SCALE;
                    velocity.lerp(new Vector3f(dir.x * pullStrength, yPull, dir.z * pullStrength), 6f * dt);

                    grappleTimer += dt;
                } else {
                    // Force detach when time runs out or we reach the point
                    state = State.AIRBORNE;
                }

                if (spaceJustPressed) performJump(1);
            }
        }

        // 4. Move and Slide (Custom Collision Loop)
        moveAndSlide(world, dt, shiftHeld);
    }

    // ── MOVEMENT & COLLISION ─────────────────────────────────────────────

    private void moveAndSlide(World world, float dt, boolean shiftHeld) {
        Vector3f delta = new Vector3f(velocity).mul(dt);

        int substeps = (int) Math.ceil(delta.length() * 10f);
        substeps = Math.max(1, substeps);
        Vector3f step = new Vector3f(delta).div(substeps);

        for (int i = 0; i < substeps; i++) {
            // X-AXIS
            if (step.x != 0) {
                player.position.x += step.x;
                if (checkCollision(player.position, world)) {
                    player.position.x -= step.x;
                    // If we hit a wall, try to step over it. If that fails, slide along it (zero out X).
                    if (!attemptStepUp(world, step.x, 0)) {
                        velocity.x = 0;
                    }
                }
            }

            // Y-AXIS
            if (step.y != 0) {
                player.position.y += step.y;
                if (checkCollision(player.position, world)) {
                    player.position.y -= step.y;

                    if (step.y < 0) { // Hit floor
                        // Fall-to-Slide Momentum Conversion
                        if (shiftHeld && state == State.AIRBORNE && velocity.y < -10f) {
                            float slideSpeed = Math.max(SLIDE_MIN_SPEED, Math.min(SLIDE_MAX_SPEED, Math.abs(velocity.y) * SLIDE_SPEED_SCALE));

                            Vector3f horizDir = new Vector3f(velocity.x, 0, velocity.z);
                            if (horizDir.lengthSquared() < 0.1f) horizDir.set(1, 0, 0); // fallback if dropping straight down
                            horizDir.normalize();

                            velocity.x = horizDir.x * slideSpeed;
                            velocity.z = horizDir.z * slideSpeed;
                            state = State.SURFACE_SLIDE;
                        }
                    } else { // Hit ceiling
                        velocity.y = 0;
                    }
                    if (state != State.SURFACE_SLIDE) velocity.y = 0;
                }
            }

            // Z-AXIS
            if (step.z != 0) {
                player.position.z += step.z;
                if (checkCollision(player.position, world)) {
                    player.position.z -= step.z;
                    if (!attemptStepUp(world, 0, step.z)) {
                        velocity.z = 0;
                    }
                }
            }
        }

        // Resolve Ground State dynamically
        if (state != State.GRAPPLING && state != State.WALLRUN) {
            boolean floorBelow = checkCollision(new Vector3f(player.position).add(0, -0.05f, 0), world);
            if (floorBelow) {
                if (state != State.SURFACE_SLIDE) state = State.GROUNDED;
            } else {
                state = State.AIRBORNE;
            }
        }
    }

    private boolean attemptStepUp(World world, float dirX, float dirZ) {
        if (state != State.GROUNDED && state != State.SURFACE_SLIDE && !player.abilities.isDashing) return false;

        for (int stepH = 1; stepH <= STEP_UP_MAX_BLOCKS; stepH++) {
            Vector3f upCheck = new Vector3f(player.position).add(0, stepH + 0.1f, 0);
            // Check if there is headroom to step up
            if (!checkCollision(upCheck, world)) {
                // Check if we can move forward into the new elevated space
                Vector3f forwardCheck = new Vector3f(upCheck).add(Math.signum(dirX) * 0.3f, 0, Math.signum(dirZ) * 0.3f);
                if (!checkCollision(forwardCheck, world)) {
                    // Impulse popping us up and over
                    velocity.y = Math.max(velocity.y, 6.0f + (stepH * 1.5f));
                    return true;
                }
            }
        }
        return false;
    }

    // [BUG 2 FIX] Pure horizontal friction
    private void applyHorizontalFriction(float dt, float amount) {
        float speed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (speed > 0) {
            float drop = speed * amount * dt;
            float newSpeed = Math.max(speed - drop, 0);
            float mult = newSpeed / speed;
            velocity.x *= mult;
            velocity.z *= mult;
        }
    }

    private void performJump(int cost) {
        if (jumpsRemaining > 0 || state == State.WALLRUN || state == State.GRAPPLING) {
            velocity.y = JUMP_FORCE;
            jumpsRemaining--;
            state = State.AIRBORNE;
        }
    }

    // ── ABILITIES ────────────────────────────────────────────────────────

    private void handleGrapple(Camera camera, World world, boolean rightClick, float dt) {
        if (rightClick && !lastRightClick && state != State.GRAPPLING) {
            // Find target
            Vector3f dir = camera.getLookDirection();
            float step = 0.5f;
            float max = 60f;
            float rx = camera.position.x, ry = camera.position.y, rz = camera.position.z;

            boolean hit = false;
            for (float d = 0f; d < max; d += step) {
                rx += dir.x * step; ry += dir.y * step; rz += dir.z * step;

                // 1. Check Enemies First
                if (enemyManager != null) {
                    for (Enemy e : enemyManager.getEnemies()) {
                        if (e.alive && new Vector3f(rx, ry, rz).distance(e.getCentre()) < e.collisionRadius + 0.5f) {
                            hookedEnemy = e;
                            hit = true; break;
                        }
                    }
                }
                if (hit) break;

                // 2. Check Voxel Blocks
                int bx = (int)Math.floor(rx);
                int by = (int)Math.floor(ry);
                int bz = (int)Math.floor(rz);

                if (by >= 0 && by < Chunk.HEIGHT && world.getBlock(bx, by, bz).isSolid()) {
                    hookPoint.set(rx, ry, rz);
                    hookedEnemy = null;
                    hit = true; break;
                }
            }

            if (hit) {
                state = State.GRAPPLING;
                grappleTimer = 0f;
                // Pop the player off the ground immediately to start flying
                velocity.y = Math.max(velocity.y, JUMP_FORCE * 0.8f);
            }
        } else if (!rightClick && state == State.GRAPPLING) {
            // Detach when button is released
            state = State.AIRBORNE;
        }
        lastRightClick = rightClick;
    }

    // [BUG 5 FIX] Detect walls using cardinal Voxel geometry directions
    private void detectWallRun(World world, float walkSpeed) {
        if (state == State.WALLRUN || state == State.GRAPPLING) return;

        Vector3f horizVel = new Vector3f(velocity.x, 0, velocity.z);
        if (horizVel.length() < walkSpeed * WALL_ENTRY_MIN_SPEED) return;

        // Voxel cardinal directions
        Vector3f[] cardinals = {
                new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
                new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)
        };

        for (Vector3f dir : cardinals) {
            // Probe slightly further than the player's AABB (0.3f)
            Vector3f probe = new Vector3f(player.position).add(new Vector3f(dir).mul(0.45f));

            if (checkCollision(probe, world)) {
                // The surface normal is the opposite of the probe direction
                Vector3f normal = new Vector3f(dir).negate();

                // Ensure we aren't face-planting straight into the wall
                float dot = horizVel.normalize().dot(normal);
                if (Math.abs(dot) < WALL_ENTRY_DOT_MAX) {
                    state = State.WALLRUN;
                    wallNormal.set(normal);

                    // Pop up slightly upon gripping the wall
                    velocity.y = Math.max(velocity.y, 3.0f);
                    return;
                }
            }
        }
    }

    // ── UTILITIES ────────────────────────────────────────────────────────

    private boolean checkCollision(Vector3f pos, World world) {
        float hw = 0.3f - 0.01f;
        float h  = 1.8f - 0.01f;
        int minX = (int)Math.floor(pos.x - hw);
        int maxX = (int)Math.floor(pos.x + hw);
        int minY = (int)Math.floor(pos.y);
        int maxY = (int)Math.floor(pos.y + h);
        int minZ = (int)Math.floor(pos.z - hw);
        int maxZ = (int)Math.floor(pos.z + hw);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (y < 0 || y >= Chunk.HEIGHT) return true;
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlock(x, y, z).isSolid()) return true;
                }
            }
        }
        return false;
    }
}