package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.World;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.Block;
import org.joml.Vector3f;

import java.util.*;

/**
 * Enemy — an AI-driven combat target.
 *
 * ── Two types ─────────────────────────────────────────────────────────────────
 *   GOLEM    stone colossus   — very slow, huge, slam + boulder throw
 *   THROWER  ranged attacker  — keeps distance, arc-throws rock projectiles
 *
 * ── State machine ─────────────────────────────────────────────────────────────
 *   IDLE → (in aggro range) → ALERTED → (alert timer) → CHASE
 *   CHASE → (in attack range) → ATTACK  |  (leash) → IDLE
 *   ATTACK → (range lost) → CHASE
 *   RETREATING  [THROWER only]  — backs away when player is too close
 *   SLAMMING    [GOLEM only]    — brief delay before shockwave AoE
 *
 * ── Pathfinding ───────────────────────────────────────────────────────────────
 *   Uses a flat A* search on the XZ plane (integer grid) to find a route
 *   around obstacles.  The path is cached and followed step-by-step; it is
 *   re-planned whenever the enemy gets within one block of the next waypoint,
 *   or whenever the cached path is stale (>PATH_CACHE_SECS seconds old), or
 *   whenever the target has moved more than PATH_REPLAN_DIST blocks.
 *
 *   Hole avoidance: a candidate step is only added to the open set if at
 *   least one of the two ground blocks directly under its landing column is
 *   solid. This prevents small/medium enemies from walking off ledges.
 *   The GOLEM ignores the hole penalty (it can jump two blocks and will
 *   smash its way through anything).
 *
 * ── GOLEM block-breaking ─────────────────────────────────────────────────────
 *   When the Golem's A* path is blocked by a wall it cannot jump, it sets
 *   pendingBlockBreak so EnemyManager can remove the block from the World.
 *   It is "smart" about which block to break: it inspects the two-block-tall
 *   column ahead and picks whichever block in the column is solid, starting
 *   from the feet upward, so it carves the minimal opening needed.
 *   If stuck in a pit (no solid ground neighbour), it breaks the wall at
 *   feet level to escape.
 */
public class Enemy {

    // ═════════════════════════════════════════════════════════════════════════
    //  Enums
    // ═════════════════════════════════════════════════════════════════════════

    public enum Type  { GOLEM, THROWER, ZOMBIE, SLIME, GUARDIAN }
    public enum State { IDLE, ALERTED, CHASE, ATTACK, RETREATING, SLAMMING }

    // ═════════════════════════════════════════════════════════════════════════
    //  Static constants
    // ═════════════════════════════════════════════════════════════════════════

    public static final float RADIUS      = 0.5f;
    public static final float HALF_HEIGHT = 1.0f;

    private static final float STRAFE_FLIP_SECS = 0.8f;
    private static final float GRAVITY          = 28f;
    private static final float MAX_FALL_SPEED   = 30f;

    private static final float PLAYER_RADIUS = 0.3f;

    // ── A* / pathfinding constants ────────────────────────────────────────────
    /** Maximum path-planning search radius (Manhattan), in blocks. */
    private static final int   PATH_MAX_RADIUS    = 24;
    /** Re-plan if target moved more than this many blocks. */
    private static final float PATH_REPLAN_DIST   = 2.5f;
    /** Re-plan at least this often (seconds), regardless of target movement. */
    private static final float PATH_CACHE_SECS    = 2.0f;
    /** How many waypoints to keep in the queued path. */
    private static final int   PATH_MAX_NODES     = 48;

    // ═════════════════════════════════════════════════════════════════════════
    //  Identity
    // ═════════════════════════════════════════════════════════════════════════

    private static int nextId = 0;
    public  final  int  id;
    public  final  Type type;

    // ═════════════════════════════════════════════════════════════════════════
    //  Stats
    // ═════════════════════════════════════════════════════════════════════════

    public  final float maxHealth;
    public        float health;
    private final float speed;
    private final float damagePerSec;
    private final float aggroRange;
    private final float attackRange;
    private final float attackInterval;

    // ═════════════════════════════════════════════════════════════════════════
    //  Per-instance hitbox
    // ═════════════════════════════════════════════════════════════════════════

    public final float collisionRadius;
    public final float halfHeight;

    // ═════════════════════════════════════════════════════════════════════════
    //  World state
    // ═════════════════════════════════════════════════════════════════════════

    public  final  Vector3f position;
    public         boolean  alive    = true;
    private        float    velocityY = 0f;
    private        boolean  onGround  = false;

    public float knockbackVelX = 0f;
    public float knockbackVelZ = 0f;

    // ═════════════════════════════════════════════════════════════════════════
    //  AI state
    // ═════════════════════════════════════════════════════════════════════════

    public  State state       = State.IDLE;
    private float alertTimer  = 0f;
    private float strafeTimer = 0f;
    private float strafeSign  = 1f;
    private float attackCooldown = 0f;

    // ── GOLEM special ─────────────────────────────────────────────────────────
    private float slamCooldown  = 0f;
    private float slamWindUp    = 0f;
    public  boolean wantsToThrow = false;
    private float throwCooldown  = 0f;

    // ── GUARDIAN special ──────────────────────────────────────────────────────
    public  float   facingYaw    = 0f;
    public  String  attackAnim   = "idle";
    private int     comboIndex   = 0;
    private float   swingTimer   = 0f;
    private boolean patrolWalking = false;
    private float   patrolTimer  = 0f;

