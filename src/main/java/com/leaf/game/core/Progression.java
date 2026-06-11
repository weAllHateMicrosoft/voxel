package com.leaf.game.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Progression  -  the ability unlock system for DESCENT.
 *
 * ── How it works ────────────────────────────────────────────────────────────
 *  • The player starts every run able to use SNIPE plus everything they have
 *    EVER unlocked in a previous run (accumulated progress, persisted to disk).
 *  • Clearing a wave unlocks that wave's tier of abilities  -  but only the first
 *    time the player reaches it. Replaying earlier waves shows no card.
 *  • Locked abilities silently do nothing when their key is pressed.
 *
 * ── Editing the unlock schedule / card text ─────────────────────────────────
 *  Everything a designer needs to tweak lives at the top of this file:
 *    - the Ability enum (label / key hint / one-line description  -  also feeds F1)
 *    - TIERS  : which abilities unlock at which wave (index = wave; 0 = start kit)
 *    - FLAVOR : the story headline shown on each wave's unlock card
 *  No other file needs to change to retune the progression.
 */
public class Progression {

    // ═══════════════════════════════════════════════════════════════════════
    //  EDIT HERE  -  abilities, their keys, and one-line descriptions
    //  (used by BOTH the unlock cards and the F1 reference, so they stay in sync)
    // ═══════════════════════════════════════════════════════════════════════
    public enum Ability {
        SNIPE      ("Sniper",           "[C] / RMB",  "Hold to charge a crystal bolt, release to fire. Longer charge = bigger blast."),
        SLASH      ("Slash",            "[F]",        "Wide melee swing  -  hits every enemy in a cone in front of you."),
        DASH       ("Dash",             "[Q]",        "Instant burst in your move direction. Short cooldown, leaves a ghost trail."),
        QUAGMIRE   ("Quagmire",         "[M]",        "Fire a mud wave along the ground. Traps the enemy it hits for several seconds."),
        LIGHTNING  ("Lightning",        "[U]",        "Strike the enemy you aim at with lightning. Double-tap [U] for an area burst."),
        HEAL       ("Heal",             "[L]",        "Hold to channel healing  -  restores health over time. You can't move while channeling."),
        GRAB       ("Grab & Slam",      "[O]",        "Grab the enemy in your crosshair, hoist them up, then slam them into the ground."),
        BLINK      ("Blink",            "[E]",        "Teleport to the point you're looking at (up to ~22 blocks)."),
        SWAP       ("Position Swap",    "[J]",        "Instantly swap places with the nearest enemy  -  perfect for escapes."),
        PILLAR     ("Stone Pillar",     "[K]",        "A stone spire erupts under you and launches you skyward."),
        CANNONBALL ("Cannonball",       "[G]",        "Hold to charge, release to launch yourself as an explosive cannonball."),
        STAND      ("Manhattan Transfer","[X] / [TAB]","Deploy a combat drone that auto-fires at enemies. [TAB] to pilot it yourself."),
        TIME       ("Time Dilation",    "[R] / [Y]",  "[R] slows time to a crawl, [Y] speeds it up  -  dodge or line up a shot."),
        SEAL       ("Minato's Seal",    "[H] / [B]",  "[H] throws a teleport seal; [B] warps you to it. Up to 5 active at once."),
        SUBSTITUTE ("Substitute",       "[V]",        "Hold to prime. The next hit is absorbed  -  you blink back and leave an exploding decoy."),
        // ── GOD-TIER ARSENAL — earned late, the climax of the run ────────────────
        GATLING    ("Gatling Gun",      "Slot + LMB", "A roaring gatling gun. Equip it and hold Left-Click to shred everything in your sights."),
        STONE_CANON ("Stone Cannon",      "[I] / RMB",  "Near stone, hold to absorb it into a giant boulder. Release to fire a wrecking shot."),
        RADAR       ("Radar Sweep",       "[F10]",      "A pulse that paints every enemy through walls and terrain. You see everything."),
        DEPRIVATION ("Deprivation Domain","[ ' ]",      "A golden hemisphere erupts around you. Any enemy that enters is instantly sliced. Press once to start, again to end early."),
        ORBITAL     ("Orbital Annihilation","[F7] / RMB","Call a cinematic strike from orbit. The sky splits and the ground is erased."),
        DOMAIN     ("The World",        "[F8] / RMB", "Stop time itself. Everything freezes while you move freely — the ultimate power."),
        KAMUI      ("Kamui",            "[Z]",        "Phase into another dimension  -  invincible while active. Drains mana fast."),
        FLIGHT     ("Flight",           "[Space x2]", "Double-tap Space to fly. [V] cycles flight modes (skim / soar / grapple).");

        public final String label, key, desc;
        Ability(String label, String key, String desc) { this.label = label; this.key = key; this.desc = desc; }
    }

