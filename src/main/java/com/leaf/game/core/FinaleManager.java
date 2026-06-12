package com.leaf.game.core;

import com.leaf.game.entity.Enemy;
import com.leaf.game.entity.TestMovementController;
import com.leaf.game.util.Camera;
import com.leaf.game.world.Block;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * FinaleManager — DESCENT's true ending.
 *
 * <pre>
 *  Voyage complete (all abilities forged, THE WORLD last)
 *        │
 *        ▼
 *  SUMMONS        "THE CRYSTAL CALLS YOU HOME" — portal built at spawn,
 *                 directive + arrow guide the player back.
 *        │ walk into the portal
 *        ▼
 *  PORTAL_WARP    purple vortex transition, white-out, teleport
 *        ▼
 *  ARENA          floating void platform far above the world. Five trials:
 *                   1 THE SWARM            12 enemies, all sides
 *                   2 THE GIANTS           3 colossal enemies
 *                   3 THE CRYSTAL MOCKS YOU  flappy bird, score 15
 *                   4 THE FLOOR BETRAYS YOU  arena crumbles mid-fight
 *                   5 AVATAR OF THE CRYSTAL  giant golem boss + meteors
 *        │ die: DEFEAT_WAKE — wake up lying outside the portal (eyelid blinks,
 *        │      camera tilted, stand up). Portal stays. Retry resumes the phase.
 *        ▼ win
 *  VICTORY_TEAR   the dimension tears itself apart, blocks fly, white tear
 *        ▼
 *  MADE_IN_HEAVEN back in the world: time races (sun/stars streak), lore
 *                 subtitles, golden wind, terrain heaves, seasons flicker
 *        ▼
 *  ASCENSION      controls lock, the player rises wrapped in golden light
 *        ▼
 *  WHITEOUT       pure white, final words
 *        ▼
 *  THE_END        black. DESCENT — THE END. [ENTER] remain as a god.
 * </pre>
 */
public class FinaleManager {

    public enum State {
        IDLE,            // waiting for the voyage to complete
        SUMMONS,         // go back to spawn; portal is waiting
        PORTAL_WARP,     // transition animation into the arena
        ARENA_INTRO,     // "THE FINAL TRIAL" title card
        PHASE_BANNER,    // big banner announcing the next trial
        FIGHT,           // active combat phase
        FLAPPY_TRIAL,    // trial 3: become the bird
        DEFEAT_FADE,     // death in arena: fade to black
        DEFEAT_WAKE,     // wake up outside the portal (eyelids, tilt, stand)
        VICTORY_TEAR,    // the arena dimension tears apart
        MADE_IN_HEAVEN,  // time races; the world transforms
        ASCENSION,       // the player rises into the sky
        WHITEOUT,        // pure white; final lore
        THE_END,         // black; the end card
        FREEPLAY         // chose to remain in the world as a god
    }

    // ── Arena geometry ─────────────────────────────────────────────────────────
    static final float ARENA_X = 777f, ARENA_Z = -2223f;
    static final int   ARENA_Y = 380;            // platform surface sits at ARENA_Y
    static final int   ARENA_R = 34;             // platform radius in blocks
    private static final float SPAWN_X = 777f, SPAWN_Z = 777f;

    private final Window win;
    private final Random rng = new Random();

    public State state = State.IDLE;
    /** Seconds in the current state. */
    public float t = 0f;
    /** Current trial 1..5. */
    public int phase = 1;
    /** Live arena ring radius — shrinks during trial 4/5. */
    private int liveRadius = ARENA_R;
    /** Wall-clock delay before the summons fires after voyage completion. */
    private float summonsDelay = -1f;

    private float portalY = 250f;                 // portal base Y at spawn (surface)
    private boolean portalBuilt = false;
    private boolean arenaBuilt  = false;
    private float arenaBuildDelay = 0f;           // wait for chunks to stream before building

    // Crumble bookkeeping for trial 4/5
    private float crumbleTimer = 0f;
    /** One-shot: the mid-warp teleport has happened for this warp. */
    private boolean warpTeleported = false;

    // Defeat-wake bookkeeping
    private int   wakeRetryPhase = 1;             // phase to resume after waking up

    // Made-in-heaven bookkeeping
    private float heaveTimer = 0f;
    private float windTimer  = 0f;

    // Subtitles (state-local; HUD reads these)
    public String subtitle = null;
    public float  subtitleAge = 0f;

    /** Stats snapshot for the end card. */
    public int   endKills = 0;
    public float endTime  = 0f;

