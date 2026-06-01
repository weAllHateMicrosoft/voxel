package com.leaf.game.core;

import com.leaf.game.entity.Enemy;
import com.leaf.game.entity.EnemyManager;
import com.leaf.game.entity.Player;
import com.leaf.game.util.Camera;
import com.leaf.game.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A designed, staged onboarding tutorial.
 *
 * Philosophy
 * ──────────
 *  • Teach ONE thing per step, in order of increasing complexity:
 *      basics → melee combat → movement tricks → ranged → spatial → flight → sustain.
 *  • Difficulty ramps: the first "enemy" is a single practice dummy spawned right
 *    in front of you; later steps add more, then tougher/ranged types.
 *  • The player drives the pace — a step completes when the player actually DOES
 *    the thing (detected from real game state), not on a blind timer.
 *  • Nothing is a hard wall: every step has a generous safety timeout and the
 *    whole tutorial can be skipped, so no one ever gets stuck.
 *  • The infinite wave spawner stays OFF until graduation, so a newcomer is
 *    never swarmed mid-lesson.
 *
 * Detection
 * ─────────
 *  Cooldown abilities expose a 0..1 "ready" fraction (1 = ready). We snapshot it
 *  when a step begins; the player "used" the ability the moment the live fraction
 *  drops below that snapshot. Active abilities (Kamui, Heal, Drone, Grab) expose a
 *  boolean we watch directly. Movement/look come from position and camera state.
 */
public class TutorialManager {

    // ── One tutorial step ──────────────────────────────────────────────────────
    private static final class Step {
        final String title, instruction, key;
        final Consumer<Ctx> onEnter;     // optional: spawn enemies, etc.
        final Predicate<Ctx> done;        // completion test
        final float timeout;              // seconds; <=0 = no auto-advance

        Step(String title, String instruction, String key,
             Consumer<Ctx> onEnter, Predicate<Ctx> done, float timeout) {
            this.title = title; this.instruction = instruction; this.key = key;
            this.onEnter = onEnter; this.done = done; this.timeout = timeout;
        }
    }

    /** Mutable context handed to step lambdas so they can read live game state. */
    private static final class Ctx {
        Player player; EnemyManager enemies; Window win; Camera camera; World world;
        float    stepTime;
        Vector3f enterPos = new Vector3f();
        int      enterKills;
        boolean  flag;       // generic one-shot latch
        float    snapshot;   // value captured at first detector call
    }

    private final List<Step> steps = new ArrayList<>();
    private int index = -1;
    private boolean finished = false, skipped = false;
    private final Ctx ctx = new Ctx();

    public TutorialManager(Player player, EnemyManager enemies, Window win,
                           Camera camera, World world) {
        ctx.player = player; ctx.enemies = enemies; ctx.win = win;
        ctx.camera = camera; ctx.world = world;
        buildSteps();
    }

    // ── HUD-facing state ───────────────────────────────────────────────────────
    public boolean isActive()    { return !finished && !skipped; }
    public boolean isFinished()  { return finished || skipped; }
    public int     stepNumber()  { return Math.max(0, index) + 1; }
    public int     stepCount()   { return steps.size(); }
    public String  title()       { return cur() == null ? "" : cur().title; }
    public String  instruction() { return cur() == null ? "" : cur().instruction; }
    public String  keyHint()     { return cur() == null ? null : cur().key; }

    private Step cur() {
        return (index >= 0 && index < steps.size()) ? steps.get(index) : null;
    }

    /** Begin the tutorial. Call once when the world is ready. */
    public void start() {
        index = -1; finished = false; skipped = false;
        ctx.enemies.wavesEnabled = false;   // keep the world calm during lessons
        advance();
    }

    /** Skip everything (player chose to). Turns on real waves. */
    public void skip() {
        if (finished || skipped) return;
        skipped = true;
        ctx.enemies.wavesEnabled = true;
    }

    /** Per-frame tick. Returns true while still running. */
    public boolean update(float dt) {
        if (finished || skipped) return false;
        Step s = cur();
        if (s == null) { finish(); return false; }

        ctx.stepTime += dt;
        boolean complete = false;
        try { complete = s.done.test(ctx); }
        catch (Exception ignored) { /* never let the tutorial crash the game */ }

        if (complete || (s.timeout > 0f && ctx.stepTime >= s.timeout)) advance();
        return true;
    }

    private void finish() {
        finished = true;
        ctx.enemies.wavesEnabled = true;   // graduation → endless survival begins
    }

    private void advance() {
        index++;
        if (index >= steps.size()) { finish(); return; }
        ctx.stepTime  = 0f;
        ctx.enterPos.set(ctx.player.position);
        ctx.enterKills = ctx.enemies.totalKills;
        ctx.flag = false;
        ctx.snapshot = 0f;
        Step s = cur();
        if (s.onEnter != null) {
            try { s.onEnter.accept(ctx); } catch (Exception ignored) {}
        }
    }

    // ── Detection helpers ───────────────────────────────────────────────────────
    private static boolean movedFar(Ctx c, float d) {
        return c.player.position.distance(c.enterPos) > d;
    }
    private static boolean killedSince(Ctx c, int n) {
        return c.enemies.totalKills - c.enterKills >= n;
    }
    /** True once a cooldown fraction has dipped below its entry snapshot (ability fired). */
    private static boolean usedCooldown(Ctx c, float liveFrac) {
        if (!c.flag) { c.snapshot = liveFrac; c.flag = true; return false; }
        return liveFrac < c.snapshot - 0.15f;
    }

