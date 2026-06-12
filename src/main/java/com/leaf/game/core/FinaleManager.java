package com.leaf.game.core;

import com.leaf.game.entity.Enemy;
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
 *  SUMMONS        "THE CRYSTAL CALLS YOU HOME" — a grand portal rises at
 *                 spawn under a 140-block beam of violet light.
 *        │ step through the gate
 *        ▼
 *  PORTAL_WARP    vortex transition, white-out, teleport
 *        ▼
 *  THE COLOSSEUM  a crowned void-colosseum: patterned floor, colonnade,
 *                 parapet walls, four spires, a ring of floating crystals
 *                 and the crystal's eye watching from above. Five trials —
 *                 every one driven by SCRIPTED attacks, not wandering AI:
 *                   1 THE SWARM            three escalating waves + skyfire
 *                   2 THE GIANTS           three NAMED colossi, stomp shockwaves
 *                   3 THE CRYSTAL MOCKS YOU  flappy bird, score 15
 *                   4 THE FLOOR BETRAYS YOU  crumbling rings + erupting geysers
 *                   5 AVATAR OF THE CRYSTAL  boss bar, crystal barrages, adds,
 *                                            enrage, meteor rain
 *        │ die: DEFEAT_FADE → DEFEAT_WAKE — wake lying outside the portal,
 *        │      curved eyelids, drowsy sway, stand up. Resume the same trial.
 *        ▼ win
 *  VICTORY_TEAR   slow-mo; the colosseum rips itself apart; white gash
 *        ▼
 *  MADE_IN_HEAVEN the overworld races — sun and stars streak, golden wind
 *        ▼
 *  UPHEAVAL       the planet itself answers: whole hillsides surge and
 *                 collapse, crystal spires erupt, seasons strobe past
 *        ▼
 *  ASCENSION      you rise — past the clouds, past the sky, past the stars
 *        ▼
 *  WHITEOUT       pure white. the last words.
 *        ▼
 *  THE_END        black. DESCENT. There is no going back.
 * </pre>
 */
public class FinaleManager {

    public enum State {
        IDLE, SUMMONS, PORTAL_WARP, ARENA_INTRO, PHASE_BANNER, FIGHT,
        FLAPPY_TRIAL, DEFEAT_FADE, DEFEAT_WAKE,
        VICTORY_TEAR, MADE_IN_HEAVEN, UPHEAVAL, ASCENSION, WHITEOUT, THE_END
    }

    // ── Arena geometry ─────────────────────────────────────────────────────────
    static final float ARENA_X = 777f, ARENA_Z = -2223f;
    static final int   ARENA_Y = 380;
    static final int   ARENA_R = 44;
    private static final float SPAWN_X = 777f, SPAWN_Z = 777f;

    private final Window win;
    private final Random rng = new Random();

    public State state = State.IDLE;
    public float t = 0f;
    public int phase = 1;

    private int liveRadius = ARENA_R;
    private float summonsDelay = -1f;

    private float portalY = 250f;
    private boolean portalBuilt = false;
    private boolean arenaBuilt  = false;
    private float arenaBuildDelay = 0f;
    private boolean warpTeleported = false;

    // ── Scripted-combat state ──────────────────────────────────────────────────
    private float crumbleTimer = 0f;
    /** Trial 1 sub-waves. */
    private int subWave = 0;
    /** Trial 2 staggered giant entries. */
    private int giantQueue = 0;
    private float giantEntryTimer = 0f;
    private final List<Enemy> giants = new ArrayList<>();
    private float stompTimer = 0f;
    /** Trial 4 geysers: {x, z, countdown}. */
    private final List<float[]> geysers = new ArrayList<>();
    private float geyserTimer = 0f;
    private float lightningTimer = 0f;
    /** The Avatar boss. */
    private Enemy boss = null;
    /** Countdown to the next meteor PULSE during the Avatar fight. */
    private float bossMeteorTimer = 0f;
    private float barrageTimer = 0f;
    /** Pending barrage strikes: {x, y, z, countdown}. */
    private final List<float[]> barrage = new ArrayList<>();
    private boolean adds66 = false, adds33 = false, enraged = false;

    /** Scripted ballistic leaps: giants JUMP at the player instead of shuffling. */
    private static final class Leap {
        Enemy e; float fx, fy, fz, tx, tz; float t, dur, slamRadius, slamDmg;
    }
    private final List<Leap> leaps = new ArrayList<>();
    /** Per-giant ability clocks (index matches spawn order in {@link #giants}). */
    private final float[] giantAbility = new float[6];
    /** Boss attack cycle. */
    private float bossAttackTimer = 0f;
    private int   bossAttackIdx = 0;
    private boolean bossEntryPending = false;

    private int wakeRetryPhase = 1;

    // Spectacle timers
    private float beamTimer = 0f;
    private float heaveTimer = 0f;
    private float windTimer  = 0f;
    private float flashTimer = 0f;

    public String subtitle = null;
    public float  subtitleAge = 0f;

    public int   endKills = 0;
    public float endTime  = 0f;

    public FinaleManager(Window win) { this.win = win; }

    // ── Queries used by Window / HUD each frame ────────────────────────────────
    public boolean locksPlayer() {
        return state == State.PORTAL_WARP || state == State.DEFEAT_FADE
            || state == State.DEFEAT_WAKE || state == State.VICTORY_TEAR
            || state == State.ASCENSION   || state == State.WHITEOUT
            || state == State.THE_END     || state == State.ARENA_INTRO
            || state == State.PHASE_BANNER;
    }

    public boolean wantsTimeRush() {
        return state == State.MADE_IN_HEAVEN || state == State.UPHEAVAL
            || state == State.ASCENSION;
    }

    /** States in which dying is still possible (everything later is past death). */
    private boolean inTrialStates() {
        return state == State.FIGHT || state == State.PHASE_BANNER
            || state == State.ARENA_INTRO || state == State.SUMMONS
            || state == State.PORTAL_WARP;
    }

    public boolean isFlappyTrial() { return state == State.FLAPPY_TRIAL; }

    public boolean active() { return state != State.IDLE; }

    public float portalX()  { return SPAWN_X; }
    public float portalZ()  { return SPAWN_Z; }
    public float portalCY() { return portalY + 5f; }

    public int enemiesLeft() {
        int n = 0;
        for (Enemy e : win.enemyManager.getEnemies()) if (e.alive) n++;
        return n;
    }

    /** "WAVE 2 / 3" label during trial 1, null otherwise. */
    public String subWaveLabel() {
        return (state == State.FIGHT && phase == 1) ? "WAVE  " + subWave + "  /  3" : null;
    }

    /** Boss HP fraction during the Avatar trial, or -1 when no boss bar should show. */
    public float bossFrac() {
        if (state != State.FIGHT || phase != PHASES || boss == null || !boss.alive) return -1f;
        return Math.max(0f, boss.health / (boss.maxHealth * boss.healthScale));
    }

    public boolean isEnraged() { return enraged; }

