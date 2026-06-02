package com.leaf.game.core;

import java.io.*;

/**
 * Persistent run records for DESCENT.
 *
 * Tracked per death:
 *   • deaths this run  (reset on new run)
 *   • wave reached on each death
 *
 * Tracked all-time (persisted to descent_records.txt):
 *   • total deaths ever
 *   • best wave reached
 */
public class RunRecords {

    private static final String FILE = "descent_records.txt";

    // ── Current run (live, not persisted until death) ─────────────────────────
    public  int   deathsThisRun = 0;
    public  float runStartTime  = 0f;  // glfwGetTime() snapshot when run began

    // ── All-time (loaded + saved on death) ────────────────────────────────────
    private int     totalDeaths       = 0;
    private int     bestWave          = 0;
    /** Deaths accumulated BEFORE Kamui was unlocked. Resets once Kamui is granted. */
    private int     deathsBeforeKamui = 0;
    /** True once Kamui was ever awakened via this system (persisted). */
    public  boolean kamuiEverUnlocked = false;
    /** Deaths required before the Kamui awakening triggers. */
    public  static final int KAMUI_AWAKEN_DEATHS = 3;

    // Singleton used by Window
    public static final RunRecords INSTANCE = new RunRecords();
    private RunRecords() { load(); }

    // ── API ────────────────────────────────────────────────────────────────────

    public int  totalDeaths() { return totalDeaths; }
    public int  bestWave()    { return bestWave;    }

    /** Call when a new run starts (PLAY button or restart after death). */
    public void newRun(float nowSecs) {
        deathsThisRun = 0;
        runStartTime  = nowSecs;
    }

    /**
     * Record a death. Updates all-time bests and persists.
     * @return formatted death-screen summary strings: { waveLine, deathLine, timeLine, bestLine }
     */
    public String[] recordDeath(int waveReached, float nowSecs) {
        deathsThisRun++;
        totalDeaths++;

        boolean newBest = waveReached > bestWave;
        bestWave = Math.max(bestWave, waveReached);

        // Count deaths toward Kamui separately — robust regardless of total deaths history.
        if (!kamuiEverUnlocked) deathsBeforeKamui++;
        if (deathsBeforeKamui >= KAMUI_AWAKEN_DEATHS && !kamuiEverUnlocked)
            kamuiEverUnlocked = true;  // awakening triggers this death

        float elapsed = nowSecs - runStartTime;
        int mins = (int)(elapsed / 60f);
        int secs = (int)(elapsed % 60f);

        save();

        return new String[]{
            "Wave Reached   " + waveReached,
            "Deaths This Run   " + deathsThisRun,
            String.format("Time Survived   %d:%02d", mins, secs),
            newBest ? "NEW PERSONAL BEST - Wave " + bestWave
                    : "Personal Best   Wave " + bestWave,
        };
    }

    /**
     * True if the death just recorded (via recordDeath) triggered the Kamui awakening.
     * Uses deathsBeforeKamui reaching the threshold — works regardless of total deaths.
     */
    public boolean wasKamuiAwakenDeath() {
        return deathsBeforeKamui == KAMUI_AWAKEN_DEATHS && kamuiEverUnlocked;
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private void load() {
        File f = new File(FILE);
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if      (line.startsWith("DEATHS:"))   totalDeaths       = Integer.parseInt(line.substring(7).trim());
                else if (line.startsWith("BEST:"))     bestWave          = Integer.parseInt(line.substring(5).trim());
                else if (line.startsWith("KAMUI:"))    kamuiEverUnlocked = line.substring(6).trim().equals("1");
                else if (line.startsWith("KDEATHS:"))  deathsBeforeKamui = Integer.parseInt(line.substring(8).trim());
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try (PrintWriter w = new PrintWriter(FILE)) {
            w.println("DEATHS:"  + totalDeaths);
            w.println("BEST:"    + bestWave);
            w.println("KAMUI:"   + (kamuiEverUnlocked ? "1" : "0"));
            w.println("KDEATHS:" + deathsBeforeKamui);
        } catch (Exception e) {
            System.err.println("[Records] Could not save: " + e.getMessage());
        }
    }
}
