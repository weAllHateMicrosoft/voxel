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
 *      basics -> melee combat -> movement tricks -> ranged -> spatial -> flight -> sustain.
 *  • Difficulty ramps: the first "enemy" is a single practice dummy spawned right
 *    in front of you; later steps add more, then tougher/ranged types.
 *  • The player drives the pace  -  a step completes when the player actually DOES
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
        ctx.enemies.wavesEnabled = true;   // graduation -> endless survival begins
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

        // A short but complete tutorial that teaches movement, the three starting
        // abilities, and the Sniper before handing off to the Voyage (Flight + beams).

        // 1 ── MOVE + LOOK (combined)
        steps.add(new Step("Move",
            "Use the MOUSE to look and  [W] [A] [S] [D]  to walk.", "WASD + Mouse",
            null, c -> movedFar(c, 4f), 9f));

        // 2 ── SLASH  —  grants on enter so the key is immediately usable
        steps.add(new Step("Runic Cleave",
            "Press [F] to swing the Runic Cleave  —  a wide melee arc. Try it!", "F",
            c -> c.player.progression.unlock(Progression.Ability.SLASH),
            c -> usedCooldown(c, c.player.attacks.getMeleeCooldownFrac()), 15f));

        // 3 ── DASH  —  grants on enter
        steps.add(new Step("Dash",
            "Press [Q] to Dash  —  an instant burst in your movement direction. Try it!", "Q",
            c -> c.player.progression.unlock(Progression.Ability.DASH),
            c -> usedCooldown(c, c.player.abilities.getDashCooldownFrac()), 12f));

        // 4 ── SNIPER  —  the ranged weapon already in slot 1
        steps.add(new Step("Your First Weapon",
            "Hold [C] (or Right-Click with the Sniper selected) to charge a crystal bolt, release to fire.", "C / RMB",
            null,
            c -> usedCooldown(c, c.player.attacks.getSnipeIconFrac()), 14f));

        // 5 ── BATTLE BEGINS — auto-advances and lets finish() turn waves on.
        // FLIGHT is granted later, when the Voyage opens after wave 6.
        steps.add(new Step("Battle Begins",
            "Enemies are coming!  Defeat each wave to unlock new powers.  Clear 6 waves and the crystal will give you the sky.", "Fight!",
            null,
            c -> false, 4f));
    }

    private static float angleDiff(float a, float b) {
        float d = a - b;
        while (d >  Math.PI) d -= (float) (2 * Math.PI);
        while (d < -Math.PI) d += (float) (2 * Math.PI);
        return d;
    }
}
