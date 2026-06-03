package com.leaf.game.core;

import com.leaf.game.entity.Enemy;
import com.leaf.game.entity.Enemy.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Per-ability hands-on tutorial sequences.
 *
 * HOW TO ADD / EDIT CONTENT:
 *   Find the case block for your ability in forAbility() below.
 *   Each Step has:
 *     title       - shown in the header
 *     instruction - multi-line (use \n), shown on the card
 *     keyHint     - key chip shown in the header
 *     required    - how many times the done() predicate must fire before advancing
 *     done        - called every frame; return true to count one successful action
 *     onEnter     - called once when the step starts (spawn dummies, damage player, etc.)
 *     doneText    - shown briefly in green when required count is reached
 *     allowSkip   - if false, ENTER cannot skip this step (forced completion)
 *     timeout     - auto-advance after this many seconds if stuck (safety valve)
 *     showManaArrow / showCooldownArrow - draw HUD annotations for first abilities
 */
public class AbilityPractice {

    // ─────────────────────────────────────────────────────────────────────────
    //  Context
    // ─────────────────────────────────────────────────────────────────────────

    public static class StepCtx {
        public Window  win;
        public float   stepAge;
        public boolean flag;        // generic one-shot latch
        public float   snapshot;    // record a value at step-start
        public int     counter;     // how many times done() has fired this step
        public int     required;    // target count

        /** First alive DUMMY in the world, or null. */
        public Enemy dummy() {
            if (win == null) return null;
            return win.enemyManager.getEnemies().stream()
                    .filter(e -> e.alive && e.type == Type.DUMMY)
                    .findFirst().orElse(null);
        }

        /** Count of alive enemies of any type (including dummies). */
        public long aliveCount() {
            return win.enemyManager.getEnemies().stream().filter(e -> e.alive).count();
        }