    /**
     * Which abilities unlock at which wave. Index = wave cleared; index 0 = starting kit.
     *
     * KAMUI is intentionally absent from the tier list.
     * It is a death-earned easter egg granted by RunRecords after 3 deaths.
     * Progression.grantKamui() handles it separately.
     */
    private static final Ability[][] TIERS = {
        /* start  */ { Ability.SNIPE, Ability.RADAR },               // Sniper + Radar from the start
        /* wave 1 */ { Ability.SLASH, Ability.DASH },               // close combat + mobility
        /* wave 2 */ { Ability.QUAGMIRE, Ability.HEAL },            // control + sustain
        /* wave 3 */ { Ability.LIGHTNING, Ability.GRAB },           // power + grapple
        /* wave 4 */ { Ability.BLINK, Ability.SWAP, Ability.TIME }, // escape kit
        /* wave 5 */ { Ability.PILLAR, Ability.CANNONBALL },        // launch yourself
        /* wave 6 */ { Ability.STAND, Ability.SUBSTITUTE, Ability.SEAL }, // tactician's toolkit
        // ── THE ASCENSION — the crystal stops holding back. God-tier weapons. ──
        /* wave 7 */ { Ability.GATLING },                           // first true weapon
        /* wave 8 */ { Ability.STONE_CANON },                        // siege weapon
        /* wave 9 */ { Ability.ORBITAL },                           // annihilation from orbit
        /* wave 10*/ { Ability.DOMAIN },                            // stop time — the ultimate
        /* wave 11*/ { Ability.FLIGHT },                            // final gift — the ending
    };

    /** Story headline shown on each wave's unlock card (index = wave cleared). */
    private static final String[] FLAVOR = {
        "The crystal stirs. Its first gift is yours.",
        "They're closing in. Move faster  -  strike harder.",
        "Hold them. Outlast them.",
        "Their numbers grow. Answer with thunder.",
        "Slip through every trap. Bend space to escape.",
        "The earth itself launches you skyward.",
        "Lay your traps. Command the battlefield.",
        "Enough holding back. The crystal hands you a WEAPON.",
        "Tear through stone  -  and see every foe through it.",
        "Look up. Call the sky down upon them.",
        "Time bows to you now. The world holds its breath.",
        "One last gift. The mountain releases you. The sky is yours.",
    };

    /** The wave that triggers the ENDING cutscene and grants FLIGHT. */
    public static final int ENDING_WAVE = 11;

    /** After clearing this many waves the Voyage opens (flight + shard collection). */
    public static final int VOYAGE_START_WAVE = 6;

    /** Always shown on unlock cards  -  the user wants players reminded about mana. */
    public static final String MANA_NOTE = "Most abilities draw MANA  -  the blue bar under your health. It refills over time.";

    // ═══════════════════════════════════════════════════════════════════════

    private final EnumSet<Ability> unlocked = EnumSet.noneOf(Ability.class);
    private int maxTier;

    public Progression() {
        reset();   // every run starts fresh  -  only the wave-0 starting kit
    }

    /**
     * Reset to the starting kit (SNIPE only). Called at the start of every run so
     * abilities unlock wave-by-wave each playthrough  -  that's what makes the unlock
     * cards appear every wave and the ability icons reveal one at a time.
     * (Abilities are intentionally NOT carried across runs.)
     */
    public void reset() {
        unlocked.clear();
        maxTier = 0;
        for (int t = 0; t <= maxTier && t < TIERS.length; t++)
            for (Ability a : TIERS[t]) unlocked.add(a);
        // KAMUI is death-earned (3 deaths) and PERSISTS across runs. The awakening
        // only fires once ever, so without this every new run would wipe it — re-grant
        // it here whenever it's already been earned (flag persisted in RunRecords).
        // NOTE: add it directly (NOT grantKamui), which would bump maxTier to 8 and skip
        // all the wave-1..8 unlock cards.
        if (RunRecords.INSTANCE.isKamuiUnlocked()) unlocked.add(Ability.KAMUI);
    }

    /** True if the player may use this ability right now. */
    public boolean isUnlocked(Ability a) { return unlocked.contains(a); }

    /** Unlock EVERY ability — used for multiplayer so both players are fully armed for PvP. */
    public void unlockAll() {
        for (Ability a : Ability.values()) unlocked.add(a);
        maxTier = TIERS.length;
    }

    /** Grant a single ability directly (used by the Voyage when a weapon is forged). */
    public void unlock(Ability a) { unlocked.add(a); }

    /** The abilities a given wave's tier grants (always  -  independent of unlock history). */
    public List<Ability> abilitiesForWave(int wave) {
        List<Ability> out = new ArrayList<>();
        if (wave >= 1 && wave < TIERS.length) for (Ability a : TIERS[wave]) out.add(a);
        return out;
    }

    /**
     * Mark a cleared wave's tier as unlocked.
     * @return the abilities newly unlocked by this clear (empty if already owned or no tier).
     */
    public List<Ability> unlockForWave(int wave) {
        List<Ability> gained = new ArrayList<>();
        if (wave < 1 || wave >= TIERS.length) return gained;  // wave 10+ = boss, no tier
        if (wave <= maxTier) return gained;                   // already earned this run
        for (Ability a : TIERS[wave]) if (unlocked.add(a)) gained.add(a);
        maxTier = Math.max(maxTier, wave);
        return gained;
    }

    /**
     * Directly grant Kamui regardless of wave progress.
     * Called when the 3rd death triggers the awakening cutscene.
     */
    public void grantKamui() {
        // Kamui is death-earned and independent of wave progress — do NOT touch maxTier,
        // or the wave-by-wave unlock cards up to that tier would be skipped this run.
        unlocked.add(Ability.KAMUI);
    }

    /** Story headline for a wave's unlock card. */
    public String flavorFor(int wave) {
        return (wave >= 0 && wave < FLAVOR.length) ? FLAVOR[wave] : "";
    }

    /** The wave at which an ability unlocks (0 = starting kit). */
    public int unlockWaveOf(Ability a) {
        for (int t = 0; t < TIERS.length; t++)
            for (Ability x : TIERS[t]) if (x == a) return t;
        return -1;
    }

    /** Highest wave-tier reached this run. */
    public int maxTier() { return maxTier; }

    public Ability[] allAbilities() { return Ability.values(); }
}