    // ── The lesson plan ──────────────────────────────────────────────────────────
    private void buildSteps() {
        final World w = ctx.world;

        // 1 ── LOOK
        steps.add(new Step("Look Around",
            "Move your MOUSE to look around.", "Mouse",
            null,
            c -> {
                if (!c.flag) { c.snapshot = c.camera.yaw; c.flag = true; return false; }
                return Math.abs(angleDiff(c.camera.yaw, c.snapshot)) > 1.2f;
            }, 12f));

        // 2 ── MOVE
        steps.add(new Step("Move",
            "Walk with  [W] [A] [S] [D].", "WASD",
            null, c -> movedFar(c, 4f), 14f));

        // 3 ── SPRINT
        steps.add(new Step("Sprint",
            "Double-tap [W] and keep holding to SPRINT.", "W W",
            null, c -> c.player.isSprinting() || movedFar(c, 18f), 14f));

        // 4 ── JUMP
        steps.add(new Step("Jump",
            "Press [SPACE] to jump.", "Space",
            null,
            c -> { if (!c.player.isOnGround()) c.flag = true; return c.flag; }, 10f));

        // 5 ── FIRST DUMMY → SLASH
        steps.add(new Step("Your First Enemy",
            "A practice dummy appeared! Walk up and press [F] to SLASH it.", "F",
            c -> c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.ZOMBIE, 1, 7f),
            c -> killedSince(c, 1), 45f));

        // 6 ── DASH
        steps.add(new Step("Dash",
            "Press [Q] to DASH — a fast burst in your move direction.", "Q",
            null, c -> usedCooldown(c, c.player.abilities.getDashCooldownFrac()), 16f));

        // 7 ── TWO ENEMIES
        steps.add(new Step("Hold the Line",
            "Two more incoming. Dash in and SLASH [F] to clear them both.", "F",
            c -> c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.ZOMBIE, 2, 9f),
            c -> c.enemies.aliveCount() == 0, 60f));

        // 8 ── SNIPE
        steps.add(new Step("Snipe",
            "A distant target. Hold [C] to charge a crystal bolt, release to fire.", "C",
            c -> c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.THROWER, 1, 22f),
            c -> killedSince(c, 1) || usedCooldown(c, c.player.attacks.getSnipeIconFrac()), 50f));

        // 9 ── LIGHTNING
        steps.add(new Step("Lightning",
            "Aim at the enemy and press [U] to call down LIGHTNING.", "U",
            c -> c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.ZOMBIE, 1, 12f),
            c -> usedCooldown(c, c.player.lightning.getCooldownFrac()) || killedSince(c, 1), 40f));

        // 10 ── GRAB & SLAM
        steps.add(new Step("Grab & Slam",
            "Get close, look at the enemy, press [O] to GRAB then SLAM it down.", "O",
            c -> c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.ZOMBIE, 1, 6f),
            c -> c.player.grab.isActive() || killedSince(c, 1), 45f));

        // 11 ── QUAGMIRE
        steps.add(new Step("Quagmire",
            "Press [M] to fire a mud wave that traps an enemy in place.", "M",
            c -> c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.ZOMBIE, 1, 14f),
            c -> c.win.quagmireCooldown > 0.01f || killedSince(c, 1), 40f));

        // 12 ── STONE PILLAR
        steps.add(new Step("Stone Pillar",
            "Press [K] — a stone spire erupts under you and launches you skyward.", "K",
            null, c -> usedCooldown(c, c.player.abilities.getPillarCooldownFrac()), 18f));

        // 13 ── POSITION SWAP
        steps.add(new Step("Position Swap",
            "Press [J] to instantly swap places with the nearest enemy.", "J",
            c -> c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.ZOMBIE, 1, 16f),
            c -> c.win.todoSwapCooldown > 0.01f || killedSince(c, 1), 40f));

        // 14 ── SEAL throw + warp
        steps.add(new Step("Minato's Seal",
            "Press [H] to throw a teleport seal, then [B] to warp to it.", "H  then  B",
            null,
            c -> {
                if (c.player.seals.getSealCount() > 0) c.flag = true; // seal thrown
                // complete once a seal exists AND the player teleported (moved far suddenly)
                return c.flag && movedFar(c, 6f);
            }, 35f));

        // 15 ── DRONE
        steps.add(new Step("Deploy Drone",
            "Press [X] to deploy your combat drone. It auto-fires at enemies.", "X",
            c -> c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.ZOMBIE, 1, 18f),
            c -> c.player.stand.isDeployed(), 25f));

        // 16 ── FLIGHT
        steps.add(new Step("Take Flight",
            "Double-tap [SPACE] to FLY. (Press [V] anytime to change flight mode.)", "Space x2",
            null, c -> c.player.debugMode, 25f));

        // 17 ── LAND
        steps.add(new Step("Land",
            "Double-tap [SPACE] again to stop flying and land safely.", "Space x2",
            null, c -> !c.player.debugMode, 25f));

        // 18 ── HEAL
        steps.add(new Step("Heal",
            "Hold [L] to channel HEALING. (You can't move while healing.)", "L",
            null,
            c -> c.player.abilities.isHealing
                 || c.player.health >= c.player.maxHealth - 0.01f, 22f));

        // 19 ── GRADUATION
        steps.add(new Step("Final Test",
            "Clear these three, and the real fight begins — endless waves. Good luck!", null,
            c -> {
                c.enemies.wavesEnabled = true;
                c.enemies.spawnTutorialEnemies(w, c.player.position, Enemy.Type.ZOMBIE, 3, 16f);
            },
            c -> killedSince(c, 3), 0f));
    }

    private static float angleDiff(float a, float b) {
        float d = a - b;
        while (d >  Math.PI) d -= (float) (2 * Math.PI);
        while (d < -Math.PI) d += (float) (2 * Math.PI);
        return d;
    }
}