    // ── Block Breaking State ──────────────────────────────────────────────────
    public boolean pendingBlockBreak = false;
    public int     breakX, breakY, breakZ;
    private float  stuckTimer = 0f;

    // ─── A* path cache ────────────────────────────────────────────────────────
    /** Queued waypoints (XZ integer grid coordinates), closest first. */
    private final Deque<int[]> pathWaypoints = new ArrayDeque<>();
    /** World-XZ of the target when the current path was planned. */
    private int   pathTargetX = Integer.MIN_VALUE;
    private int   pathTargetZ = Integer.MIN_VALUE;
    /** Seconds since the path was last planned. */
    private float pathAge     = Float.MAX_VALUE;
    /** Set true when A* confirmed there is NO path (avoids re-planning every frame). */
    private boolean pathBlocked = false;

    // ── Sound timers (prevent sound spam) ────────────────────────────────────
    private float stepSoundTimer    = 0f;
    private float breakSoundPlayed  = 0f; // cooldown between break sounds
    private float attackSoundTimer  = 0f;

    // ═════════════════════════════════════════════════════════════════════════
    //  Visual / output
    // ═════════════════════════════════════════════════════════════════════════

    public float hitFlashTimer    = 0f;
    public float framePlayerDamage = 0f;
    public float mudTrapTimer     = 0f;

    // ── GRAB STATE ────────────────────────────────────────────────────────────
    public boolean isGrabbed    = false;
    public boolean isThrown     = false;
    public float thrownVelX = 0f, thrownVelY = 0f, thrownVelZ = 0f;
    public boolean pendingGrabImpact = false;
    public int     grabImpactX = 0, grabImpactY = 0, grabImpactZ = 0;
    public boolean grabImpactIsGround = false;

    // ═════════════════════════════════════════════════════════════════════════
    //  Construction
    // ═════════════════════════════════════════════════════════════════════════

    public Enemy(float x, float y, float z, Type type) {
        this.id       = nextId++;
        this.type     = type;
        this.position = new Vector3f(x, y, z);

        float cr = RADIUS, hh = HALF_HEIGHT;
        float health_, speed_, dps_, aggro_, atk_, atkI_;

        switch (type) {
            case GOLEM -> {
                health_ = GameConfig.golemHealth;  speed_ = GameConfig.golemSpeed;
                dps_    = GameConfig.golemDamagePerSec;  aggro_ = GameConfig.golemAggroRange;
                atk_    = GameConfig.golemAttackRange;   atkI_  = GameConfig.golemAttackInterval;
                cr = 1.1f; hh = 1.8f;
            }
            case THROWER -> {
                health_ = GameConfig.throwerHealth; speed_ = GameConfig.throwerSpeed;
                dps_    = GameConfig.throwerDamagePerSec; aggro_ = GameConfig.throwerAggroRange;
                atk_    = GameConfig.throwerAttackRange;  atkI_  = GameConfig.throwerAttackInterval;
                cr = 0.5f; hh = 1.1f;
            }
            case ZOMBIE -> {
                health_ = GameConfig.zombieHealth;  speed_ = GameConfig.zombieSpeed;
                dps_    = GameConfig.zombieDamagePerSec;  aggro_ = GameConfig.zombieAggroRange;
                atk_    = GameConfig.zombieAttackRange;   atkI_  = GameConfig.zombieAttackInterval;
                cr = 0.5f; hh = 1.0f;
            }
            case SLIME -> {
                health_ = GameConfig.slimeHealth;  speed_ = GameConfig.slimeSpeed;
                dps_    = GameConfig.slimeDamagePerSec;  aggro_ = GameConfig.slimeAggroRange;
                atk_    = GameConfig.slimeAttackRange;   atkI_  = GameConfig.slimeAttackInterval;
                cr = 0.6f; hh = 0.5f;
            }
            case GUARDIAN -> {
                health_ = GameConfig.guardianHealth; speed_ = GameConfig.guardianSpeed;
                dps_    = GameConfig.guardianHitDamage; aggro_ = GameConfig.guardianAggroRange;
                atk_    = GameConfig.guardianAttackRange; atkI_ = GameConfig.guardianAttackTime;
                cr = 1.1f; hh = 1.4f;
            }
            default -> {
                health_ = GameConfig.throwerHealth; speed_ = GameConfig.throwerSpeed;
                dps_    = GameConfig.throwerDamagePerSec; aggro_ = GameConfig.throwerAggroRange;
                atk_    = GameConfig.throwerAttackRange;  atkI_  = GameConfig.throwerAttackInterval;
                cr = 0.5f; hh = 1.1f;
            }
        }

        this.maxHealth      = health_;
        this.health         = health_;
        this.speed          = speed_;
        this.damagePerSec   = dps_;
        this.aggroRange     = aggro_;
        this.attackRange    = atk_;
        this.attackInterval = atkI_;
        this.collisionRadius = cr;
        this.halfHeight      = hh;
    }

    public Enemy(float x, float y, float z) { this(x, y, z, Type.THROWER); }

    // ═════════════════════════════════════════════════════════════════════════
    //  Damage / knockback API
    // ═════════════════════════════════════════════════════════════════════════

    public boolean applyDamage(float amount) {
        if (!alive) return false;
        if (type == Type.GOLEM) amount *= GameConfig.golemSmashResist + (1f - GameConfig.golemSmashResist);
        health        = Math.max(0f, health - amount);
        hitFlashTimer = 0.18f;
        if (health <= 0f) {
            alive = false;
            com.leaf.game.core.AudioManager.playAt("fall_smash", position, (Vector3f) null, 50f);
            return true;
        }
        com.leaf.game.core.AudioManager.playAt("seal_hit", position, (Vector3f) null, 35f);
        return false;
    }

