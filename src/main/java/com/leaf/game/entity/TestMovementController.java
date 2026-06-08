package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;

public class TestMovementController {

    // ─── STATE MACHINE ────────────────────────────────────────────────────────
    public enum State { GROUNDED, AIRBORNE, WALLRUN, GRAPPLING, SURFACE_SLIDE, FLAPPY }

    // ─── FLAPPY BIRD MECHANICS ────────────────────────────────────────────────
    public int flappyScore = 0;
    public int lastPassedPipeX = 5010; // Initialize to 5010 to prevent ghost scores on the platform
    private static final int FLAPPY_START_X = 5020;
    private static final int FLAPPY_INTERVAL = 20; // Matches WorldGen spacing
    public boolean flappyWaitingToStart = true;
    // Custom 2D Side-View variables
    public boolean flappySideView = false; // Toggled by TAB in-game
    private boolean lastTab = false;

    // ─── TUNABLE FORWARD SPEED ───
    // Edit this number to speed up or slow down the game! (e.g. 12.0f is fast, 6.0f is slow)
    public float FLAPPY_FORWARD_SPEED = 10.5f;

    public State state = State.AIRBORNE;

    // ─── PHYSICS ─────────────────────────────────────────────────────────────
    public  Vector3f velocity   = new Vector3f();
    public int      jumpsLeft  = 2; // Unified double jump tracker

    // Step-up (Tunable)
    public float STEP_UP_MAX_BLOCKS = 2.0f;
    private EnemyManager enemyManager = null;
    private final Player player;

    // ─── TUNABLE CONSTANTS ────────────────────────────────────────────────────
    public float WALK_SPEED          = 7.0f;
    public float SPRINT_SPEED        = 14.0f;
    public float SPRINT_MULTIPLIER   = 1.6f;
    public float TEST_DASH_SPEED     = 32.0f;

    // Quake-style acceleration
    public float GROUND_ACCEL        = 80f;
    public float GROUND_FRICTION     = 10f;
    public float AIR_ACCEL           = 18f;

    // Slide
    public float SLIDE_FRICTION      = 1.2f;
    public float SLIDE_SPEED_SCALE   = 1.4f;
    public float SLIDE_MIN_SPEED     = 6.0f;
    public float SLIDE_MAX_SPEED     = 35.0f;
    public float SLIDE_EXIT_SPEED    = 3.0f;

    // Vertical
    public float GRAVITY             = 60.0f;
    public float JUMP_FORCE          = 16.0f;
    public float MAX_FALL_SPEED      = 60.0f;

    // Grapple (Pendulum Physics)
    public float GRAPPLE_REEL_SPEED  = 12.0f;  // Speed the rope shrinks
    public float GRAPPLE_PULL_ACCEL  = 25.0f;  // Extra forward tug
    public float GRAPPLE_MAX_DUR     = 3.0f;   // Extended for big swings

    // Wall run
    public float WALLRUN_GRAV_SCALE  = 0.08f;
    public float WALLRUN_STICK       = 3.0f;
    public float WALLRUN_ROLL_DEG    = 6.0f;
    public float WALL_PROBE_DIST     = 0.65f;
    public float WALL_MIN_SPEED_MUL  = 1.1f;
    public float WALL_DOT_MAX        = 0.7f;

    // State Trackers
    private boolean lastSpace = false;
    private boolean lastW     = false;
    private double  lastWTime = 0.0;
    private boolean isSprinting = false;
    private boolean wasDashing  = false;

    // Grapple
    private boolean  lastRMB     = false;
    private float    grappleTime = 0f;
    private float    ropeLength  = 0f;
    private Vector3f hookPoint   = new Vector3f();
    private Enemy    hookedEnemy = null;

    // Wall run
    private Vector3f wallNormal  = new Vector3f();
    private float    cameraRoll  = 0f;

    // ─── HITBOX ───────────────────────────────────────────────────────────────
    private static final float WIDTH  = 0.6f;
    private static final float HEIGHT = 1.8f;
    private static final float EPSILON = 0.01f;

    private static final float HW = WIDTH / 2f;
    private static final float HH = HEIGHT;