    private boolean lastEnter = false;

    public FinaleManager(Window win) { this.win = win; }

    // ── Queries used by Window each frame ─────────────────────────────────────
    /** True while the finale freezes normal player input/update. */
    public boolean locksPlayer() {
        return state == State.PORTAL_WARP || state == State.DEFEAT_FADE
            || state == State.DEFEAT_WAKE || state == State.VICTORY_TEAR
            || state == State.ASCENSION   || state == State.WHITEOUT
            || state == State.THE_END     || state == State.ARENA_INTRO
            || state == State.PHASE_BANNER;
    }

    /** True while the finale wants the Made-in-Heaven time acceleration. */
    public boolean wantsTimeRush() {
        return state == State.MADE_IN_HEAVEN || state == State.ASCENSION;
    }

    /** True when finale handles deaths itself (skip normal death flow). */
    public boolean handlesDeath() {
        return state == State.FIGHT || state == State.PHASE_BANNER
            || state == State.ARENA_INTRO || state == State.SUMMONS;
    }

    /** True during the flappy bird trial (deaths auto-restart the run). */
    public boolean isFlappyTrial() { return state == State.FLAPPY_TRIAL; }

    public boolean active() { return state != State.IDLE && state != State.FREEPLAY; }

    /** Portal centre, for HUD arrow + distance. */
    public float portalX() { return SPAWN_X; }
    public float portalZ() { return SPAWN_Z; }
    public float portalCY() { return portalY + 3f; }

    /** Live enemy count, for the HUD phase tracker. */
    public int enemiesLeft() {
        int n = 0;
        for (Enemy e : win.enemyManager.getEnemies()) if (e.alive) n++;
        return n;
    }

    // ── Main update, called every frame from the run loop ─────────────────────
    public void update(Camera camera, float dt) {
        t += dt;
        if (subtitle != null) subtitleAge += dt;

        switch (state) {
            case IDLE            -> tickIdle(dt);
            case SUMMONS         -> tickSummons(dt);
            case PORTAL_WARP     -> tickPortalWarp(camera, dt);
            case ARENA_INTRO     -> tickArenaIntro(dt);
            case PHASE_BANNER    -> tickPhaseBanner(dt);
            case FIGHT           -> tickFight(dt);
            case FLAPPY_TRIAL    -> tickFlappy(dt);
            case DEFEAT_FADE     -> tickDefeatFade(camera, dt);
            case DEFEAT_WAKE     -> tickDefeatWake(camera, dt);
            case VICTORY_TEAR    -> tickVictoryTear(camera, dt);
            case MADE_IN_HEAVEN  -> tickMadeInHeaven(camera, dt);
            case ASCENSION       -> tickAscension(camera, dt);
            case WHITEOUT        -> tickWhiteout(dt);
            case THE_END         -> tickTheEnd(dt);
            case FREEPLAY        -> win.player.health = win.player.maxHealth;   // a god does not bleed
        }
    }

    private void enter(State s) { state = s; t = 0f; }

    private void say(String line) { subtitle = line; subtitleAge = 0f; }

    // ═══════════════════════════════════════════════════════════════════════
    //  IDLE → SUMMONS
    // ═══════════════════════════════════════════════════════════════════════
    private void tickIdle(float dt) {
        if (win.voyage != null && win.voyage.complete) {
            if (summonsDelay < 0f) summonsDelay = 8f;   // let the forge message play out
            summonsDelay -= dt;
            if (summonsDelay <= 0f) beginSummons();
        }
    }

    private void beginSummons() {
        enter(State.SUMMONS);
        win.enemyManager.wavesEnabled = false;
        win.enemyManager.freeExploreMode = false;
        win.enemyManager.beginNextWave();   // clear any stale wave-cleared flag
        for (Enemy e : win.enemyManager.getEnemies()) e.alive = false;  // the world goes quiet
        buildPortal();
        AudioManager.play("seal_collect", 1.0f);
        ScreenEffectManager.INSTANCE.flash(0.7f, 0.4f, 1.0f, 0.45f, 0.8f);
        win.requestShake(0.18f, 0.9f);
    }

