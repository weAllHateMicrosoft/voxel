package com.leaf.game.core;

import com.leaf.game.entity.Enemy;
import com.leaf.game.world.Block;
import com.leaf.game.world.gen.biome.Biome;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The Voyage — DESCENT's guided exploration quest.
 *
 * <p>After a short tutorial the crystal grants FLIGHT and the player follows a
 * beam of light to each scattered shard. Reaching a shard's biome (and, in the
 * Ashlands, felling the Inferno Tower first) collects the material and the crystal
 * forges it into a powerful weapon. The chain is strictly ordered and always
 * sign-posted, so the player is never left wondering where to go:
 *
 * <pre>
 *   Crystal Fields  → Gatling Gun
 *   Glowing Groves  → Deprivation Domain
 *   Floating Isles  → Stone Cannon
 *   The Ashlands    → Orbital Annihilation   (requires defeating the Inferno Tower)
 *   — finale —      → The World (Time Stop)
 * </pre>
 *
 * <p>Guidance lives in two places: the in-world {@code beacon} beam (rendered by
 * {@link Window}) and the HUD objective banner + waypoint marker (drawn by
 * {@link WindowHud}). Both read this object's live state.
 */
public class Voyage {

    /** A single fly-there-and-collect step. */
    public static final class Objective {
        public final Biome   biome;          // null = the sky objective (high-altitude beacon)
        public final boolean sky;            // floating-isle objective: beacon sits high in the air
        public final String  place;          // "the Crystal Fields"
        public final String  shard;          // "Crystal Shard"
        public final String  weapon;         // "Gatling Gun"
        public final Block    material;
        public final Progression.Ability reward;
        public final float[]  beam;          // beacon colour {r,g,b}
        public final boolean  requireTowerKill;
        public final String   forgeLore;     // shown when the weapon is forged

        // ── runtime ──
        public Vector3f location;            // beacon base position
        public boolean  located = false;
        public boolean  done    = false;
        public Enemy    tower   = null;      // spawned Inferno Tower (Ashlands only)
        public boolean  towerKilled = false;

        Objective(Biome biome, boolean sky, String place, String shard, String weapon,
                  Block material, Progression.Ability reward, float[] beam,
                  boolean requireTowerKill, String forgeLore) {
            this.biome = biome; this.sky = sky; this.place = place; this.shard = shard;
            this.weapon = weapon; this.material = material; this.reward = reward;
            this.beam = beam; this.requireTowerKill = requireTowerKill; this.forgeLore = forgeLore;
        }
    }

    private final Window win;
    public final List<Objective> objectives = new ArrayList<>();
    public int     current  = 0;
    public boolean active    = false;
    public boolean complete  = false;

    /** Fly within this 3-D distance of the beam to claim the shard. */
    private static final float COLLECT_RADIUS = 8f;
    private static final int   LOCATE_STEP  = 96;
    private static final int   LOCATE_RANGE = 3600;

    public Voyage(Window win) { this.win = win; }

    /** Build the objective chain and grant the opening kit (Flight + basic combat). */
    public void start() {
        objectives.clear();
        objectives.add(new Objective(Biome.CRYSTAL_FIELDS, false,
                "the Crystal Fields", "Crystal Shard", "Gatling Gun",
                Block.MAT_CRYSTAL_CORE, Progression.Ability.GATLING, new float[]{0.70f, 0.40f, 1.0f}, false,
                "The crystal forges your first true GUN  -  the Gatling. Hold Left-Click to tear through anything."));
        objectives.add(new Objective(Biome.MUSHROOM, false,
                "the Glowing Groves", "Spore Shard", "Deprivation Domain",
                Block.MAT_GLOW_SPORE, Progression.Ability.DEPRIVATION, new float[]{0.30f, 1.0f, 0.70f}, false,
                "Deprivation Domain forged. Press [ ' ] to erupt a golden hemisphere  -  any enemy that enters is instantly sliced."));
        objectives.add(new Objective(null, true,
                "the Floating Isles", "Aether Shard", "Stone Cannon",
                Block.MAT_AETHER_SHARD, Progression.Ability.STONE_CANON, new float[]{0.55f, 0.85f, 1.0f}, false,
                "The STONE CANNON is yours. Stand near stone, hold Right-Click to absorb it, release to fire a boulder."));
        objectives.add(new Objective(Biome.VOLCANIC, false,
                "the Ashlands", "Molten Core", "Orbital Annihilation (the Laser)",
                Block.MAT_MOLTEN_CORE, Progression.Ability.ORBITAL, new float[]{1.0f, 0.45f, 0.12f}, true,
                "From the tower's molten heart the crystal forges the LASER  -  Orbital Annihilation. Press [F7] to call down the sky."));

        current = 0; active = true; complete = false;

        // The opening gift: the sky, plus a basic melee + dash so travel isn't defenceless.
        Progression p = win.player.progression;
        p.unlock(Progression.Ability.FLIGHT);
        p.unlock(Progression.Ability.SLASH);
        p.unlock(Progression.Ability.DASH);

        locateCurrent();
        announce();
    }

    public Objective cur() {
        return (active && !complete && current < objectives.size()) ? objectives.get(current) : null;
    }