    private static final float[][] CARDINALS = {
            { 1, 0,  0}, {-1, 0,  0},
            { 0, 0,  1}, { 0, 0, -1}
    };

    public TestMovementController(Player p) { this.player = p; }
    public void setEnemyManager(EnemyManager em) { this.enemyManager = em; }
    public float getCameraRoll() { return cameraRoll; }

    // ─────────────────────────────────────────────────────────────────────────
    //  MAIN TICK
    // ─────────────────────────────────────────────────────────────────────────
    public void tick(long window, Camera camera, World world, float dt) {
        // Gather Input
        boolean wHeld     = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        boolean sHeld     = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
        boolean aHeld     = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
        boolean dHeld     = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
        boolean jumpHeld  = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        boolean shiftHeld = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
        boolean rmb       = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        boolean jumpPressed = jumpHeld && !lastSpace;
        lastSpace = jumpHeld;

        // Sprint Tracking (Double-tap W or Left Control)
        double now = glfwGetTime();
        if (wHeld && !lastW) {
            if (now - lastWTime < 0.3) isSprinting = true;
            lastWTime = now;
        }
        if (!wHeld) isSprinting = false;
        lastW = wHeld;
        boolean sprintActive = isSprinting || glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS;
        float targetSpeed = sprintActive ? SPRINT_SPEED : WALK_SPEED;

        // Flatten directions to XZ plane to prevent floaty vertical drifting
        Vector3f fwd = flat(camera.getForward());
        Vector3f rt  = flat(camera.getRight());
        Vector3f wishDir = new Vector3f();
        if (wHeld) wishDir.add(fwd);
        if (sHeld) wishDir.sub(fwd);
        if (dHeld) wishDir.add(rt);
        if (aHeld) wishDir.sub(rt);
        if (wishDir.lengthSquared() > 1e-6f) wishDir.normalize();

        // ── Grapple Evaluation ──
        handleGrapple(camera, world, rmb, dt);

        // ── Jump Logic (Double Jump) ──
        // FIX (Bug 3): Removed the outer jump block that was duplicating the jump
        // velocity on the same frame as the GROUNDED case's doJump() call.
        // All jumping is now handled exclusively inside the state machine cases below.

        // ── State Machine ──
        switch (state) {
            case GROUNDED -> {
                jumpsLeft = 2;
                rollToward(0f, 10f, dt);

                if (shiftHeld && horizSpeed() > WALK_SPEED * 0.8f) {
                    state = State.SURFACE_SLIDE;
                    break;
                }

                groundMove(wishDir, targetSpeed, dt);
                if (jumpPressed) doJump();
            }

            case AIRBORNE -> {
                rollToward(0f, 10f, dt);
                velocity.y = Math.max(-MAX_FALL_SPEED, velocity.y - GRAVITY * dt);
                airMove(wishDir, targetSpeed, dt);
                detectWallRun(world, wHeld);

                if (jumpPressed && jumpsLeft > 0) doJump();
            }

            case SURFACE_SLIDE -> {
                rollToward(0f, 10f, dt);
                velocity.y = Math.max(-MAX_FALL_SPEED, velocity.y - GRAVITY * dt);
                horizFriction(SLIDE_FRICTION, dt);

                if (!shiftHeld || horizSpeed() < SLIDE_EXIT_SPEED) state = State.AIRBORNE;
                if (jumpPressed) doJump();
            }

            case WALLRUN -> {
                // Instantly exit wall run if W is released
                if (!wHeld) {
                    state = State.AIRBORNE;
                    break;
                }

                jumpsLeft = 1;
                velocity.y = Math.max(-MAX_FALL_SPEED, velocity.y - GRAVITY * WALLRUN_GRAV_SCALE * dt);

                float into = velocity.dot(wallNormal);
                if (into < 0) velocity.sub(new Vector3f(wallNormal).mul(into));
                velocity.add(new Vector3f(wallNormal).mul(-WALLRUN_STICK * dt));

                float side = camera.getRight().dot(wallNormal);
                float rollTarget = (float) Math.toRadians(-WALLRUN_ROLL_DEG * Math.signum(side));
                cameraRoll += (rollTarget - cameraRoll) * 8f * dt;

                if (jumpPressed) {
                    velocity.x = wallNormal.x * JUMP_FORCE;
                    velocity.z = wallNormal.z * JUMP_FORCE;
                    velocity.y = JUMP_FORCE * 0.8f;
                    state = State.AIRBORNE;
                } else {
                    Vector3f intoWall = new Vector3f(player.position).sub(new Vector3f(wallNormal).mul(WALL_PROBE_DIST));
                    if (!checkCollision(intoWall, world)) state = State.AIRBORNE;
                }
            }

            case GRAPPLING -> {
                rollToward(0f, 10f, dt);

                if (hookedEnemy != null) {
                    if (!hookedEnemy.alive) { state = State.AIRBORNE; break; }
                    hookPoint.set(hookedEnemy.getCentre()).add(0, 0.5f, 0);
                }

                velocity.y = Math.max(-MAX_FALL_SPEED, velocity.y - GRAVITY * dt);
                airMove(wishDir, targetSpeed, dt); // Allow swinging

                // Reel in the rope
                ropeLength = Math.max(1.5f, ropeLength - GRAPPLE_REEL_SPEED * dt);

                // Pendulum Constraint
                Vector3f toPlayer = new Vector3f(player.position).sub(hookPoint);
                float dist = toPlayer.length();

                if (dist > ropeLength) {
                    toPlayer.normalize();
                    // Snap position to edge of sphere
                    player.position.set(hookPoint.x + toPlayer.x * ropeLength,
                            hookPoint.y + toPlayer.y * ropeLength,
                            hookPoint.z + toPlayer.z * ropeLength);

                    // Remove outward velocity (Tension)
                    float outward = velocity.dot(toPlayer);
                    if (outward > 0) {
                        velocity.sub(new Vector3f(toPlayer).mul(outward));
                    }
                }

                // Add gentle forward tug
                Vector3f toHook = new Vector3f(hookPoint).sub(player.position);
                if (toHook.lengthSquared() > 0.001f) {
                    velocity.add(new Vector3f(toHook).normalize().mul(GRAPPLE_PULL_ACCEL * dt));
                }

                grappleTime += dt;
                if (dist <= 1.8f || grappleTime > GRAPPLE_MAX_DUR) {
                    state = State.AIRBORNE;
                }

                // Sever on Jump
                if (jumpPressed) {
                    state = State.AIRBORNE;
                    velocity.y = Math.max(velocity.y, JUMP_FORCE * 0.8f);
                }
            }

            case FLAPPY -> {
                rollToward(0f, 10f, dt);

                // Disable WASD! Motion is completely locked to the 2D plane
                player.position.z = 5000f; // Force lock on Z=5000
                velocity.z = 0f;

                // Toggle Side-View Camera with TAB
                boolean tabPressed = glfwGetKey(window, GLFW_KEY_TAB) == GLFW_PRESS;
                if (tabPressed && !lastTab) {
                    flappySideView = !flappySideView;
                    if (flappySideView) {
                        // Snap camera yaw to -90 degrees (facing -Z) to look from +Z
                        camera.yaw   = (float) Math.toRadians(-90.0f);
                        camera.pitch = 0f;
                    } else {
                        // RESET camera direction back to straight first-person down the track!
                        camera.yaw   = 0f;
                        camera.pitch = 0f;
                    }
                }
                lastTab = tabPressed;

                // ── CAMERA OVERRIDE ──
                if (flappySideView) {
                    // Position camera 18 blocks back along +Z (further away), centered on player X/Y
                    camera.position.set(player.position.x, player.position.y + 0.4f, 5000f + 18.0f);
                    camera.yaw   = (float) Math.toRadians(-90.0f); // Looking towards -Z (Left to Right movement)
                    camera.pitch = 0f;
                }

                if (flappyWaitingToStart) {
                    // Hold statically on the starting platform until Space is pressed
                    velocity.set(0f, 0f, 0f);
                    player.position.x = 4980f;
                    player.position.y = 230.05f;

                    // LOCK camera view directly down the track!
                    if (!flappySideView) {
                        camera.yaw   = 0f;
                        camera.pitch = 0f;
                    }

                    if (jumpPressed) {
                        flappyWaitingToStart = false; // Start!
                        velocity.set(0f, JUMP_FORCE * 1.5f, 0f); // Clean launch
                        com.leaf.game.core.AudioManager.play("swoosh", 0.6f, 1.25f);
                    }
                    break;
                }

                // Constant, automatic forward motion along +X (Driven by customizable speed)
                velocity.x = FLAPPY_FORWARD_SPEED;

                // Heavy Flappy Gravity
                velocity.y = Math.max(-MAX_FALL_SPEED, velocity.y - GRAVITY * dt);

                // Space bar acts as the vertical FLAP
                if (jumpPressed) {
                    velocity.y = JUMP_FORCE * 1.2f;
                    com.leaf.game.core.AudioManager.play("swoosh", 0.6f, 1.25f);
                }

                // ── LAVA TICK DAMAGE (In-mode check) ──
                if (player.position.y <= 201.2f) {
                    player.health -= 250f * dt;
                    if (player.health < 0f) player.health = 0f;
                }

                // ── SCORE DETECTOR ──
                int currentPipeIndex = (int) Math.floor((player.position.x - FLAPPY_START_X) / FLAPPY_INTERVAL);
                int currentPipeX = FLAPPY_START_X + currentPipeIndex * FLAPPY_INTERVAL;
                if (player.position.x > currentPipeX + 2.0f && currentPipeX > lastPassedPipeX) {
                    lastPassedPipeX = currentPipeX;
                    flappyScore++;
                    com.leaf.game.core.AudioManager.play("seal_collect", 1.2f, 1.2f);
                }
            }
        }

        // Apply physical movement and robust AABB sub-stepping
        applyCollision(world, dt, shiftHeld, wishDir);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  MOVEMENT HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void doJump() {
        velocity.y = JUMP_FORCE;
        jumpsLeft = Math.max(0, jumpsLeft - 1);
        state = State.AIRBORNE;
    }

    private void groundMove(Vector3f wishDir, float targetSpeed, float dt) {
        if (player.abilities.isDashing) {
            if (!wasDashing) { velocity.y = 0f; wasDashing = true; }
            velocity.x = player.abilities.dashDirX * TEST_DASH_SPEED;
            velocity.z = player.abilities.dashDirZ * TEST_DASH_SPEED;
            return;
        }
        wasDashing = false;

        horizFriction(GROUND_FRICTION, dt);
        if (wishDir.lengthSquared() < 1e-6f) return;

        float cur = velocity.x * wishDir.x + velocity.z * wishDir.z;
        float add = targetSpeed - cur;
        if (add <= 0f) return;

        float accel = Math.min(GROUND_ACCEL * dt, add);
        velocity.x += wishDir.x * accel;
        velocity.z += wishDir.z * accel;
    }

    private void airMove(Vector3f wishDir, float targetSpeed, float dt) {
        if (player.abilities.isDashing) {
            if (!wasDashing) { velocity.y = 0f; wasDashing = true; }
            velocity.x = player.abilities.dashDirX * TEST_DASH_SPEED;
            velocity.z = player.abilities.dashDirZ * TEST_DASH_SPEED;
            return;
        }
        wasDashing = false;

        if (wishDir.lengthSquared() < 1e-6f) return;

        float cur = velocity.x * wishDir.x + velocity.z * wishDir.z;
        float add = targetSpeed - cur;
        if (add <= 0f) return;

        float accel = Math.min(AIR_ACCEL * dt, add);
        velocity.x += wishDir.x * accel;
        velocity.z += wishDir.z * accel;
    }

    private void horizFriction(float rate, float dt) {
        float speed = horizSpeed();
        if (speed < 0.1f) return;

        // Stop speed prevents asymptotic sliding
        float drop = Math.max(speed, 4.0f) * rate * dt;
        float newSpeed = Math.max(0, speed - drop);

        velocity.x *= newSpeed / speed;
        velocity.z *= newSpeed / speed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GRAPPLE (PENDULUM PHYSICS)
    // ─────────────────────────────────────────────────────────────────────────

    private void handleGrapple(Camera camera, World world, boolean rmb, float dt) {
        if (rmb && !lastRMB && state != State.GRAPPLING) {
            Vector3f dir = camera.getLookDirection();
            float rx = camera.position.x, ry = camera.position.y, rz = camera.position.z;
            final float STEP = 0.5f, MAX_DIST = 50f;
            boolean hit = false;
            hookedEnemy = null;

            for (float d = 0f; d < MAX_DIST && !hit; d += STEP) {
                rx += dir.x * STEP; ry += dir.y * STEP; rz += dir.z * STEP;

                if (enemyManager != null) {
                    for (Enemy e : enemyManager.getEnemies()) {
                        if (e.alive && vecDist(rx, ry, rz, e.getCentre()) < e.collisionRadius + 0.5f) {
                            hookedEnemy = e;
                            hookPoint.set(e.getCentre()).add(0, 0.9f, 0);
                            hit = true;
                            break;
                        }
                    }
                }
                if (!hit && world.getBlock((int) Math.floor(rx), (int) Math.floor(ry), (int) Math.floor(rz)).isSolid()) {
                    hookPoint.set(rx, ry, rz);
                    hit = true;
                }
            }

            if (hit) {
                state = State.GRAPPLING;
                grappleTime = 0f;
                ropeLength = new Vector3f(hookPoint).distance(player.position);
                velocity.y = Math.max(velocity.y, JUMP_FORCE * 0.4f); // Small launch pop
            }

        } else if (!rmb && state == State.GRAPPLING) {
            state = State.AIRBORNE;
            hookedEnemy = null;
        }
        lastRMB = rmb;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  WALL RUN DETECTION (Requires W)
    // ─────────────────────────────────────────────────────────────────────────

    private void detectWallRun(World world, boolean wHeld) {
        if (!wHeld) return; // Must hold forward to wall run

        float hs = horizSpeed();
        if (hs < WALK_SPEED * WALL_MIN_SPEED_MUL) return;

        for (float[] dir : CARDINALS) {
            Vector3f probe = new Vector3f(
                    player.position.x + dir[0] * WALL_PROBE_DIST,
                    player.position.y,
                    player.position.z + dir[2] * WALL_PROBE_DIST);

            if (!checkCollision(probe, world)) continue;

            float velToward = (velocity.x * dir[0] + velocity.z * dir[2]) / hs;
            if (velToward < 0f || velToward >= WALL_DOT_MAX) continue;

            state = State.WALLRUN;
            wallNormal.set(-dir[0], 0f, -dir[2]);
            velocity.y = Math.max(velocity.y, 1.5f);
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  COLLISION & STEP-UP
    // ─────────────────────────────────────────────────────────────────────────

    private void applyCollision(World world, float dt, boolean shiftHeld, Vector3f wishDir) {
        Vector3f delta = new Vector3f(velocity).mul(dt);

        // Number of substeps scales with the largest displacement component
        // to prevent tunnelling at high speed.
        int n = Math.max(1, (int) Math.ceil(
                Math.max(Math.abs(delta.x),
                        Math.max(Math.abs(delta.y), Math.abs(delta.z))) * 12f));

        float sx = delta.x / n;
        float sy = delta.y / n;
        float sz = delta.z / n;

        boolean groundedThisFrame = false;

        for (int i = 0; i < n; i++) {

            // ── X ────────────────────────────────────────────────────────────
            if (sx != 0f) {
                player.position.x += sx;
                if (checkCollision(player.position, world)) {
                    // Only kill if we are past the safe starting platform!
                    if (state == State.FLAPPY && player.position.x > 5000f) { killFlappyPlayer(); return; } // Instant pipe-hit death
                    player.position.x -= sx;
                    if (!tryStepUp(world, sx, 0f)) { velocity.x = 0f; sx = 0f; }
                }
            }

            // ── Y ────────────────────────────────────────────────────────────
            if (sy != 0f) {
                player.position.y += sy;
                if (checkCollision(player.position, world)) {
                    // Only kill if we are past the safe starting platform!
                    if (state == State.FLAPPY && player.position.x > 5000f) { killFlappyPlayer(); return; } // Instant pipe-hit death
                    player.position.y -= sy;
                    if (sy < 0f) {                      // hit the floor
                        groundedThisFrame = true;
                        tryLandingSlide(shiftHeld, wishDir);
                    }
                    velocity.y = 0f; sy = 0f;
                }
            }

            // ── Z ────────────────────────────────────────────────────────────
            if (sz != 0f) {
                player.position.z += sz;
                if (checkCollision(player.position, world)) {
                    if (state == State.FLAPPY && player.position.x > 5000f) { killFlappyPlayer(); return; } // Instant pipe-hit death
                    player.position.z -= sz;
                    if (!tryStepUp(world, 0f, sz)) { velocity.z = 0f; sz = 0f; }
                }
            }
        }

        // ── Ground state resolution ──────────────────────────────────────────
        // State Guard: Prevent State.FLAPPY from being overwritten back to Airborne/Grounded
        if (state != State.GRAPPLING && state != State.WALLRUN && state != State.FLAPPY) {
            boolean onFloor = groundedThisFrame ||
                    checkCollision(new Vector3f(player.position).add(0, -0.05f, 0), world);

            if (onFloor) {
                if (state == State.AIRBORNE) state = State.GROUNDED;
            } else {
                if (state == State.GROUNDED)      state = State.AIRBORNE;
                if (state == State.SURFACE_SLIDE) state = State.AIRBORNE; // flew off a ledge — keep momentum
            }
        }
    }

    public boolean resolveAxisFluid(World world, int axis, float delta) {
        float halfW = WIDTH / 2f;
        float[] lo = { -halfW, 0f,     -halfW };
        float[] hi = {  halfW, HEIGHT,  halfW };

        float p[] = { player.position.x, player.position.y, player.position.z };
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;

        int lo1 = (int)Math.floor(p[a1] + lo[a1] + EPSILON), hi1 = (int)Math.floor(p[a1] + hi[a1] - EPSILON);
        int lo2 = (int)Math.floor(p[a2] + lo[a2] + EPSILON), hi2 = (int)Math.floor(p[a2] + hi[a2] - EPSILON);

        boolean positive = delta > 0f;
        int block = (int)Math.floor(positive ? p[axis] + hi[axis] : p[axis] + lo[axis]);

        int[] c = new int[3];
        c[axis] = block;

        boolean hit = false;
        float highestHitY = -1;

        for (int i1 = lo1; i1 <= hi1; i1++) {
            c[a1] = i1;
            for (int i2 = lo2; i2 <= hi2; i2++) {
                c[a2] = i2;
                if (world.getBlock(c[0], c[1], c[2]).isSolid()) {
                    hit = true;
                    if (axis == 0 || axis == 2) {
                        int checkY = (axis == 0) ? i1 : i2;
                        highestHitY = Math.max(highestHitY, checkY);
                    }
                }
            }
            if (hit) break;
        }

        if (hit) {
            // ── FLAPPY BIRD COLLISION DEATH ──
            if (state == State.FLAPPY) {
                // Only die if we collide with blocks above the starting platform floor (Y=229)
                // This prevents the platform beneath our feet from instantly killing us!
                if (player.position.x > 5000f) {
                    killFlappyPlayer();
                    return true;
                }
            }

            player.position.setComponent(axis, positive ? (block - hi[axis]) : (block + 1f - lo[axis]));

            if (axis == 1) {
                velocity.y = 0;
                // State-Corruption Guard: Never let the floor reset State.FLAPPY
                if (delta < 0 && state != State.FLAPPY) {
                    state = State.GROUNDED;
                    jumpsLeft = 2;
                }
                return true;
            }

            if (state == State.GROUNDED || player.abilities.isDashing || state == State.SURFACE_SLIDE) {
                float wallH = (highestHitY + 1f) - player.position.y;
                if (wallH > 0f && wallH <= STEP_UP_MAX_BLOCKS) {
                    int headCheckY = (int) Math.floor(player.position.y + wallH + HEIGHT);
                    boolean hasHeadroom = !world.getBlock((int)player.position.x, headCheckY, (int)player.position.z).isSolid();

                    if (hasHeadroom) {
                        float glideSpeed = (float) Math.sqrt(2 * GRAVITY * (wallH + 0.1f));
                        if (velocity.y < glideSpeed) {
                            velocity.y = glideSpeed;
                        }
                        return true;
                    }
                }
            }

            if (axis == 0) velocity.x = 0;
            if (axis == 2) velocity.z = 0;
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    private boolean checkCollision(Vector3f pos, World world) {
        int x0 = (int) Math.floor(pos.x - HW), x1 = (int) Math.floor(pos.x + HW);
        int y0 = (int) Math.floor(pos.y),       y1 = (int) Math.floor(pos.y + HEIGHT);
        int z0 = (int) Math.floor(pos.z - HW), z1 = (int) Math.floor(pos.z + HW);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                if (y < 0 || y >= Chunk.HEIGHT) return true;
                for (int z = z0; z <= z1; z++) {
                    if (world.getBlock(x, y, z).isSolid()) return true;
                }
            }
        }
        return false;
    }

    private static Vector3f flat(Vector3f v) {
        float len = (float) Math.sqrt(v.x * v.x + v.z * v.z);
        return len > 1e-6f ? new Vector3f(v.x / len, 0f, v.z / len) : new Vector3f();
    }

    private float horizSpeed() { return (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z); }
    private void rollToward(float t, float r, float dt) { cameraRoll += (t - cameraRoll) * r * dt; }
    private static float vecDist(float x, float y, float z, Vector3f v) {
        float dx = x - v.x, dy = y - v.y, dz = z - v.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Attempts to step over a low obstacle (up to STEP_UP_MAX_BLOCKS).
     * Teleports the player up immediately — no velocity impulse — so the
     * step is instant and smooth rather than a physics bounce.
     */
    private boolean tryStepUp(World world, float dirX, float dirZ) {
        // Allow step-up during active dashes, but keep other states restricted
        boolean canStep = (state == State.GROUNDED) || player.abilities.isDashing;
        if (!canStep) return false;

        // Cast our float constant to an int for the loop
        for (int h = 1; h <= (int) STEP_UP_MAX_BLOCKS; h++) {
            Vector3f above = new Vector3f(player.position.x, player.position.y + h, player.position.z);
            if (checkCollision(above, world)) continue;

            Vector3f ahead = new Vector3f(above.x + Math.signum(dirX) * 0.4f,
                    above.y,
                    above.z + Math.signum(dirZ) * 0.4f);
            if (checkCollision(ahead, world)) continue;

            // Both clear — step up instantly (camera Y-offset smoothing handles the rest)
            player.position.y += h;
            return true;
        }
        return false;
    }

    /**
     * When the player hits the floor while airborne and holding shift,
     * convert the downward velocity into a horizontal slide.
     */
    private void tryLandingSlide(boolean shiftHeld, Vector3f wishDir) {
        if (!shiftHeld || state != State.AIRBORNE) return;
        if (velocity.y > -SLIDE_MIN_SPEED) return;

        float hs = horizSpeed();
        float slideSpeed = Math.min(SLIDE_MAX_SPEED, Math.abs(velocity.y) * SLIDE_SPEED_SCALE);

        if (hs > 1f) {
            float scale = slideSpeed / hs;
            velocity.x *= scale;
            velocity.z *= scale;
        } else if (wishDir.lengthSquared() > 1e-6f) {
            velocity.x = wishDir.x * slideSpeed;
            velocity.z = wishDir.z * slideSpeed;
        }

        state = State.SURFACE_SLIDE;
    }

    // ─── HELPER METHOD FOR FLAPPY COLLISION DEATH ───
    private void killFlappyPlayer() {
        com.leaf.game.core.AudioManager.play("fall_hit", 1.4f);
        player.health = 0f; // Instantly triggers central death flow
    }
}