    private void tickSummons(float dt) {
        // Swirling portal vortex + sky beam so the destination is unmissable.
        portalAmbientFx(dt);

        // Entry: stepping into the ring triggers the warp.
        Vector3f p = win.player.position;
        float dx = p.x - SPAWN_X, dz = p.z - SPAWN_Z;
        float dy = p.y - portalY;
        if (Math.abs(dx) < 2.2f && Math.abs(dz) < 1.6f && dy > -1f && dy < 6f) {
            warpTeleported = false;
            enter(State.PORTAL_WARP);
            AudioManager.play("snipe_loadgun", 1.0f);
            win.requestShake(0.25f, 1.5f);
        }
    }

    /** Purple swirl + light pillar at the portal, every frame while it waits. */
    private void portalAmbientFx(float dt) {
        // Orbiting sparks around the ring
        double a = t * 2.4;
        float ox = (float) Math.cos(a) * 2.6f, oy = 3.0f + (float) Math.sin(t * 1.7) * 1.6f;
        win.fxBurst(SPAWN_X + ox, portalY + oy, SPAWN_Z, 0.05f, 0.5f, 0.30f, 1.8f, 0.6f, 2.6f);
        win.fxBurst(SPAWN_X - ox, portalY + (6f - oy), SPAWN_Z, 0.05f, 0.5f, 0.30f, 2.2f, 0.5f, 2.8f);
        // Sky pillar — a column of rising rings visible from far away
        if (((int) (t * 4)) % 2 == 0) {
            float ry = portalY + (t * 30f) % 90f;
            win.fxRing(SPAWN_X, ry, SPAWN_Z, 1.2f, 2.8f, 0.5f, 1.6f, 0.7f, 2.8f);
        }
    }

