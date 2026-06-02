package com.leaf.game.core;

import com.leaf.game.entity.Enemy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Per-ability hands-on tutorial steps.
 *
 * Each ability has an ordered list of Step objects.  The practice engine in
 * Window runs them sequentially — pausing the wave, showing the instruction,
 * waiting for the player to actually perform the action, then advancing.
 *
 * HOW TO EDIT:
 *   All text is in the forAbility() switch below — one block per ability.
 *   Each step has: title, instruction, key hint, done() predicate, optional
 *   onEnter() setup (e.g. spawn a dummy), optional doneText celebration, timeout.
 *
 * DETECTION helpers in Step.done() receive a StepCtx with:
 *   ctx.win          - the live Window (access player, abilities, seals, etc.)
 *   ctx.dummy()      - the current practice dummy Enemy, or null if none
 *   ctx.stepAge      - seconds the player has been on this step
 *   ctx.flag / snapshot  - general-purpose latches you can set once and reuse
 */
public class AbilityPractice {

    // ── Step context handed to every lambda ───────────────────────────────────
    public static class StepCtx {
        public Window  win;
        public float   stepAge;
        public boolean flag;        // single latch — set once, stays true
        public float   snapshot;    // record a value at step-start

        /** Convenience: the live DUMMY enemy (first alive one in the enemy list). */
        public Enemy dummy() {
            if (win == null) return null;
            return win.enemyManager.getEnemies().stream()
                    .filter(e -> e.alive && e.type == Enemy.Type.DUMMY)
                    .findFirst().orElse(null);
        }
    }

    // ── One tutorial step ─────────────────────────────────────────────────────
    public static class Step {
        public final String            title;
        public final String            instruction;
        public final String            keyHint;       // shown as a chip, can be null
        public final Predicate<StepCtx> done;
        public final Consumer<StepCtx>  onEnter;      // setup when step starts, can be null
        public final String            doneText;      // celebration line, can be null
        public final float             timeout;       // give up after this many seconds

        public Step(String title, String instruction, String keyHint,
                    Predicate<StepCtx> done, Consumer<StepCtx> onEnter,
                    String doneText, float timeout) {
            this.title       = title;
            this.instruction = instruction;
            this.keyHint     = keyHint;
            this.done        = done;
            this.onEnter     = onEnter;
            this.doneText    = doneText;
            this.timeout     = timeout;
        }
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private static Step step(String title, String instruction, String key,
                             Predicate<StepCtx> done, float timeout) {
        return new Step(title, instruction, key, done, null, null, timeout);
    }
    private static Step step(String title, String instruction, String key,
                             Predicate<StepCtx> done, Consumer<StepCtx> onEnter,
                             float timeout) {
        return new Step(title, instruction, key, done, onEnter, null, timeout);
    }
    private static Step step(String title, String instruction, String key,
                             Predicate<StepCtx> done, Consumer<StepCtx> onEnter,
                             String doneText, float timeout) {
        return new Step(title, instruction, key, done, onEnter, doneText, timeout);
    }

    /** Spawn one DUMMY ~5 blocks in front of the player at the same height. */
    public static void spawnDummy(StepCtx ctx) {
        Window win = ctx.win;
        // Kill any existing dummies first
        win.enemyManager.getEnemies().stream()
                .filter(e -> e.type == Enemy.Type.DUMMY)
                .forEach(e -> e.alive = false);
        // Use the player's current facing yaw (stored in the camera, which we don't have
        // direct access to — derive forward from position delta or just spawn due-north).
        // We expose it through Window.lastCameraYaw set each frame.
        float yaw = win.lastCameraYaw;
        float dx = (float) Math.sin(yaw);
        float dz = (float) Math.cos(yaw);
        float px = win.player.position.x + dx * 5f;
        float pz = win.player.position.z + dz * 5f;
        win.enemyManager.spawnAt(px, win.player.position.y + 1f, pz, Enemy.Type.DUMMY);
    }

    // ── True if the dummy took a hit this frame ───────────────────────────────
    private static boolean dummyFlashed(StepCtx ctx) {
        Enemy d = ctx.dummy();
        return d != null && d.hitFlashTimer > 0.05f;
    }

