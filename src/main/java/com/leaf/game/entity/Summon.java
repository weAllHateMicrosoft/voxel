package com.leaf.game.entity;

import org.joml.Vector3f;

/**
 * Summon — a lightweight "troop" a player spawns in multiplayer to attack the
 * OTHER player. Each client simulates only its OWN summons (charging the remote
 * player) and streams their positions to the peer, which renders them as enemy-
 * coloured troops. Deliberately simple: a homing flyer with a contact attack, so
 * there's no world-collision/pathfinding to desync. Damage is relayed over the
 * network when it reaches the opponent.
 */
public class Summon {

    public final Vector3f pos = new Vector3f();
    private final Vector3f vel = new Vector3f();

    public float health = 20f;
    public boolean alive = true;
    public float life;                 // seconds left before it expires on its own
    private float hitCd = 0f;          // attack cooldown

    private static final float SPEED      = 9.0f;
    private static final float HIT_RANGE  = 1.6f;
    private static final float HIT_DAMAGE = 6.0f;
    private static final float HIT_PERIOD = 0.8f;

    public Summon(float x, float y, float z, float life) {
        pos.set(x, y, z);
        this.life = life;
    }

    /**
     * Steer toward {@code target} (the opponent) and bite them on contact.
     * @return damage to relay to the opponent this frame (0 if none).
     */
    public float update(float dt, Vector3f target) {
        life  -= dt;
        hitCd -= dt;
        if (life <= 0f) { alive = false; return 0f; }

        Vector3f to = new Vector3f(target.x, target.y + 1.0f, target.z).sub(pos);
        float dist = to.length();
        if (dist > 0.0001f) {
            to.div(dist);
            // Smoothly home toward the target.
            vel.lerp(new Vector3f(to).mul(SPEED), Math.min(1f, 4f * dt));
            pos.fma(dt, vel);
        }

        if (dist <= HIT_RANGE && hitCd <= 0f) {
            hitCd = HIT_PERIOD;
            return HIT_DAMAGE;
        }
        return 0f;
    }
}