    /** Find where the current objective's beacon should sit (nearest matching biome, or high sky). */
    private void locateCurrent() {
        Objective o = cur();
        if (o == null || o.located) return;
        int px = (int) win.player.position.x;
        int pz = (int) win.player.position.z;

        if (o.sky) {
            // Floating isle: a beam high in the air a short flight away.
            float ang = (float) (Math.random() * Math.PI * 2);
            int sx = px + (int) (Math.cos(ang) * 260);
            int sz = pz + (int) (Math.sin(ang) * 260);
            float groundY = win.worldGen.surfaceYEstimate(sx, sz);
            o.location = new Vector3f(sx + 0.5f, groundY + 95f, sz + 0.5f);
            o.located  = true;
            return;
        }

        int foundX = Integer.MIN_VALUE, foundZ = Integer.MIN_VALUE;
        float bestSq = Float.MAX_VALUE;
        for (int dx = -LOCATE_RANGE; dx <= LOCATE_RANGE; dx += LOCATE_STEP) {
            for (int dz = -LOCATE_RANGE; dz <= LOCATE_RANGE; dz += LOCATE_STEP) {
                if (win.worldGen.biomeAt(px + dx, pz + dz) == o.biome) {
                    float dSq = (float) dx * dx + (float) dz * dz;
                    if (dSq < bestSq) { bestSq = dSq; foundX = px + dx; foundZ = pz + dz; }
                }
            }
        }
        if (foundX == Integer.MIN_VALUE) {
            // Fallback: drop the beacon a fixed distance ahead so the voyage never stalls.
            foundX = px + 600; foundZ = pz;
        }
        float gy = win.worldGen.surfaceYEstimate(foundX, foundZ);
        o.location = new Vector3f(foundX + 0.5f, gy + 2f, foundZ + 0.5f);
        o.located  = true;
    }

    /** Per-frame: keep the beacon located, manage the tower, and test for collection. */
    public void update(float dt) {
        if (!active || complete) return;
        Objective o = cur();
        if (o == null) return;
        if (!o.located) locateCurrent();
        if (o.location == null) return;

        Vector3f pp = win.player.position;
        float dx = pp.x - o.location.x, dz = pp.z - o.location.z;
        float horiz = (float) Math.sqrt(dx * dx + dz * dz);

        // ── Ashlands: raise the Inferno Tower as the player nears, then watch it die ──
        if (o.requireTowerKill) {
            if (o.tower == null && horiz < 110f && !o.towerKilled) {
                float ty = win.worldGen.surfaceYEstimate((int) o.location.x, (int) o.location.z);
                o.tower = win.enemyManager.spawnAt(o.location.x, ty + 1f, o.location.z,
                        Enemy.Type.INFERNO_TOWER);
            }
            if (o.tower != null && !o.tower.alive) o.towerKilled = true;
        }

        // ── Collection: flying through the BEAM COLUMN claims the shard. The beam
        // rises ~70 blocks, so the player collects anywhere along its height — much
        // more forgiving than hitting one exact point. ──
        boolean reachable = !o.requireTowerKill || o.towerKilled;
        boolean inColumn = horiz <= COLLECT_RADIUS
                && pp.y >= o.location.y - 8f && pp.y <= o.location.y + 74f;
        if (reachable && inColumn) {
            collect(o);
        }
    }

    private void collect(Objective o) {
        o.done = true;
        win.inventory.addBlockAmount(o.material, 1);

        // Forge: unlock the ability and drop its weapon into the hotbar.
        win.player.progression.unlock(o.reward);
        win.grantWeaponForAbility(o.reward);

        // Reveal: gold flash + a forge sound + a lore line.
        ScreenEffectManager.INSTANCE.flash(1.0f, 0.85f, 0.4f, 0.6f, 0.5f);
        AudioManager.play("snipe_loadgun");
        win.showVoyageForge(o.shard + " claimed!  " + o.forgeLore);

        // Advance.
        current++;
        if (current >= objectives.size()) {
            finish();
        } else {
            locateCurrent();
            announce();
        }
    }

    /** All shards gathered → the crystal forges the ultimate power. */
    private void finish() {
        complete = true;
        active   = false;
        win.player.progression.unlock(Progression.Ability.DOMAIN);
        win.grantWeaponForAbility(Progression.Ability.DOMAIN);
        win.player.progression.unlock(Progression.Ability.TIME);
        ScreenEffectManager.INSTANCE.flash(0.6f, 0.9f, 1.0f, 0.8f, 0.8f);
        AudioManager.play("seal_collect");
        win.showVoyageForge("Every shard reunited. The crystal forges THE WORLD  -  press [F8] to stop time itself. "
                + "The voyage is complete. The world is yours.");
    }

    /** Push the "fly here next" directive to the HUD hint line. */
    private void announce() {
        Objective o = cur();
        if (o == null) return;
        String how = o.requireTowerKill
                ? "Defeat the Inferno Tower, then claim the " + o.shard + "."
                : (o.sky ? "Fly UP to the beam and claim the " + o.shard + "."
                         : "Follow the beam and claim the " + o.shard + ".");
        win.showVoyageForge("VOYAGE  -  fly to " + o.place + ".  " + how);
    }

    // ── HUD / render accessors ───────────────────────────────────────────────
    public Vector3f beaconPos()  { Objective o = cur(); return o == null ? null : o.location; }
    public float[]  beaconColor(){ Objective o = cur(); return o == null ? null : o.beam; }
    public boolean  beaconReady(){ Objective o = cur(); return o != null && o.located; }

    /** One-line directive for the objective banner. */
    public String directive() {
        Objective o = cur();
        if (o == null) return complete ? "Voyage complete  -  the world is yours." : "";
        if (o.requireTowerKill && o.tower != null && !o.towerKilled)
            return "Destroy the Inferno Tower  -  then claim the " + o.shard;
        return "Fly to " + o.place + "  -  claim the " + o.shard;
    }

    /** "→ forge the Gatling Gun" style sub-line. */
    public String rewardLine() {
        Objective o = cur();
        return o == null ? "" : "Reward:  " + o.weapon;
    }
}