        /** True if the dummy's HP dropped since flag was last reset. */
        public boolean dummyHpDropped() {
            Enemy d = dummy();
            if (d == null) return false;
            if (!flag) { snapshot = d.health; flag = true; return false; }
            if (d.health < snapshot - 0.5f) { snapshot = d.health; return true; }
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Step definition
    // ─────────────────────────────────────────────────────────────────────────

    public static class Step {
        public final String             title;
        public final String             instruction;
        public final String             keyHint;
        public final int                required;        // times done() must fire
        public final Predicate<StepCtx> done;            // one successful action
        public final Consumer<StepCtx>  onEnter;
        public final Consumer<StepCtx>  onTick;          // optional per-frame logic
        public final String             doneText;
        public final boolean            allowSkip;       // can player press ENTER to skip?
        public final float              timeout;
        public final boolean            showManaArrow;
        public final boolean            showCooldownArrow;

        Step(Builder b) {
            title = b.title; instruction = b.instruction; keyHint = b.keyHint;
            required = b.required; done = b.done; onEnter = b.onEnter; onTick = b.onTick;
            doneText = b.doneText; allowSkip = b.allowSkip; timeout = b.timeout;
            showManaArrow = b.showManaArrow; showCooldownArrow = b.showCooldownArrow;
        }

        public static Builder of(String title) { return new Builder(title); }
    }

    public static class Builder {
        String title = "", instruction = "", keyHint = null, doneText = null;
        int required = 1;
        Predicate<StepCtx> done = ctx -> false;
        Consumer<StepCtx>  onEnter = null, onTick = null;
        boolean allowSkip = false, showManaArrow = false, showCooldownArrow = false;
        float timeout = 90f;

        Builder(String t) { title = t; }
        public Builder instr(String s)  { instruction = s; return this; }
        public Builder key(String k)    { keyHint = k; return this; }
        public Builder need(int n)      { required = n; return this; }
        public Builder when(Predicate<StepCtx> p) { done = p; return this; }
        public Builder setup(Consumer<StepCtx> c) { onEnter = c; return this; }
        public Builder tick(Consumer<StepCtx> c)  { onTick  = c; return this; }
        public Builder win(String t)    { doneText = t; return this; }
        public Builder skip()           { allowSkip = true; return this; }
        public Builder noSkip()         { allowSkip = false; return this; }
        public Builder manaArrow()      { showManaArrow = true; return this; }
        public Builder cooldownArrow()  { showCooldownArrow = true; return this; }
        public Builder tout(float s)    { timeout = s; return this; }
        public Step build()             { return new Step(this); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Spawn helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Kill all current dummies. */
    public static void clearDummies(StepCtx ctx) {
        ctx.win.enemyManager.getEnemies().stream()
                .filter(e -> e.type == Type.DUMMY)
                .forEach(e -> e.alive = false);
    }

    /** Spawn N dummies in a rough arc in front of the player. */
    public static void spawnDummies(StepCtx ctx, int count) {
        clearDummies(ctx);
        float yaw = ctx.win.lastCameraYaw;
        for (int i = 0; i < count; i++) {
            float angle = yaw + (float)(i - count / 2) * 0.45f;
            float dist  = 5f + i * 1.2f;
            float px = ctx.win.player.position.x + (float)Math.sin(angle) * dist;
            float pz = ctx.win.player.position.z + (float)Math.cos(angle) * dist;
            ctx.win.enemyManager.spawnAt(px, ctx.win.player.position.y + 1f, pz, Type.DUMMY);
        }
    }

    /** Spawn a single THROWER as a fleeing "running away" target. */
    public static void spawnFleeing(StepCtx ctx) {
        clearDummies(ctx);
        float yaw = ctx.win.lastCameraYaw;
        // Spawn it 6 blocks ahead then it will retreat (THROWER retreats when too close)
        float px = ctx.win.player.position.x + (float)Math.sin(yaw) * 6f;
        float pz = ctx.win.player.position.z + (float)Math.cos(yaw) * 6f;
        ctx.win.enemyManager.spawnAt(px, ctx.win.player.position.y + 1f, pz, Type.THROWER);
    }

    /** Damage the player to 30% HP so they need to heal. */
    public static void damagePlayer(StepCtx ctx) {
        ctx.win.player.health = ctx.win.player.maxHealth * 0.30f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Per-ability definitions
    // ─────────────────────────────────────────────────────────────────────────

    public static List<Step> forAbility(Progression.Ability a) {
        List<Step> s = new ArrayList<>();
        switch (a) {

        // ── WAVE 0: SNIPE ─────────────────────────────────────────────────────
        case SNIPE -> {
            s.add(Step.of("SNIPE")
                .instr("Your crystal bolt is your starting weapon.\n" +
                       "Hold  [ C ]  to charge it  -  the longer you hold, the bigger the blast.\n" +
                       "Release  [ C ]  to fire.\n\n" +
                       "Watch your MANA (blue bar) and the [ C ] cooldown icon on the right.")
                .key("C")
                .need(3)
                .manaArrow().cooldownArrow()
                .setup(ctx -> spawnDummies(ctx, 1))
                .when(ctx -> {
                    // One shot fired = snipe went from charging to cooldown
                    float frac = ctx.win.player.attacks.getSnipeIconFrac();
                    if (!ctx.flag) { ctx.snapshot = frac; ctx.flag = true; return false; }
                    if (frac < ctx.snapshot - 0.25f && ctx.snapshot > 0.2f) {
                        ctx.snapshot = frac; return true;
                    }
                    ctx.snapshot = frac;
                    return false;
                })
                .win("Shot fired!")
                .tout(120f).build());
        }

        // ── WAVE 1: SLASH + DASH ──────────────────────────────────────────────
        case SLASH -> {
            s.add(Step.of("SLASH")
                .instr("Press  [ F ]  to swing a wide arc and hit everything in front of you.\n" +
                       "Walk close to an enemy and slash it.\n" +
                       "Do this  3 times.")
                .key("F")
                .need(3)
                .cooldownArrow()
                .setup(ctx -> spawnDummies(ctx, 2))
                .when(ctx -> ctx.dummyHpDropped())
                .win("Slash!")
                .tout(90f).build());
        }
        case DASH -> {
            s.add(Step.of("DASH")
                .instr("Press  [ Q ]  to burst instantly in your move direction.\n" +
                       "Great for closing gaps or escaping surrounded situations.\n" +
                       "Try it once.")
                .key("Q")
                .need(1)
                .cooldownArrow()
                .when(ctx -> ctx.win.player.abilities.isDashing)
                .win("Dashed!")
                .tout(60f).build());
        }

        // ── WAVE 2: QUAGMIRE ─────────────────────────────────────────────────
        case QUAGMIRE -> {
            s.add(Step.of("QUAGMIRE  -  the enemy is fleeing!")
                .instr("An enemy is running away!\n" +
                       "Press  [ M ]  to fire a mud wave along the ground.\n" +
                       "It will trap the enemy in place  -  then you can move in for the kill.")
                .key("M")
                .need(1)
                .setup(ctx -> spawnFleeing(ctx))
                .when(ctx -> {
                    // True when any living enemy has mudTrapTimer > 0
                    return ctx.win.enemyManager.getEnemies().stream()
                            .anyMatch(e -> e.alive && e.mudTrapTimer > 0f);
                })
                .win("Trapped!")
                .tout(90f).build());
            s.add(Step.of("QUAGMIRE  -  finish it!")
                .instr("Good  -  the enemy is stuck in mud.\n" +
                       "Walk up and kill it while it can't move.\n" +
                       "Use  [ F ]  to slash or  [ C ]  to snipe.")
                .key("F / C")
                .need(1)
                .when(ctx -> ctx.win.enemyManager.getEnemies().stream()
                        .noneMatch(e -> e.alive && e.type != Type.DUMMY))
                .win("Enemy down!")
                .tout(60f).build());
        }

        // ── WAVE 3: LIGHTNING ────────────────────────────────────────────────
        case LIGHTNING -> {
            s.add(Step.of("LIGHTNING  -  single target")
                .instr("Press  [ U ]  to call a lightning strike on the enemy you are aiming at.\n" +
                       "Try it  2 times.")
                .key("U")
                .need(2)
                .cooldownArrow()
                .setup(ctx -> spawnDummies(ctx, 1))
                .when(ctx -> ctx.dummyHpDropped())
                .win("Strike!")
                .tout(60f).build());
            s.add(Step.of("LIGHTNING  -  crowd burst")
                .instr("Double-tap  [ U ]  quickly for an area burst.\n" +
                       "It hits every enemy nearby at once.\n" +
                       "Try it  2 times  -  these dummies are grouped for it.")
                .key("U U")
                .need(2)
                .setup(ctx -> spawnDummies(ctx, 4))
                .when(ctx -> {
                    // 3+ dummies took hits simultaneously = area burst fired
                    long flashed = ctx.win.enemyManager.getEnemies().stream()
                            .filter(e -> e.alive && e.type == Type.DUMMY && e.hitFlashTimer > 0.05f)
                            .count();
                    return flashed >= 3;
                })
                .win("Area burst!")
                .tout(60f).build());
        }
        case HEAL -> {
            s.add(Step.of("HEAL")
                .instr("You took damage!\n" +
                       "Hold  [ L ]  to channel healing energy.\n" +
                       "Watch the MANA bar  -  healing drains it.\n" +
                       "You cannot move while channeling.")
                .key("L")
                .need(1)
                .manaArrow()
                .setup(ctx -> damagePlayer(ctx))
                .when(ctx -> ctx.win.player.abilities.isHealing)
                .win("Healed!")
                .tout(45f).build());
        }

        // ── WAVE 4: GRAB ─────────────────────────────────────────────────────
        case GRAB -> {
            s.add(Step.of("GRAB  -  get close")
                .instr("Grab-and-Slam works at close range.\n" +
                       "Walk up to the dummy until you are within 3 blocks.")
                .key("Walk")
                .need(1)
                .setup(ctx -> spawnDummies(ctx, 1))
                .when(ctx -> {
                    Enemy d = ctx.dummy();
                    return d != null && ctx.win.player.position.distance(d.position) < 3.5f;
                })
                .win("In range!")
                .tout(40f).build());
            s.add(Step.of("GRAB  -  slam!")
                .instr("Now hold  [ O ]  to grab the enemy, hoist it up,\n" +
                       "and slam it into the ground.\n" +
                       "Do this  2 times.")
                .key("O")
                .need(2)
                .setup(ctx -> { ctx.flag = false; spawnDummies(ctx, 1); })
                .when(ctx -> ctx.dummyHpDropped())
                .win("Slammed!")
                .tout(60f).build());
        }

        // ── WAVE 5: BLINK + SWAP ─────────────────────────────────────────────
        case BLINK -> {
            s.add(Step.of("BLINK")
                .instr("Look at any surface at least 4 blocks away.\n" +
                       "Press  [ E ]  to teleport there instantly.\n" +
                       "Works on floors, walls, and ceilings.")
                .key("E")
                .need(1)
                .when(ctx -> {
                    float frac = ctx.win.player.abilities.getBlinkCooldownFrac();
                    if (!ctx.flag) { ctx.snapshot = frac; ctx.flag = true; return false; }
                    boolean fired = frac < ctx.snapshot - 0.1f && ctx.snapshot > 0.9f;
                    ctx.snapshot = frac; return fired;
                })
                .win("Blinked!")
                .tout(60f).build());
        }
        case SWAP -> {
            s.add(Step.of("POSITION SWAP")
                .instr("Press  [ J ]  to instantly swap places with the nearest enemy.\n" +
                       "Watch: you will teleport to where the dummy was,\n" +
                       "and it will appear where you were standing.")
                .key("J")
                .need(1)
                .setup(ctx -> spawnDummies(ctx, 1))
                .when(ctx -> ctx.win.todoSwapCooldown > GameConfig.todoCooldown * 0.8f)
                .win("Positions swapped!")
                .tout(45f).build());
        }

        // ── WAVE 6: PILLAR + CANNONBALL ──────────────────────────────────────
        case PILLAR -> {
            s.add(Step.of("STONE PILLAR  -  find solid ground")
                .instr("The Stone Pillar needs solid blocks beneath your feet.\n" +
                       "You're on solid ground when the ground under you is stone or dirt.\n\n" +
                       "Press  [ K ]  to erupt a pillar and launch yourself skyward!")
                .key("K")
                .need(1)
                .when(ctx -> ctx.win.player.abilities.getPillarCooldownFrac() < 0.92f)
                .win("Launched!")
                .tout(60f).build());
        }
        case CANNONBALL -> {
            // Text only — user said don't force tutorial
            s.add(Step.of("CANNONBALL")
                .instr("Hold  [ G ]  to curl into a cannonball and build momentum.\n" +
                       "Release to fire yourself like an explosive projectile.\n" +
                       "You detonate through blocks and enemies on contact.\n\n" +
                       "(You'll figure this one out  -  press  [ ENTER ]  to continue.)")
                .key("G")
                .need(1)
                .skip()
                .when(ctx -> ctx.win.player.abilities.isCannonballing)
                .tout(15f).build());  // short timeout, basically text card
        }

        // ── WAVE 7: STAND (MANHATTAN TRANSFER) ────────────────────────────────
        case STAND -> {
            s.add(Step.of("MANHATTAN TRANSFER  -  deploy")
                .instr("Manhattan Transfer is a combat drone that extends your range.\n" +
                       "Think of it as a floating sniper nest you can pilot.\n\n" +
                       "Press  [ X ]  to deploy the drone above you.")
                .key("X")
                .need(1)
                .setup(ctx -> {
                    // Spawn a target around the corner — to the player's right at 12 blocks
                    float yaw = ctx.win.lastCameraYaw + (float)Math.PI * 0.5f;
                    float px  = ctx.win.player.position.x + (float)Math.sin(yaw) * 12f;
                    float pz  = ctx.win.player.position.z + (float)Math.cos(yaw) * 12f;
                    clearDummies(ctx);
                    ctx.win.enemyManager.spawnAt(px, ctx.win.player.position.y + 1f, pz, Type.DUMMY);
                })
                .when(ctx -> ctx.win.player.stand.isDeployed())
                .win("Drone deployed!")
                .tout(60f).build());

            s.add(Step.of("MANHATTAN TRANSFER  -  pilot the drone")
                .instr("The enemy target is off to your side  -  you can't snipe it directly.\n" +
                       "Press  [ TAB ]  to take control of the drone.\n" +
                       "Fly it: WASD moves, SPACE = up, SHIFT = down.\n\n" +
                       "Maneuver around until you can see the target.")
                .key("TAB")
                .need(1)
                .when(ctx -> ctx.win.player.stand.isInStandPerspective())
                .win("Drone perspective active!")
                .tout(60f).build());

            s.add(Step.of("MANHATTAN TRANSFER  -  fire through the drone")
                .instr("You can see the target from the drone!\n" +
                       "Left-click to fire the drone's weapon at it.\n\n" +
                       "The drone redirects shots  -  the line does not need to go through YOU.\n" +
                       "Fire  3 times  to get the feel of it.")
                .key("LMB")
                .need(3)
                .when(ctx -> ctx.dummyHpDropped())
                .win("Hit!")
                .tout(90f).build());

            s.add(Step.of("MANHATTAN TRANSFER  -  auto-fire mode")
                .instr("Press  [ TAB ]  again to exit drone perspective.\n" +
                       "When you're not piloting, the drone auto-targets the nearest enemy.\n\n" +
                       "Exit the drone and let it auto-fire  2 more shots.")
                .key("TAB to exit")
                .need(2)
                .when(ctx -> !ctx.win.player.stand.isInStandPerspective()
                             && ctx.dummyHpDropped())
                .win("Auto-fire!")
                .tout(60f).build());
        }

        // ── WAVE 8: STONE CANON + SUBSTITUTE ─────────────────────────────────
        case STONE_CANON -> {
            s.add(Step.of("STONE CANON")
                .instr("Near a stone surface, hold  [ I ]  to absorb stone into a giant boulder.\n" +
                       "Release to fire it in the direction you are looking.\n" +
                       "Try firing  2 times.")
                .key("I")
                .need(2)
                .when(ctx -> {
                    // Detect shot fired: was charging, now not charging
                    if (!ctx.flag && ctx.win.isChargingStoneCanon) { ctx.flag = true; return false; }
                    if (ctx.flag && !ctx.win.isChargingStoneCanon) { ctx.flag = false; return true; }
                    return false;
                })
                .win("Fired!")
                .tout(90f).build());
        }
        case SUBSTITUTE -> {
            s.add(Step.of("PAPER SUBSTITUTE")
                .instr("Hold  [ V ]  to prime a paper dummy.\n" +
                       "The very next hit you take is completely absorbed.\n" +
                       "You blink backward, a paper clone appears, and then it explodes.\n\n" +
                       "Try priming it now  -  hold  [ V ].")
                .key("V")
                .need(1)
                .skip()
                .when(ctx -> ctx.win.substitutePrimed)
                .win("Substitute primed!")
                .tout(20f).build());
        }

        // ── WAVE 9: SEAL (MINATO'S SEAL) ─────────────────────────────────────
        case SEAL -> {
            s.add(Step.of("MINATO'S SEAL  -  place seals")
                .instr("Press  [ H ]  to throw a teleport seal  -  it sticks to any surface.\n" +
                       "Look in  3 different directions and place a seal in each spot.\n" +
                       "You can mark escape routes, vantage points, or ambush positions.\n\n" +
                       "Place  3 seals  now.")
                .key("H")
                .need(3)
                .when(ctx -> {
                    int cur = ctx.win.player.seals.getSealCount();
                    if (!ctx.flag) { ctx.snapshot = cur; ctx.flag = true; return false; }
                    if (cur > ctx.snapshot) { ctx.snapshot = cur; return true; }
                    return false;
                })
                .win("Seal placed!")
                .tout(90f).build());

            s.add(Step.of("MINATO'S SEAL  -  teleport to a seal")
                .instr("Look at one of your seals and press  [ B ]  to warp there instantly.\n" +
                       "Do this  2 times  -  each from a different position.")
                .key("B")
                .need(2)
                .when(ctx -> {
                    float frac = ctx.win.player.abilities.getBlinkCooldownFrac();
                    if (!ctx.flag) { ctx.snapshot = frac; ctx.flag = true; return false; }
                    boolean fired = frac < ctx.snapshot - 0.1f && ctx.snapshot > 0.9f;
                    ctx.snapshot = frac; return fired;
                })
                .win("Warped!")
                .tout(90f).build());

            s.add(Step.of("MINATO'S SEAL  -  recall")
                .instr("You can have up to  5 seals  at once.\n" +
                       "Look at a seal you no longer need and press  [ N ]  to recall it.\n" +
                       "It disappears without teleporting you.\n\n" +
                       "Recall  1 seal  now.")
                .key("N")
                .need(1)
                .when(ctx -> {
                    int cur = ctx.win.player.seals.getSealCount();
                    if (!ctx.flag) { ctx.snapshot = cur; ctx.flag = true; return false; }
                    if (cur < ctx.snapshot) { ctx.snapshot = cur; return true; }
                    return false;
                })
                .win("Seal recalled!")
                .tout(60f).build());
        }

        // ── KAMUI (Easter egg  -  death-unlocked, NOT a wave reward) ─────────
        case KAMUI -> {
            s.add(Step.of("KAMUI  -  enter the void")
                .instr("Your body has learned to slip between dimensions.\n" +
                       "Press  [ Z ]  to phase into another reality.\n" +
                       "While inside: you are completely invincible.")
                .key("Z")
                .need(1)
                .setup(ctx -> spawnDummies(ctx, 1))
                .when(ctx -> ctx.win.player.abilities.isKamui)
                .win("In the void!")
                .tout(60f).build());

            s.add(Step.of("KAMUI  -  absorb an enemy")
                .instr("While in Kamui, hold  [ Left Click ]  near an enemy\n" +
                       "to charge an absorption vortex.\n" +
                       "It pulls enemies in and destroys them.\n\n" +
                       "Absorb the dummy  -  do not exit Kamui until it is dead.")
                .key("LMB hold")
                .need(1)
                .when(ctx -> !ctx.win.player.abilities.isKamui
                             && ctx.aliveCount() == 0)  // exited and dummy is dead
                .win("Absorbed!")
                .tout(90f).build());
        }

        // ── FLIGHT (wave 10 = ENDING  -  tutorial is the cutscene) ───────────
        case FLIGHT -> {
            // The ending cutscene already shows "double-tap SPACE to fly".
            // No extra practice step needed.
        }

        // ── Anything without a tutorial passes through immediately ────────────
        default -> { }
        }
        return s;
    }
}
