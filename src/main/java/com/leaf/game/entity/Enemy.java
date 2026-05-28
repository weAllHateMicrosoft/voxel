package com.leaf.game.entity;

import com.leaf.game.core.GameConfig;
import com.leaf.game.world.World;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.Block;
import org.joml.Vector3f;

import java.util.List;

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
 */
public class Enemy {

    // ═════════════════════════════════════════════════════════════════════════
    //  Enums
    // ═════════════════════════════════════════════════════════════════════════

    public enum Type  { GOLEM, THROWER, ZOMBIE }
    public enum State { IDLE, ALERTED, CHASE, ATTACK, RETREATING, SLAMMING }

    // ═════════════════════════════════════════════════════════════════════════
    //  Static constants (default hitbox; per-type values override via instance)
    // ═════════════════════════════════════════════════════════════════════════

    public static final float RADIUS      = 0.5f;
    public static final float HALF_HEIGHT = 1.0f;

    private static final float STRAFE_FLIP_SECS = 0.8f;
    private static final float GRAVITY          = 28f;
    private static final float MAX_FALL_SPEED   = 30f;

    // ═════════════════════════════════════════════════════════════════════════
    //  Identity
    // ═════════════════════════════════════════════════════════════════════════

    private static int nextId = 0;
    public  final  int  id;
    public  final  Type type;

    // ═════════════════════════════════════════════════════════════════════════
    //  Stats (copied from GameConfig at construction)
    // ═════════════════════════════════════════════════════════════════════════

    public  final float maxHealth;
    public        float health;
    private final float speed;
    private final float damagePerSec;
    private final float aggroRange;
    private final float attackRange;
    private final float attackInterval;

    // ═════════════════════════════════════════════════════════════════════════
    //  Per-instance hitbox (varies by type)
    // ═════════════════════════════════════════════════════════════════════════

    /** Horizontal collision radius.  Used by SealController + hit tests. */
    public final float collisionRadius;
    /** Half the total standing height.  Full height = 2 × halfHeight. */
    public final float halfHeight;

    // ═════════════════════════════════════════════════════════════════════════
    //  World state
    // ═════════════════════════════════════════════════════════════════════════

    public  final  Vector3f position;
    public         boolean  alive    = true;
    private        float    velocityY = 0f;
    private        boolean  onGround  = false;

    /** Horizontal knockback/pounce velocity; decays over time. */
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
    private float slamWindUp    = 0f;   // time spent in SLAMMING state
    /** EnemyManager reads this to spawn a boulder projectile. */
    public  boolean wantsToThrow = false;
    private float throwCooldown  = 0f;

    // ── THROWER special ───────────────────────────────────────────────────────
    // (wantsToThrow also used here; throwCooldown shared)


    // ═════════════════════════════════════════════════════════════════════════
    //  Visual / output
    // ═════════════════════════════════════════════════════════════════════════

    public float hitFlashTimer    = 0f;
    public float framePlayerDamage = 0f;
    /** When > 0 the enemy is mud-trapped and cannot move or attack. */
    public float mudTrapTimer     = 0f;