    // ── Main update ────────────────────────────────────────────────────────────
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
            case UPHEAVAL        -> tickUpheaval(camera, dt);
            case ASCENSION       -> tickAscension(camera, dt);
            case WHITEOUT        -> tickWhiteout(dt);
            case THE_END         -> { /* terminal — the pause menu still offers Quit */ }
        }
    }

    private void enter(State s) { state = s; t = 0f; }
    private void say(String line) { subtitle = line; subtitleAge = 0f; }

    // ═══════════════════════════════════════════════════════════════════════
    //  IDLE → SUMMONS
    // ═══════════════════════════════════════════════════════════════════════
    private void tickIdle(float dt) {
        if (win.voyage != null && win.voyage.complete) {
            if (summonsDelay < 0f) summonsDelay = 8f;
            summonsDelay -= dt;
            if (summonsDelay <= 0f) beginSummons();
        }
    }

    private void beginSummons() {
        enter(State.SUMMONS);
        win.enemyManager.wavesEnabled = false;
        win.enemyManager.freeExploreMode = false;
        win.enemyManager.beginNextWave();
        for (Enemy e : win.enemyManager.getEnemies()) e.alive = false;
        buildPortal();
        AudioManager.play("seal_collect", 1.0f);
        ScreenEffectManager.INSTANCE.flash(0.7f, 0.4f, 1.0f, 0.45f, 0.8f);
        win.requestShake(0.18f, 0.9f);
    }

    private void tickSummons(float dt) {
        portalAmbientFx(dt);

        Vector3f p = win.player.position;
        float dx = p.x - SPAWN_X, dz = p.z - SPAWN_Z;
        float dy = p.y - portalY;
        if (Math.abs(dx) < 3.5f && Math.abs(dz) < 2.2f && dy > -1f && dy < 10f) {
            warpTeleported = false;
            enter(State.PORTAL_WARP);
            AudioManager.play("snipe_loadgun", 1.0f);
            win.requestShake(0.25f, 1.5f);
        }
    }

    /**
     * The portal's living presence: a 140-block beam of violet light (visible
     * across the map), counter-rotating spark orbits, inward-spiralling motes,
     * a shimmering mouth, and ground-pulse rings.
     */
    private void portalAmbientFx(float dt) {
        float gx = SPAWN_X, gz = SPAWN_Z;
        float midY = portalY + 6f;

        // ── Sky beam — same trick as the Voyage beacon, but taller and violet ──
        beamTimer -= dt;
        if (beamTimer <= 0f) {
            beamTimer = 0.05f;
            for (int i = 0; i < 12; i++)
                win.fxBolt(gx, portalY + 4f + i * 12f, gz, 0f, 1f, 0f,
                        11f, 0.6f, 0.5f, 1.6f, 0.7f, 2.8f);
            win.fxRing(gx, portalY + 0.4f, gz, 1.5f, 9f, 0.7f, 1.6f, 0.7f, 2.8f);
        }

        // ── Counter-rotating orbit sparks around the ring mouth ──
        for (int k = 0; k < 2; k++) {
            double a = (k == 0 ? 1 : -1) * t * 2.6 + k * Math.PI;
            float r = 4.2f + (float) Math.sin(t * 1.9 + k) * 0.8f;
            float oy = midY + (float) Math.sin(t * 2.3 + k * 2.1) * 3.5f;
            win.fxBurst(gx + (float) Math.cos(a) * r, oy, gz + (float) Math.sin(a) * r * 0.4f,
                    0.05f, 0.55f, 0.30f, 2.0f, 0.6f, 2.8f);
        }
        // ── Inward-spiralling motes pulled toward the mouth ──
        if (rng.nextInt(2) == 0) {
            double a = rng.nextDouble() * Math.PI * 2;
            float r = 7f + rng.nextFloat() * 5f;
            float sx = gx + (float) Math.cos(a) * r;
            float sz2 = gz + (float) Math.sin(a) * r;
            float sy = portalY + 1f + rng.nextFloat() * 9f;
            Vector3f dir = new Vector3f(gx - sx, midY - sy, gz - sz2).normalize();
            win.fxBolt(sx, sy, sz2, dir.x, dir.y, dir.z, 3.5f, 0.10f, 0.45f, 1.9f, 0.5f, 2.9f);
        }
        // ── Shimmering mouth ──
        win.fxBurst(gx + (rng.nextFloat() - 0.5f) * 3.5f, portalY + 2f + rng.nextFloat() * 7f,
                gz, 0.05f, 0.4f, 0.25f, 1.4f, 0.5f, 2.6f);
    }

    /**
     * The Gate: a raised crystal dais, a 13-tall double-thick ring crowned with
     * amethyst, and two flanking obelisks. Built once, at spawn.
     */
    private void buildPortal() {
        if (portalBuilt) return;
        int sx = (int) SPAWN_X, sz = (int) SPAWN_Z;
        int sy = 300;
        while (sy > 100 && !win.world.getBlock(sx, sy, sz).isSolid()) sy--;
        portalY = sy + 1;
        int base = (int) portalY;

        // Dais: 13×9 platform with a crystal border, one block high.
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                boolean edge = Math.abs(dx) == 6 || Math.abs(dz) == 4;
                win.world.setBlock(sx + dx, base - 1, sz + dz,
                        edge ? Block.CRYSTAL_BASE : Block.MEGALITH);
            }

        // Standing ring: 11 wide × 13 tall, two blocks thick in Z.
        for (int dzz = 0; dzz <= 1; dzz++)
            for (int dx = -5; dx <= 5; dx++)
                for (int dyy = 0; dyy <= 12; dyy++) {
                    boolean frame = Math.abs(dx) == 5 || dyy == 0 || dyy == 12
                            || (Math.abs(dx) == 4 && (dyy <= 1 || dyy >= 11));
                    if (!frame) continue;
                    Block b = ((dx + dyy) % 3 == 0) ? Block.CRYSTAL_AMETHYST : Block.MEGALITH;
                    win.world.setBlock(sx + dx, base + dyy, sz + dzz, b);
                }
        // Crown: amethyst cluster on top centre.
        for (int dx = -1; dx <= 1; dx++)
            win.world.setBlock(sx + dx, base + 13, sz, Block.CRYSTAL_AMETHYST);
        win.world.setBlock(sx, base + 14, sz, Block.CRYSTAL_AMETHYST);

        // Flanking obelisks.
        for (int side = -1; side <= 1; side += 2) {
            int ox = sx + side * 8;
            for (int dyy = 0; dyy < 9; dyy++)
                for (int dx = 0; dx <= 1; dx++)
                    for (int dz = 0; dz <= 1; dz++)
                        win.world.setBlock(ox + dx, base + dyy, sz + dz,
                                dyy >= 7 ? Block.CRYSTAL_AMETHYST : Block.CRYSTAL_BASE);
        }
        portalBuilt = true;
    }

    private void removePortal() {
        if (!portalBuilt) return;
        int sx = (int) SPAWN_X, sz = (int) SPAWN_Z;
        int base = (int) portalY;
        for (int dx = -9; dx <= 9; dx++)
            for (int dz = -5; dz <= 5; dz++)
                for (int dyy = -1; dyy <= 15; dyy++)
                    if (win.world.getBlock(sx + dx, base + dyy, sz + dz).isSolid()
                            && (Math.abs(dx) > 6 || Math.abs(dz) > 4 || dyy >= 0))
                        win.world.setBlock(sx + dx, base + dyy, sz + dz, Block.AIR);
        portalBuilt = false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PORTAL WARP
    // ═══════════════════════════════════════════════════════════════════════
    private void tickPortalWarp(Camera camera, float dt) {
        for (int i = 0; i < 4; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            float r = 4f + rng.nextFloat() * 7f;
            win.fxBolt(camera.position.x + (float) Math.cos(ang) * r,
                       camera.position.y + (rng.nextFloat() - 0.5f) * 7f,
                       camera.position.z + (float) Math.sin(ang) * r,
                       -(float) Math.cos(ang), 0f, -(float) Math.sin(ang),
                       4f, 0.10f, 0.35f, 1.8f, 0.6f, 2.8f);
        }
        if (t >= 1.6f && !warpTeleported) {
            warpTeleported = true;
            win.player.position.set(ARENA_X, ARENA_Y + 2f, ARENA_Z);
            win.player.setVelocityY(0f);
            win.player.highestY = ARENA_Y + 2f;
            win.world.clearAllChunks();
            arenaBuilt = false;
            arenaBuildDelay = 0.9f;
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

    // ═══════════════════════════════════════════════════════════════════════
    //  THE COLOSSEUM
    // ═══════════════════════════════════════════════════════════════════════
    private void buildArena() {
        int cx = (int) ARENA_X, cz = (int) ARENA_Z;

        // ── Floor: concentric pattern — crystal rim, banded rings, vein sparkle ──
        for (int dx = -ARENA_R; dx <= ARENA_R; dx++) {
            for (int dz = -ARENA_R; dz <= ARENA_R; dz++) {
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d > ARENA_R) continue;
                Block b;
                if (d > ARENA_R - 1.5f)        b = Block.CRYSTAL_BASE;        // glowing rim
                else if (d % 8f < 1.2f)        b = Block.CRYSTAL_BASE;        // pattern bands
                else if (d < 5f)               b = Block.CRYSTAL_BASE;        // centre dais
                else if (rng.nextInt(30) == 0) b = Block.CRYSTAL_AMETHYST;    // veins
                else                           b = Block.MEGALITH;
                win.world.setBlock(cx + dx, ARENA_Y - 1, cz + dz, b);
                // Solid 6-deep slab: meteor craters and ability blasts can scar the
                // surface but NEVER punch a hole through to the void below.
                for (int u = 2; u <= 7; u++)
                    win.world.setBlock(cx + dx, ARENA_Y - u, cz + dz, Block.MEGALITH);
            }
        }
        // Centre eye inlay
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                win.world.setBlock(cx + dx, ARENA_Y - 1, cz + dz, Block.CRYSTAL_AMETHYST);

        // ── Parapet wall (r 41..43, 3 high, crenellated, 4 gates) ──
        for (int dx = -ARENA_R; dx <= ARENA_R; dx++)
            for (int dz = -ARENA_R; dz <= ARENA_R; dz++) {
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d < 41f || d > 43f) continue;
                // Gates at the four compass points
                if (Math.abs(dx) < 4 && Math.abs(dz) > 38) continue;
                if (Math.abs(dz) < 4 && Math.abs(dx) > 38) continue;
                win.world.setBlock(cx + dx, ARENA_Y, cz + dz, Block.MEGALITH);
                win.world.setBlock(cx + dx, ARENA_Y + 1, cz + dz, Block.MEGALITH);
                if (((dx * 31 + dz * 17) & 1) == 0)      // crenellation teeth
                    win.world.setBlock(cx + dx, ARENA_Y + 2, cz + dz, Block.CRYSTAL_BASE);
            }

        // ── Colonnade: 12 pillars just inside the wall ──
        for (int i = 0; i < 12; i++) {
            double a = i / 12.0 * Math.PI * 2 + Math.PI / 12.0;
            int px = cx + (int) (Math.cos(a) * 37);
            int pz = cz + (int) (Math.sin(a) * 37);
            buildPillar(px, pz, 13);
        }

        // ── Four grand spires at the diagonals (tapering, crystal-tipped) ──
        int sr = 30;
        int[][] diag = {{sr, sr}, {-sr, sr}, {sr, -sr}, {-sr, -sr}};
        for (int[] d : diag) buildSpire(cx + (int) (d[0] * 0.7071f), cz + (int) (d[1] * 0.7071f));

        // ── Floating crystal ring + the watching eye ──
        for (int i = 0; i < 10; i++) {
            double a = i / 10.0 * Math.PI * 2;
            int fx = cx + (int) (Math.cos(a) * 20);
            int fz = cz + (int) (Math.sin(a) * 20);
            win.world.setBlock(fx, ARENA_Y + 16, fz, Block.CRYSTAL_AMETHYST);
            win.world.setBlock(fx, ARENA_Y + 17, fz, Block.CRYSTAL_AMETHYST);
        }
        // The eye: a floating diamond cluster dead centre, watching.
        int ey = ARENA_Y + 24;
        for (int dx = -1; dx <= 1; dx++)
            for (int dyy = -1; dyy <= 1; dyy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (Math.abs(dx) + Math.abs(dyy) + Math.abs(dz) <= 1)
                        win.world.setBlock(cx + dx, ey + dyy, cz + dz, Block.CRYSTAL_AMETHYST);

        liveRadius = ARENA_R;
        arenaBuilt = true;
    }

    private void buildPillar(int px, int pz, int height) {
        for (int dyy = 0; dyy < height; dyy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    boolean tip = dyy >= height - 2;
                    win.world.setBlock(px + dx, ARENA_Y + dyy, pz + dz,
                            tip ? Block.CRYSTAL_AMETHYST : Block.CRYSTAL_BASE);
                }
    }

    private void buildSpire(int px, int pz) {
        // 5×5 base → 3×3 shaft → 1×1 amethyst needle (total 18 tall)
        for (int dyy = 0; dyy < 6; dyy++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    win.world.setBlock(px + dx, ARENA_Y + dyy, pz + dz, Block.MEGALITH);
        for (int dyy = 6; dyy < 14; dyy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    win.world.setBlock(px + dx, ARENA_Y + dyy, pz + dz, Block.CRYSTAL_BASE);
        for (int dyy = 14; dyy < 18; dyy++)
            win.world.setBlock(px, ARENA_Y + dyy, pz, Block.CRYSTAL_AMETHYST);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INTRO + BANNERS
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

    private void pinPlayerAtArenaCentre() {
        win.player.position.set(ARENA_X, ARENA_Y + 1.05f, ARENA_Z);
        win.player.setVelocityY(0f);
        win.player.highestY = ARENA_Y + 1.05f;
        win.camera.position.set(ARENA_X, ARENA_Y + 1.05f + 1.62f, ARENA_Z);
    }

    /** Total number of trials. */
    public static final int PHASES = 6;

    public String phaseName() {
        return switch (phase) {
            case 1  -> "THE SWARM";
            case 2  -> "THE GIANTS";
            case 3  -> "THE CRYSTAL MOCKS YOU";
            case 4  -> "EYES OF THE CRYSTAL";
            case 5  -> "THE FLOOR BETRAYS YOU";
            case 6  -> "AVATAR OF THE CRYSTAL";
            default -> "";
        };
    }

    public String phaseSub() {
        return switch (phase) {
            case 1  -> "Three waves.  Skyfire walks among them.  Hold the centre.";
            case 2  -> "Three names.  Three colossi.  DODGE THE STOMP.";
            case 3  -> "Become the bird.  Score 15 to be taken seriously again.";
            case 4  -> "Six crystal eyes watch from the pillars.  SHOOT THEM DOWN.";
            case 5  -> "The ground erupts where you stand.  NEVER stop moving.";
            case 6  -> "The crystal itself takes form.  End this.";
            default -> "";
        };
    }

    private void tickPhaseBanner(float dt) {
        maybeRebuildArena(dt);
        pinPlayerAtArenaCentre();
        // Full restore between trials — every fight starts fresh.
        win.player.health = win.player.maxHealth;
        win.player.mana   = win.player.maxMana;
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

    private void maybeRebuildArena(float dt) {
        if (!arenaBuilt && arenaBuildDelay > 0f) {
            arenaBuildDelay -= dt;
            if (arenaBuildDelay <= 0f) buildArena();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TRIAL SETUP
    // ═══════════════════════════════════════════════════════════════════════
    private void spawnPhase(int ph) {
        crumbleTimer = 6f;
        giants.clear(); geysers.clear(); barrage.clear(); leaps.clear();
        boss = null; adds66 = adds33 = enraged = false; bossEntryPending = false;
        lightningTimer = 2.5f;
        for (int i = 0; i < giantAbility.length; i++) giantAbility[i] = 3f + i * 1.5f;
        switch (ph) {
            case 1 -> { subWave = 0; nextSubWave(); }
            case 2 -> {
                giantQueue = 3;
                giantEntryTimer = 0.8f;
                stompTimer = 5f;
            }
            case 4 -> {
                // EYES OF THE CRYSTAL: six sentinels perched on alternating
                // colonnade pillars. They cannot move — but they SHOOT.
                for (int i = 0; i < 6; i++) {
                    double a = (i * 2) / 12.0 * Math.PI * 2 + Math.PI / 12.0;  // every other pillar
                    float px = ARENA_X + (float) (Math.cos(a) * 37);
                    float pz = ARENA_Z + (float) (Math.sin(a) * 37);
                    Enemy s = win.enemyManager.spawnAt(px, ARENA_Y + 13.2f, pz, Enemy.Type.DUMMY);
                    if (s != null) {
                        s.scaleMul    = 1.5f;
                        s.health      = 140f;
                        s.healthScale = 140f / s.maxHealth;   // HP bar reads correctly
                        s.speedMul    = 0f;
                        giants.add(s);
                        win.fxBurst(px, ARENA_Y + 14f, pz, 0.3f, 2.5f, 0.5f, 2.0f, 0.6f, 2.9f);
                    }
                    giantAbility[i] = 2.5f + i * 0.8f;   // staggered first volleys
                }
            }
            case 5 -> {
                for (int i = 0; i < 8; i++) {
                    double a = i / 8.0 * Math.PI * 2;
                    spawnAt(a, 20f, (i % 2 == 0) ? Enemy.Type.ZOMBIE : Enemy.Type.THROWER);
                }
                Enemy gs = spawnGiant(Math.PI / 4, 16f, Enemy.Type.SPIDER, 2.5f, 6f);
                if (gs != null) giants.add(gs);
                stompTimer  = 7f;
                geyserTimer = 3.5f;
            }
            case 6 -> {
                // The Avatar DESCENDS: spawns high above and crashes into the arena.
                // (HP was 30x — an attrition marathon under permanent meteor rain
                //  that nobody survived. 12x ends the fight before the player.)
                boss = spawnGiant(0.0, 16f, Enemy.Type.GOLEM, 5.0f, 12f);
                if (boss != null) {
                    giants.add(boss);
                    boss.position.y = ARENA_Y + 30f;
                    bossEntryPending = true;
                }
                bossMeteorTimer = 6f;     // meteors come in PULSES now, not forever
                barrageTimer    = 6f;
                bossAttackTimer = 5f;
                bossAttackIdx   = 0;
                stompTimer      = 9f;
                win.requestShake(0.3f, 1.2f);
                say("IT  DESCENDS.");
            }
        }
    }

    /** Trial 1: three escalating waves. */
    private void nextSubWave() {
        subWave++;
        switch (subWave) {
            case 1 -> {
                for (int i = 0; i < 5; i++) spawnAt(i / 5.0 * Math.PI * 2, 22f, Enemy.Type.ZOMBIE);
                say("THEY  COME.");
            }
            case 2 -> {
                for (int i = 0; i < 4; i++) spawnAt(i / 4.0 * Math.PI * 2, 24f, Enemy.Type.SLIME);
                for (int i = 0; i < 3; i++) spawnAt(i / 3.0 * Math.PI * 2 + 0.5, 18f, Enemy.Type.THROWER);
                say("MORE  OF  THEM.");
            }
            case 3 -> {
                for (int i = 0; i < 9; i++) {
                    Enemy.Type tt = (i % 3 == 0) ? Enemy.Type.SLIME
                            : (i % 3 == 1) ? Enemy.Type.ZOMBIE : Enemy.Type.THROWER;
                    spawnAt(i / 9.0 * Math.PI * 2, 26f, tt);
                }
                say("ALL  OF  THEM.");
            }
        }
        ScreenEffectManager.INSTANCE.flash(0.7f, 0.4f, 1.0f, 0.3f, 0.4f);
        win.requestShake(0.15f, 0.5f);
    }

    private static final String[] GIANT_NAMES = {
            "VORGAR,  THE  MOUNTAIN",
            "THE  SHAMBLING  COLOSSUS",
            "GLUTT,  THE  DEVOURER",
    };

    private Enemy spawnAt(double angle, float r, Enemy.Type type) {
        float x = ARENA_X + (float) Math.cos(angle) * r;
        float z = ARENA_Z + (float) Math.sin(angle) * r;
        Enemy e = win.enemyManager.spawnAt(x, ARENA_Y + 1f, z, type);
        if (e != null) e.speedMul = 1.7f;   // arena minions hunt, not shuffle
        win.fxBurst(x, ARENA_Y + 1.5f, z, 0.3f, 2.2f, 0.4f, 2.2f, 0.5f, 2.6f);
        return e;
    }

    private Enemy spawnGiant(double angle, float r, Enemy.Type type, float size, float hp) {
        Enemy e = spawnAt(angle, r, type);
        if (e != null) {
            e.makeGiant(size, hp);
            e.speedMul = type == Enemy.Type.ZOMBIE ? 3.0f : 2.2f;  // colossi STRIDE
            float x = e.position.x, z = e.position.z;
            // Entrance slam: ground shockwave + debris bolts
            win.fxRing(x, ARENA_Y + 0.4f, z, 0.6f, 10f, 0.8f, 2.8f, 0.6f, 0.6f);
            win.fxRing(x, ARENA_Y + 0.4f, z, 0.4f, 6f, 0.6f, 3.2f, 1.4f, 0.4f);
            for (int i = 0; i < 8; i++) {
                double a = i / 8.0 * Math.PI * 2;
                win.fxBolt(x, ARENA_Y + 0.5f, z, (float) Math.cos(a) * 0.5f, 1f, (float) Math.sin(a) * 0.5f,
                        5f, 0.14f, 0.7f, 2.4f, 0.8f, 0.5f);
            }
            win.requestShake(0.3f, 0.9f);
            AudioManager.play("fall_light", 1.0f);
        }
        return e;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TRIAL TICK — all the scripted attacks live here
    // ═══════════════════════════════════════════════════════════════════════
    // ── Scripted attack toolkit ──────────────────────────────────────────────

    /** Launch a giant on a ballistic arc onto the player's head. */
    private void startLeap(Enemy g, float slamRadius, float slamDmg) {
        for (Leap l : leaps) if (l.e == g) return;   // already airborne
        Vector3f p = win.player.position;
        Leap l = new Leap();
        l.e = g;
        l.fx = g.position.x; l.fy = g.position.y; l.fz = g.position.z;
        l.tx = p.x; l.tz = p.z;
        l.t = 0f;
        float dist = (float) Math.hypot(p.x - l.fx, p.z - l.fz);
        l.dur = Math.max(0.7f, Math.min(1.3f, dist / 18f));
        l.slamRadius = slamRadius; l.slamDmg = slamDmg;
        leaps.add(l);
        // Wind-up burst + a warning ring where it will LAND
        win.fxBurst(l.fx, l.fy + 2f, l.fz, 0.4f, 3f, 0.4f, 2.6f, 0.8f, 0.5f);
        win.fxRing(l.tx, ARENA_Y + 0.3f, l.tz, slamRadius - 1f, slamRadius, 0.9f, 3.0f, 0.4f, 0.3f);
        AudioManager.play("fall_light", 0.8f);
    }

    /** Animate airborne giants; on landing, the ground answers. */
    private void tickLeaps(float dt) {
        for (int i = leaps.size() - 1; i >= 0; i--) {
            Leap l = leaps.get(i);
            if (l.e == null || !l.e.alive) { leaps.remove(i); continue; }
            l.t += dt;
            float s = Math.min(1f, l.t / l.dur);
            float arcH = 7f + l.dur * 5f;
            l.e.position.x = l.fx + (l.tx - l.fx) * s;
            l.e.position.z = l.fz + (l.tz - l.fz) * s;
            l.e.position.y = l.fy + (ARENA_Y + 1f - l.fy) * s + arcH * 4f * s * (1f - s);
            if (s >= 1f) {
                landSlam(l.tx, l.tz, l.slamRadius, l.slamDmg);
                leaps.remove(i);
            }
        }
    }

    /** Ground slam at (x,z): shockwave fx, debris, damage + launch if close. */
    private void landSlam(float x, float z, float radius, float dmg) {
        win.fxRing(x, ARENA_Y + 0.4f, z, 0.8f, radius + 4f, 0.8f, 3.0f, 0.7f, 0.4f);
        win.fxRing(x, ARENA_Y + 0.8f, z, 0.5f, radius, 0.6f, 3.2f, 1.2f, 0.5f);
        win.fxBurst(x, ARENA_Y + 1f, z, 0.5f, 5f, 0.5f, 2.6f, 0.9f, 0.5f);
        for (int i = 0; i < 8; i++) {
            double a = i / 8.0 * Math.PI * 2;
            win.fxBolt(x, ARENA_Y + 0.5f, z, (float) Math.cos(a) * 0.5f, 1f, (float) Math.sin(a) * 0.5f,
                    5f, 0.14f, 0.6f, 2.4f, 0.8f, 0.5f);
        }
        win.requestShake(0.30f, 0.7f);
        AudioManager.play("fall_light", 1.0f);
        Vector3f p = win.player.position;
        float dx = p.x - x, dz = p.z - z;
        if (dx * dx + dz * dz < radius * radius && p.y < ARENA_Y + 4f) {
            hurtPlayer(dmg);
            win.player.setVelocityY(14f);
        }
    }

    /** Radial nova: a ring of real projectiles bursts outward from the caster. */
    private void nova(Enemy caster, int count, float dmg, float speed) {
        float x = caster.position.x, z = caster.position.z;
        float y = caster.position.y + 1.5f * caster.scaleMul;
        win.fxRing(x, y, z, 0.5f, 6f, 0.5f, 2.2f, 0.6f, 2.9f);
        win.fxBurst(x, y, z, 0.4f, 3f, 0.4f, 2.4f, 0.7f, 2.9f);
        for (int i = 0; i < count; i++) {
            double a = i / (double) count * Math.PI * 2;
            Vector3f vel = new Vector3f((float) Math.cos(a) * speed, 4.5f, (float) Math.sin(a) * speed);
            win.enemyManager.projectiles.add(new com.leaf.game.entity.EnemyManager.EnemyProjectile(
                    new Vector3f(x, y, z), vel, dmg, caster.id));
        }
        AudioManager.play("fall_light", 0.7f);
    }

    /** Aimed volley: lobbed shots leading the player. */
    /**
     * A violet LASER: an instant beam from the caster's eye to where the
     * player stood at the moment of firing. The half-second aim-glint before
     * each shot is the dodge window — sidestep and the beam scorches stone.
     */
    private void fireLaser(Enemy caster, float dmg) {
        Vector3f p = win.player.position;
        float sx = caster.position.x, sy = caster.position.y + 2f * caster.scaleMul, sz = caster.position.z;
        float tx = p.x, ty = p.y + 0.9f, tz = p.z;
        float dx = tx - sx, dy = ty - sy, dz = tz - sz;
        float len = Math.max(0.001f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        float nx = dx / len, ny = dy / len, nz = dz / len;
        // The beam: overlapping bolt segments along the whole line, fired at once
        for (float d = 0f; d < len; d += 2.2f) {
            win.fxBolt(sx + nx * d, sy + ny * d, sz + nz * d, nx, ny, nz,
                    3.2f, 0.16f, 0.22f, 2.6f, 0.4f, 2.9f);
        }
        // Muzzle flare + impact burst
        win.fxBurst(sx, sy, sz, 0.2f, 1.6f, 0.25f, 2.6f, 0.5f, 2.9f);
        win.fxBurst(tx, ty, tz, 0.3f, 2.8f, 0.35f, 2.6f, 0.5f, 2.9f);
        win.fxRing(tx, ty - 0.5f, tz, 0.3f, 3.5f, 0.35f, 2.4f, 0.5f, 2.8f);
        AudioManager.play("snipe_loadgun", 0.55f);
        // Hit check in full 3D — airborne dodges count, but so do airborne hits.
        Vector3f now = win.player.position;
        float hx = now.x - tx, hy = (now.y + 0.9f) - ty, hz = now.z - tz;
        if (hx * hx + hy * hy + hz * hz < 2.2f * 2.2f) hurtPlayer(dmg);
    }

    private void volley(Enemy caster, int shots, float dmg) {
        Vector3f p = win.player.position;
        float y = caster.position.y + 2f * caster.scaleMul;
        for (int i = 0; i < shots; i++) {
            float sx = p.x + (rng.nextFloat() - 0.5f) * 4f;
            float sz = p.z + (rng.nextFloat() - 0.5f) * 4f;
            float dx = sx - caster.position.x, dz = sz - caster.position.z;
            float dist = Math.max(1f, (float) Math.sqrt(dx * dx + dz * dz));
            float flight = Math.max(0.6f, dist / 16f);
            Vector3f vel = new Vector3f(dx / flight, 5f + dist * 0.18f, dz / flight);
            win.enemyManager.projectiles.add(new com.leaf.game.entity.EnemyManager.EnemyProjectile(
                    new Vector3f(caster.position.x, y, caster.position.z), vel, dmg, caster.id));
        }
        win.fxBurst(caster.position.x, y, caster.position.z, 0.3f, 2.5f, 0.35f, 2.6f, 0.7f, 0.5f);
    }

    private void tickFight(float dt) {
        keepPlayerOnArena();
        tickLeaps(dt);
        for (Enemy e : win.enemyManager.getEnemies())
            if (e.alive && e.position.y < ARENA_Y - 25) e.alive = false;

        // ── Ambient drama: violet embers rising off the rim ──
        if (rng.nextInt(4) == 0) {
            double a = rng.nextDouble() * Math.PI * 2;
            float r = liveRadius - 2f;
            win.fxBolt(ARENA_X + (float) Math.cos(a) * r, ARENA_Y + 0.5f,
                       ARENA_Z + (float) Math.sin(a) * r, 0f, 1f, 0f,
                       2.5f, 0.07f, 0.8f, 1.6f, 0.6f, 2.4f);
        }

        switch (phase) {
            case 1 -> tickSwarm(dt);
            case 2 -> tickGiants(dt);
            case 4 -> tickSentinels(dt);
            case 5 -> tickFloor(dt);
            case 6 -> tickAvatar(dt);
        }

        boolean spawningMore = (phase == 1 && subWave < 3) || (phase == 2 && giantQueue > 0);
        if (enemiesLeft() == 0 && !spawningMore && t > 2f) {
            if (phase == 1 && subWave < 3) { nextSubWave(); return; }
            AudioManager.play("seal_collect", 1.0f);
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.9f, 0.4f, 0.5f, 0.5f);
            subtitle = null;
            if (phase >= PHASES) {
                beginVictory();
            } else {
                phase++;
                wakeRetryPhase = phase;
                enter(State.PHASE_BANNER);
                win.requestShake(0.2f, 0.8f);
            }
        }
    }

    /** Trial 1: sub-wave flow + random skyfire keeps everyone moving. */
    private void tickSwarm(float dt) {
        if (enemiesLeft() == 0 && subWave < 3) { nextSubWave(); return; }
        lightningTimer -= dt;
        if (lightningTimer <= 0f) {
            lightningTimer = 2.2f + rng.nextFloat() * 1.5f;
            skyfire();
        }
    }

    /** A bolt of violet skyfire crashes onto a random arena spot — hurts everyone near it. */
    private void skyfire() {
        double a = rng.nextDouble() * Math.PI * 2;
        float r = rng.nextFloat() * (liveRadius - 6f);
        float x = ARENA_X + (float) Math.cos(a) * r;
        float z = ARENA_Z + (float) Math.sin(a) * r;
        win.fxBolt(x, ARENA_Y + 40f, z, 0f, -1f, 0f, 40f, 0.5f, 0.35f, 2.2f, 0.8f, 2.9f);
        win.fxBurst(x, ARENA_Y + 1f, z, 0.3f, 3.5f, 0.4f, 2.2f, 0.8f, 2.9f);
        win.fxRing(x, ARENA_Y + 0.4f, z, 0.4f, 5f, 0.5f, 2.0f, 0.7f, 2.8f);
        win.requestShake(0.10f, 0.25f);
        // Splash: damages enemies AND the player — stay alert.
        for (Enemy e : win.enemyManager.getEnemies()) {
            if (!e.alive) continue;
            float dx = e.position.x - x, dz = e.position.z - z;
            if (dx * dx + dz * dz < 16f) e.applyDamage(40f);
        }
        hurtPlayerNear(x, z, 4f, 10f);
    }

    /**
     * Trial 2: three NAMED mini-bosses, each with its own attack —
     *   VORGAR (golem)    leaps onto your head, slam on landing
     *   COLOSSUS (zombie) strides fast, erupts radial rock novas
     *   GLUTT (slime)     bounds around, spits aimed volleys
     * — plus rolling stomp shockwaves from everyone.
     */
    private void tickGiants(float dt) {
        if (giantQueue > 0) {
            giantEntryTimer -= dt;
            if (giantEntryTimer <= 0f) {
                int idx = 3 - giantQueue;
                double a = idx * Math.PI * 2 / 3;
                Enemy.Type tt = idx == 0 ? Enemy.Type.GOLEM : idx == 1 ? Enemy.Type.ZOMBIE : Enemy.Type.SLIME;
                float size = idx == 0 ? 3.0f : idx == 1 ? 3.2f : 3.5f;
                Enemy g = spawnGiant(a, 22f, tt, size, 8f);
                if (g != null) giants.add(g);
                say(GIANT_NAMES[idx]);
                giantQueue--;
                giantEntryTimer = 2.4f;
            }
        }
        tickStomps(dt, 5.5f);

        // Per-giant signature attacks
        for (int i = 0; i < giants.size() && i < giantAbility.length; i++) {
            Enemy g = giants.get(i);
            if (g == null || !g.alive) continue;
            giantAbility[i] -= dt;
            if (giantAbility[i] > 0f) continue;
            switch (i) {
                case 0 -> { giantAbility[i] = 6.0f; startLeap(g, 7f, 16f); }       // VORGAR
                case 1 -> { giantAbility[i] = 7.0f; nova(g, 10, 12f, 8.5f); }      // COLOSSUS
                case 2 -> { giantAbility[i] = 4.5f; volley(g, 3, 12f); }           // GLUTT
            }
        }
    }

    /** Every living giant periodically slams the ground: expanding ring, real damage. */
    private void tickStomps(float dt, float interval) {
        stompTimer -= dt;
        if (stompTimer > 0f) return;
        stompTimer = interval + rng.nextFloat() * 1.5f;
        for (Enemy g : giants) {
            if (g == null || !g.alive) continue;
            float x = g.position.x, z = g.position.z;
            win.fxRing(x, ARENA_Y + 0.4f, z, 0.8f, 12f, 0.8f, 3.0f, 0.7f, 0.4f);
            win.fxRing(x, ARENA_Y + 0.8f, z, 0.5f, 8f, 0.6f, 3.2f, 1.2f, 0.5f);
            win.fxBurst(x, ARENA_Y + 1f, z, 0.5f, 4f, 0.5f, 2.6f, 0.9f, 0.5f);
            win.requestShake(0.22f, 0.5f);
            AudioManager.play("fall_light", 0.9f);
            // The shockwave: if the player is grounded within 7 blocks — launched + hurt.
            Vector3f p = win.player.position;
            float dx = p.x - x, dz = p.z - z;
            float distSq = dx * dx + dz * dz;
            if (distSq < 49f && p.y < ARENA_Y + 3f) {
                hurtPlayer(10f);
                win.player.setVelocityY(12f);   // jump the shockwave or get thrown
            }
        }
    }

    /**
     * Trial 4: EYES OF THE CRYSTAL — six immobile sentinels on the pillar tops.
     * Each one is shrouded in violet light and fires aimed volleys on its own
     * rhythm; the more you destroy, the angrier the survivors get.
     */
    private void tickSentinels(float dt) {
        int aliveCount = 0;
        for (Enemy s : giants) if (s != null && s.alive) aliveCount++;
        float rage = 1f + (6 - aliveCount) * 0.12f;   // survivors fire faster

        for (int i = 0; i < giants.size() && i < giantAbility.length; i++) {
            Enemy s = giants.get(i);
            if (s == null || !s.alive) continue;
            // Violet shroud so they read as glowing crystal eyes, not dummies
            win.fxBurst(s.position.x + (rng.nextFloat() - 0.5f) * 0.8f,
                        s.position.y + 1f + rng.nextFloat() * 1.2f,
                        s.position.z + (rng.nextFloat() - 0.5f) * 0.8f,
                        0.05f, 0.5f, 0.30f, 2.0f, 0.6f, 2.9f);
            // Aim-line glint toward the player just before firing
            giantAbility[i] -= dt * rage;
            if (giantAbility[i] < 0.5f && giantAbility[i] > 0f && ((int) (t * 10)) % 2 == 0) {
                Vector3f p = win.player.position;
                Vector3f dir = new Vector3f(p.x - s.position.x, p.y - s.position.y, p.z - s.position.z).normalize();
                win.fxBolt(s.position.x, s.position.y + 1.5f, s.position.z,
                        dir.x, dir.y, dir.z, 4f, 0.06f, 0.15f, 2.4f, 0.5f, 2.9f);
            }
            if (giantAbility[i] <= 0f) {
                giantAbility[i] = 4.2f;
                fireLaser(s, 3f);
            }
        }
    }

    /** Trial 5: crumbling floor + geysers that erupt where you linger + a pouncing spider. */
    private void tickFloor(float dt) {
        if (liveRadius > 16) {
            crumbleTimer -= dt;
            if (crumbleTimer <= 0f) { crumbleTimer = 6f; crumbleRing(); }
        }
        tickStomps(dt, 8f);

        // The giant spider POUNCES across the arena
        if (!giants.isEmpty()) {
            giantAbility[0] -= dt;
            if (giantAbility[0] <= 0f && giants.get(0) != null && giants.get(0).alive) {
                giantAbility[0] = 6.5f;
                startLeap(giants.get(0), 6f, 14f);
            }
        }

        geyserTimer -= dt;
        if (geyserTimer <= 0f) {
            geyserTimer = 3.2f;
            // Telegraph a geyser at the player's CURRENT position — keep moving.
            Vector3f p = win.player.position;
            geysers.add(new float[]{ p.x, p.z, 1.1f });
        }
        for (int i = geysers.size() - 1; i >= 0; i--) {
            float[] g = geysers.get(i);
            g[2] -= dt;
            if (g[2] > 0f) {
                // Warning: pulsing ring on the ground
                win.fxRing(g[0], ARENA_Y + 0.3f, g[1], 2.2f, 2.6f, 0.12f, 2.8f, 0.5f, 0.4f);
            } else {
                // ERUPTION
                win.fxBolt(g[0], ARENA_Y, g[1], 0f, 1f, 0f, 14f, 0.7f, 0.5f, 2.8f, 0.8f, 0.4f);
                win.fxBurst(g[0], ARENA_Y + 2f, g[1], 0.4f, 4f, 0.5f, 2.6f, 0.7f, 0.4f);
                win.fxRing(g[0], ARENA_Y + 0.4f, g[1], 0.5f, 6f, 0.5f, 2.8f, 0.8f, 0.4f);
                win.requestShake(0.18f, 0.4f);
                Vector3f p = win.player.position;
                float dx = p.x - g[0], dz = p.z - g[1];
                if (dx * dx + dz * dz < 9f) {
                    hurtPlayer(12f);
                    win.player.setVelocityY(16f);   // launched skyward — smash back down!
                }
                geysers.remove(i);
            }
        }
    }

    /**
     * Trial 5: the Avatar — a real boss.
     *   Entrance: falls from the sky, arena-shaking landing slam.
     *   Attack cycle (one every few seconds, telegraphed callouts):
     *     BARRAGE  crystal strikes ring the player, detonate after a beat
     *     NOVA     a full ring of projectiles bursts outward
     *     LEAP     the Avatar launches itself onto your position
     *     VOLLEY   lobbed boulders lead your movement
     *   Adds at 66%%, enrage below 33%% (faster cycle, more of everything).
     */
    private void tickAvatar(float dt) {
        if (boss == null) return;

        // ── ENTRANCE: the Avatar plummets into the arena ──
        if (bossEntryPending) {
            // Falling trail
            win.fxBolt(boss.position.x, boss.position.y + 4f, boss.position.z,
                    0f, -1f, 0f, 8f, 0.4f, 0.3f, 2.4f, 0.7f, 2.9f);
            if (boss.position.y <= ARENA_Y + 3f) {
                bossEntryPending = false;
                landSlam(boss.position.x, boss.position.z, 9f, 12f);
                ScreenEffectManager.INSTANCE.flash(0.8f, 0.4f, 1.0f, 0.6f, 0.7f);
                win.requestShake(0.5f, 1.4f);
                say("THE  AVATAR  OF  THE  CRYSTAL.");
            }
            return;   // no attacks until it lands
        }

        float frac = bossFrac();
        if (!adds66 && frac > 0f && frac < 0.66f) {
            adds66 = true;
            for (int i = 0; i < 3; i++) spawnAt(i / 3.0 * Math.PI * 2, 20f, Enemy.Type.ZOMBIE);
            dropMercyHotdogs(3);
            say("THE  AVATAR  CALLS  FOR  AID.");
            ScreenEffectManager.INSTANCE.flash(0.8f, 0.3f, 1.0f, 0.4f, 0.5f);
        }
        if (!adds33 && frac > 0f && frac < 0.33f) {
            adds33 = true;
            enraged = true;
            boss.speedMul = 3.2f;
            for (int i = 0; i < 3; i++) spawnAt(i / 3.0 * Math.PI * 2 + 0.5, 22f, Enemy.Type.SLIME);
            dropMercyHotdogs(3);
            say("THE  AVATAR  RAGES.");
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.15f, 0.1f, 0.55f, 0.8f);
            win.requestShake(0.3f, 1.2f);
        }

        // ── Meteor PULSES: a few seconds of skyfall, then a breather ──
        bossMeteorTimer -= dt;
        if (bossMeteorTimer <= 0f) {
            bossMeteorTimer = enraged ? 9f : 13f;
            win.startMeteorStorm(3.5f);
        }

        // The arena tightens around the duel.
        if (liveRadius > 18) {
            crumbleTimer -= dt;
            if (crumbleTimer <= 0f) { crumbleTimer = enraged ? 9f : 13f; crumbleRing(); }
        }
        tickStomps(dt, enraged ? 6f : 8f);

        // ── ATTACK CYCLE ──
        bossAttackTimer -= dt;
        if (bossAttackTimer <= 0f && boss.alive) {
            bossAttackTimer = enraged ? 4.5f : 6.5f;
            switch (bossAttackIdx % 4) {
                case 0 -> {   // CRYSTAL BARRAGE
                    Vector3f p = win.player.position;
                    int shots = enraged ? 5 : 3;
                    for (int i = 0; i < shots; i++) {
                        double a = rng.nextDouble() * Math.PI * 2;
                        float r = 1.5f + rng.nextFloat() * 5f;
                        barrage.add(new float[]{ p.x + (float) Math.cos(a) * r, ARENA_Y,
                                                 p.z + (float) Math.sin(a) * r, 1.2f });
                    }
                    win.fxBurst(boss.position.x, boss.position.y + 6f, boss.position.z,
                            0.5f, 5f, 0.5f, 2.2f, 0.6f, 2.9f);
                }
                case 1 -> nova(boss, enraged ? 16 : 12, 9f, 9f);           // RADIAL NOVA
                case 2 -> startLeap(boss, 9f, 12f);                        // LEAP SLAM
                case 3 -> volley(boss, enraged ? 4 : 3, 10f);              // AIMED VOLLEY
            }
            bossAttackIdx++;
        }

        // ── Barrage strikes: telegraph rings, then detonation ──
        for (int i = barrage.size() - 1; i >= 0; i--) {
            float[] b = barrage.get(i);
            b[3] -= dt;
            if (b[3] > 0f) {
                win.fxRing(b[0], ARENA_Y + 0.3f, b[2], 1.6f, 2.0f, 0.10f, 2.2f, 0.5f, 2.8f);
            } else {
                win.fxBurst(b[0], ARENA_Y + 1f, b[2], 0.3f, 3.2f, 0.4f, 2.4f, 0.6f, 2.9f);
                win.fxBolt(b[0], ARENA_Y + 12f, b[2], 0f, -1f, 0f, 12f, 0.4f, 0.3f, 2.2f, 0.6f, 2.9f);
                hurtPlayerNear(b[0], b[2], 2.2f, 10f);
                barrage.remove(i);
            }
        }
    }

    /** The crystal offers mercy: hotdogs scatter near the player mid-bossfight. */
    private void dropMercyHotdogs(int n) {
        Vector3f p = win.player.position;
        for (int i = 0; i < n; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            float r = 3f + rng.nextFloat() * 4f;
            win.enemyManager.pendingFoodDrops.add(new float[]{
                    p.x + (float) Math.cos(a) * r, ARENA_Y + 1.5f, p.z + (float) Math.sin(a) * r });
        }
    }

    /** Apply scripted damage to the player (respects post-revival immunity). */
    private void hurtPlayer(float dmg) {
        // ADD "|| win.player.abilities.isKamui" to the if-statement!
        if (win.immunityTimer > 0f || win.showcaseMode || win.player.abilities.isKamui) return;
        win.player.health -= dmg;
        win.damageFlashTimer = 0.4f;
        ScreenEffectManager.INSTANCE.flash(1f, 0.1f, 0.05f, 0.35f, 0.18f);
        if (win.player.health <= 0f) { win.player.health = 0f; onPlayerDeath(); }
    }

    private void hurtPlayerNear(float x, float z, float radius, float dmg) {
        Vector3f p = win.player.position;
        float dx = p.x - x, dz = p.z - z;
        if (dx * dx + dz * dz < radius * radius && p.y < ARENA_Y + 4f) hurtPlayer(dmg);
    }

    private void keepPlayerOnArena() {
        if (win.player.position.y < ARENA_Y - 30) {
            win.player.health = 0f;
            onPlayerDeath();
        }
    }

    /** The floor betrays you: the outer annulus — floor, walls, towers — is erased. */
    private void crumbleRing() {
        int cx = (int) ARENA_X, cz = (int) ARENA_Z;
        int newR = liveRadius - 5;
        for (int dx = -liveRadius; dx <= liveRadius; dx++) {
            for (int dz = -liveRadius; dz <= liveRadius; dz++) {
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d > liveRadius || d <= newR) continue;
                for (int dyy = -8; dyy <= 20; dyy++)
                    if (win.world.getBlock(cx + dx, ARENA_Y + dyy, cz + dz).isSolid())
                        win.world.setBlock(cx + dx, ARENA_Y + dyy, cz + dz, Block.AIR);
                if (rng.nextInt(10) == 0)
                    win.fxBurst(cx + dx, ARENA_Y + 1, cz + dz, 0.2f, 2f, 0.5f, 2.4f, 0.6f, 0.7f);
            }
        }
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

    private void resetArena() {
        for (Enemy e : win.enemyManager.getEnemies()) e.alive = false;
        giants.clear(); geysers.clear(); barrage.clear(); boss = null;
        arenaBuilt = false;
        liveRadius = ARENA_R;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TRIAL 3 — THE BIRD
    // ═══════════════════════════════════════════════════════════════════════
    private void tickFlappy(float dt) {
        if (win.player.testMovement.flappyScore >= 15) {
            win.finaleExitFlappy(ARENA_X, ARENA_Y + 2f, ARENA_Z);
            arenaBuildDelay = 0.9f;
            arenaBuilt = false;
            phase = 4;
            wakeRetryPhase = 4;
            enter(State.PHASE_BANNER);
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.9f, 0.4f, 0.6f, 0.6f);
            AudioManager.play("seal_collect", 1.0f);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DEFEAT — wake up outside the portal
    // ═══════════════════════════════════════════════════════════════════════
    public void onPlayerDeath() {
        if (state == State.DEFEAT_FADE || state == State.DEFEAT_WAKE) return;
        if (!inTrialStates()) {
            // Post-victory: you have transcended dying. Shrug it off.
            win.player.health = win.player.maxHealth;
            return;
        }
        wakeRetryPhase = Math.max(1, phase);
        enter(State.DEFEAT_FADE);
        win.stopMeteorStorm();
        AudioManager.stopContinuous("kamui_duration");
        AudioManager.stopContinuous("kamui_distortion");
        ScreenEffectManager.INSTANCE.desaturate(0.85f, 1.2f);
    }

    private void tickDefeatFade(Camera camera, float dt) {
        if (t >= 1.4f) {
            resetArena();
            win.player.position.set(SPAWN_X + 7f, portalY + 1.5f, SPAWN_Z + 4f);
            win.player.setVelocityY(0f);
            win.player.health = win.player.maxHealth;
            win.player.mana   = win.player.maxMana;
            win.player.highestY = portalY + 1.5f;
            camera.yaw = (float) Math.toRadians(205);
            camera.pitch = -0.18f;
            enter(State.DEFEAT_WAKE);
        }
    }

    /**
     * The wake-up. Timeline:
     *   0.0-4.2  eyes closed → three slow blinks (curved lids drawn by HUD)
     *   4.2-7.0  un-tilt from 75° roll, rise from the dirt to standing
     *   5.5+     "The crystal is not done with you."
     *   9.0      control returns; the portal still hums behind you
     * The camera also sways drowsily the whole time.
     */
    private void tickDefeatWake(Camera camera, float dt) {
        float standT = clamp01((t - 4.2f) / 2.8f);
        float ease = standT * standT * (3f - 2f * standT);

        win.player.externalRoll = (float) Math.toRadians(75) * (1f - ease);
        float eyeY = 0.32f + (1.62f - 0.32f) * ease
                   + (float) Math.sin(t * 2.1) * 0.025f * (1f - ease);   // groggy breathing
        Vector3f p = win.player.position;
        camera.position.set(p.x, p.y + eyeY, p.z);
        // Drowsy sway — fades out as you find your feet.
        float sway = (1f - ease) * 0.0022f;
        camera.yaw   += (float) Math.sin(t * 0.7) * sway;
        camera.pitch += (float) Math.sin(t * 1.13) * sway * 0.6f;

        if (t >= 5.5f && subtitle == null)
            say("The crystal is not done with you.   Return when ready.");

        if (t >= 9.0f) {
            win.player.externalRoll = 0f;
            subtitle = null;
            ScreenEffectManager.INSTANCE.desaturate(0f, 1.2f);
            enter(State.SUMMONS);
        }
    }

    /** Eyelid coverage 0..1 (1 = shut). Three slow blinks, then open for good. */
    public float wakeEyelid() {
        if (state == State.DEFEAT_FADE) return Math.min(1f, t / 0.9f);
        if (state != State.DEFEAT_WAKE) return 0f;
        if (t < 1.2f)  return 1f;                                  // dark
        if (t < 1.75f) return 1f - 0.55f * (t - 1.2f) / 0.55f;     // first crack of light
        if (t < 2.35f) return 0.45f + 0.55f * (t - 1.75f) / 0.6f;  // slide shut again
        if (t < 3.0f)  return 1f - 0.8f * (t - 2.35f) / 0.65f;     // second, wider
        if (t < 3.3f)  return 0.2f + 0.8f * (t - 3.0f) / 0.3f;     // quick reflex shut
        if (t < 4.4f)  return Math.max(0f, 1f - (t - 3.3f) / 1.1f);// open for good
        return 0f;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  VICTORY — the dimension tears apart
    // ═══════════════════════════════════════════════════════════════════════
    private void beginVictory() {
        endKills = win.enemyManager.totalKills;
        endTime  = (float) org.lwjgl.glfw.GLFW.glfwGetTime() - RunRecords.INSTANCE.runStartTime;
        enter(State.VICTORY_TEAR);
        warpTeleported = false;
        win.stopMeteorStorm();
        for (Enemy e : win.enemyManager.getEnemies()) e.alive = false;
        ScreenEffectManager.INSTANCE.hitStopSeconds(0.5f);
        ScreenEffectManager.INSTANCE.flash(1f, 1f, 1f, 0.85f, 0.8f);
        AudioManager.play("seal_collect", 1.0f);
        win.requestShake(0.45f, 3.0f);
    }

    private void tickVictoryTear(Camera camera, float dt) {
        int cx = (int) ARENA_X, cz = (int) ARENA_Z;
        // Blocks rip outward in growing violence
        int rips = 14 + (int) (t * 10);
        for (int i = 0; i < rips; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            float r = rng.nextFloat() * liveRadius;
            int bx = cx + (int) (Math.cos(a) * r), bz = cz + (int) (Math.sin(a) * r);
            int by = ARENA_Y - 1 - rng.nextInt(3) + (rng.nextInt(4) == 0 ? rng.nextInt(18) : 0);
            if (win.world.getBlock(bx, by, bz).isSolid()) {
                win.world.setBlock(bx, by, bz, Block.AIR);
                if (rng.nextInt(3) == 0)
                    win.fxBolt(bx, by + 1, bz,
                            (float) Math.cos(a) * 0.5f, 1f, (float) Math.sin(a) * 0.5f,
                            6f, 0.16f, 0.7f, 2.6f, 0.7f, 2.8f);
            }
        }
        if (((int) (t * 6)) % 2 == 0)
            win.fxRing(cx, ARENA_Y + 2f, cz, 1f + t * 6f, 3f + t * 9f, 0.4f, 2.6f, 0.8f, 2.9f);
        camera.position.set(win.player.position.x, win.player.position.y + 1.62f, win.player.position.z);

        if (t >= 3.8f && !warpTeleported) {
            warpTeleported = true;
            win.player.position.set(SPAWN_X + 4f, portalY + 1f, SPAWN_Z + 9f);
            win.player.setVelocityY(0f);
            win.player.highestY = portalY + 1f;
            win.player.health = win.player.maxHealth;
            removePortal();
            win.world.clearAllChunks();
        }
        if (t >= 5.6f) {
            enter(State.MADE_IN_HEAVEN);
            say("You passed the trial.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MADE IN HEAVEN — the world races past
    // ═══════════════════════════════════════════════════════════════════════
    private void tickMadeInHeaven(Camera camera, float dt) {
        win.player.health = win.player.maxHealth;

        if (t >= 4.0f  && t < 4.1f)  say("But power like yours was never meant to stay.");
        if (t >= 8.0f  && t < 8.1f)  say("It transcends time.   It transcends existence.");
        if (t >= 12.0f && t < 12.1f) say("Watch.   The world already moves on without you.");

        goldenWind(dt, 0.12f);
        // Gentle foreshocks
        heaveTimer -= dt;
        if (heaveTimer <= 0f) { heaveTimer = 0.5f; terrainHeave(1); }

        if (t >= 15f) {
            enter(State.UPHEAVAL);
            say("The world can no longer hold you.");
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.85f, 0.4f, 0.5f, 0.8f);
            win.requestShake(0.35f, 2.0f);
        }
    }

    private void goldenWind(float dt, float interval) {
        windTimer -= dt;
        if (windTimer > 0f) return;
        windTimer = interval;
        Vector3f p = win.player.position;
        for (int i = 0; i < 2; i++) {
            float wy = p.y + rng.nextFloat() * 10f - 1f;
            float wx = p.x + (rng.nextFloat() - 0.5f) * 50f;
            float wz = p.z + (rng.nextFloat() - 0.5f) * 50f;
            win.fxBolt(wx, wy, wz, 0.92f, 0.10f, 0.36f, 8f, 0.08f, 0.9f, 3.0f, 2.2f, 0.5f);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UPHEAVAL — the planet itself answers
    // ═══════════════════════════════════════════════════════════════════════
    private void tickUpheaval(Camera camera, float dt) {
        win.player.health = win.player.maxHealth;

        if (t >= 3.5f && t < 3.6f)  say("Mountains bow.   Valleys rise.");
        if (t >= 7.5f && t < 7.6f)  say("Seasons blur into heartbeats.");
        if (t >= 11.0f && t < 11.1f) say("Rise.");

        // Violent, constant ground-shake and gold light
        win.requestShake(0.10f, 0.3f);
        goldenWind(dt, 0.08f);

        // Heavy terrain surges — several large domes per pulse
        heaveTimer -= dt;
        if (heaveTimer <= 0f) {
            heaveTimer = 0.22f;
            terrainHeave(3);
            if (rng.nextInt(3) == 0) crystalSpike();
        }
        // Periodic blinding pulses
        flashTimer -= dt;
        if (flashTimer <= 0f) {
            flashTimer = 2.6f;
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.9f, 0.55f, 0.30f, 0.5f);
        }

        if (t >= 14f) {
            enter(State.ASCENSION);
            subtitle = null;
            AudioManager.play("seal_collect", 1.0f);
            ScreenEffectManager.INSTANCE.flash(1.0f, 0.92f, 0.55f, 0.6f, 1.2f);
        }
    }

    /** Surge whole hillsides: dome-shaped rises and sinkholes, n at a time. */
    private void terrainHeave(int count) {
        Vector3f p = win.player.position;
        for (int n = 0; n < count; n++) {
            double a = rng.nextDouble() * Math.PI * 2;
            float r = 20f + rng.nextFloat() * 70f;
            int hx = (int) (p.x + Math.cos(a) * r), hz = (int) (p.z + Math.sin(a) * r);
            int sy = 300;
            while (sy > 120 && !win.world.getBlock(hx, sy, hz).isSolid()) sy--;
            boolean up = rng.nextBoolean();
            int radius = 3 + rng.nextInt(3);            // dome footprint 3..5
            int height = 4 + rng.nextInt(7);            // 4..10 blocks of change
            for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++) {
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d > radius) continue;
                    int local = Math.round(height * (1f - d / radius));
                    if (local <= 0) continue;
                    if (up) {
                        for (int dyy = 1; dyy <= local; dyy++)
                            win.world.setBlock(hx + dx, sy + dyy, hz + dz, Block.STONE);
                    } else {
                        for (int dyy = 0; dyy < local; dyy++)
                            win.world.setBlock(hx + dx, sy - dyy, hz + dz, Block.AIR);
                    }
                }
            win.fxBurst(hx, sy + (up ? height : 0), hz, 0.5f, 3.5f, 0.7f,
                    up ? 1.5f : 1.0f, up ? 1.2f : 0.9f, 0.8f);
            win.fxRing(hx, sy + 1f, hz, 1f, (float) radius * 2.2f, 0.5f, 1.3f, 1.0f, 0.8f);
        }
    }

    /** A crystal needle erupts from the racing landscape. */
    private void crystalSpike() {
        Vector3f p = win.player.position;
        double a = rng.nextDouble() * Math.PI * 2;
        float r = 25f + rng.nextFloat() * 55f;
        int hx = (int) (p.x + Math.cos(a) * r), hz = (int) (p.z + Math.sin(a) * r);
        int sy = 300;
        while (sy > 120 && !win.world.getBlock(hx, sy, hz).isSolid()) sy--;
        int height = 8 + rng.nextInt(10);
        for (int dyy = 1; dyy <= height; dyy++) {
            Block b = dyy > height - 3 ? Block.CRYSTAL_AMETHYST : Block.CRYSTAL_BASE;
            win.world.setBlock(hx, sy + dyy, hz, b);
            if (dyy < height / 2) {
                win.world.setBlock(hx + 1, sy + dyy, hz, Block.CRYSTAL_BASE);
                win.world.setBlock(hx, sy + dyy, hz + 1, Block.CRYSTAL_BASE);
            }
        }
        win.fxBolt(hx, sy + height, hz, 0f, 1f, 0f, 8f, 0.3f, 0.6f, 2.0f, 0.7f, 2.8f);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ASCENSION — past the sky, past the stars
    // ═══════════════════════════════════════════════════════════════════════
    private void tickAscension(Camera camera, float dt) {
        // Accelerating rise: gentle lift-off → screaming climb
        float speed = 2.5f + t * t * 1.1f;
        win.player.position.y += speed * dt;
        win.player.setVelocityY(0f);
        win.player.highestY = win.player.position.y;
        Vector3f p = win.player.position;
        camera.position.set(p.x, p.y + 1.62f, p.z);

        if (t >= 2.0f && t < 2.1f)  say("Rise.");
        if (t >= 6.0f && t < 6.1f)  say("Past the sky.");
        if (t >= 10.0f && t < 10.1f) say("Past the stars.");
        if (t >= 14.0f && t < 14.1f) say("Past  everything.");

        // Golden double-helix wrapping the climb
        double a1 = t * 7.0;
        for (int k = 0; k < 2; k++) {
            double a = a1 + k * Math.PI;
            win.fxBurst(p.x + (float) Math.cos(a) * 2.4f, p.y + ((t * 2f) % 3f),
                        p.z + (float) Math.sin(a) * 2.4f,
                        0.05f, 0.7f, 0.35f, 3.0f, 2.2f, 0.6f);
        }
        // Rings of light shooting past as speed builds
        if (((int) (t * 5)) % 2 == 0)
            win.fxRing(p.x, p.y - 2f, p.z, 0.8f, 5f + t * 0.4f, 0.4f, 3.0f, 2.0f, 0.6f);
        // Vertical light streaks racing downward past the camera
        for (int i = 0; i < 2; i++) {
            double aa = rng.nextDouble() * Math.PI * 2;
            float rr = 5f + rng.nextFloat() * 8f;
            win.fxBolt(p.x + (float) Math.cos(aa) * rr, p.y + 9f + rng.nextFloat() * 5f,
                       p.z + (float) Math.sin(aa) * rr, 0f, -1f, 0f,
                       10f + t, 0.10f, 0.4f, 2.8f, 2.0f, 0.7f);
        }

        if (t >= 18f) {
            enter(State.WHITEOUT);
            AudioManager.play("seal_collect", 0.8f);
        }
    }

    /** White overlay 0..1 (HUD reads this during ASCENSION). */
    public float whiteout() {
        if (state == State.ASCENSION) return clamp01((t - 13f) / 5f);
        if (state == State.WHITEOUT)  return 1f;
        return 0f;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  WHITEOUT + THE END
    // ═══════════════════════════════════════════════════════════════════════
    /** The last words, shown on pure white. */
    public String whiteoutLine() {
        if (state != State.WHITEOUT) return null;
        if (t < 3.2f)   return "Your descent is over.";
        if (t < 6.4f)   return "Now begins your ascension.";
        if (t < 10.0f)  return "Time forgets you.   Space releases you.";
        if (t < 13.5f)  return "You are no longer the hunted.   You are no longer even human.";
        return "YOU  HAVE  BECOME  E T E R N A L";
    }

    /** True when the final ETERNAL line is showing (HUD draws it bigger, in gold). */
    public boolean whiteoutClimax() { return state == State.WHITEOUT && t >= 13.5f; }

    private void tickWhiteout(float dt) {
        if (t >= 17.5f) enter(State.THE_END);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }
}