    public void applyKnockback(float kx, float ky, float kz) {
        float resist = (type == Type.GOLEM) ? 0.15f : 1.0f;
        knockbackVelX = kx * resist;
        knockbackVelZ = kz * resist;
        if (ky > 0f) velocityY = Math.max(velocityY, ky * resist);
        // Knockback invalidates the current path
        invalidatePath();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main update
    // ═════════════════════════════════════════════════════════════════════════

    public void update(float dt, World world, Vector3f playerPos, List<Enemy> allEnemies) {
        framePlayerDamage    = 0f;
        wantsToThrow         = false;
        pendingGrabImpact    = false;

        if (!alive) {
            if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);
            return;
        }
        if (hitFlashTimer > 0f) hitFlashTimer = Math.max(0f, hitFlashTimer - dt);

        if (isGrabbed) return;

        if (isThrown) {
            tickThrownFlight(dt, world);
            return;
        }

        Vector3f playerCentre = new Vector3f(playerPos.x, playerPos.y + 0.9f, playerPos.z);
        float    distToPlayer = new Vector3f(playerCentre).sub(getCentre()).length();

        tickCooldowns(dt);
        tickSoundTimers(dt);
        tickAI(dt, distToPlayer, playerCentre, playerPos);
        tickMovement(dt, world, playerCentre, distToPlayer, allEnemies);
        tickGravity(dt, world);

        if (knockbackVelX != 0f || knockbackVelZ != 0f) {
            position.x += knockbackVelX * dt;
            position.z += knockbackVelZ * dt;
            float decay = (float) Math.pow(GameConfig.knockbackDecay, dt);
            knockbackVelX *= decay;
            knockbackVelZ *= decay;
            if (Math.abs(knockbackVelX) < 0.05f) knockbackVelX = 0f;
            if (Math.abs(knockbackVelZ) < 0.05f) knockbackVelZ = 0f;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Cooldown / sound ticks
    // ═════════════════════════════════════════════════════════════════════════

    private void tickCooldowns(float dt) {
        if (attackCooldown > 0f) attackCooldown -= dt;
        if (slamCooldown  > 0f) slamCooldown  -= dt;
        if (throwCooldown > 0f) throwCooldown  -= dt;
        if (mudTrapTimer  > 0f) mudTrapTimer   -= dt;
        pathAge += dt;
    }

    private void tickSoundTimers(float dt) {
        if (stepSoundTimer   > 0f) stepSoundTimer   -= dt;
        if (breakSoundPlayed > 0f) breakSoundPlayed -= dt;
        if (attackSoundTimer > 0f) attackSoundTimer -= dt;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  AI State Machine
    // ═════════════════════════════════════════════════════════════════════════

    private void tickAI(float dt, float distToPlayer,
                        Vector3f playerCentre, Vector3f playerPos) {
        if (mudTrapTimer > 0f) return;

        float leash = aggroRange * 1.8f;

        switch (type) {
            case GOLEM    -> tickGolemAI(dt, distToPlayer, leash, playerCentre);
            case THROWER  -> tickThrowerAI(dt, distToPlayer, leash);
            case ZOMBIE, SLIME -> tickZombieAI(dt, distToPlayer, leash);
            case GUARDIAN -> tickGuardianAI(dt, distToPlayer, leash, playerCentre);
        }
    }

    // ─── GUARDIAN ───────────────────────────────────────────────────────────────

    private void tickGuardianAI(float dt, float dist, float leash, Vector3f playerCentre) {
        switch (state) {
            case IDLE -> {
                tickPatrol(dt);
                if (dist <= aggroRange) { state = State.ALERTED; alertTimer = 0.6f; patrolWalking = false; }
            }
            case ALERTED -> {
                faceToward(playerCentre);
                alertTimer -= dt;
                if (alertTimer <= 0f) state = State.CHASE;
            }
            case CHASE -> {
                faceToward(playerCentre);
                if (dist > leash) { state = State.IDLE; patrolTimer = 0f; patrolWalking = false; break; }
                if (dist <= attackRange) { state = State.ATTACK; beginSwing(); }
            }
            case ATTACK -> {
                faceToward(playerCentre);
                swingTimer -= dt;
                if (swingTimer <= 0f) {
                    if (dist <= attackRange * 1.2f) {
                        boolean both = (comboIndex == 2);
                        framePlayerDamage = GameConfig.guardianHitDamage * (both ? 2f : 1f);
                        com.leaf.game.core.AudioManager.playAt("ground_smash", position, (Vector3f) null, 45f);
                    }
                    comboIndex = (comboIndex + 1) % 3;
                    if (dist > attackRange * 1.4f) state = State.CHASE;
                    else beginSwing();
                }
            }
            default -> state = State.CHASE;
        }
    }

    private void beginSwing() {
        swingTimer = GameConfig.guardianAttackTime;
        attackAnim = switch (comboIndex) {
            case 0  -> "attack_left";
            case 1  -> "attack_right";
            default -> "attack";
        };
    }

    private void tickPatrol(float dt) {
        patrolTimer -= dt;
        if (patrolTimer <= 0f) {
            if (patrolWalking) {
                patrolWalking = false;
                patrolTimer   = 2f + (float) Math.random() * 4f;
                if (Math.random() < 0.6) facingYaw = randomYaw();
            } else {
                patrolWalking = true;
                facingYaw     = randomYaw();
                patrolTimer   = 2f + (float) Math.random() * 3f;
            }
        }
    }

    private void faceToward(Vector3f p) {
        facingYaw = (float) Math.atan2(p.x - position.x, p.z - position.z);
    }
    private static float randomYaw() { return (float) (Math.random() * Math.PI * 2.0); }

    public String getAnimName() {
        if (!alive) return "idle";
        return switch (state) {
            case ATTACK, SLAMMING -> (type == Type.GUARDIAN && attackAnim != null) ? attackAnim : "attack";
            case CHASE, ALERTED   -> "walk";
            case IDLE             -> patrolWalking ? "walk" : "idle";
            default               -> "idle";
        };
    }

    // ─── GOLEM ────────────────────────────────────────────────────────────────

    private void tickGolemAI(float dt, float dist, float leash, Vector3f playerCentre) {
        switch (state) {
            case IDLE    -> { if (dist <= aggroRange) {
                state = State.ALERTED; alertTimer = 1.5f;
                // Golem awakens — deep rumble
                com.leaf.game.core.AudioManager.playAt("ground_smash", position, (Vector3f) null, 60f);
            }}
            case ALERTED -> { alertTimer -= dt; if (alertTimer <= 0f) state = State.CHASE; }

            case CHASE -> {
                if (dist > leash) { state = State.IDLE; break; }
                if (dist <= GameConfig.golemSlamRange && slamCooldown <= 0f) {
                    state = State.SLAMMING;
                    slamWindUp = 0.6f;
                    break;
                }
                if (dist >= GameConfig.golemSlamRange && dist <= GameConfig.golemThrowRange
                        && throwCooldown <= 0f) {
                    wantsToThrow  = true;
                    throwCooldown = GameConfig.golemThrowCooldown;
                }
                if (dist <= attackRange) state = State.ATTACK;
            }

            case SLAMMING -> {
                slamWindUp -= dt;
                if (slamWindUp <= 0f) {
                    framePlayerDamage = GameConfig.golemSlamDamage;
                    slamCooldown = GameConfig.golemSlamCooldown;
                    state        = State.CHASE;
                    com.leaf.game.core.AudioManager.playAt("fall_smash", position, (Vector3f) null, 70f);
                }
            }

            case ATTACK -> {
                // Damage fires at 0.6 s into the attack interval, not at the end.
                // We use attackCooldown as a count-down; when it crosses the 0.6 s
                // threshold from above we deal damage, then let it run to zero
                // before resetting for the next swing.
                if (attackCooldown <= 0f) {
                    // Start a new attack cycle
                    attackCooldown = attackInterval;
                    // Play swing-start sound
                    if (attackSoundTimer <= 0f) {
                        com.leaf.game.core.AudioManager.playAt("ground_smash", position, (Vector3f) null, 45f);
                        attackSoundTimer = attackInterval * 0.9f;
                    }
                } else if (attackCooldown <= (attackInterval - 0.6f)) {
                    // 0.6 s have elapsed since the swing started → deal damage once
                    if (framePlayerDamage == 0f) {
                        framePlayerDamage = damagePerSec * attackInterval;
                    }
                }
                if (dist > attackRange * 1.3f) state = State.CHASE;
            }
            default -> state = State.CHASE;
        }
    }

    // ─── THROWER ──────────────────────────────────────────────────────────────

    private void tickThrowerAI(float dt, float dist, float leash) {
        switch (state) {
            case IDLE    -> { if (dist <= aggroRange) { state = State.ALERTED; alertTimer = 0.4f; } }
            case ALERTED -> { alertTimer -= dt; if (alertTimer <= 0f) state = State.CHASE; }

            case CHASE -> {
                if (dist > leash) { state = State.IDLE; break; }
                if (dist < GameConfig.throwerPreferredDist * 0.6f) {
                    state = State.RETREATING; break;
                }
                if (dist <= GameConfig.throwerAggroRange && throwCooldown <= 0f) {
                    wantsToThrow  = true;
                    throwCooldown = GameConfig.throwerThrowCooldown;
                }
                if (dist <= attackRange) state = State.ATTACK;
            }

            case RETREATING -> {
                if (dist >= GameConfig.throwerPreferredDist) state = State.CHASE;
                if (dist > leash) state = State.IDLE;
            }

            case ATTACK -> {
                if (attackCooldown <= 0f) {
                    framePlayerDamage = damagePerSec * attackInterval;
                    attackCooldown    = attackInterval;
                }
                if (dist > attackRange * 1.5f) state = State.CHASE;
            }
            default -> state = State.CHASE;
        }
    }

    // ─── ZOMBIE ───────────────────────────────────────────────────────────────

    private void tickZombieAI(float dt, float dist, float leash) {
        switch (state) {
            case IDLE    -> { if (dist <= aggroRange) { state = State.ALERTED; alertTimer = 0.7f; } }
            case ALERTED -> { alertTimer -= dt; if (alertTimer <= 0f) state = State.CHASE; }
            case CHASE   -> {
                if (dist > leash)        { state = State.IDLE;   break; }
                if (dist <= attackRange)   state = State.ATTACK;
            }
            case ATTACK  -> {
                if (attackCooldown <= 0f) {
                    framePlayerDamage = damagePerSec * attackInterval;
                    attackCooldown    = attackInterval;
                    // Zombie attack grunt
                    if (attackSoundTimer <= 0f) {
                        com.leaf.game.core.AudioManager.playAt("seal_hit", position, (Vector3f) null, 25f);
                        attackSoundTimer = attackInterval * 0.8f;
                    }
                }
                if (dist > attackRange * 1.6f) state = State.CHASE;
                if (dist > leash)              state = State.IDLE;
            }
            default -> state = State.CHASE;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  A* Pathfinding  (flat XZ grid, integer coordinates)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Encode an XZ pair into a single long for use as a hash key.
     * Safe for coordinates in ±1 million range.
     */
    private static long xzKey(int x, int z) {
        return ((long)(x + 1_000_000)) * 2_000_001L + (z + 1_000_000);
    }

    /**
     * Run A* from (startX, startZ) to (goalX, goalZ) at the enemy's current
     * foot level.  Returns an ordered list of [x,z] waypoints, or null if no
     * path was found within the search budget.
     *
     * Neighbour validity rules:
     *  • The two-block-tall column at the neighbour must be passable (not solid)
     *    for the enemy's height.  For the Golem (hh ≈ 1.8) we check three blocks.
     *  • Unless this enemy is a Golem, the ground one block below the neighbour
     *    must be solid (hole avoidance — won't walk into pits).
     *  • A one-block-high step up is allowed (normal jump).
     *  • The Golem allows a two-block-high step up.
     */
    private List<int[]> runAStar(World world, int startX, int startZ,
                                 int goalX,  int goalZ,  int footY) {

        boolean isGolem = (type == Type.GOLEM || type == Type.GUARDIAN);
        int bodyBlocks  = (halfHeight >= 1.5f) ? 3 : 2; // blocks of vertical clearance needed

        // A* open set ordered by f = g + h
        PriorityQueue<ANode> open = new PriorityQueue<>(Comparator.comparingInt(n -> n.f));
        Map<Long, ANode> visited  = new HashMap<>();

        ANode start = new ANode(startX, startZ, 0, heuristic(startX, startZ, goalX, goalZ), null);
        open.add(start);
        visited.put(xzKey(startX, startZ), start);

        int iters = 0;
        while (!open.isEmpty() && iters++ < PATH_MAX_NODES * 8) {
            ANode cur = open.poll();

            // Goal reached
            if (cur.x == goalX && cur.z == goalZ) {
                return reconstructPath(cur);
            }

            // Expand 4-directional neighbours (diagonal allowed but costs √2)
            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
            for (int[] d : dirs) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                long key = xzKey(nx, nz);

                if (visited.containsKey(key)) continue;
                if (Math.abs(nx - startX) > PATH_MAX_RADIUS ||
                        Math.abs(nz - startZ) > PATH_MAX_RADIUS) continue;

                // Determine the actual foot-Y at this neighbour by checking a
                // one/two block step-up from the current node's Y.
                int nFootY = findStandableY(world, nx, nz, footY, isGolem ? 2 : 1);
                if (nFootY < 0) continue; // can't stand here

                // Hole avoidance: non-Golem enemies refuse to step onto a column
                // where there is no solid ground beneath (i.e., a cliff edge).
                if (!isGolem && !hasSolidGround(world, nx, nFootY, nz)) continue;

                // Passability: body clearance above nFootY
                boolean passable = true;
                for (int by = nFootY + 1; by <= nFootY + bodyBlocks; by++) {
                    if (world.getBlock(nx, by, nz).isSolid()) { passable = false; break; }
                }
                if (!passable) continue;

                int moveCost = (d[0] != 0 && d[1] != 0) ? 14 : 10; // diagonal ≈ √2 × 10
                int g = cur.g + moveCost;
                int h = heuristic(nx, nz, goalX, goalZ);
                ANode next = new ANode(nx, nz, g, g + h, cur);
                open.add(next);
                visited.put(key, next);
            }
        }
        return null; // no path found
    }

    /** Return the Y the enemy's feet should be at to stand on column (nx, nz),
     *  given the current footY, allowing at most maxStepUp steps upward. */
    private int findStandableY(World world, int nx, int nz, int footY, int maxStepUp) {
        // Check from footY+maxStepUp downward
        for (int dy = maxStepUp; dy >= -1; dy--) {
            int ty = footY + dy;
            if (ty < 0) continue;
            boolean groundSolid = world.getBlock(nx, ty, nz).isSolid();
            boolean clearFoot   = !world.getBlock(nx, ty + 1, nz).isSolid();
            boolean clearHead   = !world.getBlock(nx, ty + 2, nz).isSolid();
            if (groundSolid && clearFoot && clearHead) return ty + 1;
        }
        // Also allow stepping down up to 2 blocks (walking down a slope)
        for (int dy = -2; dy >= -4; dy--) {
            int ty = footY + dy;
            if (ty < 0) continue;
            boolean groundSolid = world.getBlock(nx, ty, nz).isSolid();
            boolean clearFoot   = !world.getBlock(nx, ty + 1, nz).isSolid();
            boolean clearHead   = !world.getBlock(nx, ty + 2, nz).isSolid();
            if (groundSolid && clearFoot && clearHead) return ty + 1;
        }
        return -1;
    }

    /** True if there is a solid block directly under this column (no pit below). */
    private boolean hasSolidGround(World world, int nx, int footY, int nz) {
        // Require at least one solid block in the two cells below foot level
        return world.getBlock(nx, footY - 1, nz).isSolid()
                || world.getBlock(nx, footY - 2, nz).isSolid();
    }

    private static int heuristic(int ax, int az, int bx, int bz) {
        // Octile heuristic (good for 8-directional movement)
        int dx = Math.abs(ax - bx), dz = Math.abs(az - bz);
        return 10 * Math.max(dx, dz) + (14 - 10) * Math.min(dx, dz);
    }

    private static List<int[]> reconstructPath(ANode goal) {
        List<int[]> path = new ArrayList<>();
        for (ANode n = goal; n != null; n = n.parent) path.add(new int[]{n.x, n.z});
        Collections.reverse(path);
        if (path.size() > PATH_MAX_NODES) path = path.subList(0, PATH_MAX_NODES);
        return path;
    }

    private void invalidatePath() {
        pathWaypoints.clear();
        pathAge     = Float.MAX_VALUE;
        pathBlocked = false;
    }

    /** Lightweight A* node. */
    private static final class ANode {
        final int x, z, g, f;
        final ANode parent;
        ANode(int x, int z, int g, int f, ANode parent) {
            this.x = x; this.z = z; this.g = g; this.f = f; this.parent = parent;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Horizontal movement
    // ═════════════════════════════════════════════════════════════════════════

    private void tickMovement(float dt, World world, Vector3f target,
                              float distToTarget, List<Enemy> allEnemies) {
        if (mudTrapTimer > 0f) return;

        boolean guardianPatrol = (type == Type.GUARDIAN && state == State.IDLE && patrolWalking);
        boolean shouldMove;
        if (type == Type.GUARDIAN) {
            shouldMove = (state == State.CHASE) || guardianPatrol;
        } else {
            shouldMove = switch (state) {
                case CHASE, ATTACK, SLAMMING, RETREATING -> true;
                default -> false;
            };
        }
        if (!shouldMove) return;

        // ── Determine desired direction ────────────────────────────────────────
        float dx, dz;
        if (guardianPatrol) {
            dx = (float) Math.sin(facingYaw);
            dz = (float) Math.cos(facingYaw);
        } else if (state == State.RETREATING) {
            dx = position.x - target.x;
            dz = position.z - target.z;
        } else {
            // Chase / Attack — use A* waypoint if available; fall back to direct
            int footY  = (int) Math.floor(position.y);
            int goalX  = (int) Math.floor(target.x);
            int goalZ  = (int) Math.floor(target.z);
            int startX = (int) Math.floor(position.x);
            int startZ = (int) Math.floor(position.z);

            // Decide whether to replan
            boolean targetMoved = (Math.abs(goalX - pathTargetX) + Math.abs(goalZ - pathTargetZ)) > (int) PATH_REPLAN_DIST;
            boolean stale       = pathAge > PATH_CACHE_SECS;
            boolean emptyPath   = pathWaypoints.isEmpty();

            if ((emptyPath || targetMoved || stale) && !pathBlocked) {
                pathTargetX = goalX;
                pathTargetZ = goalZ;
                pathAge     = 0f;
                List<int[]> newPath = runAStar(world, startX, startZ, goalX, goalZ, footY);
                pathWaypoints.clear();
                if (newPath != null && newPath.size() > 1) {
                    // Skip the first node (it's our own position)
                    for (int i = 1; i < newPath.size(); i++) pathWaypoints.add(newPath.get(i));
                    pathBlocked = false;
                } else {
                    pathBlocked = (newPath == null);
                }
            }

            // Pop waypoints we've passed
            while (!pathWaypoints.isEmpty()) {
                int[] wp = pathWaypoints.peek();
                float wpDx = wp[0] + 0.5f - position.x;
                float wpDz = wp[1] + 0.5f - position.z;
                if (wpDx * wpDx + wpDz * wpDz < 0.8f * 0.8f) {
                    pathWaypoints.poll(); // reached this waypoint
                } else {
                    break;
                }
            }

            if (!pathWaypoints.isEmpty()) {
                int[] wp = pathWaypoints.peek();
                dx = wp[0] + 0.5f - position.x;
                dz = wp[1] + 0.5f - position.z;
            } else {
                // No path or path exhausted — steer directly
                dx = target.x - position.x;
                dz = target.z - position.z;
            }
        }

        float hd = (float) Math.sqrt(dx * dx + dz * dz);
        if (hd < 0.1f) return;

        float ndx = dx / hd;
        float ndz = dz / hd;

        int underX = (int) Math.floor(position.x);
        int underY = (int) Math.floor(position.y - 0.1f);
        int underZ = (int) Math.floor(position.z);
        boolean onMud = world.getBlock(underX, underY, underZ) == Block.MUD;

        float moveSpeed = guardianPatrol         ? GameConfig.guardianPatrolSpeed
                : (state == State.RETREATING) ? GameConfig.throwerRetreatSpeed
                  : speed;
        float step = moveSpeed * dt * (onMud ? 0.10f : 1.0f);

        // ── Footstep sound ─────────────────────────────────────────────────────
        if (onGround && stepSoundTimer <= 0f) {
            float stepInterval = switch (type) {
                case GOLEM    -> 0.55f;   // heavy thud
                case GUARDIAN -> 0.45f;
                default       -> 0.35f;
            };
            String stepSound = switch (type) {
                case GOLEM, GUARDIAN -> "fall_smash"; // deep boom
                default              -> "seal_hit";   // lighter thump
            };
            com.leaf.game.core.AudioManager.playAt(stepSound, position, (Vector3f) null,
                    type == Type.GOLEM ? 40f : 20f);
            stepSoundTimer = stepInterval;
        }

        float nx = position.x + ndx * step;
        float nz = position.z + ndz * step;
        int   footY = (int) Math.floor(position.y + 0.1f);

        boolean blockedX = isSolidColumn(world, nx, footY, position.z);
        boolean blockedZ = isSolidColumn(world, position.x, footY, nz);

        // Wall sliding
        if (blockedX && !blockedZ) {
            position.z += Math.signum(ndz) * step * 1.5f;
        } else if (blockedZ && !blockedX) {
            position.x += Math.signum(ndx) * step * 1.5f;
        }

        if (!blockedX) position.x = nx;
        if (!blockedZ) position.z = nz;

        // ── Wall-escape push ───────────────────────────────────────────────────
        if (isSolidColumn(world, position.x, footY, position.z)) {
            float escape = collisionRadius + 0.2f;
            float[][] dirs8 = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}};
            for (float[] d : dirs8) {
                float len = (float) Math.sqrt(d[0]*d[0]+d[1]*d[1]);
                float ex = position.x + d[0]/len * escape;
                float ez = position.z + d[1]/len * escape;
                if (!isSolidColumn(world, ex, footY, ez)) {
                    position.x = ex; position.z = ez; break;
                }
            }
        }

        if ((blockedX || blockedZ) && onGround) {
            if (guardianPatrol) patrolTimer = 0f;

            int blockAheadX = (int) Math.floor(position.x + ndx * 0.9f);
            int blockAheadZ = (int) Math.floor(position.z + ndz * 0.9f);

            // ── GOLEM / GUARDIAN: can jump 2 blocks ───────────────────────────
            boolean isGolem = (type == Type.GOLEM || type == Type.GUARDIAN);
            int jumpClearBlocks = isGolem ? 2 : 1; // how many clear blocks we need above foot

            boolean canJump = true;
            for (int by = footY + 1; by <= footY + jumpClearBlocks + 1; by++) {
                if (world.getBlock(blockAheadX, by, blockAheadZ).isSolid()) { canJump = false; break; }
            }

            if (canJump) {
                // Normal jump (1 block high) or Golem high-jump (2 blocks)
                velocityY = isGolem ? 9.5f : 7.5f;
                // Invalidate path so the next tick uses fresh waypoints
                invalidatePath();
            } else {
                stuckTimer += dt;

                if (stuckTimer > 0.6f && isGolem) {
                    // ── GOLEM SMART BLOCK BREAKING ────────────────────────────
                    // Find the lowest solid block in the 3-tall column ahead and
                    // break it first so we carve the minimum necessary opening.
                    int breakY = -1;
                    for (int by = footY; by <= footY + 2; by++) {
                        if (world.getBlock(blockAheadX, by, blockAheadZ).isSolid()) {
                            breakY = by; break;
                        }
                    }
                    // If still stuck (hole? pit wall?) try above the foot level
                    if (breakY < 0) breakY = footY;

                    pendingBlockBreak = true;
                    breakX = blockAheadX;
                    breakY = breakY;   // local to this block (intentional re-use)
                    breakZ = blockAheadZ;
                    stuckTimer = 0f;
                    invalidatePath(); // path is no longer valid after block change

                    // Block-break sound
                    if (breakSoundPlayed <= 0f) {
                        com.leaf.game.core.AudioManager.playAt("fall_smash", position, (Vector3f) null, 55f);
                        breakSoundPlayed = 0.8f;
                    }

                    if (type == Type.GUARDIAN) {
                        state = State.ATTACK; beginSwing();
                    } else {
                        state = State.SLAMMING; slamWindUp = 0.5f;
                    }
                } else if (stuckTimer > 0.6f) {
                    // Smaller enemies strafe around the wall
                    strafeTimer += dt;
                    if (strafeTimer >= STRAFE_FLIP_SECS) { strafeSign = -strafeSign; strafeTimer = 0f; }
                    float sx = position.x + (-ndz * strafeSign) * step;
                    float sz = position.z + ( ndx * strafeSign) * step;
                    if (!isSolidColumn(world, sx, footY, sz)) { position.x = sx; position.z = sz; }
                    // Also invalidate path so it plans around the obstacle
                    if (stuckTimer > 1.2f) { invalidatePath(); stuckTimer = 0f; }
                } else {
                    strafeTimer += dt;
                    if (strafeTimer >= STRAFE_FLIP_SECS) { strafeSign = -strafeSign; strafeTimer = 0f; }
                    float sx = position.x + (-ndz * strafeSign) * step;
                    float sz = position.z + ( ndx * strafeSign) * step;
                    if (!isSolidColumn(world, sx, footY, sz)) { position.x = sx; position.z = sz; }
                }
            }
        } else {
            stuckTimer = 0f;
        }

        // ── Separation ────────────────────────────────────────────────────────
        float sepRadius = collisionRadius * 2.2f;
        for (Enemy other : allEnemies) {
            if (other == this || !other.alive) continue;
            float sx = position.x - other.position.x;
            float sz = position.z - other.position.z;
            float dist = (float) Math.sqrt(sx * sx + sz * sz);
            if (dist < sepRadius && dist > 0.001f) {
                float push = (sepRadius - dist) / sepRadius * 3.5f;
                position.x += (sx / dist) * push * dt;
                position.z += (sz / dist) * push * dt;
            }
        }

        // ── Player personal-space buffer ──────────────────────────────────────
        float minPlayerDist = collisionRadius + 0.3f;
        float pdx = position.x - target.x;
        float pdz = position.z - target.z;
        float pd  = (float) Math.sqrt(pdx * pdx + pdz * pdz);
        if (pd < minPlayerDist && pd > 0.001f) {
            float push = minPlayerDist - pd;
            position.x += (pdx / pd) * push;
            position.z += (pdz / pd) * push;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Thrown flight
    // ═════════════════════════════════════════════════════════════════════════

    private static final float THROW_GRAVITY = 32f;

    private void tickThrownFlight(float dt, World world) {
        thrownVelY -= THROW_GRAVITY * dt;

        float newX = position.x + thrownVelX * dt;
        float newY = position.y + thrownVelY * dt;
        float newZ = position.z + thrownVelZ * dt;

        newY = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, newY));

        int bx = (int) Math.floor(newX);
        int by = (int) Math.floor(newY);
        int bz = (int) Math.floor(newZ);

        boolean destSolid  = world.getBlock(bx, by, bz).isSolid();
        boolean axisHitX   = world.getBlock(bx, (int) Math.floor(position.y), (int) Math.floor(position.z)).isSolid();
        boolean axisHitY   = world.getBlock((int) Math.floor(position.x), by,  (int) Math.floor(position.z)).isSolid();
        boolean axisHitZ   = world.getBlock((int) Math.floor(position.x), (int) Math.floor(position.y),  bz).isSolid();
        boolean atFloor    = newY <= 1.1f;

        if (destSolid || axisHitX || axisHitY || axisHitZ || atFloor) {
            int impX = bx;
            int impY = atFloor ? 0 : by;
            int impZ = bz;
            if (!destSolid && !atFloor) {
                impX = axisHitX ? bx : (int) Math.floor(position.x);
                impY = axisHitY ? by : (int) Math.floor(position.y);
                impZ = axisHitZ ? bz : (int) Math.floor(position.z);
            }

            boolean slamIsGround = (thrownVelY < 0f)
                    && (Math.abs(thrownVelY) >= Math.abs(thrownVelX))
                    && (Math.abs(thrownVelY) >= Math.abs(thrownVelZ));

            grabImpactX        = impX;
            grabImpactY        = impY;
            grabImpactZ        = impZ;
            grabImpactIsGround = slamIsGround || atFloor;
            pendingGrabImpact  = true;

            float impactDamage = grabImpactIsGround ? GameConfig.grabGroundDamage : GameConfig.grabWallDamage;
            applyDamage(impactDamage);

            isThrown   = false;
            thrownVelX = 0f;
            thrownVelY = 0f;
            thrownVelZ = 0f;
            invalidatePath();

            position.x = newX;
            position.y = Math.max(1f, (float) impY + 1.01f);
            position.z = newZ;
        } else {
            position.x = newX;
            position.y = newY;
            position.z = newZ;
        }
    }

    // ─── Solid-column test ────────────────────────────────────────────────────

    private boolean isSolidColumn(World world, float x, int footY, float z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        return world.getBlock(bx, footY,     bz).isSolid()
                || world.getBlock(bx, footY + 1, bz).isSolid();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Gravity + vertical collision
    // ═════════════════════════════════════════════════════════════════════════

    private void tickGravity(float dt, World world) {
        onGround   = false;
        velocityY -= GRAVITY * dt;
        velocityY  = Math.max(-MAX_FALL_SPEED, velocityY);

        float dy = velocityY * dt;
        int   bx = (int) Math.floor(position.x);
        int   bz = (int) Math.floor(position.z);

        if (dy < 0f) {
            int feetY = (int) Math.floor(position.y + dy);
            if (feetY >= 0 && feetY < Chunk.HEIGHT && world.getBlock(bx, feetY, bz).isSolid()) {
                // Landing sound (only on non-trivial falls)
                if (velocityY < -6f) {
                    float vol = Math.min(50f, Math.abs(velocityY) * 1.5f);
                    com.leaf.game.core.AudioManager.playAt("fall_smash", position, (Vector3f) null, vol);
                }
                position.y = feetY + 1f;
                velocityY  = 0f;
                onGround   = true;
            } else {
                position.y += dy;
            }
        } else if (dy > 0f) {
            int headY = (int) Math.floor(position.y + 2f * halfHeight + dy);
            if (headY >= 0 && headY < Chunk.HEIGHT && world.getBlock(bx, headY, bz).isSolid()) {
                velocityY = 0f;
            } else {
                position.y += dy;
            }
        } else {
            int feetY = (int) Math.floor(position.y - 0.05f);
            if (feetY >= 0 && world.getBlock(bx, feetY, bz).isSolid()) onGround = true;
        }
        position.y = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, position.y));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers / accessors
    // ═════════════════════════════════════════════════════════════════════════

    public Vector3f getCentre() {
        return new Vector3f(position.x, position.y + halfHeight, position.z);
    }

    public boolean isHitByPlayer(Vector3f p) {
        if (p.y < position.y || p.y > position.y + 2f * halfHeight) return false;
        float dx = p.x - position.x;
        float dz = p.z - position.z;
        return (dx * dx + dz * dz) <= collisionRadius * collisionRadius;
    }

    public float[] renderScaleVec() {
        return switch (type) {
            case GOLEM    -> new float[]{ 1.40f, 1.60f, 1.40f };
            case THROWER  -> new float[]{ 0.48f, 0.92f, 0.48f };
            case ZOMBIE   -> new float[]{ 0.75f, 0.88f, 0.75f };
            case SLIME    -> new float[]{ 0.85f, 0.85f, 0.85f };
            case GUARDIAN -> new float[]{ 1.0f,  1.0f,  1.0f  };
        };
    }

    public float renderScale() {
        return renderScaleVec()[1];
    }
}