    // ── GRAB STATE (GrabController) ───────────────────────────────────────────
    /** True while the player is holding this enemy (AI/movement frozen). */
    public boolean isGrabbed    = false;
    /** True while this enemy is flying after being thrown. */
    public boolean isThrown     = false;
    /** Velocity applied each frame while isThrown == true (blocks/sec). */
    public float thrownVelX = 0f, thrownVelY = 0f, thrownVelZ = 0f;
    /**
     * Set by the thrown-flight update the frame this enemy hits a solid surface.
     * Window reads this once per frame to spawn a crater + ejecta, then resets it.
     */
    public boolean pendingGrabImpact = false;
    public int     grabImpactX = 0, grabImpactY = 0, grabImpactZ = 0;
    /** true = hit the floor (ground slam), false = hit a wall/ceiling. */
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
                cr = 1.1f; hh = 1.8f;   // wide and tall
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
                cr = 0.5f; hh = 1.0f;  // human-sized
            }
            default -> { // THROWER (fallback)
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

    /** Apply damage and trigger hit-flash. Returns true if the kill shot. */
    public boolean applyDamage(float amount) {
        if (!alive) return false;
        // Golems have smash resistance
        if (type == Type.GOLEM) amount *= GameConfig.golemSmashResist + (1f - GameConfig.golemSmashResist);
        health        = Math.max(0f, health - amount);
        hitFlashTimer = 0.18f;
        if (health <= 0f) { alive = false; return true; }
        return false;
    }

    /**
     * Apply knockback impulse.
     * kx/kz = horizontal launch speed (blocks/s); ky = upward speed (added, not replaced).
     * Golems are heavily resistant to knockback.
     */
    public void applyKnockback(float kx, float ky, float kz) {
        float resist = (type == Type.GOLEM) ? 0.15f : 1.0f;
        knockbackVelX = kx * resist;
        knockbackVelZ = kz * resist;
        if (ky > 0f) velocityY = Math.max(velocityY, ky * resist);
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

        // ── GRABBED — completely frozen ────────────────────────────────────────
        if (isGrabbed) return;

        // ── THROWN — ballistic flight until wall/floor collision ───────────────
        if (isThrown) {
            tickThrownFlight(dt, world);
            return;
        }

        Vector3f playerCentre = new Vector3f(playerPos.x, playerPos.y + 0.9f, playerPos.z);
        float    distToPlayer = new Vector3f(playerCentre).sub(getCentre()).length();

        tickCooldowns(dt);
        tickAI(dt, distToPlayer, playerCentre, playerPos);
        tickMovement(dt, world, playerCentre, distToPlayer, allEnemies);
        tickGravity(dt, world);

        // Horizontal knockback decay
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
    //  Cooldown ticks
    // ═════════════════════════════════════════════════════════════════════════

    private void tickCooldowns(float dt) {
        if (slamCooldown  > 0f) slamCooldown  -= dt;
        if (throwCooldown > 0f) throwCooldown  -= dt;
        if (mudTrapTimer  > 0f) mudTrapTimer   -= dt;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  AI State Machine
    // ═════════════════════════════════════════════════════════════════════════

    private void tickAI(float dt, float distToPlayer,
                        Vector3f playerCentre, Vector3f playerPos) {
        if (mudTrapTimer > 0f) return;

        float leash = aggroRange * 1.8f;

        switch (type) {

            // ── GOLEM — slam + ranged throw ───────────────────────────────────
            case GOLEM -> tickGolemAI(dt, distToPlayer, leash, playerCentre);

            // ── THROWER (skeleton archer) — ranged; retreats from close ───────
            case THROWER -> tickThrowerAI(dt, distToPlayer, leash);

            // ── ZOMBIE — slow shambling melee chaser ──────────────────────────
            case ZOMBIE -> tickZombieAI(dt, distToPlayer, leash);
        }
    }

    // ─── GOLEM ────────────────────────────────────────────────────────────────

    private void tickGolemAI(float dt, float dist, float leash, Vector3f playerCentre) {
        switch (state) {
            case IDLE    -> { if (dist <= aggroRange) { state = State.ALERTED; alertTimer = 1.5f; } }
            case ALERTED -> { alertTimer -= dt; if (alertTimer <= 0f) state = State.CHASE; }

            case CHASE -> {
                if (dist > leash) { state = State.IDLE; break; }
                // Slam check
                if (dist <= GameConfig.golemSlamRange && slamCooldown <= 0f) {
                    state = State.SLAMMING;
                    slamWindUp = 0.6f;
                    break;
                }
                // Long-range throw
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
                    // Shockwave — damage/knockback is processed by EnemyManager.pendingPlayerDamage
                    // We signal it through framePlayerDamage with a massive number capped by Window
                    // Actually: EnemyManager doesn't have the player dist here.
                    // Use framePlayerDamage; Window checks dist.
                    framePlayerDamage = GameConfig.golemSlamDamage; // distance checked by EnemyManager
                    slamCooldown = GameConfig.golemSlamCooldown;
                    state        = State.CHASE;
                }
            }

            case ATTACK -> {
                if (attackCooldown <= 0f) {
                    framePlayerDamage = damagePerSec * attackInterval;
                    attackCooldown    = attackInterval;
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
                // Too close — retreat
                if (dist < GameConfig.throwerPreferredDist * 0.6f) {
                    state = State.RETREATING; break;
                }
                // In throw range and cooldown ready
                if (dist <= GameConfig.throwerAggroRange && throwCooldown <= 0f) {
                    wantsToThrow  = true;
                    throwCooldown = GameConfig.throwerThrowCooldown;
                }
                // Weak melee if cornered
                if (dist <= attackRange) state = State.ATTACK;
            }

            case RETREATING -> {
                // Return to CHASE once preferred distance is re-established
                if (dist >= GameConfig.throwerPreferredDist) state = State.CHASE;
                if (dist > leash) state = State.IDLE;
            }

            case ATTACK -> {
                // Only used if player corners the thrower
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
                }
                if (dist > attackRange * 1.6f) state = State.CHASE;
                if (dist > leash)              state = State.IDLE;
            }
            default -> state = State.CHASE;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Horizontal movement
    // ═════════════════════════════════════════════════════════════════════════

    private void tickMovement(float dt, World world, Vector3f target,
                              float distToTarget, List<Enemy> allEnemies) {
        if (mudTrapTimer > 0f) return;

        boolean shouldMove = switch (state) {
            case CHASE, ATTACK, SLAMMING -> true;
            case RETREATING -> true;   // Thrower moves away
            default         -> false;
        };
        if (!shouldMove) return;

        float dx, dz;
        if (state == State.RETREATING) {
            // Move away from player
            dx = position.x - target.x;
            dz = position.z - target.z;
        } else {
            dx = target.x - position.x;
            dz = target.z - position.z;
        }
        float hd = (float) Math.sqrt(dx * dx + dz * dz);
        if (hd < 0.1f) return;

        float ndx = dx / hd;
        float ndz = dz / hd;

        int underX = (int) Math.floor(position.x);
        int underY = (int) Math.floor(position.y - 0.1f);
        int underZ = (int) Math.floor(position.z);
        boolean onMud  = world.getBlock(underX, underY, underZ) == Block.MUD;
        float   moveSpeed = (state == State.RETREATING)
                ? GameConfig.throwerRetreatSpeed : speed;
        float step = moveSpeed * dt * (onMud ? 0.10f : 1.0f);

        float nx = position.x + ndx * step;
        float nz = position.z + ndz * step;
        int   footY = (int) Math.floor(position.y + 0.1f);

        boolean blockedX = isSolidColumn(world, nx, footY, position.z);
        boolean blockedZ = isSolidColumn(world, position.x, footY, nz);

        if (!blockedX) position.x = nx;
        if (!blockedZ) position.z = nz;

        if ((blockedX || blockedZ) && onGround) {
            int blockAheadX = (int) Math.floor(position.x + ndx * 0.9f);
            int blockAheadZ = (int) Math.floor(position.z + ndz * 0.9f);
            boolean clear1  = !world.getBlock(blockAheadX, footY + 1, blockAheadZ).isSolid();
            boolean clear2  = !world.getBlock(blockAheadX, footY + 2, blockAheadZ).isSolid();
            if (clear1 && clear2) {
                velocityY = 7.5f;
            } else {
                strafeTimer += dt;
                if (strafeTimer >= STRAFE_FLIP_SECS) { strafeSign = -strafeSign; strafeTimer = 0f; }
                float sx = position.x + (-ndz * strafeSign) * step;
                float sz = position.z + ( ndx * strafeSign) * step;
                if (!isSolidColumn(world, sx, footY, sz)) { position.x = sx; position.z = sz; }
            }
        }

        // Separation
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
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Thrown flight (grab slam / wall throw)
    // ═════════════════════════════════════════════════════════════════════════

    private static final float THROW_GRAVITY = 32f;

    private void tickThrownFlight(float dt, World world) {
        // Apply gravity to downward velocity
        thrownVelY -= THROW_GRAVITY * dt;

        float newX = position.x + thrownVelX * dt;
        float newY = position.y + thrownVelY * dt;
        float newZ = position.z + thrownVelZ * dt;

        // Clamp to world bounds
        newY = Math.max(1f, Math.min(Chunk.HEIGHT - 2f, newY));

        int bx = (int) Math.floor(newX);
        int by = (int) Math.floor(newY);
        int bz = (int) Math.floor(newZ);

        // Check destination block AND intermediate axis blocks for clean wall detection
        boolean destSolid  = world.getBlock(bx, by, bz).isSolid();
        boolean axisHitX   = world.getBlock(bx, (int) Math.floor(position.y), (int) Math.floor(position.z)).isSolid();
        boolean axisHitY   = world.getBlock((int) Math.floor(position.x), by,  (int) Math.floor(position.z)).isSolid();
        boolean axisHitZ   = world.getBlock((int) Math.floor(position.x), (int) Math.floor(position.y),  bz).isSolid();

        // Also trigger on floor: enemy fell to ground level
        boolean atFloor = newY <= 1.1f;

        if (destSolid || axisHitX || axisHitY || axisHitZ || atFloor) {
            // Use destination block as the crater centre, or fall back to current position
            int impX = bx;
            int impY = atFloor ? 0 : by;
            int impZ = bz;
            if (!destSolid && !atFloor) {
                impX = axisHitX ? bx : (int) Math.floor(position.x);
                impY = axisHitY ? by : (int) Math.floor(position.y);
                impZ = axisHitZ ? bz : (int) Math.floor(position.z);
            }

            // Ground slam = mostly downward, wall slam = mostly horizontal
            boolean slamIsGround = (thrownVelY < 0f)
                    && (Math.abs(thrownVelY) >= Math.abs(thrownVelX))
                    && (Math.abs(thrownVelY) >= Math.abs(thrownVelZ));

            grabImpactX        = impX;
            grabImpactY        = impY;
            grabImpactZ        = impZ;
            grabImpactIsGround = slamIsGround || atFloor;
            pendingGrabImpact  = true;

            float impactDamage = grabImpactIsGround
                    ? GameConfig.grabGroundDamage
                    : GameConfig.grabWallDamage;
            applyDamage(impactDamage);

            isThrown   = false;
            thrownVelX = 0f;
            thrownVelY = 0f;
            thrownVelZ = 0f;

            // Place enemy just above the impact point
            position.x = newX;
            position.y = Math.max(1f, (float) impY + 1.01f);
            position.z = newZ;
        } else {
            position.x = newX;
            position.y = newY;
            position.z = newZ;
        }
    }

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

    /** Body centre — used for LOS checks, HP bar, targeting, and seal-attach. */
    public Vector3f getCentre() {
        return new Vector3f(position.x, position.y + halfHeight, position.z);
    }

    /** Test whether point p is inside the enemy's capsule-approximated hitbox. */
    public boolean isHitByPlayer(Vector3f p) {
        if (p.y < position.y || p.y > position.y + 2f * halfHeight) return false;
        float dx = p.x - position.x;
        float dz = p.z - position.z;
        return (dx * dx + dz * dz) <= collisionRadius * collisionRadius;
    }

    // ── Visual scale per type (non-uniform for new types) ────────────────────

    /**
     * Returns { scaleX, scaleY, scaleZ } for rendering.
     * Window uses .scale(v[0], v[1], v[2]) instead of a single uniform scale.
     */
    public float[] renderScaleVec() {
        return switch (type) {
            case GOLEM   -> new float[]{ 1.40f, 1.60f, 1.40f }; // wide and tall — tank
            case THROWER -> new float[]{ 0.48f, 0.92f, 0.48f }; // slim and tall — skeleton archer
            case ZOMBIE  -> new float[]{ 0.75f, 0.88f, 0.75f }; // stocky, human-sized
        };
    }

    /** Uniform scale fallback (used when renderScaleVec is not needed). */
    public float renderScale() {
        float[] v = renderScaleVec();
        return v[1]; // use Y (height) as proxy
    }
}