    // ── True if dummy health dropped below the snapshot ──────────────────────
    private static boolean dummyHpDropped(StepCtx ctx) {
        Enemy d = ctx.dummy();
        if (d == null) return false;
        if (!ctx.flag) { ctx.snapshot = d.health; ctx.flag = true; return false; }
        return d.health < ctx.snapshot - 0.5f;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PER-ABILITY DEFINITIONS  — edit instructions here
    // ═════════════════════════════════════════════════════════════════════════

    public static List<Step> forAbility(Progression.Ability a) {
        List<Step> s = new ArrayList<>();
        switch (a) {

        case SNIPE -> {
            s.add(step("SNIPE — aim",
                "A practice target has appeared ahead.\n" +
                "Look at it and hold  [ C ]  to charge your crystal bolt.\n" +
                "The longer you charge, the bigger the explosion.",
                "C",
                ctx -> ctx.win.player.attacks.getChargeFrac() > 0.05f,   // charging
                AbilityPractice::spawnDummy, 60f));
            s.add(step("SNIPE — fire",
                "Good — bolt is charged!\n" +
                "Release  [ C ]  to fire at the target.\n" +
                "Watch for the impact.",
                "release C",
                AbilityPractice::dummyFlashed,
                null, "Direct hit!", 20f));
        }

        case SLASH -> {
            s.add(step("SLASH — get close",
                "Slash is a short-range melee swing.\n" +
                "Walk toward the dummy — get within 3 blocks.\n" +
                "You can sprint with  [ W W ]  (double-tap).",
                "WASD",
                ctx -> {
                    Enemy d = ctx.dummy();
                    if (d == null) return false;
                    return ctx.win.player.position.distance(d.position) < 3.5f;
                },
                AbilityPractice::spawnDummy, 40f));
            s.add(step("SLASH — swing",
                "Now press  [ F ]  to swing.\n" +
                "It hits everything in a wide arc in front of you.",
                "F",
                AbilityPractice::dummyHpDropped, null, "Nice slice!", 15f));
        }

        case DASH -> {
            s.add(step("DASH",
                "Press  [ Q ]  to dash.\n" +
                "You burst in your current move direction.\n" +
                "Try dashing toward — or away from — the target.",
                "Q",
                ctx -> ctx.win.player.abilities.isDashing,
                AbilityPractice::spawnDummy, 30f));
        }

        case QUAGMIRE -> {
            s.add(step("QUAGMIRE — aim",
                "Quagmire fires a mud wave along the ground.\n" +
                "Face the dummy and press  [ M ].\n" +
                "The wave travels forward and traps anything it touches.",
                "M",
                ctx -> {
                    Enemy d = ctx.dummy();
                    return d != null && d.mudTrapTimer > 0f;
                },
                AbilityPractice::spawnDummy, 40f));
        }

        case LIGHTNING -> {
            s.add(step("LIGHTNING — aim and strike",
                "Aim at the dummy and press  [ U ]  to call lightning.\n" +
                "Double-tap  [ U ]  for an area burst that hits everything nearby.",
                "U",
                AbilityPractice::dummyHpDropped,
                AbilityPractice::spawnDummy, "Lightning lands!", 30f));
        }

        case HEAL -> {
            s.add(step("HEAL",
                "Hold  [ L ]  to channel healing energy.\n" +
                "It costs MANA (the blue bar). Healing stops if mana runs out.\n" +
                "You cannot move while channeling.",
                "L",
                ctx -> ctx.win.player.abilities.isHealing,
                null, "Healing active!", 30f));
        }

        case GRAB -> {
            s.add(step("GRAB — position",
                "Grab-and-Slam works at close range.\n" +
                "Move close to the dummy (within 3 blocks).",
                "WASD",
                ctx -> {
                    Enemy d = ctx.dummy();
                    return d != null && ctx.win.player.position.distance(d.position) < 3.2f;
                },
                AbilityPractice::spawnDummy, 40f));
            s.add(step("GRAB — slam",
                "Hold  [ O ]  to grab the enemy, hoist them up,\n" +
                "and slam them into the ground.",
                "O",
                AbilityPractice::dummyHpDropped, null, "Crushed it!", 20f));
        }

        case BLINK -> {
            s.add(step("BLINK",
                "Look at any surface at least 4 blocks away.\n" +
                "Press  [ E ]  to teleport directly to that point.\n" +
                "Works on floors, walls, ceilings — anywhere you can see.",
                "E",
                ctx -> {
                    // Blink went off: cooldown fraction dropped below 1 (it was full at step start)
                    if (!ctx.flag) { ctx.snapshot = ctx.win.player.abilities.getBlinkCooldownFrac();
                                     ctx.flag = true; return false; }
                    return ctx.win.player.abilities.getBlinkCooldownFrac() < 0.92f;
                },
                null, "Blinked!", 30f));
        }

        case PILLAR -> {
            s.add(step("STONE PILLAR",
                "Press  [ K ]  to erupt a pillar of stone beneath your feet.\n" +
                "It launches you skyward — useful to reach high ground or\n" +
                "to escape enemies closing in around you.",
                "K",
                ctx -> ctx.win.player.abilities.getPillarCooldownFrac() < 0.92f,
                null, "Launched!", 30f));
        }

        case CANNONBALL -> {
            s.add(step("CANNONBALL — charge",
                "Hold  [ G ]  to curl into a cannonball and build energy.\n" +
                "The longer you hold, the more explosive the launch.",
                "G",
                ctx -> ctx.win.player.abilities.isCharging(),
                null, 30f));
            s.add(step("CANNONBALL — launch",
                "Release  [ G ]  to fire yourself like a projectile.\n" +
                "You explode through blocks and enemies on contact.",
                "release G",
                ctx -> ctx.win.player.abilities.isCannonballing,
                null, "Airborne!", 15f));
        }

        case STAND -> {
            // Manhattan Transfer: already handled by the wave-8 practice session.
            // Include a brief recap here for the tutorial flow.
            s.add(step("MANHATTAN TRANSFER — deploy",
                "Press  [ X ]  to deploy your combat drone above you.\n" +
                "It auto-targets and fires at the nearest enemy.",
                "X",
                ctx -> ctx.win.player.stand.isDeployed(),
                AbilityPractice::spawnDummy, "Drone active!", 30f));
            s.add(step("MANHATTAN TRANSFER — pilot",
                "Press  [ TAB ]  to enter the drone's perspective.\n" +
                "WASD to fly, Space = up, Shift = down.\n" +
                "Left-click to fire. Press  [ TAB ]  again to exit.",
                "TAB",
                ctx -> ctx.win.player.stand.isInStandPerspective(),
                null, 30f));
        }

        case SEAL -> {
            s.add(step("MINATO'S SEAL — place",
                "Press  [ H ]  to throw a teleport seal.\n" +
                "It sticks to any surface — floor, wall, or ceiling.\n" +
                "You can have up to 5 seals active at once.",
                "H",
                ctx -> ctx.win.player.seals.getSealCount() > 0,
                null, "Seal placed!", 30f));
            s.add(step("MINATO'S SEAL — warp",
                "Now look at the seal you just placed.\n" +
                "Press  [ B ]  to warp instantly to it.\n" +
                "Use  [ N ]  to recall a seal without teleporting.",
                "B",
                ctx -> {
                    if (!ctx.flag) { ctx.snapshot = ctx.win.player.position.x;
                                     ctx.flag = true; return false; }
                    float d = Math.abs(ctx.win.player.position.x - ctx.snapshot);
                    return d > 2f;
                },
                null, "Warped!", 30f));
        }

        case SUBSTITUTE -> {
            s.add(step("SUBSTITUTE — prime",
                "Hold  [ V ]  to prime the paper-dummy substitute.\n" +
                "The next hit you take will be completely absorbed.\n" +
                "You blink backward, a paper clone appears and then explodes.",
                "V",
                ctx -> ctx.win.substitutePrimed,
                null, "Substitute primed!", 30f));
        }

        case KAMUI -> {
            s.add(step("KAMUI — enter the void",
                "Press  [ Z ]  to phase your body into another dimension.\n" +
                "While inside: you are completely invincible.\n" +
                "Enemies and attacks pass straight through you.",
                "Z",
                ctx -> ctx.win.player.abilities.isKamui,
                AbilityPractice::spawnDummy, "Phased in!", 40f));
            s.add(step("KAMUI — absorb",
                "While in Kamui, hold  [ Left Click ]  to charge an absorption vortex.\n" +
                "Enemies near you get sucked in and take heavy damage.\n" +
                "Press  [ Z ]  again (or run out of mana) to exit.",
                "Z again",
                ctx -> !ctx.win.player.abilities.isKamui,
                null, "Back to reality.", 40f));
        }

        case SWAP -> {
            s.add(step("POSITION SWAP — setup",
                "A dummy has been placed near you.\n" +
                "Walk close to it, then press  [ J ]  to swap positions.\n" +
                "The nearest enemy is always the target.",
                "J",
                ctx -> {
                    if (!ctx.flag) { ctx.snapshot = ctx.win.player.position.x;
                                     ctx.flag = true; return false; }
                    // Detect: swap cooldown just started (swap happened)
                    return ctx.win.todoSwapCooldown > GameConfig.todoCooldown * 0.8f;
                },
                AbilityPractice::spawnDummy, "Swapped!", 30f));
        }

        case STONE_CANON -> {
            s.add(step("STONE CANON",
                "Near a stone surface, hold  [ I ]  to absorb stone into a giant boulder.\n" +
                "Release to fire it in the direction you are looking.\n" +
                "Requires stone blocks nearby — check the ground.",
                "I",
                ctx -> ctx.win.isChargingStoneCanon,
                null, 40f));
        }

        case TIME -> {
            s.add(step("TIME DILATION — slow",
                "Press  [ R ]  to slow time to a near-stop.\n" +
                "Everything freezes around you. Use it to dodge,\n" +
                "reposition, or line up a precise shot.",
                "R",
                ctx -> com.leaf.game.core.TimeController.getInstance().getScale() < 0.5f,
                null, "Time slowing!", 30f));
            s.add(step("TIME DILATION — fast",
                "Press  [ Y ]  to speed time up instead.\n" +
                "Enemies and projectiles move much faster.\n" +
                "Use it carefully — or let it expire on its own.",
                "Y",
                ctx -> ctx.stepAge > 5f,   // just wait 5 s — hard to detect
                null, 30f));
        }

        case FLIGHT -> {
            s.add(step("FLIGHT",
                "Double-tap  [ SPACE ]  to take flight.\n" +
                "You are free of the mountain.\n" +
                "Press  [ V ]  to cycle flight modes.",
                "Space x2",
                ctx -> ctx.win.player.debugMode,
                null, "Airborne!", 30f));
        }

        default -> {
            // No tutorial steps defined for this ability — skip immediately.
        }
        }
        return s;
    }
}
