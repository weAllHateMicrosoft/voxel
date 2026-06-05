package com.leaf.game.core;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Central registry of every keyboard binding in the game.
 *
 * Why this exists: every letter A–Z is already bound to an ability, and new
 * features kept silently colliding (e.g. Chocolate Disco was put on K, which was
 * already Stone Pillar). This class is the single source of truth for what each
 * key does, and {@link #verify()} prints the map + LOUDLY warns about any key
 * that is registered twice — so a clash is caught at startup instead of in-game.
 *
 * New abilities should take their key from a constant here (see {@link #DISCO},
 * {@link #QUANTUM_BULLET}) and register it below, guaranteeing they don't reuse a
 * key that's already spoken for.
 */
public final class KeyBindings {

    private KeyBindings() {}

    // ── Keys for the new showcase abilities (kept off the fully-booked A–Z set) ──
    public static final int DISCO             = GLFW_KEY_PERIOD;      // '.'  Chocolate Disco grid
    public static final int QUANTUM_BULLET    = GLFW_KEY_COMMA;       // ','  Quantum Bullet (reserved)
    public static final int DEPRIVATION_DOMAIN = GLFW_KEY_APOSTROPHE; // '''  Water God Stance
    public static final int GRAVITY_FLIP      = GLFW_KEY_SLASH;       // '/'  Change gravity direction (look + press)
    public static final int MP_SUMMON         = GLFW_KEY_RIGHT_BRACKET; // ']' Spawn a troop at the enemy (multiplayer)

    /** key code → human description (insertion-ordered for a tidy printout). */
    private static final Map<Integer, String> REG = new LinkedHashMap<>();

    private static boolean conflict = false;

    /** Register a binding; warns (and flags) if the key is already taken. */
    public static void register(int key, String action) {
        String existing = REG.get(key);
        if (existing != null) {
            conflict = true;
            System.err.println("[KeyBindings] ⚠ CONFLICT: " + name(key)
                    + " is '" + existing + "' AND '" + action + "'");
        } else {
            REG.put(key, action);
        }
    }

    /** True if a key is already documented as bound (use before adding a new one). */
    public static boolean isReserved(int key) { return REG.containsKey(key); }

    static {
        // ── Movement / camera ──
        register(GLFW_KEY_W, "Move forward");
        register(GLFW_KEY_A, "Move left");
        register(GLFW_KEY_S, "Move back");
        register(GLFW_KEY_D, "Move right");
        register(GLFW_KEY_SPACE, "Jump / ascend");
        register(GLFW_KEY_LEFT_SHIFT, "Sneak / descend");
        // ── Abilities (survival) ──
        register(GLFW_KEY_Q, "Dash");
        register(GLFW_KEY_E, "Blink");
        register(GLFW_KEY_R, "Time slow (hold)");
        register(GLFW_KEY_Y, "Time fast (hold)");
        register(GLFW_KEY_U, "Lightning");
        register(GLFW_KEY_I, "Stone Canon (hold)");
        register(GLFW_KEY_O, "Grab Slam");
        register(GLFW_KEY_F, "Runic Cleave (melee)");
        register(GLFW_KEY_G, "Cannonball (hold)");
        register(GLFW_KEY_H, "Minato's Seal — fire");
        register(GLFW_KEY_J, "Todo's Technique — swap");
        register(GLFW_KEY_K, "Stone Pillar (hold)");
        register(GLFW_KEY_L, "Heal (hold)");
        register(GLFW_KEY_Z, "Kamui / Rewind");
        register(GLFW_KEY_X, "Manhattan Transfer (Stand)");
        register(GLFW_KEY_C, "Void Shard (hold)");
        register(GLFW_KEY_V, "Substitute / cycle flight");
        register(GLFW_KEY_B, "Minato's Seal — warp");
        register(GLFW_KEY_N, "Minato's Seal — reclaim");
        register(GLFW_KEY_M, "Quagmire");
        register(GLFW_KEY_SEMICOLON, "Knife combo");
        register(GLFW_KEY_TAB, "Stand perspective");
        // ── New showcase abilities ──
        register(DISCO, "Chocolate Disco grid");
        register(QUANTUM_BULLET, "Quantum Bullet (phases through walls)");
        register(DEPRIVATION_DOMAIN, "Deprivation Domain – Water God Stance");
        register(GRAVITY_FLIP, "Gravity Flip – change gravity direction");
        register(MP_SUMMON, "Spawn troop at enemy (multiplayer)");
        // ── System / debug ──
        register(GLFW_KEY_T, "Chat");
        register(GLFW_KEY_P, "Debug: spawn enemy");
        register(GLFW_KEY_F1, "Help");
        register(GLFW_KEY_F3, "Debug overlay");
        register(GLFW_KEY_F5, "Warp to Canyon");
        register(GLFW_KEY_F6, "Layered Rooms");
        register(GLFW_KEY_F7, "Orbital Annihilation");
        register(GLFW_KEY_F8, "The World (time stop)");
        register(GLFW_KEY_F9, "Debug: skip wave");
        register(GLFW_KEY_F10, "Radar Sweep");
    }

    /** Print the binding table once at startup, and shout if anything clashed. */
    public static void verify() {
        System.out.println("[KeyBindings] " + REG.size() + " keys registered"
                + (conflict ? "  — ⚠ CONFLICTS DETECTED (see above)" : "  — no conflicts"));
    }

    /** Best-effort printable name for a GLFW key code. */
    public static String name(int key) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z) return String.valueOf((char) ('A' + (key - GLFW_KEY_A)));
        switch (key) {
            case GLFW_KEY_PERIOD:     return "PERIOD '.'";
            case GLFW_KEY_COMMA:      return "COMMA ','";
            case GLFW_KEY_SEMICOLON:  return "SEMICOLON ';'";
            case GLFW_KEY_SLASH:      return "SLASH '/'";
            case GLFW_KEY_SPACE:      return "SPACE";
            case GLFW_KEY_TAB:        return "TAB";
            case GLFW_KEY_LEFT_SHIFT: return "LSHIFT";
            default:                  return "key#" + key;
        }
    }
}