    /** Construct the standing portal ring at spawn out of crystal blocks. */
    private void buildPortal() {
        if (portalBuilt) return;
        int sx = (int) SPAWN_X, sz = (int) SPAWN_Z;
        // Find the surface
        int sy = 300;
        while (sy > 100 && !win.world.getBlock(sx, sy, sz).isSolid()) sy--;
        portalY = sy + 1;

        // A 7-wide × 8-tall standing ring (XY plane), hollow centre.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dyy = 0; dyy <= 7; dyy++) {
                boolean edge = Math.abs(dx) == 3 || dyy == 0 || dyy == 7;
                boolean corner = Math.abs(dx) == 3 && (dyy == 0 || dyy == 7);
                if (!edge) continue;
                Block b = corner ? Block.CRYSTAL_AMETHYST : Block.MEGALITH;
                if ((dx + dyy) % 3 == 0) b = Block.CRYSTAL_AMETHYST;
                win.world.setBlock(sx + dx, (int) portalY + dyy, sz, b);
            }
        }
        portalBuilt = true;
    }

    /** Remove the portal ring (used after the finale completes). */
    private void removePortal() {
        if (!portalBuilt) return;
        int sx = (int) SPAWN_X, sz = (int) SPAWN_Z;
        for (int dx = -3; dx <= 3; dx++)
            for (int dyy = 0; dyy <= 7; dyy++)
                win.world.setBlock(sx + dx, (int) portalY + dyy, sz, Block.AIR);
        portalBuilt = false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PORTAL WARP — 3s transition into the arena
    // ═══════════════════════════════════════════════════════════════════════
    private void tickPortalWarp(Camera camera, float dt) {
        // Particle rush converging on the camera
        for (int i = 0; i < 3; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            float r = 4f + rng.nextFloat() * 6f;
            win.fxBolt(camera.position.x + (float) Math.cos(ang) * r,
                       camera.position.y + (rng.nextFloat() - 0.5f) * 6f,
                       camera.position.z + (float) Math.sin(ang) * r,
                       -(float) Math.cos(ang), 0f, -(float) Math.sin(ang),
                       3.5f, 0.10f, 0.35f, 1.8f, 0.6f, 2.8f);
        }
        if (t >= 1.6f && !warpTeleported) {
            // Teleport mid-white-out so the player never sees the seam.
            warpTeleported = true;
            win.player.position.set(ARENA_X, ARENA_Y + 2f, ARENA_Z);
            win.player.setVelocityY(0f);
            win.player.highestY = ARENA_Y + 2f;
            win.world.clearAllChunks();
            arenaBuilt = false;
            arenaBuildDelay = 0.9f;     // give chunk gen a beat before stamping the arena
        }
        if (warpTeleported) {
            camera.position.set(win.player.position.x, win.player.position.y + 1.62f, win.player.position.z);
            maybeRebuildArena(dt);
        }
        if (t >= 3.0f && arenaBuilt) {
            enter(State.ARENA_INTRO);
            AudioManager.play("seal_collect", 0.9f);
        }
    }

    /** Stamp the floating arena into the sky: disc + edge ring + 4 crystal pillars. */
    private void buildArena() {
        int cx = (int) ARENA_X, cz = (int) ARENA_Z;
        for (int dx = -ARENA_R; dx <= ARENA_R; dx++) {
            for (int dz = -ARENA_R; dz <= ARENA_R; dz++) {
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d > ARENA_R) continue;
                Block b;
                if (d > ARENA_R - 1.5f)      b = Block.CRYSTAL_BASE;       // glowing rim
                else if (rng.nextInt(38) == 0) b = Block.CRYSTAL_AMETHYST; // crystal veins
                else                          b = Block.MEGALITH;
                win.world.setBlock(cx + dx, ARENA_Y - 1, cz + dz, b);
                // A slim underside so it reads as a floating island from below
                if (d > ARENA_R - 6 || rng.nextInt(5) == 0)
                    win.world.setBlock(cx + dx, ARENA_Y - 2, cz + dz, Block.MEGALITH);
            }
        }
        // 4 crystal pillars at the compass points
        int pr = 24;
        int[][] dirs = {{pr, 0}, {-pr, 0}, {0, pr}, {0, -pr}};
        for (int[] d : dirs) buildPillar(cx + d[0], cz + d[1], 11);
        liveRadius = ARENA_R;
        arenaBuilt = true;
    }

    private void buildPillar(int px, int pz, int height) {
        for (int dyy = 0; dyy < height; dyy++) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    boolean tip = dyy >= height - 2;
                    win.world.setBlock(px + dx, ARENA_Y + dyy, pz + dz,
                            tip ? Block.CRYSTAL_AMETHYST : Block.CRYSTAL_BASE);
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ARENA INTRO + PHASE BANNERS
    // ═══════════════════════════════════════════════════════════════════════
    private void tickArenaIntro(float dt) {
        pinPlayerAtArenaCentre();
        if (t >= 4.5f) {
            phase = wakeRetryPhase;
            enter(State.PHASE_BANNER);
            AudioManager.play("cystal_click", 1.0f);
            win.requestShake(0.22f, 0.8f);
        }
    }

    /** Hold the player at the arena centre while titles play (update is gated). */
    private void pinPlayerAtArenaCentre() {
        win.player.position.set(ARENA_X, ARENA_Y + 1.05f, ARENA_Z);
        win.player.setVelocityY(0f);
        win.player.highestY = ARENA_Y + 1.05f;
        win.camera.position.set(ARENA_X, ARENA_Y + 1.05f + 1.62f, ARENA_Z);
    }

    public String phaseName() {
        return switch (phase) {
            case 1  -> "THE SWARM";
            case 2  -> "THE GIANTS";
            case 3  -> "THE CRYSTAL MOCKS YOU";
            case 4  -> "THE FLOOR BETRAYS YOU";
            case 5  -> "AVATAR OF THE CRYSTAL";
            default -> "";
        };
    }

    public String phaseSub() {
        return switch (phase) {
            case 1  -> "They come from every side.  Hold the centre.";
            case 2  -> "Three colossi.  Aim for the head  -  or anywhere, really.";
            case 3  -> "Become the bird.  Score 15 to be taken seriously again.";
            case 4  -> "The arena is alive.  Do not trust the ground.";
            case 5  -> "The crystal itself takes form.  End this.";
            default -> "";
        };
    }

    private void tickPhaseBanner(float dt) {
        maybeRebuildArena(dt);          // post-flappy: the arena restamps under the banner
        pinPlayerAtArenaCentre();
        if (t >= 3.2f && arenaBuilt) {
            if (phase == 3) {
                enter(State.FLAPPY_TRIAL);
                win.finaleEnterFlappy();
            } else {
                spawnPhase(phase);
                enter(State.FIGHT);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  THE FIVE TRIALS
    // ═══════════════════════════════════════════════════════════════════════
    private void spawnPhase(int ph) {
        crumbleTimer = 6f;
        switch (ph) {
            case 1 -> {  // THE SWARM — 12 fast enemies in a circle
                for (int i = 0; i < 12; i++) {
                    double a = i / 12.0 * Math.PI * 2;
                    Enemy.Type tType = (i % 3 == 0) ? Enemy.Type.SLIME : Enemy.Type.ZOMBIE;
                    spawnAt(a, 22f, tType);
                }
            }
            case 2 -> {  // THE GIANTS — three colossi
                spawnGiant(0.0,                 20f, Enemy.Type.GOLEM,  3.0f, 8f);
                spawnGiant(Math.PI * 2 / 3,     20f, Enemy.Type.ZOMBIE, 3.2f, 8f);
                spawnGiant(Math.PI * 4 / 3,     20f, Enemy.Type.SLIME,  3.5f, 8f);
            }
            case 4 -> {  // THE FLOOR BETRAYS YOU — mixed wave + giant spider
                for (int i = 0; i < 8; i++) {
                    double a = i / 8.0 * Math.PI * 2;
                    Enemy.Type tType = (i % 2 == 0) ? Enemy.Type.ZOMBIE : Enemy.Type.THROWER;
                    spawnAt(a, 18f, tType);
                }
                spawnGiant(Math.PI / 4, 16f, Enemy.Type.SPIDER, 2.5f, 6f);
            }
            case 5 -> {  // AVATAR OF THE CRYSTAL — the boss
                spawnGiant(0.0, 18f, Enemy.Type.GOLEM, 5.0f, 30f);
                win.startMeteorStorm(9999f);   // meteors rain for the whole fight
                win.requestShake(0.3f, 1.2f);
            }
        }
    }

    private Enemy spawnAt(double angle, float r, Enemy.Type type) {
        float x = ARENA_X + (float) Math.cos(angle) * r;
        float z = ARENA_Z + (float) Math.sin(angle) * r;
        Enemy e = win.enemyManager.spawnAt(x, ARENA_Y + 1f, z, type);
        win.fxBurst(x, ARENA_Y + 1.5f, z, 0.3f, 2.2f, 0.4f, 2.2f, 0.5f, 2.6f);
        return e;
    }

    private Enemy spawnGiant(double angle, float r, Enemy.Type type, float size, float hp) {
        Enemy e = spawnAt(angle, r, type);
        if (e != null) {
            e.makeGiant(size, hp);
            float x = e.position.x, z = e.position.z;
            win.fxRing(x, ARENA_Y + 0.5f, z, 0.5f, 6f, 0.7f, 2.8f, 0.6f, 0.6f);
            win.requestShake(0.18f, 0.6f);
        }
        return e;
    }

    private void tickFight(float dt) {
        keepPlayerOnArena();

        // Enemies knocked off the platform plummet out of the trial — count them out.
        for (Enemy e : win.enemyManager.getEnemies())
            if (e.alive && e.position.y < ARENA_Y - 25) e.alive = false;

        // Trial 4 + 5: the arena eats itself.
        if (phase >= 4 && liveRadius > 12) {
            crumbleTimer -= dt;
            if (crumbleTimer <= 0f) {
                crumbleTimer = phase == 4 ? 6f : 8f;
                crumbleRing();
            }
        }

        if (enemiesLeft() == 0 && t > 2f) {
            // Trial cleared!
            AudioManager.play("seal_collect", 1.0f);
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.9f, 0.4f, 0.5f, 0.5f);
            if (phase >= 5) {
                beginVictory();
            } else {
                phase++;
                wakeRetryPhase = phase;
                enter(State.PHASE_BANNER);
                win.requestShake(0.2f, 0.8f);
            }
        }
    }

    /** Soft net: anyone who walks off the platform is nudged back (void is for trial 4 drama only). */
    private void keepPlayerOnArena() {
        Vector3f p = win.player.position;
        if (p.y < ARENA_Y - 30) {
            // Fell off the world — that's a defeat.
            win.player.health = 0f;
            onPlayerDeath();
        }
    }

    /** Remove the outermost ring of the arena with eruption fx — the floor betrays you. */
    private void crumbleRing() {
        int cx = (int) ARENA_X, cz = (int) ARENA_Z;
        int newR = liveRadius - 4;
        for (int dx = -liveRadius; dx <= liveRadius; dx++) {
            for (int dz = -liveRadius; dz <= liveRadius; dz++) {
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d > liveRadius || d <= newR) continue;
                win.world.setBlock(cx + dx, ARENA_Y - 1, cz + dz, Block.AIR);
                win.world.setBlock(cx + dx, ARENA_Y - 2, cz + dz, Block.AIR);
                if (rng.nextInt(14) == 0)
                    win.fxBurst(cx + dx, ARENA_Y, cz + dz, 0.2f, 1.8f, 0.5f, 2.4f, 0.6f, 0.7f);
            }
        }
        // Fresh rim so the shrunken platform still glows
        for (int dx = -newR; dx <= newR; dx++)
            for (int dz = -newR; dz <= newR; dz++) {
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d <= newR && d > newR - 1.5f)
                    win.world.setBlock(cx + dx, ARENA_Y - 1, cz + dz, Block.CRYSTAL_BASE);
            }
        liveRadius = newR;
        win.requestShake(0.3f, 1.0f);
        AudioManager.play("fall_light", 1.0f);
    }

    /** Rebuild the arena fresh (after defeat, before re-entry). */
    private void resetArena() {
        for (Enemy e : win.enemyManager.getEnemies()) e.alive = false;
        arenaBuilt = false;   // rebuilt on next warp
        liveRadius = ARENA_R;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TRIAL 3 — THE BIRD
    // ═══════════════════════════════════════════════════════════════════════
    private void tickFlappy(float dt) {
        if (win.player.testMovement.flappyScore >= 15) {
            // Passed! Return to the arena.
            win.finaleExitFlappy(ARENA_X, ARENA_Y + 2f, ARENA_Z);
            arenaBuildDelay = 0.9f;   // chunks were cleared; restamp the arena shortly
            arenaBuilt = false;
            phase = 4;
            wakeRetryPhase = 4;
            enter(State.PHASE_BANNER);
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.9f, 0.4f, 0.6f, 0.6f);
            AudioManager.play("seal_collect", 1.0f);
        }
    }

    /** Called from PHASE_BANNER while waiting to restamp the arena post-flappy. */
    private void maybeRebuildArena(float dt) {
        if (!arenaBuilt && arenaBuildDelay > 0f) {
            arenaBuildDelay -= dt;
            if (arenaBuildDelay <= 0f) buildArena();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DEFEAT — wake up outside the portal
    // ═══════════════════════════════════════════════════════════════════════
    /** Window.triggerDeath routes here while the finale owns death handling. */
    public void onPlayerDeath() {
        if (state == State.DEFEAT_FADE || state == State.DEFEAT_WAKE) return;
        wakeRetryPhase = Math.max(1, phase);
        enter(State.DEFEAT_FADE);
        win.stopMeteorStorm();
        AudioManager.stopContinuous("kamui_duration");
        AudioManager.stopContinuous("kamui_distortion");
        ScreenEffectManager.INSTANCE.desaturate(0.8f, 1.2f);
    }

    private void tickDefeatFade(Camera camera, float dt) {
        if (t >= 1.4f) {
            // Teleport to just outside the portal, lying on the ground.
            resetArena();
            win.stopMeteorStorm();
            win.player.position.set(SPAWN_X + 6f, portalY + 1.5f, SPAWN_Z + 3f);
            win.player.setVelocityY(0f);
            win.player.health = win.player.maxHealth;
            win.player.mana   = win.player.maxMana;
            win.player.highestY = portalY + 0.5f;
            camera.yaw = (float) Math.toRadians(200);   // facing vaguely at the portal
            camera.pitch = -0.25f;
            enter(State.DEFEAT_WAKE);
        }
    }

    /**
     * The wake-up: eyes closed → 3 blinks → eyes open, camera tilted on the
     * ground → slowly un-tilt and rise to standing. HUD draws the eyelids from
     * {@link #wakeEyelid()}; we drive camera roll/height here.
     *
     * Timeline: 0-3.2s blinks · 3.2-5.7s stand up · 5.7-7.5s steady + text.
     */
    private void tickDefeatWake(Camera camera, float dt) {
        float standT = clamp01((t - 3.2f) / 2.5f);
        float ease = standT * standT * (3f - 2f * standT);   // smoothstep

        win.player.externalRoll = (float) Math.toRadians(75) * (1f - ease);
        float eyeY = 0.35f + (1.62f - 0.35f) * ease;
        Vector3f p = win.player.position;
        camera.position.set(p.x, p.y + eyeY, p.z);

        if (t >= 4.5f && subtitle == null)
            say("The crystal is not done with you.  Return when ready.");

        if (t >= 7.5f) {
            win.player.externalRoll = 0f;
            subtitle = null;
            enter(State.SUMMONS);   // portal still waits; directive returns
        }
    }

    /**
     * Eyelid coverage 0..1 for the wake-up (1 = eyes fully shut).
     * Three blinks of decreasing length, then open for good.
     */
    public float wakeEyelid() {
        if (state == State.DEFEAT_FADE) return Math.min(1f, t / 0.9f);
        if (state != State.DEFEAT_WAKE) return 0f;
        // blink pattern: shut(0-0.8) open(0.8-1.2) shut(1.2-1.8) open(1.8-2.4) shut(2.4-2.7) open→
        if (t < 0.8f) return 1f;
        if (t < 1.2f) return 1f - (t - 0.8f) / 0.4f;
        if (t < 1.8f) return (t - 1.2f) / 0.6f;
        if (t < 2.4f) return 1f - (t - 1.8f) / 0.6f;
        if (t < 2.7f) return (t - 2.4f) / 0.3f;
        if (t < 3.4f) return Math.max(0f, 1f - (t - 2.7f) / 0.7f);
        return 0f;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  VICTORY — the dimension tears apart
    // ═══════════════════════════════════════════════════════════════════════
    private void beginVictory() {
        endKills = win.enemyManager.totalKills;
        endTime  = (float) org.lwjgl.glfw.GLFW.glfwGetTime() - RunRecords.INSTANCE.runStartTime;
        enter(State.VICTORY_TEAR);
        warpTeleported = false;     // reused as the tear's one-shot teleport latch
        win.stopMeteorStorm();
        for (Enemy e : win.enemyManager.getEnemies()) e.alive = false;
        ScreenEffectManager.INSTANCE.hitStopSeconds(0.35f);
        ScreenEffectManager.INSTANCE.flash(1f, 1f, 1f, 0.8f, 0.6f);
        AudioManager.play("seal_collect", 1.0f);
        win.requestShake(0.4f, 2.5f);
    }

    private void tickVictoryTear(Camera camera, float dt) {
        // The arena rips itself apart: random blocks burst outward each frame.
        int cx = (int) ARENA_X, cz = (int) ARENA_Z;
        for (int i = 0; i < 26; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            float r = rng.nextFloat() * liveRadius;
            int bx = cx + (int) (Math.cos(a) * r), bz = cz + (int) (Math.sin(a) * r);
            int by = ARENA_Y - 1 - rng.nextInt(2);
            if (win.world.getBlock(bx, by, bz).isSolid()) {
                win.world.setBlock(bx, by, bz, Block.AIR);
                if (rng.nextInt(4) == 0)
                    win.fxBolt(bx, by + 1, bz,
                            (float) Math.cos(a) * 0.4f, 1f, (float) Math.sin(a) * 0.4f,
                            5f, 0.14f, 0.6f, 2.6f, 0.7f, 2.8f);
            }
        }
        camera.position.set(win.player.position.x, win.player.position.y + 1.62f, win.player.position.z);

        if (t >= 3.5f && !warpTeleported) {
            // White tear is at full cover: slip back to the overworld now so the
            // spawn chunks have a second to stream in while the player is frozen.
            warpTeleported = true;
            win.player.position.set(SPAWN_X + 4f, portalY + 1f, SPAWN_Z + 8f);
            win.player.setVelocityY(0f);
            win.player.highestY = portalY + 1f;
            win.player.health = win.player.maxHealth;
            removePortal();
            win.world.clearAllChunks();
        }
        if (t >= 4.8f) {
            enter(State.MADE_IN_HEAVEN);
            say("You passed the trial.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MADE IN HEAVEN — the world races past
    // ═══════════════════════════════════════════════════════════════════════
    private void tickMadeInHeaven(Camera camera, float dt) {
        win.player.health = win.player.maxHealth;   // untouchable now

        // Lore subtitles, paced over ~19s
        if (t >= 4.5f  && t < 4.6f)  say("But power like yours was never meant to stay.");
        if (t >= 9.0f  && t < 9.1f)  say("It transcends time.  It transcends existence.");
        if (t >= 13.5f && t < 13.6f) say("The world can no longer hold you.");
        if (t >= 17.5f && t < 17.6f) say("Rise.");

        // Golden wind — streaks blowing sideways past the camera
        windTimer -= dt;
        if (windTimer <= 0f) {
            windTimer = 0.12f;
            Vector3f p = win.player.position;
            float wy = p.y + rng.nextFloat() * 8f - 1f;
            float wx = p.x + (rng.nextFloat() - 0.5f) * 40f;
            float wz = p.z + (rng.nextFloat() - 0.5f) * 40f;
            win.fxBolt(wx, wy, wz, 0.9f, 0.08f, 0.35f, 7f, 0.08f, 0.8f, 3.0f, 2.2f, 0.5f);
        }

        // The terrain heaves: columns rise and fall in the middle distance.
        heaveTimer -= dt;
        if (heaveTimer <= 0f) {
            heaveTimer = 0.35f;
            for (int i = 0; i < 3; i++) terrainHeave();
        }

        if (t >= 20f) {
            enter(State.ASCENSION);
            subtitle = null;
            AudioManager.play("seal_collect", 1.0f);
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.92f, 0.55f, 0.5f, 1.0f);
        }
    }

    /** Raise or sink one terrain column nearby — mountains rising and falling. */
    private void terrainHeave() {
        Vector3f p = win.player.position;
        double a = rng.nextDouble() * Math.PI * 2;
        float r = 28f + rng.nextFloat() * 50f;
        int hx = (int) (p.x + Math.cos(a) * r), hz = (int) (p.z + Math.sin(a) * r);
        int sy = 300;
        while (sy > 120 && !win.world.getBlock(hx, sy, hz).isSolid()) sy--;
        if (rng.nextBoolean()) {
            int h = 2 + rng.nextInt(6);
            for (int dyy = 1; dyy <= h; dyy++)
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        if (Math.abs(dx) + Math.abs(dz) < 2 || dyy < h - 1)
                            win.world.setBlock(hx + dx, sy + dyy, hz + dz, Block.STONE);
            win.fxBurst(hx, sy + h, hz, 0.4f, 2.5f, 0.6f, 1.4f, 1.2f, 0.9f);
        } else {
            int h = 2 + rng.nextInt(4);
            for (int dyy = 0; dyy < h; dyy++)
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        win.world.setBlock(hx + dx, sy - dyy, hz + dz, Block.AIR);
            win.fxBurst(hx, sy, hz, 0.4f, 2.2f, 0.5f, 1.1f, 0.9f, 0.7f);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ASCENSION — rising into the light
    // ═══════════════════════════════════════════════════════════════════════
    private void tickAscension(Camera camera, float dt) {
        // Accelerating rise
        float speed = 3f + t * t * 1.8f;
        win.player.position.y += speed * dt;
        win.player.setVelocityY(0f);
        win.player.highestY = win.player.position.y;
        Vector3f p = win.player.position;
        camera.position.set(p.x, p.y + 1.62f, p.z);

        // Golden spiral around the rising body
        double a1 = t * 6.0;
        for (int k = 0; k < 2; k++) {
            double a = a1 + k * Math.PI;
            win.fxBurst(p.x + (float) Math.cos(a) * 2.2f, p.y + (t % 1f) * 3f,
                        p.z + (float) Math.sin(a) * 2.2f,
                        0.05f, 0.6f, 0.35f, 3.0f, 2.2f, 0.6f);
        }
        if (((int) (t * 3)) % 3 == 0)
            win.fxRing(p.x, p.y - 1f, p.z, 0.8f, 4f, 0.5f, 3.0f, 2.0f, 0.5f);

        if (t >= 8f) {
            enter(State.WHITEOUT);
            AudioManager.play("seal_collect", 0.8f);
        }
    }

    /** White overlay 0..1 during ascension/whiteout (HUD reads this). */
    public float whiteout() {
        if (state == State.ASCENSION) return clamp01((t - 4.5f) / 3.5f);
        if (state == State.WHITEOUT)  return 1f;
        if (state == State.THE_END)   return 0f;
        return 0f;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  WHITEOUT + THE END
    // ═══════════════════════════════════════════════════════════════════════
    /** Lines shown on the pure-white screen, by time. */
    public String whiteoutLine() {
        if (state != State.WHITEOUT) return null;
        if (t < 3.0f)  return "Your descent is over.";
        if (t < 6.0f)  return "Now begins your ascension.";
        if (t < 10.0f) return "You are no longer the hunted.  You are no longer even human.";
        return "You have become  E T E R N A L .";
    }

    private void tickWhiteout(float dt) {
        if (t >= 13.5f) enter(State.THE_END);
    }

    private void tickTheEnd(float dt) {
        boolean en = org.lwjgl.glfw.GLFW.glfwGetKey(win.window,
                org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(win.window,
                org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (en && !lastEnter && t > 2f) {
            // Remain in the world, eternal: god mode free play.
            win.player.progression.unlockAll();
            win.player.debugMode = true;                  // flight on
            win.player.position.set(SPAWN_X, portalY + 40f, SPAWN_Z);
            win.player.health = win.player.maxHealth;
            win.player.mana   = win.player.maxMana;
            win.enemyManager.wavesEnabled    = false;
            win.enemyManager.freeExploreMode = true;      // the world lives on around a god
            enter(State.FREEPLAY);
        }
        lastEnter = en;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }
}
