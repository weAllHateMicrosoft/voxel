package com.leaf.game.core;

import com.leaf.game.entity.AttackController;
import com.leaf.game.entity.DroppedItem;
import com.leaf.game.entity.Enemy;
import com.leaf.game.entity.EnemyManager;
import com.leaf.game.entity.FlightController;
import com.leaf.game.entity.Inventory;
import com.leaf.game.entity.Player;
import com.leaf.game.entity.RemotePlayer;
import com.leaf.game.entity.SealController;
import com.leaf.game.entity.StandController;
import com.leaf.game.net.NetworkSession;
import com.leaf.game.render.Mesh;
import com.leaf.game.render.Shader;
import com.leaf.game.util.Camera;
import com.leaf.game.util.NoiseVisualizer;
import com.leaf.game.util.RaycastResult;
import com.leaf.game.world.Block;
import com.leaf.game.world.Chunk;
import com.leaf.game.world.World;
import com.leaf.game.world.gen.WorldGen;
import com.leaf.game.util.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class Window {
    long window;
    Player player;
    World world;
    WorldGen worldGen;


    // ── SPAWN POINT ───────────────────────────────────────────────────────────
    // Fixed world-spawn XZ. Y is determined by scanning for the surface at load.
    private static final float SPAWN_X = 777.0f;
    private static final float SPAWN_Z = 777.0f;
    /** Actual surface Y found during preload; used for respawning on death. */
    private float spawnSurfaceY = 250.0f;

    NoiseVisualizer noiseVis;
    boolean showNoiseViewer = false;
    RaycastResult lastTarget = null;

    NetworkSession network;
    RemotePlayer remotePlayer;

    private boolean networkInitialized = false;
    final ImString ipInput = new ImString("127.0.0.1", 64);

    private final double[]  lastMouseX = {640.0};
    private final double[]  lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};

    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imguiGl3  = new ImGuiImplGl3();

    // ── UI STATE ──────────────────────────────────────────────────────────────
    boolean isPaused  = false;
    boolean showDebug = false;
    boolean showChat  = false;

    // ── ABILITY-UNLOCK CARD (shown between waves) ──────────────────────────────
    boolean showUnlockCard = false;
    int     unlockCardWave  = 0;
    java.util.List<Progression.Ability> unlockCardAbilities = new java.util.ArrayList<>();
    private boolean lastCardSpace = false;

    // ── CUTSCENE (intro / ending) ──────────────────────────────────────────────
    final CutsceneManager cutscene = new CutsceneManager();
    /** Set by the "Play Game" button; the intro plays once the spawn finishes loading. */
    boolean playIntroOnSpawn = false;

    /** Counts down after the player takes damage; HUD pulses the health bar red. */
    float damageFlashTimer = 0f;

    // ── DEATH SCREEN ──────────────────────────────────────────────────────────
    boolean      showDeathScreen     = false;
    String[]     deathScreenLines    = null;
    private boolean lastDeathEnter   = false;

    /**
     * Set when the player dies and a cutscene sequence is pending.
     * KAMUI_AWAKEN plays first (on 3rd death), then REVIVAL auto-plays, then
     * Window restores the player automatically when the revival ends.
     * While any death cutscene is active: player update is frozen (same gate as showDeathScreen).
     */
    boolean      deathCutscenePending  = false;
    /** Set when the 3rd death fires the Kamui awakening; consumed once revival ends. */
    private boolean kamuiJustUnlockedThisRevival = false;
    /** Seconds of post-revival immunity remaining (player is invincible, flickers). */
    float        immunityTimer = 0f;
    static final float REVIVAL_IMMUNITY_SECS = 4f;

    // ── PRACTICE SESSION — multi-step hands-on ability tutorial ──────────────
    /** Which ability is currently being taught; null when no session is active. */
    Progression.Ability practiceAbility  = null;
    /** Per-step list for the active tutorial; null = not running. */
    java.util.List<AbilityPractice.Step> practiceSteps = null;
    /** Index into practiceSteps for the current step. */
    int practiceStepIndex = 0;
    /** Seconds elapsed on the current step. */
    float practiceStepAge = 0f;
    /** Context passed to step lambdas (reused across steps). */
    final AbilityPractice.StepCtx practiceCtx = new AbilityPractice.StepCtx();
    /** True once the current step's done() predicate fired once (latch). */
    boolean practiceStepDone = false;
    /** Celebration pause: seconds remaining after done() fired before advancing. */
    float practiceCelebration = 0f;
    static final float PRACTICE_TIMEOUT = 60f;   // per step
    static final float PRACTICE_CELEBRATE_SECS = 1.4f;
    /** Short warning shown at the top of the practice card; cleared when timer expires. */
    String practiceWarnText  = null;
    float  practiceWarnTimer = 0f;
    /** Queued practice sessions for the current wave (e.g. wave 8 = Seal then Kamui). */
    final java.util.ArrayDeque<Progression.Ability> practiceQueue = new java.util.ArrayDeque<>();
    /** ENTER edge-detect (so the card-dismiss ENTER can't immediately skip). */
    private boolean lastPracticeEnter = false;
    /** Set after the wave-9 ending cutscene fires; stops further waves. */
    boolean gameEnded = false;
    /** Camera yaw snapped each frame for AbilityPractice.spawnDummy. */
    float lastCameraYaw = 0f;

    final ImString chatInput = new ImString(256);
    final List<String> chatHistory = new ArrayList<>();
    final ImString seedInput = new ImString(32);

    final Inventory inventory = new Inventory();
    int   selectedSlot  = 0;
    Block selectedBlock = Block.AIR;
    float digParticleTimer = 0f;

    final Block[] hotbar = {
            Block.GRASS, Block.DIRT, Block.STONE, Block.WATER,
            Block.AIR, Block.AIR, Block.AIR, Block.AIR, Block.AIR
    };

    final List<DroppedItem> droppedItems = new ArrayList<>();
    private final Map<Block, Mesh>  itemMeshes   = new HashMap<>();

    // ── ENEMY SYSTEM ──────────────────────────────────────────────────────────
    EnemyManager enemyManager;

    // ── Animated enemy rendering ──────────────────────────────────────────────
    // Single shared model definition; each enemy has its own AnimPlayer instance
    // (independent animation time) stored by enemy ID.
    private com.leaf.game.anim.AnimModel enemyAnimModel = null;
    private com.leaf.game.anim.AnimModel slimeAnimModel = null;
    private com.leaf.game.anim.AnimModel golemAnimModel = null;
    private final java.util.Map<Integer, com.leaf.game.anim.AnimPlayer> enemyAnimPlayers
            = new java.util.HashMap<>();
    /** Edge-detect for P key to spawn enemies. */
    private boolean lastP = false;
    private boolean lastZeroKey = false;   // debug: spawn a GUARDIAN with [0]

    // ── TODO'S TECHNIQUE (J key) ──────────────────────────────────────────────
    float   todoSwapCooldown = 0f;
    private boolean lastJ            = false;

    // ── QUAGMIRE (M key) ─────────────────────────────────────────────────────
    float   quagmireCooldown = 0f;
    private boolean lastM            = false;
    /**
     * Active mud waves.  Each float[12]:
     *   [0-2] current position   [3-4] direction (x,z normalised)
     *   [5]   speed              [6]   dist travelled   [7] total dist
     *   [8]   target enemy ID    [9]   (reserved)
     *   [10]  last placed block X  [11] last placed block Z
     */
    final List<float[]> mudWaves = new ArrayList<>();

    // ── STONE CANON (I key) ───────────────────────────────────────────────────
    boolean isChargingStoneCanon    = false;
    float   stoneCanonCharge        = 0f;
    private float   stoneCanonNextConsume   = 0f;  // countdown to next block consumed
    private int     stoneCanonBlocksConsumed = 0;
    private Vector3f stoneCanonLockedPos    = null; // position locked when charging starts
    private Vector3f stoneCanonGroundPos   = null; // ground point in front where boulder rises
    float   stoneCanonCooldownTimer = 0f;
    private boolean lastI                   = false;
    private final List<ActiveStoneShot> stoneShotList = new ArrayList<>();

    /** A stone projectile fired by the Stone Canon ability. */
    private static class ActiveStoneShot {
        final Vector3f pos;
        final Vector3f vel;
        float scale;
        final float chargeF;
        float lifetime;
        ActiveStoneShot(Vector3f pos, Vector3f vel, float scale, float chargeF) {
            this.pos = new Vector3f(pos);
            this.vel = new Vector3f(vel);
            this.scale   = scale;
            this.chargeF = chargeF;
            this.lifetime = GameConfig.stoneCanonLifetime;
        }
    }

    // ── PAPER FIGURINE SUBSTITUTE (V hold) ────────────────────────────────────
    /** True while V is held and the ability is ready — next hit will be negated. */
    boolean substitutePrimed   = false;
    float   substituteCooldown = 0f;
    /**
     * Live paper dummies.  Each entry: float[5] = { x, y, z, timer, maxTimer }.
     * Timer counts down from substituteDummyLifetime to 0, then explodes.
     */
    private final List<float[]> substituteDummies = new ArrayList<>();

    // ── TUTORIAL / HELP ───────────────────────────────────────────────────────
    /** F1 toggles the full controls reference overlay. */
    boolean showHelp       = false;
    private boolean lastF1         = false;
    /** Auto-dismiss welcome banner shown when the game first loads. */
    float   welcomeTimer   = 0f;
    private boolean welcomeStarted = false;

    // ── Onboarding tutorial ────────────────────────────────────────────────────
    // A designed, staged tutorial (see TutorialManager). Spawns enemies by hand at
    // controlled difficulty and only turns on infinite waves once the player
    // graduates. Created when the world finishes preloading.
    TutorialManager tutorial = null;
    /** One-liner contextual hint (e.g. first stand deploy, first seal placed). */
    String  hintText       = null;
    float   hintTimer      = 0f;
    private boolean standHintShown = false;
    private boolean sealHintShown  = false;
    /** Edge-detect for contextual hint triggers. */
    private boolean wasStandDeployed = false;
    private int     lastSealCount    = 0;

    float   breakProgress = 0.0f;
    int     breakX, breakY, breakZ;
    boolean breakingActive = false;

    // ── KAMUI DISTORTION FBO ─────────────────────────────────────────────────
    // When Kamui is active the 3-D scene is rendered into an off-screen texture,
    // then a post-process distortion shader warps it before ImGui is composited.
    private int  kamuiFbo        = 0;
    private int  kamuiFboTex     = 0;
    private int  kamuiFboRbo     = 0;   // depth renderbuffer
    private int  kamuiScreenQuad = 0;   // VAO for the full-screen triangle pair
    private int  kamuiFboW       = 0;   // last known FBO size — recreate on resize
    private int  kamuiFboH       = 0;
    private com.leaf.game.render.Shader distortShader = null;
    /** All ImGui HUD rendering — see WindowHud.java */
    WindowHud hud;

    // ── PRE-GENERATION STATE ──────────────────────────────────────────────────
    boolean     isPreloading = false;
    int         preloadRadius = 10;
    private final List<Chunk> chunksToGenerate = new ArrayList<>();
    private int         totalPreloadCount    = 0;
    private int         currentPreloadProgress = 0;
    // Network Timing & State Trackers
    private double lastNetSendTime = 0;
    private int    lastNetState = 0;
    private boolean lastNetHooked = false;

    // ── TIME CONTROLLER ───────────────────────────────────────────────────────
    // Accessed every frame: TimeController.getInstance().
    // Keybindings:
    //   Hold R → slow motion  (GameConfig.timeSlowScale ≈ 0.15)
    //   Hold Y → fast time    (GameConfig.timeFastScale  ≈ 4.0)
    // (T is reserved for chat; R was chosen as the slow-time key.)

    // ── SCREEN SHAKE (Ground Smash landing effect) ────────────────────────────
    // A damped sinusoidal camera offset applied for smashShakeDuration seconds.
    // Window temporarily offsets camera.position before rendering, then restores
    // it so Player's position accounting is unaffected.
    private float smashShakeTimer = 0f;   // counts down from smashShakeDuration
    public final Random shakeRng = new Random();
    private float activeShakeAmplitude = GameConfig.smashShakeAmplitude; // Dynamic amplitude
    private float activeShakeDuration  = GameConfig.smashShakeDuration;  // Dynamic duration

    // ── METEOR EFFECT (Smash visual) ─────────────────────────────────────────
    // When a ground smash begins, a STAR_IRON DroppedItem is spawned high above
    // the player and falls at high speed — giving the descent a "meteor crashing
    // from the sky" visual without requiring a particle system.
    private boolean wasSmashing      = false;
    private boolean wasCannonballing = false;
    private boolean wasCharging      = false;   // edge-detect for preload trigger
    /** Last-frame mana, used to detect the moment mana hits zero. */
    private float   lastMana         = -1f;
    /** Last-frame camera position — lets us derive listener velocity for Doppler. */
    private final Vector3f lastListenerPos = new Vector3f();
    private boolean listenerPosInit = false;
    /** Last muffle amount sent to OpenAL; only re-sent when it moves meaningfully. */
    private float   lastMuffle       = 0f;
    /** Last reverb environment — only call setEnvironment when this changes. */
    private int     lastEnv          = AudioManager.ENV_NONE;

    // ── Water sound state ────────────────────────────────────────────────────
    private boolean lastFeetInWater  = false;
    private boolean lastCamSubmerged = false;

    // ── Wind stinger timers ──────────────────────────────────────────────────
    // wind_harsh: random gusts fired during fast air movement.
    // caveWindCooldown: wind_cemetery eerie stinger, fires slowly in cave env.
    private float windStingerCooldown = 0f;
    private float caveWindCooldown    = 5f;   // start at 5 s so it doesn't fire on room entry
    // windFade: 0→1 multiplier that smoothly fades wind beds in/out instead of
    // abruptly starting/stopping them (avoids pops and sudden silence on landing).
    private float windFade            = 0f;

    // ── Snow weather + ambient wind gusts ─────────────────────────────────────
    // Accumulated game-time drives snowflake animation (deterministic, no state).
    private float snowTimeAccum    = 0f;
    private float snowIntensity    = 0f;  // 0..1, updated each frame from altitude
    // Gust system: fires randomly, audible even when standing still, tilts snow.
    private float windGustStrength = 0f;   // smoothed 0..1 current strength
    private float windGustAngle    = 0f;   // radians — which way the gust blows
    private float windGustTimer    = 10f + (float)(Math.random() * 18f); // until next gust
    private float windGustDuration = 0f;   // remaining seconds of current gust

    // ── Footstep state ───────────────────────────────────────────────────────
    // Walking/running files are long loops — we keep one looping continuously
    // rather than re-triggering them as one-shots.
    private String  activeStepLoop    = null; // which step loop is currently running

    // ── Flight-stop swoosh ────────────────────────────────────────────────────
    private boolean lastFlightMode    = false; // player.debugMode last frame (before update)

    // ── Crystal dig sequence (plays clank1..4 in shuffled random order) ─────
    // cystal_clank2 has a typo in the filename on disk — keep it to match the file.
    static final String[] CRYSTAL_CLANKS  = {
            "crystal_clank1", "cystal_clank2", "crystal_clank3", "crystal_clank4"
    };
    private int[]   crystalClankOrder = {0, 1, 2, 3};
    private int     crystalClankIdx   = 4;    // force a shuffle on first use

    // ── Dig sound timer (fires while holding break key) ────────────────────
    float digSoundTimer = 0f;   // package-private — reset from WindowHud.updateBreaking()
    float digPreDelay   = 0f;   // how long break key has been held — sounds start after a brief delay
    float   pathReadiness    = 0f;      // 0..1 shown in HUD during charging
    private boolean clientSpawnedAtHost = false;

    // ── PHYSICAL FRAMEBUFFER SIZE ─────────────────────────────────────────────
    // Tracked as instance fields so portal/kamui FBO code can read them without
    // needing them passed as parameters.  Updated once per frame via
    // glfwGetFramebufferSize inside loop().  On Retina/HiDPI displays these are
    // 2× the logical window size — the portal viewportSize uniform MUST use
    // these values, not the hardcoded PORTAL_FBO_W/H, to avoid UV tiling.
    private final int[] fw = {1280};
    private final int[] fh = {720};

    // ═══════════════════════════════════════════════════════════════════════════
    //  NON-EUCLIDEAN "LAYERED ROOMS"  (toggle with F6)
    // ═══════════════════════════════════════════════════════════════════════════
    // A 2×2 quadrant complex with a solid central cross-pillar. Walk CLOCKWISE
    // around the pillar and you pass through an unbounded sequence of distinct
    // rooms (1→2→3→4→5→6…) instead of looping back after four. The trick: the
    // solid centre fully occludes the diagonal quadrant, so each time you round a
    // corner we silently re-skin the now-hidden diagonal room to be the NEXT room
    // in the sequence. You never see the swap; the space feels infinite.
    //
    // The whole structure is built once at a fixed faraway spot (one chunk) and
    // sealed, so it never collides with terrain. F6 teleports you in/out.
    private static final int NER_X0     = 2450;  // min-corner world X
    private static final int NER_Y0     = 400;   // floor Y (well above terrain)
    private static final int NER_Z0     = 2450;  // min-corner world Z
    private static final int NER_ROOM   = 5;     // interior size of each quadrant
    private static final int NER_HT     = 5;     // interior height
    private static final int NER_SPAN   = 2 * NER_ROOM + 3; // 13 = walls+quads+divider
    private static final int NER_CX     = NER_X0 + NER_SPAN / 2; // centre column (rel 6)
    private static final int NER_CZ     = NER_Z0 + NER_SPAN / 2; // centre row    (rel 6)
    // Distinct OPAQUE blocks (transparent ones would break the occlusion). One per
    // room, cycled — so even far-apart rooms read as clearly different spaces.
    private static final Block[] NER_PALETTE = {
        Block.RED_SAND, Block.CRYSTAL_BASE, Block.SNOW, Block.GRASS,
        Block.STAR_IRON, Block.SAND, Block.BONE, Block.SCORCHED_STONE,
    };
    private boolean nerBuilt  = false;
    private boolean nerActive = false;
    private int     nerRoom   = 1;     // current logical room number (climbs forever)
    private int     nerQuad   = 0;     // physical quadrant: 0=NW 1=NE 2=SE 3=SW (clockwise)
    private final Block[] nerQuadBlock = new Block[4]; // palette block currently applied per quad
    // Where the player was before entering, so F6 can drop them back.
    private float   nerPrevX, nerPrevY, nerPrevZ, nerPrevYaw, nerPrevPitch;
    private boolean nerPrevWaves = true;   // wave-spawner state to restore on exit
    private boolean lastF6 = false;

    // ── CANYON WARP (F5) ───────────────────────────────────────────────────────
    // F5 toggles a teleport to the fixed Shadertoy-style canyon region and back.
    // We drop the player in high above the centre, hover while its chunks stream
    // in, then snap them to the surface (no death-fall).
    private boolean lastF5 = false;
    private boolean atCanyon = false;            // currently warped to the canyon?
    private boolean canyonSettlePending = false; // waiting for canyon chunks to mesh
    private float   canyonReturnX, canyonReturnY, canyonReturnZ; // where to warp back to

    // ═══════════════════════════════════════════════════════════════════════════
    //  "ORBITAL ANNIHILATION"  — fully-3D cinematic strike (fire with F7)
    // ═══════════════════════════════════════════════════════════════════════════
    // Everything is real 3D geometry + the terrain "lidar" scan shader; the only
    // remaining 2D is nothing (no decals/rain). Phases:
    //   CHARGE  (0.0–3.0): gyroscope torus rings spin up + pulsing core; the world
    //                      darkens; flat ground rings spawn and creep outward.
    //   IMPLODE (3.0–4.2): all rings whip inward (exp ease-in), collide at the core
    //                      → detonation flash, and the lidar wavefront fires OUT.
    //   SCANOUT (4.2–6.6): wavefront sweeps the terrain (glowing voxel wireframe);
    //                      anti-gravity green embers float up filling the volume.
    //   CARVE   (6.6–7.2): wavefront implodes back → the crater is carved → a beat.
    //   LASER   (7.2–11.5): volumetric core beam + helix satellites + 3D shockwave
    //                      + voxel debris + environmental flash + violent shake.
    private boolean orbitalActive  = false;
    private float   orbitalT       = 0f;     // seconds since fire
    private float   orbEpiX, orbEpiY, orbEpiZ; // epicentre (block centre)
    private boolean orbCarved      = false;   // one-shot: crater carved
    private boolean orbStruck      = false;   // one-shot: laser flash + debris burst
    private boolean lastF7         = false;
    private final Matrix4f orbProjView = new Matrix4f(); // last frame's proj*view (unused by 3D path; kept for safety)
    // Live scan parameters driven by the phase timeline (read by the render path).
    private float   orbScanRadiusW  = 0f;   // wavefront radius in WORLD units
    private float   orbScanIntensity= 0f;   // emissive gain for the lidar scan
    private float   orbFlashAmt     = 0f;   // environmental white flash (0..~1.7)
    private boolean orbDark         = false;// true = real lighting blackout (sun/ambient/sky → black)
    private com.leaf.game.render.Shader bloomShader = null; // searing-bloom post-process
    // 3D effect meshes (unit-sized, built once, drawn emissive with per-object MVP).
    private com.leaf.game.render.Mesh orbTorus, orbSphere, orbCyl, orbCube;
    private com.leaf.game.render.Mesh radarFan;   // flat afterglow wedge (angular bright→0 fade)
    /** Lightweight 3D particles for embers (float up) and debris (burst out). */
    private static final class OrbParticle {
        float x, y, z, vx, vy, vz, life, maxLife, size, r, g, b;
    }
    private final java.util.List<OrbParticle> orbParticles = new java.util.ArrayList<>();
    // Phase boundaries (seconds)
    private static final float ORB_CHARGE = 3.0f, ORB_IMPLODE = 4.2f, ORB_SCANOUT = 6.6f,
                               ORB_CARVE  = 7.2f, ORB_LASER   = 7.6f, ORB_END     = 11.5f;
    private static final float ORB_DARK_START = 0.6f;  // blackout begins shortly after fire
    private static final int   ORB_CRATER_R   = 12;    // crater radius (blocks)
    // Tinnitus post-effect (runs after ORB_END, independent of orbitalActive).
    private float  tinnitusTimer = 0f;
    private static final float TINNITUS_DUR = 7.0f;   // seconds

    // ═══════════════════════════════════════════════════════════════════════════
    //  "THE WORLD" — time-stop domain (F8)
    // ═══════════════════════════════════════════════════════════════════════════
    // An expanding sphere of photographic-negative reality bursts from the player's
    // feet; everything inside is inverted + shifted to electric blue, enemies and
    // their projectiles freeze, then the domain collapses back. Pure spectacle.
    private boolean timeStopActive = false;
    private float   timeStopT      = 0f;
    private float   tsCenterX, tsCenterY, tsCenterZ;   // anchored at activation
    private float   tsRadiusNow    = 0f;               // live radius (world units)
    private boolean lastF8         = false;
    // ── RADAR SWEEP (F10) — one-shot scan over the REAL world ────────────────────
    // A 3D radar scope projects onto the terrain (rings + spokes + rotating sweep
    // arm with an opaque→transparent afterglow), enemies light up in wireframe and
    // "ping" as the arm passes them, then it all fades back to normal.
    private boolean vlActive   = false;
    private float   vlT        = 0f;          // seconds into the scan
    private float   vlCx, vlCy, vlCz;         // scope origin (player position at trigger)
    private float   vlRadiusNow = 0f;         // scope radius (world units)
    private float   vlAmountNow = 0f;         // 0→1→0 fade envelope
    private float   vlSweepNow  = 0f;         // sweep-arm angle (radians)
    private boolean lastF10     = false;
    private static final float VL_MAXR   = 200f;   // scope reach (blocks)
    private static final float VL_RAMP   = 1.0f;   // fade-in (s)
    private static final float VL_HOLD   = 6.0f;   // active scanning (s)
    private static final float VL_RETURN = 1.5f;   // fade-out (s)
    private static final float VL_END    = VL_RAMP + VL_HOLD + VL_RETURN;
    private static final float VL_SWEEP_SPEED = 3.14159f;  // rad/s (~1 rotation / 2 s)

    // ── CHOCOLATE DISCO GRID ('.' key) ─────────────────────────────────────────
    static final int   CD_ROWS    = 9;
    static final int   CD_COLS    = 9;
    static final int   CD_CELL    = 3;     // blocks per cell side (27×27 footprint)
    static final int   CD_SPAN    = CD_ROWS * CD_CELL;   // 27 — full footprint
    static final float CD_HALF    = CD_SPAN * 0.5f;      // 13.5
    static final float CD_LW      = 0.16f; // grid-line glow width
    static final float CD_WALL    = 5.0f;  // wireframe box height
    static final float CD_SPAWN   = 0.22f; // spawn-in animation seconds (snappy)
    static final float CD_DET_DUR = 0.32f; // detonation flash duration (s) — quick
    static final float CD_RING_DUR = 0.6f; // ground shockwave ring lifetime (s)
    static final int   CD_CRUSH_H = 22;    // blocks above grid crushed on detonation
    static final float CD_LIFT    = 0.08f; // glow lift above terrain (anti z-fight)

    boolean cdActive   = false;
    boolean showDiscoUI = false;
    float   cdSpawnT   = 0f;               // 0→1 spawn animation (then negative = despawn countdown)
    private boolean lastDisco  = false;
    float   cdGridX, cdGridY, cdGridZ;     // world-space grid centre (Y = aim point)
    final boolean[][] cdMarked  = new boolean[CD_ROWS][CD_COLS];
    final float[][]   cdDetT    = new float[CD_ROWS][CD_COLS];    // >0 = flashing
    final float[][]   cdRingT   = new float[CD_ROWS][CD_COLS];    // >0 = shockwave ring alive
    final boolean[][] cdBlasted = new boolean[CD_ROWS][CD_COLS];  // blocks already carved
    int   cdHoverR = -1, cdHoverC = -1;
    private com.leaf.game.render.Mesh cdMesh = null;    // rebuilt when state changes
    boolean cdMeshDirty = true;

    // ── DEPRIVATION DOMAIN (Water God Stance) — ['] key ─────────────────────────
    // Absolute stillness. The player locks in place. Every entity that moves inside
    // the golden hemisphere is instantly counter-struck with a lingering golden thread
    // and a dimensional-slash ring. Thread web builds over time. All configurable via
    // GameConfig (depRadius, depDuration, depDamage, …).
    private boolean depActive    = false;
    private float   depT         = 0f;   // seconds since domain activated
    private float   depCooldown  = 0f;   // seconds until usable again (counts down)
    private float   depStrike    = 0f;   // 0→1 strike flash (decays 6×/sec); fed to shader
    private boolean lastApostr   = false;
    private float   depX, depY, depZ;   // player position frozen at activation
    private float   depDetectTimer = 0f; // accumulates until DEP_TICK, then samples
    /** Previous positions of each enemy for velocity estimation. int = System.identityHashCode. */
    private final java.util.HashMap<Integer, org.joml.Vector3f> depPrevPos = new java.util.HashMap<>();
    /** Active slash crescents (the "slash storm"): {x,y,z, yaw,pitch,roll, age,life, startScale,endScale}. */
    private final java.util.ArrayList<float[]> depSlashFx = new java.util.ArrayList<>();
    /** Voxel gib debris from sliced enemies: {x,y,z, vx,vy,vz, size, age,life}. */
    private final java.util.ArrayList<float[]> depGibs = new java.util.ArrayList<>();
    /** Anime sword-sweep crescent mesh (unit, in local XY plane; built once). */
    private com.leaf.game.render.Mesh depCrescent = null;

    private static final float TS_MAXR   = 220f;   // domain reach (blocks)
    private static final float TS_EXPAND = 1.8f;   // expansion duration (s)
    private static final float TS_SHRINK = 0.9f;   // collapse duration (s)
    // TS_HOLD and TS_END are read from GameConfig at runtime so they can be tuned.
    private float tsHold() { return GameConfig.timeStopHoldSecs; }
    private float tsEnd()  { return TS_EXPAND + tsHold() + TS_SHRINK; }
    // ─────────────────────────────────────────────────────────────────────────

    public void run() {
        init();
        loop();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
        // Shut down the background chunk-generation thread pool so the JVM can
        // exit cleanly.  Without this, non-daemon worker threads kept the Java
        // process alive indefinitely after the window was closed.
        if (world != null) world.shutdown();
        // Free all GPU model/texture assets before the GL context is destroyed.
        com.leaf.game.render.AssetManager.get().cleanup();
        System.exit(0);
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE,               GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE,             GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,        GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "Minecraft Clone", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (!networkInitialized || isPreloading) return;

            // ── CUTSCENE swallows input: ENTER advances, ESC skips.
            // On the ENDING last slide: double-tap SPACE exits (matches the on-screen hint).
            if (cutscene.isActive()) {
                if (action == GLFW_PRESS) {
                    if (key == GLFW_KEY_ESCAPE) cutscene.skip();
                    else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) cutscene.advance();
                    else if (key == GLFW_KEY_SPACE && cutscene.getKind() == CutsceneManager.Kind.ENDING) {
                        cutscene.tapSpaceForEnding();  // double-tap exits ending
                    }
                }
                return;
            }

            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                if (showChat) {
                    showChat = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    firstMouse[0] = true;
                } else if (showHelp) {
                    // Help screen takes priority over pause so ESC cleanly dismisses it
                    showHelp = false;
                    boolean stillOverlay = showDebug || showNoiseViewer || isPaused;
                    glfwSetInputMode(window, GLFW_CURSOR,
                            stillOverlay ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                    if (!stillOverlay) firstMouse[0] = true;
                } else if (showNoiseViewer) {
                    showNoiseViewer = false;
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    firstMouse[0] = true;
                } else {
                    isPaused = !isPaused;
                    glfwSetInputMode(window, GLFW_CURSOR,
                            isPaused ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                    if (!isPaused) firstMouse[0] = true;
                }
            }

            if (isPaused) return;

            if (key == GLFW_KEY_F1 && action == GLFW_RELEASE && !showChat) {
                showHelp = !showHelp;
                boolean overlay1 = showHelp || showDebug || showNoiseViewer;
                glfwSetInputMode(window, GLFW_CURSOR,
                        overlay1 ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                if (!overlay1) firstMouse[0] = true;
            }

            // Skip the onboarding tutorial → jump straight to endless waves.
            if (key == GLFW_KEY_F2 && action == GLFW_RELEASE && !showChat
                    && tutorial != null && tutorial.isActive()) {
                tutorial.skip();
            }

            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE && !showChat) {
                showDebug = !showDebug;
                // F3 releases cursor so ImGui debug elements are clickable.
                // The cursorPos callback returns early when showDebug is true,
                // so camera won't spin when the cursor is free.
                boolean overlay3 = showHelp || showDebug || showNoiseViewer || isPaused;
                glfwSetInputMode(window, GLFW_CURSOR,
                        overlay3 ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                if (!overlay3) firstMouse[0] = true;
            }

            if (key == GLFW_KEY_F4 && action == GLFW_RELEASE && !showChat) {
                showNoiseViewer = !showNoiseViewer;
                boolean overlay4 = showHelp || showDebug || showNoiseViewer;
                glfwSetInputMode(window, GLFW_CURSOR,
                        overlay4 ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
                if (!overlay4) firstMouse[0] = true;
            }

            // F6 — toggle the non-Euclidean "Layered Rooms" anomaly.
            // (Edge is detected in the game loop via lastF6.)
            if (key == GLFW_KEY_F6 && action == GLFW_RELEASE && !showChat) {
                lastF6 = false;
            }

            // F9 — DEV: instantly clear the current wave / force the next wave
            if (key == GLFW_KEY_F9 && action == GLFW_RELEASE) {
                // Kill all alive enemies so the wave-clear detector fires next tick.
                if (enemyManager != null) enemyManager.getEnemies().forEach(e -> e.alive = false);
                // If already between waves, fast-forward (dismiss card / practice).
                showUnlockCard  = false;
                practiceAbility = null; practiceSteps = null;
                if (enemyManager != null && enemyManager.awaitingNextWave) enemyManager.beginNextWave();
                System.out.println("[DEV] F9 — skipped wave " + (enemyManager != null ? enemyManager.getWaveNumber() : "?"));
            }

            // T opens chat (release event only, so holding T for time-dilation is safe
            // because time-dilation uses glfwGetKey in the game loop, not this callback)
            if (key == GLFW_KEY_T && action == GLFW_RELEASE && !showChat && !showDebug && !isPaused) {
                showChat = true;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            }

            if (action == GLFW_PRESS && !showChat) {
                if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) {
                    selectedSlot = key - GLFW_KEY_1;
                }
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {

            if (!networkInitialized || isPreloading || showChat || showNoiseViewer || isPaused || showHelp || showDiscoUI) return;

            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                // Chocolate Disco: LMB marks/unmarks the hovered grid cell.
                // Consumes the click so it doesn't break a block at the same time.
                if (cdActive && action == GLFW_PRESS && cdHoverR >= 0 && cdHoverC >= 0) {
                    cdMarked[cdHoverR][cdHoverC] = !cdMarked[cdHoverR][cdHoverC];
                    if (!cdMarked[cdHoverR][cdHoverC]) { cdDetT[cdHoverR][cdHoverC] = 0f; cdBlasted[cdHoverR][cdHoverC] = false; }
                    cdMeshDirty = true;
                    return; // consumed
                }

                // While piloting the stand drone OR auto-aiming at an enemy, LMB is
                // consumed by the stand — block normal block-breaking.
                // While Kamui is active, LMB drives the absorption system instead.
                boolean standConsumedLMB = player.stand.isInStandPerspective()
                        || player.stand.autoAimedThisFrame;
                boolean kamuiConsumedLMB = player != null && player.abilities.isKamui;
                if (!standConsumedLMB && !kamuiConsumedLMB) {
                    breakingActive = (action == GLFW_PRESS || action == GLFW_REPEAT);
                    if (action == GLFW_RELEASE) { breakProgress = 0.0f; digPreDelay = 0.0f; }
                }
                if (kamuiConsumedLMB && action == GLFW_RELEASE) {
                    // Release resets absorption charge
                    if (player != null) {
                        player.abilities.absorptionCharge = 0f;
                        player.abilities.isAbsorbing      = false;
                    }
                }
            }

            if (button == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS) {
                if (lastTarget != null && lastTarget.hit && selectedBlock != Block.AIR) {
                    if (!playerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ) &&
                            !remotePlayerOccupies(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ)) {
                        if (inventory.useBlock(selectedBlock)) {
                            world.setBlock(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                            world.rebuildChunkAt(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ);
                            if (network != null && network.connected)
                                network.sendPlace(lastTarget.placeX, lastTarget.placeY, lastTarget.placeZ, selectedBlock);
                            // Play a place sound based on block material
                            String placeSnd = blockPlaceSound(selectedBlock);
                            if (placeSnd != null) AudioManager.play(placeSnd);
                        }
                    }
                }
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        ImGui.createContext();
        imguiGlfw.init(window, true);
    }

    private void setupMouseLook(Camera camera) {
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (!networkInitialized || isPreloading || showDebug || showChat || showNoiseViewer || isPaused || showHelp)
                return;
            if (firstMouse[0]) {
                lastMouseX[0] = xpos; lastMouseY[0] = ypos; firstMouse[0] = false; return;
            }
            float dx = (float)(xpos - lastMouseX[0]);
            float dy = (float)(ypos - lastMouseY[0]);
            lastMouseX[0] = xpos; lastMouseY[0] = ypos;

            // Stand perspective: route look to stand's own camera, not the player.
            if (player.stand.isInStandPerspective()) {
                player.stand.applyMouseLook(dx, dy);
                return;
            }

            // Smashing/Rewinding: camera auto-driven, block mouse entirely.
            // Charging: camera locked to aim direction — the system needs this
            //   window to preload exactly the chunks the player will see.
            // Flying (isCannonballing): full 360° free look, no pitch clamp.
            if (!player.isSmashing() && !player.abilities.isCharging()
                    && !isChargingStoneCanon) {
                camera.yaw   += dx * GameConfig.mouseSensitivity;
                camera.pitch -= dy * GameConfig.mouseSensitivity;
                if (!player.abilities.isCannonballing) {
                    camera.clampPitch();
                }
            }
        });
    }

    void startPreload() {
        worldGen.resetSeed(GameConfig.seed);
        world.clearAllChunks();
        world.meshingQueue.clear();
        networkInitialized = true;
        isPreloading       = true;
    }

    /** Begin the next queued practice session, or start the next wave if the queue is empty. */
    private void startNextPractice() {
        Progression.Ability next = practiceQueue.poll();
        if (next != null && player.progression.isUnlocked(next)) {
            java.util.List<AbilityPractice.Step> steps = AbilityPractice.forAbility(next);
            if (steps.isEmpty()) {
                // No tutorial for this ability — keep draining the queue.
                startNextPractice();
                return;
            }
            practiceAbility    = next;
            practiceSteps      = steps;
            practiceStepIndex  = 0;
            practiceStepAge    = 0f;
            practiceStepDone   = false;
            practiceCelebration = 0f;
            practiceCtx.win      = this;
            practiceCtx.flag     = false;
            practiceCtx.snapshot = 0f;
            practiceCtx.counter  = 0;
            // Kill live enemies so the player can focus, pause spawning.
            enemyManager.getEnemies().forEach(e -> { if (e.type != Enemy.Type.DUMMY) e.alive = false; });
            enemyManager.wavesEnabled = false;
            // Run the first step's onEnter.
            AbilityPractice.Step first = steps.get(0);
            practiceCtx.required = first.required;
            if (first.onEnter != null) {
                practiceCtx.stepAge = 0f;
                first.onEnter.accept(practiceCtx);
            }
        } else {
            endPractice();
        }
    }

    /** Advance to the next practice step, or finish if all done. */
    private void advancePracticeStep() {
        practiceStepIndex++;
        if (practiceStepIndex >= practiceSteps.size()) {
            endPractice();
            return;
        }
        practiceStepAge     = 0f;
        practiceStepDone    = false;
        practiceCelebration = 0f;
        practiceCtx.flag     = false;
        practiceCtx.snapshot = 0f;
        practiceCtx.counter  = 0;
        AbilityPractice.Step step = practiceSteps.get(practiceStepIndex);
        practiceCtx.required = step.required;
        if (step.onEnter != null) step.onEnter.accept(practiceCtx);
    }

    /** Finish all practice, remove dummies, resume the wave. */
    private void endPractice() {
        practiceAbility = null;
        practiceSteps   = null;
        // Remove practice dummies.
        enemyManager.getEnemies().forEach(e -> { if (e.type == Enemy.Type.DUMMY) e.alive = false; });
        enemyManager.beginNextWave();
        enemyManager.wavesEnabled = true;
        lastPracticeEnter = true;
    }

    private void loop() {
        // ── MAC OS CRASH FIX ──────────────────────────────────────────────
        // Flush the window creation events and give macOS 150ms to finish
        // building the native window chrome before we slam the CPU with threads.
        glfwPollEvents();
        try { Thread.sleep(150); } catch (InterruptedException e) {}
        // ──────────────────────────────────────────────────────────────────
        int[] ww = new int[1], wh = new int[1];
        glfwGetWindowSize(window, ww, wh);
        glfwGetFramebufferSize(window, fw, fh);

        GL.createCapabilities();
        imguiGl3.init("#version 330");

        // ── Animated enemy model ──────────────────────────────────────────────
        // ModelRenderer has its own mini-shader; init once after GL context exists.
        com.leaf.game.anim.ModelRenderer.init();
        com.leaf.game.anim.AnimModel enemyAnimModelLoaded =
                com.leaf.game.anim.AnimModel.loadFromClasspath("enemy_basic");
        if (enemyAnimModelLoaded != null)
            this.enemyAnimModel = enemyAnimModelLoaded;
        com.leaf.game.anim.AnimModel slimeAnimModelLoaded =
                com.leaf.game.anim.AnimModel.loadFromClasspath("slime");
        if (slimeAnimModelLoaded != null)
            this.slimeAnimModel = slimeAnimModelLoaded;
        com.leaf.game.anim.AnimModel golemAnimModelLoaded =
                com.leaf.game.anim.AnimModel.loadFromClasspath("golem");
        if (golemAnimModelLoaded != null)
            this.golemAnimModel = golemAnimModelLoaded;

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl");

        // ── KEY BINDING REGISTRY ─────────────────────────────────────────────
        // Documents every key and shouts at startup if two actions share one
        // (every letter A–Z is already taken — new abilities use punctuation keys).
        KeyBindings.verify();

        // ── KAMUI DISTORTION SHADER + SCREEN QUAD ────────────────────────────
        distortShader = new com.leaf.game.render.Shader(
                "src/main/resources/shaders/distort_vertex.glsl",
                "src/main/resources/shaders/distort_fragment.glsl");

        // Searing-bloom post-process for the Orbital Annihilation cinematic
        // (reuses the same fullscreen-quad vertex shader as the distort pass).
        bloomShader = new com.leaf.game.render.Shader(
                "src/main/resources/shaders/distort_vertex.glsl",
                "src/main/resources/shaders/bloom_fragment.glsl");

        // Full-screen quad: two triangles covering NDC [-1,1]
        float[] quadVerts = {
            // x      y     u     v
            -1.0f,  1.0f, 0.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        };
        kamuiScreenQuad = org.lwjgl.opengl.GL30.glGenVertexArrays();
        int quadVbo = org.lwjgl.opengl.GL15.glGenBuffers();
        org.lwjgl.opengl.GL30.glBindVertexArray(kamuiScreenQuad);
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, quadVbo);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,
                quadVerts, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
        // attrib 0: position (xy)
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 2, GL_FLOAT, false, 4*4, 0L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        // attrib 1: texcoord (uv)
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 2, GL_FLOAT, false, 4*4, 2L*4);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        org.lwjgl.opengl.GL30.glBindVertexArray(0);

        Camera camera = new Camera();
        setupMouseLook(camera);
        hud = new WindowHud(this);

        // ── AUDIO PRELOAD ──────────────────────────────────────────────────────
        // Warm up the JVM audio mixer so the very first play() call has no delay,
        // then decode every sound file once into heap memory.  Playback from this
        // point on is pure memory copy — no classpath IO, no thread-creation cost.
        //AudioManager.warmup();
        com.leaf.game.render.BlockTextureAtlas.load();
        // Subtle Doppler — enough to feel projectiles/wind pass by without the
        // cartoonish over-pitch a factor of 1.0 produces.
        /**AudioManager.setDopplerFactor(0.7f);
        for (String snd : new String[]{
                // Kamui
                "kamui_enter", "kamui_exit", "kamui_duration", "kamui_distortion",
                "distortion_snap",
                // Combat
                "swing", "hit", "block", "charged_release", "blink",
                "grab_start", "grab_slam", "ground_smash", "fall_smash",
                // Snipe / Manhattan Transfer
                "snipe1", "snipe2", "snipe_loadgun", "snipe_redirect",
                // Abilities
                "stone_canon", "charging",
                "quagmire", "clap", "paper_explode",
                "seal_place", "healing",
                // Lightning
                "lightning_charge", "lightning_strike",
                // UI / feedback
                "mana_empty",
                // Wind (layered beds + stingers)
                "wind/wind_soft", "wind/wind_blow", "wind/wind_big",
                "wind/wind_harsh", "wind/wind_cemetery",
                // Water (one-shots + ambience loop)
                "water/water_splash", "water/water_enter",
                "water/water_leave", "water/water_exit2", "water/water_exit3",
                "water/underwater_ambience",
                // Landing impacts
                "fall_hit", "fall_light", "fall_sandy",
                // Flight
                "swoosh",
                // Footsteps
                "walking", "running", "walking_sand",
                // Block sounds (place + break + dig)
                "block_stone", "block_soil", "block_sand", "block_crystal",
                "stone_digging", "soil_digging", "sand_digging",
                "crystal_clank1", "cystal_clank2", "crystal_clank3", "crystal_clank4",
                // (block sounds now live under "block_stone/soil/sand/crystal" — see preload above)
        }) { //AudioManager.preload(snd);
            }
        **/
        // ── SPAWN POINT ────────────────────────────────────────────────────────
        // Spawn at (777, 250, 777): these coordinates produce non-integer noise
        // inputs at every frequency used by the terrain samplers, ensuring the
        // terrain is visibly seed-dependent right at the start.
        this.player   = new Player(SPAWN_X, 250.0f, SPAWN_Z);
        this.world    = new World();
        this.worldGen = new WorldGen();
        this.noiseVis = new NoiseVisualizer(worldGen);

        // ── ENEMY SYSTEM ─────────────────────────────────────────────────────
        this.enemyManager = new EnemyManager();
        player.stand.setEnemyManager(enemyManager);
        player.attacks.setEnemyManager(enemyManager);
        player.seals.setEnemyManager(enemyManager);      // enables seal-on-enemy attachment
        player.lightning.setEnemyManager(enemyManager); // enables lightning targeting
        player.grab.setEnemyManager(enemyManager);       // enables grab targeting

        TimeController tc = TimeController.getInstance();

        Matrix4f model    = new Matrix4f();
        double   lastTime = glfwGetTime();

        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);

        while (!glfwWindowShouldClose(window)) {
            double now          = glfwGetTime();
            float  rawDeltaTime = (float)(now - lastTime);

            // ── FRAME RATE LIMITER ────────────────────────────────────────────
            float targetMinFrameTime = 1.0f / 120.0f;
            if (rawDeltaTime < targetMinFrameTime) {
                try { Thread.sleep((long)((targetMinFrameTime - rawDeltaTime) * 1000)); }
                catch (InterruptedException ignored) {}
                now          = glfwGetTime();
                rawDeltaTime = (float)(now - lastTime);
            }
            rawDeltaTime = Math.min(rawDeltaTime, 0.1f);
            lastTime = now;

            // ── TIME CONTROLLER UPDATE ────────────────────────────────────────
            // Must happen before anything reads tc.getScale() this frame.
            // Key policy (checked with glfwGetKey so they work as hold-keys):
            //   R → slow  (0.15 scale)
            //   Y → fast  (4.0 scale)
            //   Neither → normal (1.0)
            // Chat box suppresses time dilation so Y/R text doesn't glitch.
            if (!showChat && !isPaused && networkInitialized && !isPreloading) {
                boolean timeUnlocked = player.can(Progression.Ability.TIME);
                boolean rHeld = glfwGetKey(window, GLFW_KEY_R) == GLFW_PRESS && timeUnlocked;
                boolean yHeld = glfwGetKey(window, GLFW_KEY_Y) == GLFW_PRESS && timeUnlocked;
                if (rHeld) {
                    tc.setTargetScale(GameConfig.timeSlowScale);
                } else if (yHeld) {
                    tc.setTargetScale(GameConfig.timeFastScale);
                } else {
                    tc.setTargetScale(1.0f);
                }
            } else {
                // Menu open or not yet in-game — return to normal
                tc.setTargetScale(1.0f);
            }
            tc.update(rawDeltaTime);

            // ── SCALED DELTA TIME for physics ─────────────────────────────────
            ScreenEffectManager.INSTANCE.tick(rawDeltaTime);
            // Cutscene advances on raw time even though the world below is frozen.
            boolean cutsceneWasActive = cutscene.isActive();
            if (cutscene.isActive()) {
                cutscene.update(rawDeltaTime);
            }
            // Ending cutscene just finished — activate flight immediately so the
            // player is already flying when the world comes back.
            if (cutsceneWasActive && !cutscene.isActive()
                    && cutscene.getKind() == CutsceneManager.Kind.ENDING) {
                if (player.progression.isUnlocked(Progression.Ability.FLIGHT)) {
                    player.debugMode = true;   // debugMode is the flight-active flag
                }
            }
            if (!cutscene.isActive() && deathCutscenePending) {
                // Cutscene just ended — check which kind finished.
                CutsceneManager.Kind finishedKind = cutscene.getKind();
                if (finishedKind == CutsceneManager.Kind.KAMUI_AWAKEN) {
                    // Kamui awakening done: chain into revival, then queue Kamui practice.
                    cutscene.startRevival();
                } else if (finishedKind == CutsceneManager.Kind.REVIVAL) {
                    // Revival done — restore at SPAWN with full HP and immunity.
                    deathCutscenePending = false;
                    player.position.set(SPAWN_X, spawnSurfaceY, SPAWN_Z);
                    player.setVelocityY(0f);
                    atCanyon = false; canyonSettlePending = false;  // died at the canyon → reset F5 toggle
                    player.health = player.maxHealth;   // full HP — crystal fully healed you
                    player.mana   = player.maxMana;
                    player.abilities.isKamui          = false;
                    player.abilities.kamuiAutoExited  = false;
                    player.abilities.absorptionCharge = 0f;
                    player.abilities.isDashing        = false;
                    immunityTimer = REVIVAL_IMMUNITY_SECS;   // 4 s of invincibility
                    ScreenEffectManager.INSTANCE.desaturate(0f, 0.8f);
                    // If Kamui was JUST awakened this death (one-shot flag in RunRecords),
                    // show its practice tutorial. Never show again on subsequent deaths.
                    if (kamuiJustUnlockedThisRevival) {
                        kamuiJustUnlockedThisRevival = false;
                        practiceQueue.clear();
                        practiceQueue.add(Progression.Ability.KAMUI);
                        lastPracticeEnter = true;
                        startNextPractice();
                    }
                }
            }
            // Tick immunity — block incoming damage while it's active.
            if (immunityTimer > 0f) immunityTimer -= rawDeltaTime;
            if (damageFlashTimer > 0f) damageFlashTimer -= rawDeltaTime;
            float deltaTime = rawDeltaTime * tc.getScale()
                    * ScreenEffectManager.INSTANCE.getHitStopScale();

            glfwGetWindowSize(window, ww, wh);
            glfwGetFramebufferSize(window, fw, fh);

            if (networkInitialized) {
                // ── ASYNC MESH DRAINER ─────────────────────────────────────────
                // During cannonball and charging: drain the mesh queue much faster.
                // At 140 blocks/s the player crosses ~4 chunk columns per second;
                // the default cap of 3 per frame can't keep up. 20 per frame clears
                // a full preloaded queue within one second without spiking frame time
                // (each buildChunkMeshes call is ~0.5–1 ms on average hardware).
                boolean cannonActive = player.abilities.isCannonballing
                        || player.abilities.isCharging();
                int maxMeshesPerFrame = isPreloading ? 24 : cannonActive ? 20 : 8;
                // Per-frame wall-clock budget for mesh building. Each build also
                // issues a blocking glBufferData upload, so a burst of them (fast
                // movement / cannonball, when many chunks arrive at once) blows
                // the frame and shows as a lag spike. Cap the time spent here and
                // let leftover chunks mesh next frame — terrain pops in over a few
                // frames instead of stalling one. While preloading the player is
                // frozen at spawn, so spikes are invisible; let it run uncapped.
                long meshBudgetNanos = 4_000_000L; // ~4 ms
                long meshStartNanos  = System.nanoTime();
                int meshedThisFrame = 0;
                Chunk readyChunk;
                while (meshedThisFrame < maxMeshesPerFrame
                        && (readyChunk = world.meshingQueue.poll()) != null) {
                    world.buildChunkMeshes(readyChunk);
                    readyChunk.state = Chunk.ChunkState.MESHED;
                    meshedThisFrame++;
                    if (!isPreloading
                            && (System.nanoTime() - meshStartNanos) > meshBudgetNanos) break;
                }

                // ── PREVENT FALLING THROUGH WORLD ─────────────────────────────
                int pCX = Math.floorDiv((int)player.position.x, Chunk.SIZE);
                int pCZ = Math.floorDiv((int)player.position.z, Chunk.SIZE);
                Chunk spawnChunk = world.getChunk(pCX, 0, pCZ);
                boolean isTerrainReady = spawnChunk != null
                        && spawnChunk.state == Chunk.ChunkState.MESHED;

                if (!isTerrainReady) {
                    isPreloading = true;
                    player.position.y = 250.0f;
                    // Keep XZ at spawn if player hasn't moved yet (avoids drift during load)
                    if (player.position.x == 0f && player.position.z == 0f) {
                        player.position.x = SPAWN_X; player.position.z = SPAWN_Z;
                    }
                    world.updateChunks(world, worldGen, player);
                } else {
                    if (isPreloading) {
                        isPreloading = false;
                        int spawnX = (int)Math.floor(player.position.x);
                        int spawnZ = (int)Math.floor(player.position.z);
                        // Scan from ceiling down; find the highest outdoor solid block.
                        // Requires full sky visibility above (no solid block between
                        // the surface and Chunk.HEIGHT) — this prevents spawning on
                        // cave ceilings or inside underground structures.
                        outer:
                        for (int ly = Chunk.HEIGHT - 2; ly >= 1; ly--) {
                            if (!world.getBlock(spawnX, ly, spawnZ).isSolid()) continue;
                            // Require all blocks above to be non-solid (outdoor surface)
                            for (int sy = ly + 1; sy < Chunk.HEIGHT; sy++) {
                                if (world.getBlock(spawnX, sy, spawnZ).isSolid()) continue outer;
                            }
                            spawnSurfaceY     = ly + 1.5f;
                            player.position.y = spawnSurfaceY;
                            break;
                        }
                        // Zero out any velocity accumulated during loading so the player
                        // doesn't punch straight through the freshly-meshed ground.
                        player.setVelocityY(0f);
                        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                        // Begin the designed onboarding tutorial. It controls all
                        // enemy spawning (waves stay off) until the player graduates.
                        if (tutorial == null) {
                            tutorial = new TutorialManager(player, enemyManager, this, camera, world);
                            tutorial.start();
                        }
                        // Start welcome banner once per session
                        if (!welcomeStarted) {
                            welcomeTimer   = 6.0f;
                            welcomeStarted = true;
                        }
                        // Roll the intro cutscene the first time a NEW game reaches spawn.
                        if (playIntroOnSpawn) {
                            playIntroOnSpawn = false;
                            cutscene.startIntro();
                        }
                    }

                    // ── DEATH SCREEN — restart on ENTER ──────────────────────
                    // Must be OUTSIDE the !showDeathScreen gate so ENTER is reachable.
                    if (showDeathScreen) {
                        boolean en = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                        if (en && !lastDeathEnter) {
                            showDeathScreen = false;
                            player.position.set(SPAWN_X, spawnSurfaceY, SPAWN_Z);
                            player.setVelocityY(0f);
                            player.health = player.maxHealth;
                            player.mana   = player.maxMana;
                            player.abilities.isKamui          = false;
                            player.abilities.kamuiAutoExited  = false;
                            player.abilities.absorptionCharge = 0f;
                            player.abilities.isDashing        = false;
                            player.abilities.isCannonballing  = false;
                            // Fresh run: abilities reset to the starting kit, wave back to 1.
                            player.progression.reset();
                            enemyManager.resetForNewRun();
                            practiceAbility = null; practiceSteps = null; practiceQueue.clear();
                            showUnlockCard  = false; lastCardSpace = false; lastPracticeEnter = false;
                            deathCutscenePending = false;
                            gameEnded       = false;
                            RunRecords.INSTANCE.newRun((float) org.lwjgl.glfw.GLFW.glfwGetTime());
                            AudioManager.stopContinuous("kamui_duration");
                            AudioManager.stopContinuous("kamui_distortion");
                        }
                        lastDeathEnter = en;
                    }

                    if (!showChat && !showNoiseViewer && !isPaused && !showHelp && !cutscene.isActive() && !showDeathScreen && !deathCutscenePending) {
                        // ── PLAYER UPDATE (time-scaled) ────────────────────────
                        // Save state BEFORE update so we can detect transitions.
                        // player.update() resets highestY on landing and toggles debugMode.
                        float   savedHighestY    = player.highestY;
                        boolean wasOnGroundAudio = player.isOnGround();
                        boolean wasFlightMode    = player.debugMode;
                        player.update(window, camera, world, deltaTime);

                        // ── DEPRIVATION DOMAIN: enforce position lock every frame ─
                        // Override whatever movement player.update() computed so the
                        // player's feet are nailed to their activation position.
                        if (depActive) {
                            player.position.x = depX;
                            player.position.y = depY;
                            player.position.z = depZ;
                            player.setVelocityY(0f);
                        }

                        lastCameraYaw = camera.yaw;
                        hud.updateBreaking(deltaTime);

                        // ── 3D AUDIO LISTENER ──────────────────────────────────
                        // Move the OpenAL listener to the camera each frame and
                        // derive its velocity from the position delta (Player has
                        // no full velocity vector). Velocity drives Doppler shift.
                        Vector3f listenerVel = new Vector3f();
                        if (listenerPosInit && deltaTime > 1e-4f) {
                            listenerVel.set(camera.position).sub(lastListenerPos).div(deltaTime);
                            // Clamp absurd teleport spikes (kamui / respawn) so we
                            // don't fire a Doppler screech across the whole map.
                            if (listenerVel.length() > 60f) listenerVel.set(0, 0, 0);
                        }
                        AudioManager.updateListener(camera, listenerVel);
                        lastListenerPos.set(camera.position);
                        listenerPosInit = true;

                        // ── REVERB ENVIRONMENT ─────────────────────────────────
                        // Checked every frame but only applied when the zone
                        // actually changes, so there is no per-frame OpenAL cost.
                        //
                        // Priority: underwater > cave > open air.
                        // isUnderRoof(12): tweak the number to change how "tight"
                        // a space needs to be before reverb kicks in.
                        int targetEnv;
                        if (player.isCameraSubmerged()) {
                            targetEnv = AudioManager.ENV_UNDERWATER;
                        } else if (isUnderRoof(12)) {
                            targetEnv = AudioManager.ENV_CAVE;
                        } else {
                            targetEnv = AudioManager.ENV_NONE;
                        }
                        if (targetEnv != lastEnv) {
                            AudioManager.setEnvironment(targetEnv);
                            lastEnv = targetEnv;
                        }

                        // ── FLIGHT STOP SWOOSH ─────────────────────────────────
                        // Fires on the frame flight mode turns off (double-tap space).
                        if (wasFlightMode && !player.debugMode) {
                            AudioManager.play("swoosh", 0.80f);
                        }

                        // ── FOOTSTEPS ─────────────────────────────────────────
                        // Only while grounded, not submerged, and actually moving.
                        // Surface type: sand/red-sand → walking_sand; sprint → running.
                        float horizSpeedStep = (float) Math.sqrt(
                                listenerVel.x * listenerVel.x + listenerVel.z * listenerVel.z);
                        // Walking/running files are long loops — decide which one
                        // should be playing this frame and switch loops on change.
                        String wantedStep = null;
                        if (player.isOnGround()
                                && !player.isCameraSubmerged()
                                && !player.debugMode
                                && horizSpeedStep > 0.8f) {
                            Block underFoot = world.getBlock(
                                    (int)Math.floor(player.position.x),
                                    (int)Math.floor(player.position.y) - 1,
                                    (int)Math.floor(player.position.z));
                            boolean sandy = (underFoot == Block.SAND
                                         || underFoot == Block.RED_SAND);
                            if (sandy)                  wantedStep = "walking_sand";
                            else if (player.isSprinting()) wantedStep = "running";
                            else                           wantedStep = "walking";
                        }

                        if (!java.util.Objects.equals(wantedStep, activeStepLoop)) {
                            if (activeStepLoop != null)
                                AudioManager.stopContinuous(activeStepLoop);
                            if (wantedStep != null)
                                AudioManager.playContinuous(wantedStep, 0.70f);
                            activeStepLoop = wantedStep;
                        }

                        // ── METEOR SPAWN: smash start → STAR_IRON falling from sky ──
                        // Detects the leading edge of isSmashing so the meteor only
                        // spawns once per smash, not every frame of the descent.
                        // Only spawns on rocky/hard ground — looks wrong on sand/beach.
                        boolean nowSmashing = player.isSmashing();
                        if (nowSmashing && !wasSmashing) {
                            // Scan downward — at smash start the player is still airborne,
                            // so position.y - 1 is AIR. Find the first solid block below.
                            Block groundBlock = Block.AIR;
                            int scanX = (int)Math.floor(player.position.x);
                            int scanZ = (int)Math.floor(player.position.z);
                            int scanYStart = (int)Math.floor(player.position.y);
                            for (int sy = scanYStart; sy >= Math.max(0, scanYStart - 200); sy--) {
                                Block b = world.getBlock(scanX, sy, scanZ);
                                if (b.isSolid()) { groundBlock = b; break; }
                            }
                            boolean isRockyGround = groundBlock.hardness >= 2.5f
                                    || groundBlock == Block.GRAVEL;
                            if (isRockyGround) {
                                Vector3f meteorVel = new Vector3f(0f, -GameConfig.smashDescentSpeed * 1.5f, 0f);
                                droppedItems.add(new DroppedItem(
                                        (int)player.position.x,
                                        (int)(player.position.y + 100),
                                        (int)player.position.z,
                                        Block.STAR_IRON,
                                        meteorVel));
                            }
                        }
                        wasSmashing = nowSmashing;

                        // ── SMASH IMPACT HANDLING ──────────────────────────────
                        handleSmashImpact(camera);

                        // ── ATTACK DEBRIS DRAIN ────────────────────────────────
                        // AttackController queues DebrisSpawn records rather than
                        // touching droppedItems directly.  Drain them here each frame.
                        for (AttackController.DebrisSpawn d : player.attacks.pendingDebris) {
                            droppedItems.add(new DroppedItem(d.bx, d.by, d.bz, d.block, d.vel));
                        }
                        player.attacks.pendingDebris.clear();

                        // ── ATTACK SHAKE REQUEST ───────────────────────────────
                        if (player.attacks.shakeRequest > 0f) {
                            float req = player.attacks.shakeRequest;
                            activeShakeDuration  = req * 0.7f;
                            activeShakeAmplitude = 0.12f + req * 0.25f;
                            smashShakeTimer      = activeShakeDuration;
                            player.attacks.shakeRequest = 0f;
                        }

                        // ── STAND DEBRIS DRAIN (Manhattan Transfer) ────────────
                        for (AttackController.DebrisSpawn d : player.stand.pendingDebris) {
                            droppedItems.add(new DroppedItem(d.bx, d.by, d.bz, d.block, d.vel));
                        }
                        player.stand.pendingDebris.clear();

                        // ── STAND SHAKE REQUEST ────────────────────────────────
                        if (player.stand.shakeRequest > 0f) {
                            float req = player.stand.shakeRequest;
                            activeShakeDuration  = req * 0.7f;
                            activeShakeAmplitude = 0.12f + req * 0.25f;
                            smashShakeTimer      = Math.max(smashShakeTimer, activeShakeDuration);
                            player.stand.shakeRequest = 0f;
                        }

                        // ── GRAB SLAM IMPACT ───────────────────────────────────
                        // GrabController.tick() already polled pendingGrabImpact for
                        // the shake request; here we create the crater + ejecta.
                        for (Enemy grabEnemy : enemyManager.getEnemies()) {
                            if (!grabEnemy.pendingGrabImpact) continue;
                            int gx = grabEnemy.grabImpactX;
                            int gy = grabEnemy.grabImpactY;
                            int gz = grabEnemy.grabImpactZ;
                            int gr = GameConfig.grabCraterRadius;
                            world.createImpactCrater(gx, gy, gz, gr);
                            spawnCraterEjecta(gx, gy, gz, gr);
                            enemyManager.processExplosion(
                                    new float[]{ gx + 0.5f, gy + 0.5f, gz + 0.5f, gr * 1.5f },
                                    grabEnemy.grabImpactIsGround
                                            ? GameConfig.grabGroundDamage * 0.4f
                                            : GameConfig.grabWallDamage   * 0.4f);
                            if (grabEnemy.grabImpactIsGround) AudioManager.play("ground_smash");
                            ScreenEffectManager.INSTANCE.hitStop(3);
                            ScreenEffectManager.INSTANCE.flashGrabSlam();
                            // Brutal impact shake — bigger than standard smash
                            activeShakeDuration  = grabEnemy.grabImpactIsGround ? 0.75f : 0.55f;
                            activeShakeAmplitude = grabEnemy.grabImpactIsGround ? 0.38f : 0.28f;
                            smashShakeTimer = Math.max(smashShakeTimer, activeShakeDuration);
                            // Signal WindowHud to do an orange impact flash
                            player.grab.throwFlash = Math.max(player.grab.throwFlash, 0.40f);
                            // pendingGrabImpact is reset at the top of Enemy.update() next frame
                        }

                        // ── GRAB THROW LAUNCH RECOIL ───────────────────────────
                        // throwFlash is set the frame a wall/slam throw fires.
                        if (player.grab.throwFlash > 0f) {
                            // Brief camera judder — feels like pulling a trigger
                            smashShakeTimer = Math.max(smashShakeTimer, 0.12f);
                            activeShakeDuration  = 0.12f;
                            activeShakeAmplitude = 0.14f;
                            // (throwFlash decays automatically in GrabController.tick)
                        }

                        // ── GRAB SHAKE FALLBACK (controller's shakeRequest) ─────
                        if (player.grab.shakeRequest > 0f) {
                            smashShakeTimer = Math.max(smashShakeTimer,
                                    GameConfig.grabShakeDuration);
                            player.grab.shakeRequest = 0f;
                        }

                        // ── ENEMY SYSTEM UPDATE ────────────────────────────────
                        // Drain explosion events from attack and stand bolts.
                        for (float[] ev : player.attacks.pendingExplosions) {
                            enemyManager.processExplosion(ev);
                        }
                        player.attacks.pendingExplosions.clear();

                        for (float[] ev : player.stand.pendingExplosions) {
                            enemyManager.processExplosion(ev);
                        }
                        player.stand.pendingExplosions.clear();

                        for (float[] ev : player.attacks.pendingMeleeArcs) {
                            enemyManager.processMeleeArc(ev);
                        }
                        player.attacks.pendingMeleeArcs.clear();

                        // Update all enemies (gravity, AI, death fade, etc.).
                        // THE WORLD: time is frozen → enemies and their projectiles
                        // stop dead while the player keeps moving (dt=0 freezes them).
                        enemyManager.update(timeStopActive ? 0f : deltaTime, world, player.position);

                        // ── PROCESS GOLEM BLOCK BREAKING ──
                        for (Enemy e : enemyManager.getEnemies()) {
                            if (e.pendingBlockBreak) {
                                e.pendingBlockBreak = false;

                                // Golems smash a 2-block high hole through the wall
                                for (int dy = 0; dy <= 1; dy++) {
                                    int bx = e.breakX;
                                    int by = e.breakY + dy;
                                    int bz = e.breakZ;
                                    Block b = world.getBlock(bx, by, bz);

                                    // Don't let them break indestructible terrain
                                    if (b.isSolid() && b != Block.STAR_IRON && b != Block.MEGALITH && b != Block.MEGALITH_CARVED) {
                                        world.setBlock(bx, by, bz, Block.AIR);
                                        world.rebuildChunkAt(bx, by, bz);

                                        // Spawn flying debris
                                        Vector3f ejectVel = new Vector3f(
                                                (float)(shakeRng.nextFloat() - 0.5f) * 6f,
                                                3f + shakeRng.nextFloat() * 4f,
                                                (float)(shakeRng.nextFloat() - 0.5f) * 6f);
                                        droppedItems.add(new DroppedItem(bx, by, bz, b, ejectVel));
                                    }
                                }
                                // Play massive sound and shake the screen!
                                //AudioManager.playAt("ground_smash", e.position, (Vector3f)null, 40f);
                                //activeShakeDuration = 0.25f;
                                //activeShakeAmplitude = 0.15f;
                                //smashShakeTimer = Math.max(smashShakeTimer, activeShakeDuration);
                            }
                        }
                        if (tutorial != null) tutorial.update(deltaTime);

                        // ── WAVE CLEARED → ending / unlock card ───────────────
                        if (enemyManager.awaitingNextWave && !showUnlockCard && practiceAbility == null) {
                            int waveJustCleared = enemyManager.lastClearedWave;

                            if (waveJustCleared >= Progression.ENDING_WAVE && !gameEnded) {
                                // ── WAVE 10 = FLIGHT = THE ENDING ─────────────
                                player.progression.unlockForWave(Progression.ENDING_WAVE); // grant FLIGHT
                                float elapsed = (float) glfwGetTime() - RunRecords.INSTANCE.runStartTime;
                                int em = (int)(elapsed / 60f), es = (int)(elapsed % 60f);
                                cutscene.endingStat = String.format(
                                        "Enemies defeated: %d      Time: %d:%02d",
                                        enemyManager.totalKills, em, es);
                                cutscene.startEnding();
                                enemyManager.wavesEnabled = false;  // world is yours now — no more waves
                                enemyManager.beginNextWave();        // clear the awaiting flag
                                gameEnded = true;
                            } else {
                                // Normal wave: unlock this tier and ALWAYS show the card
                                // (abilities reset each run, so there's always something new).
                                java.util.List<Progression.Ability> gained =
                                        player.progression.unlockForWave(waveJustCleared);
                                if (gained.isEmpty()) gained = player.progression.abilitiesForWave(waveJustCleared);
                                if (gained.isEmpty()) {
                                    enemyManager.beginNextWave();   // no tier (shouldn't happen ≤ wave 9)
                                } else {
                                    unlockCardWave      = waveJustCleared;
                                    unlockCardAbilities = gained;
                                    showUnlockCard      = true;
                                    AudioManager.play("seal_collect");
                                }
                            }
                        }

                        // Dismiss the card with ENTER → queue practice (complex abilities) or next wave.
                        if (showUnlockCard) {
                            boolean en = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                    || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                            if (en && !lastCardSpace) {
                                showUnlockCard = false;
                                // Every wave unlocked gets a practice session now.
                                practiceQueue.clear();
                                for (Progression.Ability a : unlockCardAbilities) {
                                    practiceQueue.add(a);
                                }
                                lastPracticeEnter = true;
                                startNextPractice();
                            }
                            lastCardSpace = en;
                        }

                        // ── PRACTICE SESSION tick (multi-step) ───────────────
                        if (practiceWarnTimer > 0f) practiceWarnTimer -= deltaTime;
                        if (practiceWarnTimer <= 0f) practiceWarnText = null;

                        if (practiceAbility != null && practiceSteps != null) {
                            AbilityPractice.Step step = practiceSteps.get(practiceStepIndex);
                            practiceStepAge     += deltaTime;
                            practiceCtx.stepAge  = practiceStepAge;

                            // Run optional per-frame tick logic (e.g. move NPCs).
                            if (step.onTick != null) {
                                try { step.onTick.accept(practiceCtx); } catch (Exception ignored) {}
                            }

                            if (practiceStepDone) {
                                // Celebration pause before advancing.
                                practiceCelebration -= deltaTime;
                                if (practiceCelebration <= 0f) advancePracticeStep();
                            } else {
                                // Run the done() predicate — counts up to step.required.
                                boolean oneAction = false;
                                try { oneAction = step.done.test(practiceCtx); }
                                catch (Exception ignored) {}

                                if (oneAction) {
                                    practiceCtx.counter++;
                                    practiceCtx.flag = false; // reset latch for next count
                                    if (practiceCtx.counter >= step.required) {
                                        practiceStepDone    = true;
                                        practiceCelebration = step.doneText != null
                                                ? PRACTICE_CELEBRATE_SECS : 0.3f;
                                    }
                                } else if (practiceStepAge > step.timeout) {
                                    advancePracticeStep(); // safety timeout
                                }

                                // ENTER skip — only for steps that explicitly allow it.
                                boolean enterNow = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                        || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                                boolean canSkip  = step.allowSkip
                                        && practiceStepAge > 1.2f
                                        && !lastPracticeEnter && enterNow;
                                lastPracticeEnter = enterNow;
                                if (canSkip) { practiceStepDone = true; practiceCelebration = 0.1f; }
                            }
                        } else {
                            lastPracticeEnter = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS
                                    || glfwGetKey(window, GLFW_KEY_KP_ENTER) == GLFW_PRESS;
                        }

                        // ── PAPER FIGURINE SUBSTITUTE (V hold) ────────────────
                        // Must run BEFORE the damage drain so it can intercept.
                        if (substituteCooldown > 0f) substituteCooldown -= deltaTime;
                        boolean vHeld = glfwGetKey(window, GLFW_KEY_V) == GLFW_PRESS
                                && player.can(Progression.Ability.SUBSTITUTE);
                        substitutePrimed = vHeld && !player.debugMode && substituteCooldown <= 0f;

                        if (substitutePrimed && enemyManager.pendingPlayerDamage > 0f) {
                            Vector3f oldPos = new Vector3f(player.position);
                            float cyaw  = camera.yaw;
                            float backX = -(float) Math.cos(cyaw);
                            float backZ = -(float) Math.sin(cyaw);
                            // Perpendicular axes for diagonal escape attempts
                            float leftX  = -backZ,  leftZ  =  backX;
                            float rightX =  backZ,  rightZ = -backX;
                            float bd = GameConfig.substituteBackDist;

                            // Candidate directions: [dx, yShift-for-collision, dz]
                            // Try straight back first, then back-left/right diagonals,
                            // then back with a 1-block vertical offset (handles ledges).
                            float[][] dirs = {
                                { backX,                                  0f, backZ                                 },
                                { backX * 0.707f + leftX  * 0.707f,     0f, backZ * 0.707f + leftZ  * 0.707f  },
                                { backX * 0.707f + rightX * 0.707f,     0f, backZ * 0.707f + rightZ * 0.707f  },
                                { backX,                                  1f, backZ                                 },
                                { backX,                                 -1f, backZ                                 },
                            };

                            float bestTx = oldPos.x, bestTy = oldPos.y, bestTz = oldPos.z;
                            float bestDist = -1f;

                            for (float[] dir : dirs) {
                                float ddx    = dir[0], yShift = dir[1], ddz = dir[2];
                                float hlen   = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                                if (hlen > 0f) { ddx /= hlen; ddz /= hlen; }

                                float candX = oldPos.x, candZ = oldPos.z;
                                int   checkFy = (int) Math.floor(oldPos.y + yShift);
                                for (float step = 0.5f; step <= bd; step += 0.5f) {
                                    float cx = oldPos.x + ddx * step;
                                    float cz = oldPos.z + ddz * step;
                                    int bx2 = (int) Math.floor(cx);
                                    int bz2 = (int) Math.floor(cz);
                                    boolean blocked = world.getBlock(bx2, checkFy,     bz2).isSolid()
                                                   || world.getBlock(bx2, checkFy + 1, bz2).isSolid();
                                    if (blocked) break;
                                    candX = cx; candZ = cz;
                                }

                                float dist2 = (candX - oldPos.x) * (candX - oldPos.x)
                                            + (candZ - oldPos.z) * (candZ - oldPos.z);
                                if (dist2 > bestDist) {
                                    bestDist = dist2;
                                    // Snap Y to ground at this candidate XZ
                                    int bxd = (int) Math.floor(candX);
                                    int bzd = (int) Math.floor(candZ);
                                    float snapY = oldPos.y;
                                    for (int by2 = (int) oldPos.y + 4; by2 >= 1; by2--) {
                                        if (world.getBlock(bxd, by2, bzd).isSolid()
                                                && !world.getBlock(bxd, by2 + 1, bzd).isSolid()) {
                                            snapY = by2 + 1f; break;
                                        }
                                    }
                                    bestTx = candX; bestTy = snapY; bestTz = candZ;
                                }
                            }

                            player.position.set(bestTx, bestTy, bestTz);
                            player.setVelocityY(0f);
                            player.abilities.blinkFlashTimer = GameConfig.blinkFlashDecay;
                            player.abilities.blinkOrigin     = oldPos;
                            player.abilities.blinkDest       = new Vector3f(player.position);
                            float lt = GameConfig.substituteDummyLifetime;
                            substituteDummies.add(new float[]{ oldPos.x, oldPos.y, oldPos.z, lt, lt });
                            enemyManager.pendingPlayerDamage = 0f;
                            substitutePrimed   = false;
                            substituteCooldown = GameConfig.substituteCooldown;
                        }

                        // ── VOID DEATH — player fell past y=0 into the abyss ─────
                        if (player.fellIntoVoid && !showDeathScreen && !deathCutscenePending) {
                            player.fellIntoVoid = false;
                            player.health = 0f;
                            AudioManager.stopContinuous("kamui_duration");
                            AudioManager.stopContinuous("kamui_distortion");
                            ScreenEffectManager.INSTANCE.flash(0f, 0f, 0f, 1.0f, 0.5f);
                            deathScreenLines = RunRecords.INSTANCE.recordDeath(
                                    enemyManager.getWaveNumber(),
                                    (float) org.lwjgl.glfw.GLFW.glfwGetTime());
                            deathCutscenePending = true;
                            if (RunRecords.INSTANCE.wasKamuiAwakenDeath()) {
                                player.progression.grantKamui();
                                kamuiJustUnlockedThisRevival = true;
                                cutscene.startKamuiAwaken();
                            } else {
                                cutscene.startRevival();
                            }
                        }
                        player.fellIntoVoid = false; // clear every frame

                        // ── DRAIN remaining enemy damage into player health ────
                        if (enemyManager.pendingPlayerDamage > 0f) {
                            if (player.abilities.isKamui || immunityTimer > 0f) {
                                // Kamui / revival immunity = invincible
                                enemyManager.pendingPlayerDamage = 0f;
                            } else {
                                // ── DAMAGE ALERT — so a hit never comes "from nowhere" ──
                                float dmg = enemyManager.pendingPlayerDamage;
                                // Red vignette flash; stronger the harder you're hit.
                                float fa = Math.min(0.6f, 0.18f + dmg * 0.05f);
                                ScreenEffectManager.INSTANCE.flash(0.75f, 0.02f, 0.02f, fa, 0.35f);
                                AudioManager.play("fall_hit", Math.min(1f, 0.5f + dmg * 0.05f));
                                damageFlashTimer = 0.5f;   // HUD pulses the health bar

                                player.health -= dmg;
                                enemyManager.pendingPlayerDamage = 0f;
                                if (player.health <= 0f) {
                                    player.health = 0f;  // clamp; actual reset happens on restart
                                    if (!showDeathScreen && !deathCutscenePending) {
                                        AudioManager.stopContinuous("kamui_duration");
                                        AudioManager.stopContinuous("kamui_distortion");

                                        // Record death — this also checks if it's the 3rd
                                        // (Kamui awakening) and persists the flag.
                                        deathScreenLines = RunRecords.INSTANCE.recordDeath(
                                                enemyManager.getWaveNumber(),
                                                (float) org.lwjgl.glfw.GLFW.glfwGetTime());

                                        deathCutscenePending = true;

                                        // 3rd death: play Kamui awakening first, then revival.
                                        if (RunRecords.INSTANCE.wasKamuiAwakenDeath()) {
                                            player.progression.grantKamui();
                                            kamuiJustUnlockedThisRevival = true; // shows tutorial once after revival
                                            cutscene.startKamuiAwaken();
                                        } else {
                                            cutscene.startRevival();
                                        }
                                        ScreenEffectManager.INSTANCE.desaturate(0.7f, 1.5f);
                                    }
                                }
                            }
                        }

                        // ── KAMUI MANA DRAIN (passive while active) ───────────────
                        if (player.abilities.isKamui) {
                            player.mana = Math.max(0f,
                                    player.mana - GameConfig.manaKamuiDrain * deltaTime);
                            // Force-exit Kamui when mana is fully exhausted — no cooldown
                            if (player.mana <= 0f) {
                                player.abilities.isKamui          = false;
                                player.abilities.kamuiAutoExited  = false;
                                player.abilities.absorptionCharge = 0f;
                                AudioManager.play("kamui_exit");
                                AudioManager.stopContinuous("kamui_duration");
                                AudioManager.stopContinuous("kamui_distortion");
                            }
                        }

                        // ── KAMUI ABSORPTION (LMB held while in Kamui) ────────────
                        if (player.abilities.isKamui) {
                            boolean lmbHeld = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
                            if (lmbHeld) {
                                // Find best target: enemy in crosshair takes priority, else block.
                                // No range limit — Kamui can reach anything the player looks at.
                                Vector3f eyePos = camera.position;
                                Enemy absorbEnemy = enemyManager.findMostAligned(
                                        world, eyePos, camera.getLookDirection(), 2000f);
                                boolean hasTarget = absorbEnemy != null
                                        || (lastTarget != null && lastTarget.hit
                                            && world.getBlock(lastTarget.hitX, lastTarget.hitY, lastTarget.hitZ) != com.leaf.game.world.Block.AIR);

                                if (hasTarget) {
                                    player.abilities.isAbsorbing     = true;
                                    AudioManager.playContinuous("kamui_distortion");
                                    player.abilities.absorptionCharge = Math.min(1f,
                                            player.abilities.absorptionCharge
                                            + deltaTime / GameConfig.kamuiAbsorptionTime);

                                    // Project target world pos to screen for the vortex visual
                                    Vector3f targetPos = (absorbEnemy != null)
                                            ? absorbEnemy.getCentre()
                                            : new Vector3f(lastTarget.hitX + 0.5f,
                                                           lastTarget.hitY + 0.5f,
                                                           lastTarget.hitZ + 0.5f);
                                    Matrix4f vp = new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
                                    org.joml.Vector4f cp = new org.joml.Vector4f(
                                            targetPos.x, targetPos.y, targetPos.z, 1f).mul(vp);
                                    if (cp.w > 0f) {
                                        float ndcX = cp.x / cp.w;
                                        float ndcY = cp.y / cp.w;
                                        player.abilities.absorptionScrX = (ndcX + 1f) * 0.5f * ww[0];
                                        player.abilities.absorptionScrY = (1f - ndcY) * 0.5f * wh[0];
                                    }

                                    // When fully charged: absorb (delete) the target
                                    if (player.abilities.absorptionCharge >= 1f) {
                                        player.mana = Math.max(0f,
                                                player.mana - GameConfig.manaKamuiAbsorption);
                                        if (absorbEnemy != null) {
                                            absorbEnemy.applyDamage(999999f); // instant kill
                                        } else if (lastTarget != null && lastTarget.hit) {
                                            // Absorb a sphere of blocks around the target
                                            int bx0 = lastTarget.hitX, by0 = lastTarget.hitY, bz0 = lastTarget.hitZ;
                                            int absorptionR = 3;
                                            java.util.Set<com.leaf.game.world.Chunk> dirty = new java.util.HashSet<>();
                                            for (int ax = -absorptionR; ax <= absorptionR; ax++) {
                                                for (int ay = -absorptionR; ay <= absorptionR; ay++) {
                                                    for (int az = -absorptionR; az <= absorptionR; az++) {
                                                        if (ax*ax + ay*ay + az*az > absorptionR*absorptionR) continue;
                                                        int nx = bx0+ax, ny = by0+ay, nz = bz0+az;
                                                        if (world.getBlock(nx, ny, nz) != com.leaf.game.world.Block.AIR) {
                                                            world.setBlock(nx, ny, nz, com.leaf.game.world.Block.AIR);
                                                            com.leaf.game.world.Chunk c = world.getChunk(
                                                                    Math.floorDiv(nx, Chunk.SIZE), 0, Math.floorDiv(nz, Chunk.SIZE));
                                                            if (c != null) dirty.add(c);
                                                        }
                                                    }
                                                }
                                            }
                                            for (com.leaf.game.world.Chunk c : dirty) world.buildChunkMeshes(c);
                                        }
                                        player.abilities.absorptionCharge = 0f;
                                        player.abilities.isAbsorbing      = false;
                                        AudioManager.stopContinuous("kamui_distortion");
                                        AudioManager.play("distortion_snap", 2.0f); // boosted ~+6 dB
                                    }
                                } else {
                                    // No valid target — drain charge back
                                    AudioManager.stopContinuous("kamui_distortion");
                                    player.abilities.absorptionCharge = Math.max(0f,
                                            player.abilities.absorptionCharge - deltaTime * 2f);
                                }
                            } else {
                                // LMB released — bleed charge away
                                player.abilities.isAbsorbing     = false;
                                AudioManager.stopContinuous("kamui_distortion");
                                player.abilities.absorptionCharge = Math.max(0f,
                                        player.abilities.absorptionCharge - deltaTime * 3f);
                            }
                        }

                        // Tick paper dummies; explode when timer expires
                        for (int di = substituteDummies.size() - 1; di >= 0; di--) {
                            float[] dm = substituteDummies.get(di);
                            dm[3] -= deltaTime;
                            if (dm[3] <= 0f) {
                                AudioManager.play("paper_explode");
                                float[] blastEv = { dm[0], dm[1], dm[2],
                                        GameConfig.substituteBlastRadius };
                                enemyManager.processExplosion(blastEv,
                                        GameConfig.substituteBlastDamage);
                                Random fragRng = new Random();
                                for (int fi = 0; fi < 14; fi++) {
                                    float ang = fragRng.nextFloat() * (float)(2 * Math.PI);
                                    float spd = 4f + fragRng.nextFloat() * 6f;
                                    Vector3f fv = new Vector3f(
                                            (float)Math.cos(ang) * spd,
                                            2f + fragRng.nextFloat() * 5f,
                                            (float)Math.sin(ang) * spd);
                                    droppedItems.add(new DroppedItem(
                                            (int)dm[0], (int)dm[1], (int)dm[2],
                                            Block.SNOW, fv));
                                }
                                activeShakeDuration  = 0.3f;
                                activeShakeAmplitude = 0.18f;
                                smashShakeTimer      = Math.max(smashShakeTimer, activeShakeDuration);
                                substituteDummies.remove(di);
                            }
                        }

                        // P key — spawn test enemy at crosshair hit point
                        boolean pHeld = glfwGetKey(window, GLFW_KEY_P) == GLFW_PRESS;
                        if (pHeld && !lastP) {
                            RaycastResult hit = player.getTargetBlock(camera, world);
                            if (hit != null && hit.hit) {
                                enemyManager.spawnAt(
                                        hit.placeX + 0.5f,
                                        hit.placeY,
                                        hit.placeZ + 0.5f,
                                        Enemy.Type.SLIME); // <--- Add SLIME here!
                            }
                        }
                        lastP = pHeld;

                        // [0] key — DEV: spawn a GUARDIAN (golem) for testing.
                        // Drops it at the block you're aiming at, or ~6 blocks in front.
                        boolean zeroHeld = glfwGetKey(window, GLFW_KEY_0) == GLFW_PRESS;
                        if (zeroHeld && !lastZeroKey) {
                            RaycastResult ghit = player.getTargetBlock(camera, world);
                            if (ghit != null && ghit.hit) {
                                enemyManager.spawnAt(ghit.placeX + 0.5f, ghit.placeY + 1f,
                                        ghit.placeZ + 0.5f, Enemy.Type.GUARDIAN);
                            } else {
                                Vector3f fwd = camera.getLookDirection();
                                enemyManager.spawnAt(
                                        player.position.x + fwd.x * 6f,
                                        player.position.y + 3f,
                                        player.position.z + fwd.z * 6f,
                                        Enemy.Type.GUARDIAN);
                            }
                            System.out.println("[DEV] Spawned GUARDIAN (golem)");
                        }
                        lastZeroKey = zeroHeld;
                        // ── TODO'S TECHNIQUE (J key) ──────────────────────────
                        if (todoSwapCooldown > 0f) todoSwapCooldown -= deltaTime;
                        boolean jHeld = glfwGetKey(window, GLFW_KEY_J) == GLFW_PRESS
                                && player.can(Progression.Ability.SWAP);
                        if (jHeld && !lastJ && !player.debugMode && todoSwapCooldown <= 0f
                                && player.mana >= GameConfig.manaTodoSwap) {
                            Vector3f eyePos = new Vector3f(player.position.x,
                                    player.position.y + 1.6f, player.position.z);
                            Enemy swapTarget = enemyManager.findClosestVisible(
                                    world, eyePos, GameConfig.todoRange);
                            if (swapTarget != null) {
                                player.mana -= GameConfig.manaTodoSwap;
                                Vector3f oldPlayerPos = new Vector3f(player.position);
                                Vector3f oldEnemyPos  = new Vector3f(swapTarget.position);
                                player.position.set(oldEnemyPos);
                                swapTarget.position.set(oldPlayerPos);
                                player.abilities.blinkFlashTimer = GameConfig.blinkFlashDecay;
                                player.abilities.blinkOrigin     = oldPlayerPos;
                                player.abilities.blinkDest       = new Vector3f(oldEnemyPos);
                                swapTarget.hitFlashTimer = 0.35f;
                                todoSwapCooldown = GameConfig.todoCooldown;
                                AudioManager.play("clap");
                            }
                        }
                        lastJ = jHeld;

                        // ── QUAGMIRE (M key) ──────────────────────────────────
                        if (quagmireCooldown > 0f) quagmireCooldown -= deltaTime;
                        boolean mHeld = glfwGetKey(window, GLFW_KEY_M) == GLFW_PRESS
                                && player.can(Progression.Ability.QUAGMIRE);
                        if (mHeld && !lastM && !player.debugMode && quagmireCooldown <= 0f
                                && player.mana >= GameConfig.manaQuagmire) {
                            Vector3f eyePos = new Vector3f(player.position.x,
                                    player.position.y + 1.6f, player.position.z);
                            Enemy target = enemyManager.findMostAligned(
                                    world, eyePos, camera.getLookDirection(), GameConfig.quagmireRange);
                            if (target != null) {
                                // Wave starts 2 blocks in front of player
                                float wdx = target.position.x - player.position.x;
                                float wdz = target.position.z - player.position.z;
                                float wdist = (float)Math.sqrt(wdx*wdx + wdz*wdz);
                                if (wdist > 0.1f) {
                                    float ndx2 = wdx / wdist, ndz2 = wdz / wdist;
                                    float startX = player.position.x + ndx2 * 2f;
                                    float startZ = player.position.z + ndz2 * 2f;
                                    float startY = player.position.y;
                                    float totalDist = Math.max(0.1f, wdist - 2f);
                                    mudWaves.add(new float[]{
                                        startX, startY, startZ,         // [0-2] pos
                                        ndx2, ndz2,                      // [3-4] dir
                                        GameConfig.quagmireSpreadSpeed,  // [5] speed
                                        0f,                              // [6] dist travelled
                                        totalDist,                       // [7] total dist
                                        (float) target.id,               // [8] enemy id
                                        0f,                              // [9] reserved
                                        -99999f, -99999f                 // [10-11] last placed block col
                                    });
                                    player.mana -= GameConfig.manaQuagmire;
                                    quagmireCooldown = GameConfig.quagmireCooldown;
                                    AudioManager.play("quagmire");
                                }
                            }
                        }
                        lastM = mHeld;

                        // Advance mud waves — permanently paint MUD blocks on the ground
                        for (int wi = mudWaves.size() - 1; wi >= 0; wi--) {
                            float[] w = mudWaves.get(wi);
                            float stepDist = w[5] * deltaTime;
                            w[6] += stepDist;
                            w[0] += w[3] * stepDist;
                            w[2] += w[4] * stepDist;

                            // Place a MUD block each time the wave enters a new block column
                            int curBx = (int) Math.floor(w[0]);
                            int curBz = (int) Math.floor(w[2]);
                            if (curBx != (int) w[10] || curBz != (int) w[11]) {
                                w[10] = curBx;
                                w[11] = curBz;
                                // Scan downward from wave Y to find the ground surface
                                int baseY = (int) Math.floor(w[1]) + 2;
                                for (int scanY = baseY; scanY >= 0; scanY--) {
                                    if (world.getBlock(curBx, scanY, curBz).isSolid()) {
                                        // Replace the surface block with MUD
                                        world.setBlock(curBx, scanY, curBz, Block.MUD);
                                        world.rebuildChunkAt(curBx, scanY, curBz);
                                        break;
                                    }
                                }
                            }

                            // Reached target — burst MUD chunks around enemy feet, remove wave
                            if (w[6] >= w[7]) {
                                int eid = (int) w[8];
                                for (Enemy e : enemyManager.getEnemies()) {
                                    if (e.id == eid && e.alive) {
                                        e.hitFlashTimer = 0.25f;
                                        // Burst of flying MUD chunks at enemy position
                                        for (int mi = 0; mi < 10; mi++) {
                                            Vector3f mv = new Vector3f(
                                                    (shakeRng.nextFloat()-0.5f)*5f,
                                                    2.5f + shakeRng.nextFloat()*3.5f,
                                                    (shakeRng.nextFloat()-0.5f)*5f);
                                            droppedItems.add(new DroppedItem(
                                                    (int) e.position.x,
                                                    (int) e.position.y,
                                                    (int) e.position.z,
                                                    Block.MUD, mv));
                                        }
                                        // Stamp a large irregular mud pool (~3-block radius)
                                        // around the enemy's feet.
                                        int ex = (int) Math.floor(e.position.x);
                                        int ez = (int) Math.floor(e.position.z);
                                        int baseY2 = (int) Math.floor(e.position.y) + 2;
                                        int poolR = GameConfig.quagmirePoolRadius;
                                        for (int dbx = -poolR - 1; dbx <= poolR + 1; dbx++) {
                                            for (int dbz = -poolR - 1; dbz <= poolR + 1; dbz++) {
                                                // Irregular edge: sine/cosine noise on column coords
                                                float noiseR = (float)(
                                                    Math.sin(dbx * 2.3 + ez * 0.7) *
                                                    Math.cos(dbz * 1.9 + ex * 0.5)) * 0.9f;
                                                float effR = poolR + noiseR;
                                                if (dbx * dbx + dbz * dbz > effR * effR) continue;
                                                int bx2 = ex + dbx, bz2 = ez + dbz;
                                                for (int scanY = baseY2; scanY >= 0; scanY--) {
                                                    if (world.getBlock(bx2, scanY, bz2).isSolid()) {
                                                        world.setBlock(bx2, scanY, bz2, Block.MUD);
                                                        world.rebuildChunkAt(bx2, scanY, bz2);
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        break;
                                    }
                                }
                                mudWaves.remove(wi);
                            }
                        }

                        // ── STONE CANON (I key) ───────────────────────────────
                        if (stoneCanonCooldownTimer > 0f) stoneCanonCooldownTimer -= deltaTime;
                        boolean iHeld = glfwGetKey(window, GLFW_KEY_I) == GLFW_PRESS
                                && player.can(Progression.Ability.STONE_CANON);

                        if (!isChargingStoneCanon && iHeld && !lastI
                                && !player.debugMode && stoneCanonCooldownTimer <= 0f
                                && player.mana >= GameConfig.manaStoneCanonBase) {
                            // Start charging — lock position
                            isChargingStoneCanon     = true;
                            AudioManager.playContinuous("charging");
                            stoneCanonCharge         = 0f;
                            stoneCanonBlocksConsumed = 0;
                            stoneCanonNextConsume    = GameConfig.stoneCanonConsumeRate;
                            stoneCanonLockedPos      = new Vector3f(player.position);

                            // Compute boulder ground-spawn point (same logic as fire)
                            Vector3f ld0 = camera.getLookDirection();
                            float hLen0 = (float) Math.sqrt(ld0.x*ld0.x + ld0.z*ld0.z);
                            float hn0x = hLen0 > 0.001f ? ld0.x / hLen0 : 0f;
                            float hn0z = hLen0 > 0.001f ? ld0.z / hLen0 : 1f;
                            float gfpx = player.position.x + hn0x * 2.5f;
                            float gfpz = player.position.z + hn0z * 2.5f;
                            int gfpBx = (int) Math.floor(gfpx);
                            int gfpBz = (int) Math.floor(gfpz);
                            int gSpawnY = (int) Math.floor(player.position.y);
                            for (int sy2 = (int) Math.floor(player.position.y) + 3; sy2 >= 0; sy2--) {
                                if (world.getBlock(gfpBx, sy2, gfpBz).isSolid()) {
                                    gSpawnY = sy2 + 1;
                                    break;
                                }
                            }
                            stoneCanonGroundPos = new Vector3f(gfpx, (float) gSpawnY, gfpz);
                        }

                        if (isChargingStoneCanon) {
                            // Lock position
                            player.position.set(stoneCanonLockedPos);
                            player.setVelocityY(0f);

                            stoneCanonCharge += deltaTime;
                            stoneCanonNextConsume -= deltaTime;
                            // Continuous mana drain; cancel if mana runs out
                            player.mana = Math.max(0f,
                                    player.mana - GameConfig.manaStoneCanonBase * deltaTime);
                            if (player.mana <= 0f) {
                                isChargingStoneCanon = false;
                                AudioManager.stopContinuous("charging");
                                stoneCanonCooldownTimer = GameConfig.stoneCanonCooldown * 0.5f;
                            }

                            // Consume one stone block per interval
                            if (stoneCanonNextConsume <= 0f) {
                                stoneCanonNextConsume = GameConfig.stoneCanonConsumeRate;
                                int sr = (int)GameConfig.stoneCanonScanRadius;
                                int px = (int)Math.floor(player.position.x);
                                int py = (int)Math.floor(player.position.y);
                                int pz = (int)Math.floor(player.position.z);
                                outer:
                                for (int r = 1; r <= sr; r++) {
                                    for (int bx2 = px-r; bx2 <= px+r; bx2++) {
                                        for (int bz2 = pz-r; bz2 <= pz+r; bz2++) {
                                            for (int by2 = py-r; by2 <= py+r; by2++) {
                                                if (world.getBlock(bx2, by2, bz2) == Block.STONE) {
                                                    world.setBlock(bx2, by2, bz2, Block.AIR);
                                                    world.rebuildChunkAt(bx2, by2, bz2);
                                                    // Stone flies toward player
                                                    Vector3f sv = new Vector3f(
                                                            player.position.x - bx2,
                                                            player.position.y - by2 + 1f,
                                                            player.position.z - bz2)
                                                            .normalize().mul(8f);
                                                    droppedItems.add(new DroppedItem(
                                                            bx2, by2, bz2, Block.STONE, sv));
                                                    stoneCanonBlocksConsumed++;
                                                    break outer;
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Release I or exceed max charge → fire
                            if (!iHeld || stoneCanonCharge >= GameConfig.stoneCanonMaxCharge) {
                                isChargingStoneCanon = false;
                                if (stoneCanonBlocksConsumed > 0) {
                                    float chargeF = Math.min(1f,
                                            stoneCanonCharge / GameConfig.stoneCanonMaxCharge);
                                    // One-time fire cost (charge-scaled; deduct whatever's left)
                                    player.mana = Math.max(0f, player.mana
                                            - GameConfig.manaStoneCanonMax * chargeF);
                                    float speed = GameConfig.stoneCanonMinSpeed
                                            + chargeF * (GameConfig.stoneCanonMaxSpeed - GameConfig.stoneCanonMinSpeed);
                                    float scale = GameConfig.stoneCanonMinScale
                                            + chargeF * (GameConfig.stoneCanonMaxScale - GameConfig.stoneCanonMinScale);
                                    // Scale also by number of blocks consumed
                                    float blockBonus = Math.min(1f, stoneCanonBlocksConsumed / 6f);
                                    scale *= (0.7f + 0.3f * blockBonus);
                                    Vector3f lookDir = camera.getLookDirection();

                                    // ── Fire from eye in exact look direction ─────
                                    // (Previously spawned from the ground — user requested
                                    //  it fires wherever you aim, like a normal projectile)
                                    Vector3f firePos = new Vector3f(
                                            player.position.x + lookDir.x * 1.2f,
                                            player.position.y + 1.6f + lookDir.y * 1.2f,
                                            player.position.z + lookDir.z * 1.2f);
                                    Vector3f fireDir = new Vector3f(lookDir); // already normalised
                                    Vector3f fireVel = new Vector3f(fireDir).mul(speed);
                                    stoneShotList.add(new ActiveStoneShot(firePos, fireVel, scale, chargeF));
                                    AudioManager.stopContinuous("charging");
                                    AudioManager.play("stone_canon");
                                    stoneCanonCooldownTimer = GameConfig.stoneCanonCooldown;
                                } else {
                                    // No blocks consumed — fizzle, just stop the charge sound
                                    AudioManager.stopContinuous("charging");
                                }
                                stoneCanonLockedPos  = null;
                                stoneCanonGroundPos  = null;
                            }
                        }
                        lastI = iHeld;

                        // Advance stone shots
                        for (int si = stoneShotList.size() - 1; si >= 0; si--) {
                            ActiveStoneShot shot = stoneShotList.get(si);
                            shot.lifetime -= deltaTime;
                            shot.pos.add(new Vector3f(shot.vel).mul(deltaTime));
                            // Slow down slightly for visual feel
                            shot.vel.mul(0.998f);

                            boolean hitSomething = false;
                            int sx = (int)Math.floor(shot.pos.x);
                            int sy = (int)Math.floor(shot.pos.y);
                            int sz = (int)Math.floor(shot.pos.z);

                            if (world.getBlock(sx, sy, sz).isSolid() || shot.lifetime <= 0f) {
                                hitSomething = true;
                            }
                            if (hitSomething) {
                                float blastR = GameConfig.stoneCanonMinRadius
                                        + shot.chargeF * (GameConfig.stoneCanonMaxRadius - GameConfig.stoneCanonMinRadius);
                                float blastD = GameConfig.stoneCanonMinDamage
                                        + shot.chargeF * (GameConfig.stoneCanonMaxDamage - GameConfig.stoneCanonMinDamage);
                                enemyManager.processExplosion(
                                        new float[]{ shot.pos.x, shot.pos.y, shot.pos.z, blastR }, blastD);
                                // Crater
                                int cr = Math.max(1, Math.round(blastR * 0.6f));
                                world.createImpactCrater(sx, sy, sz, cr);
                                spawnCraterEjecta(sx, sy, sz, cr);
                                float shakeStr = 0.2f + shot.chargeF * 0.4f;
                                activeShakeDuration  = shakeStr;
                                activeShakeAmplitude = 0.15f + shot.chargeF * 0.2f;
                                smashShakeTimer      = Math.max(smashShakeTimer, activeShakeDuration);
                                stoneShotList.remove(si);
                            }
                        }


                        // ── MANA DEPLETED (edge: just hit zero) ───────────────
                        if (player.mana <= 0f && lastMana > 0f) {
                            AudioManager.play("mana_empty");
                        }
                        lastMana = player.mana;

                        // ── LANDING / FALL IMPACT SOUNDS ──────────────────────
                        // Detected from pre-update ground state vs post-update.
                        // savedHighestY captured before player.update() so it
                        // holds the peak Y — Player resets it to position.y on landing.
                        boolean justLanded = wasOnGroundAudio == false && player.isOnGround();
                        if (justLanded) {
                            float fallDist = savedHighestY - player.position.y;
                            // Check block directly below feet for surface type
                            Block belowBlock = world.getBlock(
                                    (int)Math.floor(player.position.x),
                                    (int)Math.floor(player.position.y) - 1,
                                    (int)Math.floor(player.position.z));
                            boolean isSandy = (belowBlock == Block.SAND
                                           || belowBlock == Block.RED_SAND);
                            if (fallDist > 4.0f) {
                                // Damage-range fall
                                AudioManager.play(isSandy ? "fall_sandy" : "fall_hit", 0.85f);
                            } else if (fallDist > 1.5f) {
                                // Short drop / jump landing — light thud
                                AudioManager.play(isSandy ? "fall_sandy" : "fall_light", 0.55f);
                            }
                        }

                        // ── WATER SOUNDS ──────────────────────────────────────
                        // Feet-level water check (same logic Player uses internally)
                        boolean feetInWater = world.getBlock(
                                (int)Math.floor(player.position.x),
                                (int)Math.floor(player.position.y + 0.1f),
                                (int)Math.floor(player.position.z)).isLiquid();
                        boolean camSubmerged = player.isCameraSubmerged();

                        // Entry: big splash when falling fast, gentle enter otherwise
                        if (feetInWater && !lastFeetInWater) {
                            float entryVy = player.getVelocityY();
                            if (entryVy < -12f) {
                                AudioManager.play("water/water_splash", 1.0f);
                            } else {
                                AudioManager.play("water/water_enter", 0.7f);
                            }
                        }
                        // Exit: pick randomly from 3 exit sounds — avoids the
                        // repetitive "same splash every step" problem when walking on water.
                        if (!feetInWater && lastFeetInWater) {
                            int pick = (int)(Math.random() * 3);
                            String exitSnd = pick == 0 ? "water/water_leave"
                                           : pick == 1 ? "water/water_exit2"
                                                       : "water/water_exit3";
                            AudioManager.play(exitSnd, 0.50f);
                        }
                        // Underwater ambience: loop while camera is submerged
                        if (camSubmerged && !lastCamSubmerged) {
                            AudioManager.playContinuous("water/underwater_ambience", 0.55f);
                        } else if (!camSubmerged && lastCamSubmerged) {
                            AudioManager.stopContinuous("water/underwater_ambience");
                        }
                        lastFeetInWater  = feetInWater;
                        lastCamSubmerged = camSubmerged;

                        // ── WIND / FLIGHT SOUNDS ───────────────────────────────
                        // Three beds that never actually stop — windFade smoothly
                        // brings them to 0 so there are no abrupt pops on landing.
                        //
                        // KEY FIXES vs previous version:
                        //  • inAir uses debugMode as override → touching blocks while
                        //    skimming no longer briefly kills the wind.
                        //  • When camSubmerged, windFade snaps to 0 immediately (water
                        //    has its own sound design).
                        //  • totalAirSpeed includes vertical so gentle rising/descending
                        //    registers — not just horizontal glides.
                        //  • wind_soft is ducked when wind_blow is strong.
                        //  • Tilt pan: rolls the stereo image left/right with camera roll.
                        float vy      = player.getVelocityY();
                        // debugMode = flight mode: ALWAYS treat as in-air so brief block
                        // grazes during skimming don't cut the wind.
                        // Underground (cave env) also suppresses wind — windFade fades
                        // out naturally when lastEnv switches to ENV_CAVE.
                        boolean inAir = (player.debugMode || !player.isOnGround())
                                        && !camSubmerged
                                        && lastEnv != AudioManager.ENV_CAVE;

                        // Snap wind off immediately on water entry — no lingering wind underwater.
                        if (camSubmerged) windFade = 0f;

                        float horizSpeed = (float) Math.sqrt(
                                listenerVel.x * listenerVel.x + listenerVel.z * listenerVel.z);
                        // In flight mode use FlightController's velocity so terrain collisions
                        // (which zero the position delta) don't kill wind volume on uphill skim.
                        float totalAirSpeed = player.debugMode
                                ? player.flightController.getFlightSpeed()
                                : (float) Math.sqrt(horizSpeed * horizSpeed + vy * vy);

                        // ── TUNING KNOBS ───────────────────────────────────────
                        final float BLOW_START   = 3f;    // lower → wind starts earlier/gentler
                        final float BIG_START    = 13f;   // lower → deep roar kicks in sooner
                        final float BLOW_MAX_VOL = 0.70f;
                        final float BIG_MAX_VOL  = 0.85f;
                        final float SOFT_MAX_VOL = 0.20f;
                        final float FADE_IN_SEC  = 0.12f; // faster fade-in for responsive feel
                        final float FADE_OUT_SEC = 0.55f; // longer fade-out — wind lingers a beat

                        if (inAir) {
                            windFade = Math.min(1f, windFade + deltaTime / FADE_IN_SEC);
                        } else {
                            windFade = Math.max(0f, windFade - deltaTime / FADE_OUT_SEC);
                        }

                        // wind_blow: main travel layer
                        float blowVol = Math.min(BLOW_MAX_VOL,
                                Math.max(0f, (totalAirSpeed - BLOW_START) / (BIG_START - BLOW_START))
                                * BLOW_MAX_VOL);

                        // wind_big: deep roar at high speed
                        float bigVol = Math.min(BIG_MAX_VOL,
                                Math.max(0f, (totalAirSpeed - BIG_START) / 10f) * BIG_MAX_VOL);

                        // wind_soft: gentle presence, ducked when blow is already loud
                        float softFade = Math.max(0f, 1f - (blowVol / BLOW_MAX_VOL) * 2f);
                        float softVol  = SOFT_MAX_VOL * softFade
                                * Math.min(1f, Math.max(0f, (totalAirSpeed - 1.5f) / 4f));

                        // Gust boost: Math.max so gusts NEVER reduce existing flight-wind.
                        float gB = windGustStrength * 0.55f;
                        AudioManager.setContinuousVolume("wind/wind_soft", Math.max(softVol * windFade, gB * 0.45f));
                        AudioManager.setContinuousVolume("wind/wind_blow", Math.max(blowVol * windFade, gB * 0.60f));
                        AudioManager.setContinuousVolume("wind/wind_big",  Math.max(bigVol  * windFade, gB * 0.28f));

                        // ── TILT PAN ───────────────────────────────────────────
                        // When flying and rolling, shift wind_blow left/right to match
                        // the direction you're banking into.
                        // pan = sin(roll): +1 = hard right, -1 = hard left.
                        // Only active during flight mode to avoid weird pan on normal jumps.
                        if (player.debugMode) {
                            float roll    = player.getCameraRoll();
                            float tiltPan = (float) Math.sin(roll) * 0.75f;
                            AudioManager.setLoopPan("wind/wind_blow", tiltPan);
                            AudioManager.setLoopPan("wind/wind_big",  tiltPan * 0.5f);
                        } else {
                            // Return to centre when not in flight mode
                            AudioManager.setLoopPan("wind/wind_blow", 0f);
                            AudioManager.setLoopPan("wind/wind_big",  0f);
                        }

                        // wind_harsh stinger: fires only when windFade is mostly in
                        windStingerCooldown -= deltaTime;
                        if (windStingerCooldown <= 0f && inAir
                                && totalAirSpeed > BLOW_START && windFade > 0.5f) {
                            float sv = 0.25f + 0.30f * Math.min(1f, totalAirSpeed / (BIG_START + 4f));
                            AudioManager.playVaried("wind/wind_harsh", Math.min(0.70f, sv), 0.10f);
                            windStingerCooldown = 1.0f + (float)Math.random() * 2.5f
                                    - Math.min(0.6f, totalAirSpeed / 40f);
                        }

                        // wind_cemetery: cave ambience stinger
                        if (lastEnv == AudioManager.ENV_CAVE) {
                            caveWindCooldown -= deltaTime;
                            if (caveWindCooldown <= 0f) {
                                AudioManager.play("wind/wind_cemetery", 0.35f);
                                caveWindCooldown = 10f + (float)Math.random() * 14f;
                            }
                        }

                        // ── AMBIENT WIND GUSTS ────────────────────────────────────
                        // Fires randomly even when standing still — a sudden gust of
                        // cold mountain wind. Boosts all wind beds, plays wind_harsh
                        // as the gust arrives, and tilts snow particles sideways.
                        // Independent of flight speed so it surprises the player.
                        windGustTimer -= deltaTime;
                        if (windGustTimer <= 0f && !camSubmerged) {
                            windGustDuration = 3.5f + (float)Math.random() * 4.0f;
                            windGustAngle    = (float)(Math.random() * Math.PI * 2);
                            windGustTimer    = 18f + (float)Math.random() * 35f;
                            AudioManager.playVaried("wind/wind_harsh",
                                    0.45f + (float)Math.random() * 0.30f, 0.07f);
                        }
                        if (windGustDuration > 0f) {
                            windGustDuration -= deltaTime;
                            windGustStrength  = Math.min(1f, windGustStrength + deltaTime * 1.8f);
                        } else {
                            windGustStrength  = Math.max(0f, windGustStrength - deltaTime * 1.1f);
                        }

                        // ── MUFFLE (low-pass on the whole mix) ────────────────
                        // Priority 1 — submerged: very heavy low-pass gives the
                        //   沉闷 underwater feel.  Combined with ENV_UNDERWATER
                        //   reverb, it sells the "thick water pressing on your ears"
                        //   sensation.  Value 0..1 where 1 = fully muffled.
                        // Priority 2 — fast air movement: lighter, speed-scaled cut.
                        //
                        // UNDERWATER_MUFFLE – raise toward 1 for heavier/deafer feel
                        final float UNDERWATER_MUFFLE = 0.82f;
                        final float MUFFLE_MAX        = 0.55f;

                        float targetMuffle;
                        if (camSubmerged) {
                            // Hard cut on all high frequencies — deep, thick, pressured
                            targetMuffle = UNDERWATER_MUFFLE;
                        } else if (windFade > 0f) {
                            targetMuffle = Math.min(MUFFLE_MAX,
                                    Math.max(0f, (totalAirSpeed - BLOW_START) / 30f))
                                    * windFade;
                        } else {
                            targetMuffle = 0f;
                        }
                        if (Math.abs(targetMuffle - lastMuffle) > 0.04f
                                || (targetMuffle == 0f && lastMuffle != 0f)) {
                            AudioManager.setListenerMuffle(targetMuffle);
                            lastMuffle = targetMuffle;
                        }

                        // ── CONTEXTUAL ABILITY HINTS ──────────────────────────
                        // Stand first-deploy: show a one-liner explaining TAB/LMB/X
                        boolean nowStandDeployed = player.stand.isDeployed();
                        if (nowStandDeployed && !wasStandDeployed && !standHintShown) {
                            hintText  = "Stand deployed!   TAB = pilot drone  ·  LMB = auto-fire bolt  ·  X = recall";
                            hintTimer = 5.0f;
                            standHintShown = true;
                        }
                        wasStandDeployed = nowStandDeployed;

                        // Seal first-placement: show a one-liner explaining B/N
                        int nowSealCount = player.seals.getSealCount();
                        if (nowSealCount > 0 && lastSealCount == 0 && !sealHintShown) {
                            hintText  = "Seal placed!   B = warp to it  ·  N = reclaim it  ·  Place up to 5";
                            hintTimer = 5.0f;
                            sealHintShown = true;
                        }
                        lastSealCount = nowSealCount;

                        // ── CANNONBALL: preload chunks at CHARGE START ─────────
                        // The charge window (~2.5 s) is used as preload time.
                        // On the leading edge of isCharging(), immediately queue
                        // a full tube of chunks around the max-power trajectory.
                        // Camera is locked during charging so the preload direction
                        // exactly matches what the player will see in flight.
                        //
                        // pathReadiness polls on every frame during charging so the
                        // HUD shows a live percentage as chunks become meshed.
                        boolean nowCharging      = player.abilities.isCharging();
                        boolean nowCannonballing = player.abilities.isCannonballing;

                        if (nowCharging && !wasCharging) {
                            // Leading edge: calculate max-power velocity along locked dir
                            float lYaw   = player.abilities.lockedYaw;
                            float lPitch = player.abilities.lockedPitch;
                            float speed  = GameConfig.cannonMaxPower;
                            float mvx = (float)(Math.cos(lPitch) * Math.cos(lYaw)) * speed;
                            float mvy = (float)(Math.sin(lPitch))                  * speed;
                            float mvz = (float)(Math.cos(lPitch) * Math.sin(lYaw)) * speed;
                            int sideR = Math.min(GameConfig.renderDistance, 4);
                            world.preloadChunksAroundPath(
                                    player.position.x, player.position.y, player.position.z,
                                    mvx, mvy, mvz, worldGen, sideR);
                            pathReadiness = 0f;
                        }

                        if (nowCharging) {
                            // Poll readiness fraction every frame for the HUD
                            float lYaw   = player.abilities.lockedYaw;
                            float lPitch = player.abilities.lockedPitch;
                            float speed  = GameConfig.cannonMaxPower;
                            float mvx = (float)(Math.cos(lPitch) * Math.cos(lYaw)) * speed;
                            float mvy = (float)(Math.sin(lPitch))                  * speed;
                            float mvz = (float)(Math.cos(lPitch) * Math.sin(lYaw)) * speed;
                            pathReadiness = world.pathReadinessFraction(
                                    player.position.x, player.position.y, player.position.z,
                                    mvx, mvy, mvz);
                        } else {
                            pathReadiness = 0f;
                        }

                        wasCharging      = nowCharging;
                        wasCannonballing = nowCannonballing;

                    } else {
                        breakingActive = false;
                    }
                }

                if (network != null && network.connected) {
                    if (network.seedReceived) {
                        GameConfig.seed = network.newSeed;
                        world.clearAllChunks();
                        worldGen.resetSeed(GameConfig.seed);

                        // We remove the old "player.position.y = 100.0f;" drop
                        // because we are going to teleport to the host instead.
                        network.seedReceived = false;
                    }

                    // ── TELEPORT CLIENT TO HOST SPAWN ──
                    if (!network.isHost() && !clientSpawnedAtHost && (network.remoteX != 0f || network.remoteZ != 0f)) {
                        // Place the client 2 blocks away from the host horizontally to avoid physics collisions,
                        // and set Y to 250.0f. This forces the preloader to build the chunks around the host
                        // and drop the client safely onto the terrain surface once loaded.
                        player.position.set(network.remoteX + 2.0f, 250.0f, network.remoteZ + 2.0f);
                        player.highestY = 250.0f;
                        clientSpawnedAtHost = true;
                    }

                    // 1. Detect & Send Discrete State Changes (Instantly)
                    int currentState = 0;
                    if (player.abilities.isDashing) currentState = 1;
                    else if (player.abilities.isCannonballing) currentState = 2;
                    else if (player.abilities.isRewinding) currentState = 3;
                    else if (player.isSmashing()) currentState = 4;
                    else if (player.debugMode) currentState = player.flightController.getMode() == FlightController.FlightMode.SOAR ? 5 : 6;

                    if (currentState != lastNetState) {
                        network.sendState(currentState);
                        lastNetState = currentState;
                    }

                    boolean currentHooked = player.flightController.isHooked();
                    if (currentHooked != lastNetHooked) {
                        Vector3f hp = player.flightController.getHookPoint();
                        network.sendGrapple(currentHooked, hp != null ? hp.x : 0, hp != null ? hp.y : 0, hp != null ? hp.z : 0);
                        lastNetHooked = currentHooked;
                    }

                    // 2. Rate-Limit Position Sync to 30Hz (Bandwidth Optimization)
                    if (now - lastNetSendTime >= (1.0 / 30.0)) {
                        network.sendPosition(player.position.x, player.position.y, player.position.z,
                                camera.yaw, camera.pitch, player.getCameraRoll());
                        lastNetSendTime = now;
                    }

                    // 3. Update Remote Player state from network
                    remotePlayer.targetX = network.remoteX;
                    remotePlayer.targetY = network.remoteY;
                    remotePlayer.targetZ = network.remoteZ;
                    remotePlayer.targetYaw = network.remoteYaw;
                    remotePlayer.targetPitch = network.remotePitch;
                    remotePlayer.targetRoll = network.remoteRoll;
                    remotePlayer.targetState = network.remoteState;
                    remotePlayer.targetHooked = network.remoteHooked;
                    remotePlayer.targetHookX = network.remoteHookX;
                    remotePlayer.targetHookY = network.remoteHookY;
                    remotePlayer.targetHookZ = network.remoteHookZ;

                    // CRITICAL: Update remote player using rawDeltaTime
                    // This prevents the remote player from stuttering if local time dilation is active
                    remotePlayer.update(rawDeltaTime);

                    // ... [rest of the polling block: pollBreak, pollChat, etc.] ...
                    int[] brk = network.pollBreak();
                    if (brk != null) {
                        Block brokenBlock = world.getBlock(brk[0], brk[1], brk[2]);
                        if (brokenBlock.isSolid())
                            droppedItems.add(new DroppedItem(brk[0], brk[1], brk[2], brokenBlock));
                        world.setBlock(brk[0], brk[1], brk[2], Block.AIR);
                        world.rebuildChunkAt(brk[0], brk[1], brk[2]);
                    }

                    int[] plc = network.pollPlace();
                    if (plc != null) {
                        world.setBlock(plc[0], plc[1], plc[2], Block.values()[plc[3]]);
                        world.rebuildChunkAt(plc[0], plc[1], plc[2]);
                    }

                    String chat = network.pollChat();
                    if (chat != null) chatHistory.add("[Friend]: " + chat);

                    // ── NETWORK CRATER SYNC ────────────────────────────────────
                    int[] crt = network.pollCrater();
                    if (crt != null) {
                        world.createImpactCrater(crt[0], crt[1], crt[2], crt[3]);
                        spawnCraterEjecta(crt[0], crt[1], crt[2], crt[3]);
                        // Remote craters get screen shake too (smaller amplitude)
                        float dist = new Vector3f(crt[0], crt[1], crt[2])
                                .distance(player.position);
                        if (dist < 80f) smashShakeTimer = GameConfig.smashShakeDuration * 0.5f;
                    }

                    int[] pk = network.pollPickup();
                    Vector3f chestPos = new Vector3f(player.position.x,
                            player.position.y + 0.9f, player.position.z);
                    for (int i = droppedItems.size() - 1; i >= 0; i--) {
                        DroppedItem item = droppedItems.get(i);
                        item.update(timeStopActive ? 0f : deltaTime, player.position);
                        if (chestPos.distance(item.position) < 0.5f) {
                            inventory.addBlock(item.blockType);
                            addBlockToHotbar(item.blockType);
                            item.alive = false;
                            if (network != null && network.connected)
                                network.sendPickup(item.originX, item.originY, item.originZ);
                            droppedItems.remove(i);
                        }
                    }
                }

                if (!isPreloading) {
                    lastTarget = player.getTargetBlock(camera, world);
                    // tickLiquids uses scaled deltaTime — fast time makes water flow faster
                    world.tickLiquids(deltaTime);
                    world.updateChunks(world, worldGen, player);

                    // ── NON-EUCLIDEAN "LAYERED ROOMS" (F6 toggles in/out) ────
                    boolean f6Now = glfwGetKey(window, GLFW_KEY_F6) == GLFW_PRESS;
                    if (f6Now && !lastF6) {
                        if (nerActive) exitLayeredRooms(camera);
                        else           enterLayeredRooms(camera);
                    }
                    lastF6 = f6Now;

                    // While inside, watch for the player rounding the pillar and
                    // re-skin the hidden diagonal room to keep the sequence going.
                    if (nerActive) updateLayeredRooms();

                    // ── CANYON WARP (F5 toggles to the mesa region and back) ───
                    boolean f5Now = glfwGetKey(window, GLFW_KEY_F5) == GLFW_PRESS;
                    if (f5Now && !lastF5) {
                        if (!atCanyon) {
                            canyonReturnX = player.position.x;
                            canyonReturnY = player.position.y;
                            canyonReturnZ = player.position.z;
                            player.position.set(GameConfig.canyonCenterX + 0.5f,
                                                GameConfig.canyonCeilingY + 50f,
                                                GameConfig.canyonCenterZ + 0.5f);
                            player.setVelocityY(0f);
                            atCanyon = true;
                            canyonSettlePending = true;
                            hintText = "WARPED TO CANYON  —  press F5 to return";
                            hintTimer = 5f;
                        } else {
                            player.position.set(canyonReturnX, canyonReturnY, canyonReturnZ);
                            player.setVelocityY(0f);
                            atCanyon = false;
                            canyonSettlePending = false;
                            hintText = "Returned to where you were";
                            hintTimer = 2.5f;
                        }
                    }
                    lastF5 = f5Now;
                    // Hover high above the canyon until its chunk meshes, then snap
                    // to the surface — avoids a long fall onto ungenerated terrain.
                    if (canyonSettlePending) {
                        int ccx = Math.floorDiv((int) Math.floor(player.position.x), Chunk.SIZE);
                        int ccz = Math.floorDiv((int) Math.floor(player.position.z), Chunk.SIZE);
                        Chunk cch = world.getChunk(ccx, 0, ccz);
                        if (cch != null && cch.state == Chunk.ChunkState.MESHED) {
                            int bx = (int) Math.floor(player.position.x);
                            int bz = (int) Math.floor(player.position.z);
                            settle:
                            for (int ly = Chunk.HEIGHT - 2; ly >= 1; ly--) {
                                if (!world.getBlock(bx, ly, bz).isSolid()) continue;
                                for (int sy = ly + 1; sy < Chunk.HEIGHT; sy++)
                                    if (world.getBlock(bx, sy, bz).isSolid()) continue settle;
                                player.position.y = ly + 1.5f;
                                break;
                            }
                            player.setVelocityY(0f);
                            canyonSettlePending = false;
                        } else {
                            player.position.y = GameConfig.canyonCeilingY + 50f; // hover while loading
                            player.setVelocityY(0f);
                        }
                    }

                    // ── ORBITAL ANNIHILATION (F7 fires the cinematic) ─────────
                    boolean f7Now = glfwGetKey(window, GLFW_KEY_F7) == GLFW_PRESS;
                    if (f7Now && !lastF7 && !orbitalActive) startOrbitalStrike(camera);
                    lastF7 = f7Now;
                    if (orbitalActive) updateOrbitalStrike(rawDeltaTime);

                    // ── THE WORLD: time-stop domain (F8) ──────────────────────
                    boolean f8Now = glfwGetKey(window, GLFW_KEY_F8) == GLFW_PRESS;
                    if (f8Now && !lastF8 && !timeStopActive) startTimeStop();
                    lastF8 = f8Now;
                    if (timeStopActive) updateTimeStop(rawDeltaTime);

                    // ── VOXEL LINES world restyle (F10 = one-shot cycle) ──────
                    boolean f10Now = glfwGetKey(window, GLFW_KEY_F10) == GLFW_PRESS;
                    if (f10Now && !lastF10 && !vlActive) {
                        vlActive = true; vlT = 0f;
                        vlCx = player.position.x; vlCy = player.position.y + 1f; vlCz = player.position.z;
                        hintText = "VOXEL LINES"; hintTimer = 3f;
                    }
                    lastF10 = f10Now;
                    if (vlActive) updateVoxelLines(rawDeltaTime);

                    // ── CHOCOLATE DISCO GRID ('.' key) ─────────────────────────────────────────
                    boolean kNow = glfwGetKey(window, KeyBindings.DISCO) == GLFW_PRESS;
                    if (kNow && !lastDisco) {
                        if (!cdActive) {
                            spawnDiscoGrid(camera, world);
                        } else if (showDiscoUI) {
                            dismissDiscoGrid();
                        }
                    }
                    lastDisco = kNow;
                    if (cdActive) updateDiscoGrid(rawDeltaTime, camera, world);

                    // ── DEPRIVATION DOMAIN – Water God Stance (' key) ─────────
                    boolean apostrNow = glfwGetKey(window, KeyBindings.DEPRIVATION_DOMAIN) == GLFW_PRESS;
                    if (apostrNow && !lastApostr) {
                        if (!depActive && depCooldown <= 0f) {
                            startDeprivationDomain();
                        } else if (depActive) {
                            stopDeprivationDomain();
                        }
                    }
                    lastApostr = apostrNow;
                    // Tick cooldown + strike flash decay every frame (outside domain too)
                    depCooldown = Math.max(0f, depCooldown - rawDeltaTime);
                    depStrike   = Math.max(0f, depStrike   - rawDeltaTime * 6f);
                    if (depActive) updateDeprivationDomain(rawDeltaTime, world);

                    Vector3f chestPos = new Vector3f(player.position.x,
                            player.position.y + 0.9f, player.position.z);
                    for (int i = droppedItems.size() - 1; i >= 0; i--) {
                        DroppedItem item = droppedItems.get(i);
                        item.update(timeStopActive ? 0f : deltaTime, player.position);
                        if (chestPos.distance(item.position) < 0.5f) {
                            inventory.addBlock(item.blockType);
                            addBlockToHotbar(item.blockType);
                            item.alive = false;
                            if (network != null && network.connected)
                                network.sendPickup(item.originX, item.originY, item.originZ);
                            droppedItems.remove(i);
                        }
                    }
                }
            }

            // ── TUTORIAL TIMER TICKS (always, regardless of pause/help state) ──
            if (welcomeTimer > 0f) welcomeTimer = Math.max(0f, welcomeTimer - rawDeltaTime);
            if (hintTimer    > 0f) hintTimer    = Math.max(0f, hintTimer    - rawDeltaTime);

            // ── TINNITUS ARC ──────────────────────────────────────────────────
            // Runs for TINNITUS_DUR seconds completely independently of orbitalActive.
            //
            // KEY: we use ONLY setMasterGain here (no muffle). Muffle applies its
            // lowpass to every source in the pool via rerouteAll(), including the
            // tinnitus one-shot — at 2.2× pitch that kills 95% of its signal.
            // setMasterGain only re-applies to loop sources, so the tinnitus
            // one-shot (spawned at masterGain=1.0) keeps its full 6.0 gain.
            //
            // Shape (real tinnitus, per the user):
            //   0 – 1.0 s : world CUTS to near-silence instantly (masterGain→0.0)
            //   1.0 – 5.5 s: world bleeds back very slowly with cubic ease-out
            //   5.5 – 7.0 s: world returns to normal; faint distorted bleed becomes clear
            if (tinnitusTimer > 0f) {
                tinnitusTimer = Math.max(0f, tinnitusTimer - rawDeltaTime);
                float p = 1f - tinnitusTimer / TINNITUS_DUR; // 0=just started, 1=done

                // Cut to silence fast (first 14% ≈ 1 s), then recover slowly.
                float gain;
                if (p < 0.14f) {
                    gain = 1f - (p / 0.14f); // 1→0 over first second
                } else {
                    float q = (p - 0.14f) / 0.86f;  // 0→1 over remaining 6 s
                    gain = q * q * q;                 // cubic: very slow then accelerates
                }
                AudioManager.setMasterGain(gain);

                if (tinnitusTimer == 0f) {
                    AudioManager.setMasterGain(1f);   // fully restored
                }
            }

            // ── RENDER ────────────────────────────────────────────────────────

            // ── KAMUI DISTORTION FBO ──────────────────────────────────────────
            // When Kamui is active the 3-D scene is redirected into an off-screen
            // texture. After all 3-D rendering is done we apply a GLSL distortion
            // shader (swirl / vortex UV warp) to that texture and blit the result
            // to the default framebuffer. ImGui is then composited on top normally.
            boolean doKamuiDistort = player != null && !isPreloading
                    && player.abilities.isKamui && distortShader != null;
            // The orbital cinematic also redirects the scene into the FBO so we can
            // run the searing-bloom pass over the emissive scan + laser flash.
            boolean doOrbitalBloom = orbitalActive && !isPreloading && bloomShader != null
                    && orbitalT >= ORB_DARK_START && orbitalT < ORB_END;
            // The radar also blooms (bright green lines + searing enemy pings bleed).
            boolean doRadarBloom = vlActive && !isPreloading && bloomShader != null && vlAmountNow > 0.05f;
            // Disco grid also blooms — the wireframe boxes and detonation pillars are
            // HDR-bright and need the bloom pass to look correct.
            boolean doDiscoBloom = cdActive && !isPreloading && bloomShader != null && cdSpawnT > 0.05f;
            // Domain always blooms — the HDR gold hemisphere + threads + boundary ring need it.
            boolean doDepBloom   = depActive && !isPreloading && bloomShader != null;
            boolean doBloom = doOrbitalBloom || doRadarBloom || doDiscoBloom || doDepBloom;
            boolean useSceneFbo = doKamuiDistort || doBloom;
            if (useSceneFbo) {
                // Recreate the FBO whenever the window is resized or on first use
                // CRITICAL FIX: Use physical framebuffer size (fw, fh) for FBO on Retina/High-DPI displays!
                if (fw[0] != kamuiFboW || fh[0] != kamuiFboH) {
                    if (kamuiFbo != 0) {
                        org.lwjgl.opengl.GL30.glDeleteFramebuffers(kamuiFbo);
                        org.lwjgl.opengl.GL11.glDeleteTextures(kamuiFboTex);
                        org.lwjgl.opengl.GL30.glDeleteRenderbuffers(kamuiFboRbo);
                    }
                    kamuiFbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
                    kamuiFboTex = org.lwjgl.opengl.GL11.glGenTextures();
                    kamuiFboRbo = org.lwjgl.opengl.GL30.glGenRenderbuffers();

                    org.lwjgl.opengl.GL30.glBindFramebuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, kamuiFbo);

                    // Color texture attachment
                    org.lwjgl.opengl.GL11.glBindTexture(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, kamuiFboTex);
                    org.lwjgl.opengl.GL11.glTexImage2D(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                            org.lwjgl.opengl.GL11.GL_RGB, fw[0], fh[0], 0,
                            org.lwjgl.opengl.GL11.GL_RGB,
                            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                            (java.nio.ByteBuffer) null);
                    org.lwjgl.opengl.GL11.glTexParameteri(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                            org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                            org.lwjgl.opengl.GL11.GL_LINEAR);
                    org.lwjgl.opengl.GL11.glTexParameteri(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                            org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                            org.lwjgl.opengl.GL11.GL_LINEAR);
                    org.lwjgl.opengl.GL30.glFramebufferTexture2D(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                            org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, kamuiFboTex, 0);

                    // Depth renderbuffer attachment
                    org.lwjgl.opengl.GL30.glBindRenderbuffer(
                            org.lwjgl.opengl.GL30.GL_RENDERBUFFER, kamuiFboRbo);
                    org.lwjgl.opengl.GL30.glRenderbufferStorage(
                            org.lwjgl.opengl.GL30.GL_RENDERBUFFER,
                            org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, fw[0], fh[0]);
                    org.lwjgl.opengl.GL30.glFramebufferRenderbuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
                            org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT,
                            org.lwjgl.opengl.GL30.GL_RENDERBUFFER, kamuiFboRbo);

                    kamuiFboW = fw[0];
                    kamuiFboH = fh[0];
                    org.lwjgl.opengl.GL30.glBindFramebuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                }
                // Redirect 3-D render into the off-screen FBO
                org.lwjgl.opengl.GL30.glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, kamuiFbo);
            }

            // Orbital blackout: the SKY (clear colour) goes pure black so the only
            // light left in the world is the emissive lidar scan + laser flash.
            if (orbitalActive && orbDark) glClearColor(0f, 0f, 0f, 1f);
            else                          glClearColor(0.5f, 0.7f, 0.9f, 1f);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (networkInitialized) {
                shader.bind();
                shader.setUniform("sunDirection",
                        new Vector3f(GameConfig.sunDirX, GameConfig.sunDirY, GameConfig.sunDirZ));
                // During the blackout, kill the sun + ambient so terrain is truly black.
                boolean orbBlack = orbitalActive && orbDark;
                shader.setUniform("sunStrength",     orbBlack ? 0f : GameConfig.sunStrength);
                shader.setUniform("ambientStrength", orbBlack ? 0f : GameConfig.ambientStrength);
                shader.setUniform("emissiveMode", 0);   // reset each frame (effects toggle it locally)
                // Orbital "lidar" scan + environmental flash uniforms.
                shader.setUniform("orbActive",    (orbitalActive && orbScanIntensity > 0.001f) ? 1 : 0);
                shader.setUniform("orbEpicenter", new Vector3f(orbEpiX, orbEpiY, orbEpiZ));
                shader.setUniform("orbRadius",    orbScanRadiusW);
                shader.setUniform("orbWidth",     6.0f);
                shader.setUniform("orbIntensity", orbScanIntensity);
                shader.setUniform("orbFlash",     orbitalActive ? orbFlashAmt : 0f);
                // "The World" time-stop domain.
                shader.setUniform("tsActive", timeStopActive ? 1 : 0);
                shader.setUniform("tsCenter", new Vector3f(tsCenterX, tsCenterY, tsCenterZ));
                shader.setUniform("tsRadius", tsRadiusNow);
                shader.setUniform("tsEdge",   2.5f);
                // Radar scope projected on the terrain.
                shader.setUniform("vlActive", vlActive ? 1 : 0);
                shader.setUniform("vlCenter", new Vector3f(vlCx, vlCy, vlCz));
                shader.setUniform("vlRadius", vlRadiusNow);
                shader.setUniform("vlAmount", vlAmountNow);
                shader.setUniform("vlSweep",  vlSweepNow);

                // Deprivation Domain world-shader tinting
                shader.setUniform("depActive", depActive ? 1 : 0);
                shader.setUniform("depCenter", new Vector3f(depX, depY + 0.9f, depZ));
                shader.setUniform("depRadius", GameConfig.depRadius);
                shader.setUniform("depStrike", depStrike);
                shader.setUniform("desaturate", ScreenEffectManager.INSTANCE.getDesaturate());

                boolean isCameraUnderwater = world.getBlock(
                        (int) Math.floor(camera.position.x),
                        (int) Math.floor(camera.position.y),
                        (int) Math.floor(camera.position.z)).isLiquid();
                shader.setUniform("isUnderwater", isCameraUnderwater ? 1 : 0);
                shader.setUniform("cameraY", camera.position.y);

                // ── SNOW BIOME ATMOSPHERE ─────────────────────────────────────
                // Fades in as the player climbs into snow-mountain altitude.
                // Suppressed underground (cave reverb env) and underwater.
                float snowAtmStr = 0f;
                if (!isCameraUnderwater && lastEnv != AudioManager.ENV_CAVE) {
                    float altStart = GameConfig.snowAltitude - 60f;
                    float altEnd   = GameConfig.snowAltitude;
                    float snowT    = (camera.position.y - altStart) / (altEnd - altStart);
                    snowAtmStr = Math.max(0f, Math.min(snowT, 1f)) * 0.18f;
                }
                shader.setUniform("snowAtmosphereStrength", snowAtmStr);

                // ── SNOW WEATHER INTENSITY (for particle effect) ──────────────
                // Starts fading in 70 blocks below snow altitude, full by 60 above.
                snowIntensity = 0f;
                if (!isCameraUnderwater && lastEnv != AudioManager.ENV_CAVE) {
                    float sLow  = GameConfig.snowAltitude - 70f;
                    float sHigh = GameConfig.snowAltitude + 60f;
                    snowIntensity = Math.max(0f, Math.min(1f,
                            (camera.position.y - sLow) / (sHigh - sLow)));
                }
                snowTimeAccum += deltaTime;

                // ── TIME DILATION VIGNETTE ────────────────────────────────────
                // Slow motion: subtle blue-grey wash
                // Fast time: warm orange tint
                float slowFactor = tc.getSlownessFactor();
                float fastFactor = tc.getFastnessFactor();
                float vignetteStrength;
                Vector3f vignetteColor;
                if (slowFactor > 0.001f) {
                    vignetteStrength = slowFactor * 0.28f;
                    vignetteColor = new Vector3f(0.52f, 0.58f, 0.70f); // blue-grey
                } else if (fastFactor > 0.001f) {
                    vignetteStrength = fastFactor * 0.22f;
                    vignetteColor = new Vector3f(0.8f, 0.55f, 0.18f); // warm orange
                } else {
                    vignetteStrength = 0f;
                    vignetteColor = new Vector3f(0f, 0f, 0f);
                }
                shader.setUniform("timeVignetteStrength", vignetteStrength);
                shader.setUniform("timeVignetteColor", vignetteColor);

                // ── ABILITY + ATTACK OVERLAY VIGNETTE ────────────────────────
                // Use whichever overlay is currently stronger so neither system
                // silently stomps the other during simultaneous effects.
                // When Kamui FBO post-process is active the distort shader is the
                // sole source of colour effects — zero the 3-D overlay so the
                // scene captured into the FBO is clean and readable.
                float abilityOverlayStr = doKamuiDistort ? 0f : player.abilities.getOverlayStrength();
                float attackOverlayStr  = player.attacks.getOverlayStrength();
                Vector3f compositeOverlayColor;
                float compositeOverlayStr;
                if (attackOverlayStr >= abilityOverlayStr) {
                    compositeOverlayColor = player.attacks.getOverlayColor();
                    compositeOverlayStr = attackOverlayStr;
                } else {
                    compositeOverlayColor = doKamuiDistort ? new Vector3f(0f) : player.abilities.getOverlayColor();
                    compositeOverlayStr = abilityOverlayStr;
                }
                // ── SEAL TELEPORT OVERLAY ─────────────────────────────────
                // White-ish flash when the player zips to a seal.
                if (player.seals.teleportFlash > 0f) {
                    float flashStr = (player.seals.teleportFlash / GameConfig.sealTeleportFlash) * 0.55f;
                    if (flashStr > compositeOverlayStr) {
                        compositeOverlayStr = flashStr;
                        compositeOverlayColor = new Vector3f(0.95f, 0.98f, 1.0f);
                    }
                }
                // ── SUBSTITUTE PRIMED OVERLAY ─────────────────────────────
                if (substitutePrimed) {
                    float timeSecs = (float) glfwGetTime();
                    float pulseStr = 0.10f + 0.05f * (float) Math.sin(timeSecs * 8.0f);
                    if (pulseStr > compositeOverlayStr) {
                        compositeOverlayStr   = pulseStr;
                        compositeOverlayColor = new Vector3f(0.95f, 0.97f, 1.0f);
                    }
                }
                // ── STORM OVERLAY (lightning charging / active) ───────────────
                float stormI = player.lightning.stormIntensity;
                if (stormI > 0f) {
                    // Deep purple-black storm that really darkens the scene
                    float stormStr = stormI * 0.72f;
                    if (stormStr > compositeOverlayStr) {
                        compositeOverlayStr   = stormStr;
                        compositeOverlayColor = new Vector3f(0.02f, 0.02f, 0.12f);
                    }
                }

                shader.setUniform("overlayVignetteStrength", compositeOverlayStr);
                shader.setUniform("overlayVignetteColor", compositeOverlayColor);
                // Default alpha multiplier (1.0 = no change). Ghost rendering overrides this.
                shader.setUniform("alphaMultiplier", 1.0f);
                // Default: no texture sampling. Set to 1 + bind texture for ModelMesh rendering.
                shader.setUniform("useTexture", 0);
                // Default: normal rendering (not portal FBO passthrough).
                shader.setUniform("portalMode", 0);

                // ── FLIGHT CAMERA EFFECTS ─────────────────────────────────────
                // Set dynamic FOV from flight controller boost (player camera only).
                // Suppressed when piloting the stand drone.
                boolean inStandView = player.stand.isInStandPerspective();
                if (!inStandView) {
                    float fovBoost = player.getCameraFovBoost();
                    // Use Math.abs so negative zoom-in (sniper charge) is applied as well as
                    // positive zoom-out (flight speed). Previously the condition fovBoost > 0.1f
                    // silently discarded any negative value, making sniper zoom do nothing.
                    camera.dynamicFov = (Math.abs(fovBoost) > 0.1f) ? GameConfig.fov + fovBoost : -1f;
                }

                // ── VIEW MATRIX + ROLL ────────────────────────────────────────
                // When piloting the drone, all cosmetic effects are suppressed:
                // no roll, no screen shake, no attack pitch — clean drone view only.
                // All rendering uses standCamera so the player sees through the drone.
                Matrix4f baseView;
                float rollAngle;
                Matrix4f projection;
                Vector3f shakeOffset;

                if (inStandView) {
                    Camera sc = player.stand.standCamera;
                    float droneFovBoost = player.attacks.getFovBoost();
                    sc.dynamicFov = (Math.abs(droneFovBoost) > 0.1f) ? GameConfig.fov + droneFovBoost : -1f;
                    baseView = sc.getViewMatrix();
                    rollAngle = 0f;
                    projection = sc.getProjectionMatrix();
                    shakeOffset = new Vector3f(0f);
                } else {
                    // Attack pitch offset is non-destructive: add, build, subtract.
                    float attackPitch = player.attacks.getPitchOffset();
                    camera.pitch += attackPitch;
                    baseView = camera.getViewMatrix();
                    camera.pitch -= attackPitch;
                    rollAngle = player.getCameraRoll();
                    projection = camera.getProjectionMatrix();
                    shakeOffset = computeShakeOffset(rawDeltaTime);
                }

                Matrix4f view;
                if (Math.abs(rollAngle) > 0.0005f) {
                    // Rotate around the camera's forward axis (Z in view space)
                    // by applying rotateZ BEFORE the view matrix (in world space
                    // this means we tilt the camera around its own look axis).
                    Matrix4f rollMat = new Matrix4f().rotateZ(rollAngle);
                    view = rollMat.mul(baseView);
                } else {
                    view = baseView;
                }

                // ── SCREEN SHAKE ──────────────────────────────────────────────
                // Damped sinusoidal offset on camera position for smashShakeDuration.
                // We temporarily move camera.position, build the MVP, then restore it.
                if (shakeOffset.lengthSquared() > 0f) {
                    camera.position.add(shakeOffset);
                    view = camera.getViewMatrix(); // recompute with shaken position
                    if (Math.abs(rollAngle) > 0.0005f) {
                        view = new Matrix4f().rotateZ(rollAngle).mul(view);
                    }
                }

                // Restore camera position after shake (BEFORE any other use)
                if (shakeOffset.lengthSquared() > 0f) {
                    camera.position.sub(shakeOffset);
                }

                int playerCX = Math.floorDiv((int) player.position.x, Chunk.SIZE);
                int playerCZ = Math.floorDiv((int) player.position.z, Chunk.SIZE);
                int R = GameConfig.renderDistance;
                int playerCY = Math.floorDiv((int) player.position.y, Chunk.HEIGHT);
                int cyMin = Math.min(playerCY - 4, -4);
                // Also render one slab above surface so Structure B (Y=900, CY=1) is
                // visible when the player is inside it.  Empty CY>0 chunks have no
                // mesh so the extra loop iteration is essentially free.
                int cyTop = Math.max(0, playerCY + 1);

                // Frustum culling uses the CLEAN view (no roll, no shake) to avoid
                // popping at the frustum edges during roll animations.
                Matrix4f cleanMvp = new Matrix4f(projection).mul(baseView);
                shader.setUniform("mvp", cleanMvp);

                // ── DIRTY MESH REBUILD ─────────────────────────────────────────
                if (!isPreloading) {
                    int maxMeshCompilesPerFrame = 6;
                    List<int[]> dirtyList = new ArrayList<>();
                    for (int dx = -R; dx <= R; dx++) {
                        for (int dz = -R; dz <= R; dz++) {
                            for (int cy = cyTop; cy >= cyMin; cy--) {
                                Chunk c = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                                if (c != null && c.dirty && c.state == Chunk.ChunkState.MESHED) {
                                    dirtyList.add(new int[]{dx, dz, dx * dx + dz * dz, cy});
                                }
                            }
                        }
                    }
                    dirtyList.sort(Comparator.comparingInt(e -> e[2]));
                    int compiled = 0;
                    for (int[] e : dirtyList) {
                        if (compiled >= maxMeshCompilesPerFrame) break;
                        Chunk c = world.getChunk(playerCX + e[0], e[3], playerCZ + e[1]);
                        if (c != null && c.dirty && c.state == Chunk.ChunkState.MESHED) {
                            world.buildChunkMeshes(c);
                            compiled++;
                        }
                    }
                }
// ── PASS 1: OPAQUE ────────────────────────────────────────────
                // Frustum culling uses the ACTUAL view matrix (including roll/shake)
                Matrix4f renderMvp = new Matrix4f(projection).mul(view);
                orbProjView.set(renderMvp);   // captured for the F7 cinematic overlay
                float[] frustumPlanes = extractFrustumPlanes(renderMvp);

                // ── DIRTY MESH REBUILD ─────────────────────────────────────────
                if (!isPreloading) {
                    int maxMeshCompilesPerFrame = 6;
                    List<int[]> dirtyList = new ArrayList<>();
                    for (int dx = -R; dx <= R; dx++) {
                        for (int dz = -R; dz <= R; dz++) {
                            for (int cy = cyTop; cy >= cyMin; cy--) {
                                Chunk c = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                                if (c != null && c.dirty && c.state == Chunk.ChunkState.MESHED) {
                                    dirtyList.add(new int[]{dx, dz, dx * dx + dz * dz, cy});
                                }
                            }
                        }
                    }
                    dirtyList.sort(Comparator.comparingInt(e -> e[2]));
                    int compiled = 0;
                    for (int[] e : dirtyList) {
                        if (compiled >= maxMeshCompilesPerFrame) break;
                        Chunk c = world.getChunk(playerCX + e[0], e[3], playerCZ + e[1]);
                        if (c != null && c.dirty && c.state == Chunk.ChunkState.MESHED) {
                            world.buildChunkMeshes(c);
                            compiled++;
                        }
                    }
                }


                // ── BIND BLOCK TEXTURE ATLAS (if the PNG has been placed) ────
                boolean atlasActive = com.leaf.game.render.BlockTextureAtlas.isLoaded();
                if (atlasActive) {
                    org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                            com.leaf.game.render.BlockTextureAtlas.getTextureId());
                    shader.setUniform("texSampler", 0);
                    shader.setUniform("useTexture", 1);
                }

                // ── PASS 1: OPAQUE ────────────────────────────────────────────
                shader.setUniform("mvp", renderMvp);
                for (int dx = -R; dx <= R; dx++) {
                    for (int dz = -R; dz <= R; dz++) {
                        for (int cy = cyTop; cy >= cyMin; cy--) {
                            Chunk chunk = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                            if (chunk != null && chunk.opaqueMesh != null
                                    && isAabbInFrustum(frustumPlanes, chunk)) {
                                chunk.opaqueMesh.render();
                            }
                        }
                    }
                }

                // ── ORBITAL ANNIHILATION: 3D effect geometry (inside the FBO so
                //    it gets the searing bloom) — drawn over opaque terrain ──────
                if (orbitalActive && !isPreloading) {
                    renderOrbital3D(shader, projection, view, renderMvp);
                }

                // ── RADAR: 3D sweep blade, afterglow wedge, enemy wireframes ────
                if (vlActive && !isPreloading) {
                    renderRadar3D(shader, projection, view, renderMvp);
                }

                // ── CHOCOLATE DISCO: 9×9 glowing geometry grid ───────────────
                if (cdActive && !isPreloading) {
                    renderDiscoGrid(shader, projection, view, renderMvp);
                }

                // ── DEPRIVATION DOMAIN: golden hemisphere + thread web ────────
                if (depActive && !isPreloading) {
                    renderDeprivationDomain(shader, projection, view, renderMvp);
                }

                // ── PASS 2: TRANSPARENT ───────────────────────────────────────
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                shader.setUniform("mvp", renderMvp);
                for (int dx = -R; dx <= R; dx++) {
                    for (int dz = -R; dz <= R; dz++) {
                        for (int cy = cyTop; cy >= cyMin; cy--) {
                            Chunk chunk = world.getChunk(playerCX + dx, cy, playerCZ + dz);
                            if (chunk != null && chunk.transparentMesh != null
                                    && isAabbInFrustum(frustumPlanes, chunk)) {
                                chunk.transparentMesh.render();
                            }
                        }
                    }
                }
                glDisable(GL_BLEND);

                // ── UNBIND ATLAS so subsequent passes use vertex colour ────────
                if (atlasActive) {
                    shader.setUniform("useTexture", 0);
                    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
                }

                // ── PASS 3: OVERLAYS, ENTITIES & PROJECTILES (Blend Enabled) ──
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                // 1. Render Grapple Cable & Laser Sight
                FlightController fc = player.flightController;
                boolean grappleActive = player.debugMode && fc.getMode() == FlightController.FlightMode.GRAPPLE;
                boolean voidAiming = player.attacks.getChargeFrac() > 0f;

                if (grappleActive || voidAiming) {
                    Vector3f playerHand = new Vector3f(player.position.x, player.position.y + 0.9f, player.position.z);
                    Vector3f targetPoint = null;
                    boolean isLaser = false;
                    Block renderBlock = Block.CRYSTAL_AMETHYST;

                    if (grappleActive) {
                        targetPoint = fc.isHooked() ? fc.getHookPoint() : fc.getAimTarget(camera, world);
                        isLaser = !fc.isHooked();
                        renderBlock = isLaser ? Block.CRYSTAL_ROSE : Block.CRYSTAL_AMETHYST;
                    } else if (voidAiming) {
                        targetPoint = player.attacks.getAimTarget(camera, world);
                        isLaser = true;
                        renderBlock = Block.CRYSTAL_AMETHYST;
                    }

                    if (targetPoint != null) {
                        Vector3f ropeDir = new Vector3f(targetPoint).sub(playerHand);
                        float ropeDist = ropeDir.length();

                        if (ropeDist > 0.1f) {
                            ropeDir.normalize();
                            org.joml.Quaternionf ropeRot = new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, 0, 1), ropeDir);

                            float thickness = isLaser ? 0.008f : 0.04f;
                            if (voidAiming) thickness += player.attacks.getChargeFrac() * 0.015f;

                            Matrix4f ropeModel = new Matrix4f()
                                    .translate(playerHand.x, playerHand.y, playerHand.z)
                                    .rotate(ropeRot)
                                    .translate(0f, 0f, ropeDist * 0.5f)
                                    .scale(thickness, thickness, ropeDist / 0.24f);

                            Matrix4f ropeMvp = new Matrix4f(projection).mul(view).mul(ropeModel);
                            shader.setUniform("mvp", ropeMvp);

                            getItemMesh(renderBlock).render();
                        }
                    }
                }

                // 2. Render Void Shard Bolts
                for (AttackController.ActiveBolt bolt : player.attacks.activeBolts) {
                    float scale = 0.20f + bolt.chargeF * 0.24f;
                    Matrix4f boltModel = new Matrix4f()
                            .translate(bolt.pos.x, bolt.pos.y, bolt.pos.z)
                            .rotateY(bolt.spinPhase)
                            .rotateX(bolt.spinPhase * 0.6f)
                            .rotateZ(bolt.spinPhase * 0.4f)
                            .scale(scale);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(boltModel));
                    getItemMesh(Block.CRYSTAL_AMETHYST).render();
                }

                // 3. Render Stand Drone (Manhattan Transfer)
                if (player.stand.isDeployed()) {
                    float bob = (float) Math.sin(player.stand.bobPhase) * GameConfig.standHoverBob;
                    float droneSpin = (float) (glfwGetTime() * 1.5);
                    Vector3f sp = player.stand.standPos;

                    if (!inStandView) {
                        // A. Physical Drone Body
                        shader.setUniform("alphaMultiplier", 1.0f);

                        Matrix4f droneModel = new Matrix4f()
                                .translate(sp.x, sp.y + bob, sp.z)
                                .rotateY(droneSpin)
                                .scale(0.55f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(droneModel));

                        com.leaf.game.render.Texture standTex = com.leaf.game.render.AssetManager.get().getTexture("stand");
                        if (standTex != null) {
                            shader.setUniform("useTexture", 1);
                            standTex.bind();
                        }
                        com.leaf.game.render.AssetManager.get().getModel("stand").render();
                        if (standTex != null) {
                            shader.setUniform("useTexture", 0);
                        }

                        // B. Permanent Pulsing Energy Aura (The Yellow Glow)
                        float timeSecs = (float) glfwGetTime();
                        float pulseScale = 1.1f + 0.05f * (float) Math.sin(timeSecs * 4.0f);
                        float pulseAlpha = 0.40f + 0.15f * (float) Math.cos(timeSecs * 4.0f);


                        shader.setUniform("alphaMultiplier", pulseAlpha);
                        Matrix4f glowModel = new Matrix4f()
                                .translate(sp.x, sp.y + bob, sp.z)
                                .rotateY(-droneSpin * 0.5f)
                                .rotateX(timeSecs * 0.3f)
                                .scale(pulseScale);

                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(glowModel));
                        getItemMesh(Block.CRYSTAL_CITRINE).render();

                        // C. Inner Core Flare (Actively Targeted Highlight)
                        boolean aimingAtStand = player.attacks.isAimingAtStand(camera);
                        if (aimingAtStand) {
                            shader.setUniform("alphaMultiplier", 0.85f);
                            Matrix4f targetCore = new Matrix4f()
                                    .translate(sp.x, sp.y + bob, sp.z)
                                    .rotateY(droneSpin * 2.0f)
                                    .scale(0.85f);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(targetCore));
                            getItemMesh(Block.CRYSTAL_CITRINE).render();
                        }

                        // Blocked-LOS Warning
                        float blockedF = player.stand.getBlockedFlash();
                        if (blockedF > 0f) {
                            float alpha = (blockedF / GameConfig.standBlockedFlashTime) * 0.55f;
                            shader.setUniform("alphaMultiplier", alpha);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(droneModel));
                            getItemMesh(Block.CRATER_BLOOM).render();
                        }

                        shader.setUniform("alphaMultiplier", 1.0f);
                    }

                    // D. Stand Redirect Bolts
                    for (StandController.StandBolt bolt : player.stand.activeBolts) {
                        Matrix4f boltModel = new Matrix4f()
                                .translate(bolt.pos.x, bolt.pos.y, bolt.pos.z)
                                .rotateY(bolt.spinPhase)
                                .rotateX(bolt.spinPhase * 0.6f)
                                .rotateZ(bolt.spinPhase * 0.4f)
                                .scale(0.18f);
                        shader.setUniform("alphaMultiplier", 1.0f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(boltModel));
                        getItemMesh(Block.CRYSTAL_CITRINE).render();
                    }
                }

                // 4. In-Flight Seal Projectiles
                for (SealController.SealProjectile proj : player.seals.inFlightSeals) {
                    Matrix4f projModel = new Matrix4f()
                            .translate(proj.pos.x, proj.pos.y, proj.pos.z)
                            .rotateY(proj.spinPhase)
                            .scale(0.15f);
                    shader.setUniform("alphaMultiplier", 1.0f);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(projModel));
                    getItemMesh(Block.CRYSTAL_CITRINE).render();
                }

                // 5. Placed Seals (Normal Pass & Ghost Pass)
                if (!player.seals.placedSeals.isEmpty()) {
                    com.leaf.game.render.Texture sealTex = com.leaf.game.render.AssetManager.get().getTexture("seal");
                    if (sealTex != null) {
                        shader.setUniform("useTexture", 1);
                        sealTex.bind();
                    }
                    for (SealController.SealEntry seal : player.seals.placedSeals) {
                        float pulse = 0.85f + 0.15f * (float) Math.sin(seal.pulsePhase);
                        float scale = seal.targeted
                                ? 0.45f * GameConfig.sealTargetedScale
                                : 0.45f;
                        Matrix4f sealModel = new Matrix4f()
                                .translate(seal.position.x, seal.position.y, seal.position.z)
                                .rotateY(seal.spinPhase)
                                .scale(scale * pulse);
                        shader.setUniform("alphaMultiplier", 1.0f);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(sealModel));
                        com.leaf.game.render.AssetManager.get().getModel("seal").render();
                    }
                    if (sealTex != null) {
                        shader.setUniform("useTexture", 0);
                    }

                    // Through-wall ghost pass
                    glDisable(GL_DEPTH_TEST);
                    for (SealController.SealEntry seal : player.seals.placedSeals) {
                        float ghostAlpha = GameConfig.sealThroughWallAlpha
                                * (seal.targeted ? 1.0f : 0.7f);
                        float scale = seal.targeted
                                ? 0.28f * GameConfig.sealTargetedScale
                                : 0.28f;
                        Matrix4f sealModel = new Matrix4f()
                                .translate(seal.position.x, seal.position.y, seal.position.z)
                                .rotateY(seal.spinPhase)
                                .scale(scale);
                        shader.setUniform("alphaMultiplier", ghostAlpha);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(sealModel));
                        com.leaf.game.render.AssetManager.get().getModel("seal").render();
                    }
                    glEnable(GL_DEPTH_TEST);
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // 6. Render Enemies
                if (!enemyManager.getEnemies().isEmpty()) {
                    // RADAR: draw enemies as their EXACT model in see-through wireframe
                    // (no solid fill = transparent body) and ignore depth so contacts
                    // show through terrain — the "detection" look.
                    boolean radarWire = vlActive && vlAmountNow > 0.05f;
                    if (radarWire) {
                        org.lwjgl.opengl.GL11.glPolygonMode(
                                org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK, org.lwjgl.opengl.GL11.GL_LINE);
                        org.lwjgl.opengl.GL11.glLineWidth(2.5f);   // bold contact lines
                        glDisable(GL_DEPTH_TEST);
                    }
                    if (enemyAnimModel != null) {
                        // ── Animated path: enemy_basic.json with walk/attack/death ──
                        // ── Animated path ──
                        for (Enemy enemy : enemyManager.getEnemies()) {
                            float flashF = enemy.hitFlashTimer > 0f ? (enemy.hitFlashTimer / 0.18f) : 0f;
                            float alpha  = enemy.alive ? 1.0f : flashF;
                            if (alpha < 0.02f) {
                                enemyAnimPlayers.remove(enemy.id); continue;
                            }

                            // Pick the right model for the enemy type
                            boolean isGuardian = enemy.type == Enemy.Type.GUARDIAN || enemy.type == Enemy.Type.GOLEM;

                            com.leaf.game.anim.AnimModel targetModel =
                                    (enemy.type == Enemy.Type.SLIME    && slimeAnimModel != null) ? slimeAnimModel
                                            : (isGuardian                        && golemAnimModel != null) ? golemAnimModel
                                              : enemyAnimModel;

                            com.leaf.game.anim.AnimPlayer ap = enemyAnimPlayers.computeIfAbsent(
                                    enemy.id, id -> {
                                        com.leaf.game.anim.AnimPlayer p = new com.leaf.game.anim.AnimPlayer(targetModel);
                                        p.play("idle");
                                        return p;
                                    });

                            // Decide target animation from AI state
                            String want;
                            if (isGuardian) {
                                // The guardian drives its own clip (patrol walk/idle + 3-hit combo).
                                want = enemy.getAnimName();
                            } else if (!enemy.alive) {
                                want = "death";
                            } else if (enemy.state == Enemy.State.CHASE || enemy.state == Enemy.State.RETREATING) {
                                // ── USE "move" FOR SLIMES, "walk" FOR OTHERS ──
                                want = (enemy.type == Enemy.Type.SLIME) ? "move" : "walk";
                            } else if (enemy.state == Enemy.State.ATTACK || enemy.state == Enemy.State.SLAMMING) {
                                want = "attack";
                            } else {
                                want = "idle";
                            }

                            String cur = ap.getCurrentClip();
                            if (!want.equals(cur)) {
                                // Guardian attack clips are one-shot (1.3 s swing); everything else loops.
                                boolean once = isGuardian && want.startsWith("attack");
                                if ("death".equals(want)) {
                                    ap.play("death", false);
                                } else if (once) {
                                    ap.play(want, false);
                                } else if (cur == null || "idle".equals(cur) || "death".equals(cur)) {
                                    ap.play(want);
                                } else {
                                    ap.crossfade(want, 0.12f);
                                }
                            }
                            // Time stop: freeze the animation clock for every enemy
                            // so idle/walk/attack loops halt mid-frame.
                            ap.tick(timeStopActive ? 0f : rawDeltaTime);

                            // Face the player (Y-axis rotation only).
                            // The golem's front is on the +Z face, so it needs NO +PI flip
                            // (the slime/enemy_basic front is -Z and does). The guardian
                            // controls its own facing (patrol heading / toward player).
                            float faceY;
                            if (isGuardian) {
                                faceY = enemy.facingYaw;
                            } else {
                                float faceDx = player.position.x - enemy.position.x;
                                float faceDz = player.position.z - enemy.position.z;
                                faceY  = (float) Math.atan2(faceDx, faceDz) + (float) Math.PI;
                            }

                            float[] sv = enemy.renderScaleVec();
                            Matrix4f worldMat = new Matrix4f()
                                    .translate(enemy.position.x, enemy.position.y, enemy.position.z)
                                    .rotateY(faceY)
                                    .scale(sv[0], sv[1], sv[2]);

                            // RADAR: bright-green tint, depth off (through walls), and a
                            // searing glow spike the instant the sweep arm crosses this
                            // enemy's bearing — fading over the afterglow. Always at least
                            // base-bright so every contact (even far/hidden) reads clearly.
                            if (radarWire) {
                                float dxb = enemy.position.x - vlCx, dzb = enemy.position.z - vlCz;
                                float bearing = (float) Math.atan2(dzb, dxb);
                                float behind = ((vlSweepNow - bearing) % 6.2831853f + 6.2831853f) % 6.2831853f;
                                float ping = (float) Math.exp(-behind * 2.2f);   // sharp flare just behind the arm
                                // Resting = bright green; on contact the colour shifts white-hot
                                // (reads as "hotter" even through 8-bit clamping) + a glow boost
                                // so the bloom halo flares. Always ≥ base-bright so every contact
                                // (far or wall-occluded) is clearly visible.
                                float tr = 0.1f + 0.85f * ping;
                                float tb = 0.35f + 0.6f * ping;
                                com.leaf.game.anim.ModelRenderer.setOverride(
                                        tr, 1.0f, tb, 1.0f, 1.6f + 2.4f * ping, false);
                            }

                            com.leaf.game.anim.ModelRenderer.render(
                                    targetModel, ap.getPose(), worldMat, view, projection);

                            shader.bind();
                        }
                    } else {
                        // ── Fallback: plain capsule (no model loaded) ──────────
                        com.leaf.game.render.ModelMesh capsule =
                                com.leaf.game.render.AssetManager.get().getModel("player");
                        for (Enemy enemy : enemyManager.getEnemies()) {
                            float flashF = enemy.hitFlashTimer > 0f
                                    ? (enemy.hitFlashTimer / 0.18f) : 0f;
                            float alpha  = enemy.alive ? 1.0f : flashF;
                            if (alpha < 0.02f) continue;
                            float[] sv = enemy.renderScaleVec();
                            Matrix4f enemyMat = new Matrix4f()
                                    .translate(enemy.position.x, enemy.position.y,
                                               enemy.position.z)
                                    .scale(sv[0], sv[1], sv[2]);
                            shader.setUniform("alphaMultiplier", alpha);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(enemyMat));
                            capsule.render();
                        }
                    }
                    // Reset overlay state
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                    if (radarWire) {   // restore solid fill + depth after the wireframe pass
                        com.leaf.game.anim.ModelRenderer.clearOverride();
                        glEnable(GL_DEPTH_TEST);
                        org.lwjgl.opengl.GL11.glPolygonMode(
                                org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK, org.lwjgl.opengl.GL11.GL_FILL);
                        org.lwjgl.opengl.GL11.glLineWidth(1.0f);
                    }
                }

                // ── Render enemy projectiles (boulders / thrown rocks) ────────────
                if (!enemyManager.projectiles.isEmpty()) {
                    com.leaf.game.render.ModelMesh stoneModel =
                            com.leaf.game.render.AssetManager.get().getModel("player");
                    shader.setUniform("overlayVignetteStrength", 0.25f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0.55f, 0.55f, 0.60f));
                    for (EnemyManager.EnemyProjectile proj : enemyManager.projectiles) {
                        if (!proj.alive) continue;
                        float lifeF = proj.lifetime / GameConfig.projectileLifetime;
                        shader.setUniform("alphaMultiplier", Math.min(1f, lifeF * 4f));
                        float pScale = 0.22f;
                        Matrix4f projMat = new Matrix4f()
                                .translate(proj.pos.x, proj.pos.y, proj.pos.z)
                                .scale(pScale);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(projMat));
                        stoneModel.render();
                    }
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // 6b. Render charging boulder rising from ground while I is held
                if (isChargingStoneCanon && stoneCanonGroundPos != null) {
                    float chargeProgress = Math.min(1f,
                            stoneCanonCharge / GameConfig.stoneCanonMaxCharge);
                    // Boulder rises from ground level up by ~1.5 blocks at full charge
                    float riseY = stoneCanonGroundPos.y + chargeProgress * 1.5f;
                    // Scale grows from 0.08 → max scale as charge builds
                    float minSc = GameConfig.stoneCanonMinScale * 0.22f;
                    float maxSc = GameConfig.stoneCanonMinScale
                            + chargeProgress * (GameConfig.stoneCanonMaxScale - GameConfig.stoneCanonMinScale);
                    float blockBonus = Math.min(1f, stoneCanonBlocksConsumed / 6f);
                    maxSc *= (0.7f + 0.3f * blockBonus);
                    float chargeScale = minSc + chargeProgress * (maxSc - minSc);
                    // Slow spin that speeds up as charge increases
                    float timeSecs2 = (float) glfwGetTime();
                    float spin2 = timeSecs2 * (1.5f + chargeProgress * 5f);
                    // Stone-grey with growing orange glow
                    float glow2 = chargeProgress * 0.45f;
                    shader.setUniform("alphaMultiplier", 0.7f + chargeProgress * 0.3f);
                    shader.setUniform("overlayVignetteStrength", 0.10f + glow2);
                    shader.setUniform("overlayVignetteColor",
                            new Vector3f(0.65f + glow2, 0.55f + glow2 * 0.3f, 0.4f));
                    Matrix4f chargeMat = new Matrix4f()
                            .translate(stoneCanonGroundPos.x, riseY, stoneCanonGroundPos.z)
                            .rotateY(spin2)
                            .rotateX(spin2 * 0.6f)
                            .scale(chargeScale);
                    shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(chargeMat));
                    getItemMesh(Block.STONE).render();
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // 6c. Render Stone Canon shots
                if (!stoneShotList.isEmpty()) {
                    float timeSecs = (float) glfwGetTime();
                    for (ActiveStoneShot shot : stoneShotList) {
                        float spin = timeSecs * (3f + shot.chargeF * 4f);
                        shader.setUniform("alphaMultiplier", 1.0f);
                        // Stone-grey base, slight orange glow at high charge
                        float glow = shot.chargeF * 0.35f;
                        shader.setUniform("overlayVignetteStrength", 0.15f + glow);
                        shader.setUniform("overlayVignetteColor",
                                new Vector3f(0.65f + glow, 0.55f + glow * 0.3f, 0.4f));
                        Matrix4f shotMat = new Matrix4f()
                                .translate(shot.pos.x, shot.pos.y, shot.pos.z)
                                .rotateY(spin)
                                .rotateX(spin * 0.7f)
                                .scale(shot.scale);
                        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(shotMat));
                        getItemMesh(Block.STONE).render();
                    }
                    shader.setUniform("overlayVignetteStrength", 0f);
                    shader.setUniform("overlayVignetteColor", new Vector3f(0f));
                    shader.setUniform("alphaMultiplier", 1.0f);
                }

                // 7a. Render Paper Figurine Substitute dummies
                if (!substituteDummies.isEmpty()) {
                    com.leaf.game.render.ModelMesh dummyModel =
                            com.leaf.game.render.AssetManager.get().getModel("player");
                    if (dummyModel != null) {
                        for (float[] dm : substituteDummies) {
                            float lifeFrac = dm[3] / dm[4]; // 1=fresh … 0=about to explode
                            // Scale expands slightly as timer runs out (0.9 → 1.2)
                            float dscale = 0.9f + (1f - lifeFrac) * 0.3f;
                            float alpha  = Math.max(0.15f, lifeFrac * 0.90f);
                            shader.setUniform("alphaMultiplier", alpha);
                            // Dark silhouette — near-black with faint blue tinge
                            shader.setUniform("overlayVignetteStrength", 0.80f + (1f - lifeFrac) * 0.15f);
                            shader.setUniform("overlayVignetteColor", new Vector3f(0.02f, 0.02f, 0.06f));
                            Matrix4f dummyMat = new Matrix4f()
                                    .translate(dm[0], dm[1], dm[2])
                                    .scale(dscale);
                            shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(dummyMat));
                            dummyModel.render();
                        }
                        shader.setUniform("overlayVignetteStrength", 0f);
                        shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
                        shader.setUniform("alphaMultiplier", 1.0f);
                    }
                }

                // 7. Render Items
                for (DroppedItem item : droppedItems) {
                    Mesh itemMesh = getItemMesh(item.blockType);
                    float bob = (float) Math.sin(item.age * 3.0f) * 0.05f;
                    Matrix4f itemModel = new Matrix4f()
                            .translate(item.position.x, item.position.y + bob, item.position.z)
                            .rotateY(item.age * 1.5f);
                    Matrix4f itemMvp = new Matrix4f(projection).mul(view).mul(itemModel);
                    shader.setUniform("mvp", itemMvp);
                    itemMesh.render();
                }

                // 8. Render Remote Player
                if (network != null && network.connected) {
                    remotePlayer.render(shader, projection, view);
                }

                // 9. Render Ability Ghost Trails
                renderAbilityGhosts(shader, projection, view);

                // 10. Knife view model — disabled (re-enable when model is ready)
                // if (player != null && !player.debugMode && !player.stand.isInStandPerspective()) {
                //     renderKnifeViewModel(shader, projection, view, camera);
                // }

                glDisable(GL_BLEND);
                shader.unbind();

                // ── KAMUI DISTORTION POST-PROCESS ─────────────────────────────
                // Blit the off-screen FBO texture back to the default framebuffer
                // through the swirl/vortex distortion shader.
                if (doKamuiDistort) {
                    org.lwjgl.opengl.GL30.glBindFramebuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                    glClear(GL_COLOR_BUFFER_BIT);

                    distortShader.bind();
                    distortShader.setUniform("screenTexture", 0);
                    distortShader.setUniform("time", (float) glfwGetTime());
                    distortShader.setUniform("kamuiCharge",
                            player.abilities.absorptionCharge);
                    distortShader.setUniform("absorptionPos",
                            player.abilities.absorptionScrX / Math.max(1, ww[0]),
                            player.abilities.absorptionScrY / Math.max(1, wh[0]));
                    distortShader.setUniform("aspectRatio",
                            (float) ww[0] / Math.max(1, wh[0]));

                    org.lwjgl.opengl.GL13.glActiveTexture(
                            org.lwjgl.opengl.GL13.GL_TEXTURE0);
                    org.lwjgl.opengl.GL11.glBindTexture(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, kamuiFboTex);

                    glDisable(GL_DEPTH_TEST);
                    org.lwjgl.opengl.GL30.glBindVertexArray(kamuiScreenQuad);
                    glDrawArrays(GL_TRIANGLES, 0, 6);
                    org.lwjgl.opengl.GL30.glBindVertexArray(0);
                    glEnable(GL_DEPTH_TEST);

                    distortShader.unbind();
                } else if (doBloom) {
                    // ── SEARING-BLOOM POST-PROCESS (orbital strike OR radar) ──
                    // Bleed the emissive scan lines + laser flash into the dark.
                    org.lwjgl.opengl.GL30.glBindFramebuffer(
                            org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                    glClear(GL_COLOR_BUFFER_BIT);

                    bloomShader.bind();
                    bloomShader.setUniform("screenTexture", 0);
                    bloomShader.setUniform("texel",
                            1f / Math.max(1, fw[0]), 1f / Math.max(1, fh[0]));
                    bloomShader.setUniform("bloomStrength", 1.7f);
                    // Domain strike flash overlay (0 when domain is inactive — safe to always set)
                    bloomShader.setUniform("depStrike", depStrike);
                    bloomShader.setUniform("threshold", 0.65f);

                    org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                    org.lwjgl.opengl.GL11.glBindTexture(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, kamuiFboTex);

                    glDisable(GL_DEPTH_TEST);
                    org.lwjgl.opengl.GL30.glBindVertexArray(kamuiScreenQuad);
                    glDrawArrays(GL_TRIANGLES, 0, 6);
                    org.lwjgl.opengl.GL30.glBindVertexArray(0);
                    glEnable(GL_DEPTH_TEST);

                    bloomShader.unbind();
                }
            }
            // ── IMGUI ─────────────────────────────────────────────────────────
            imguiGlfw.newFrame();
            ImGui.newFrame();

            if (!networkInitialized) {
                hud.renderConnectionMenu(ww[0], wh[0]);
            } else {
                if (isPreloading) {
                    hud.renderPreloadProgress(ww[0], wh[0]);
                } else if (cutscene.isActive()) {
                    // Cutscene takes over the screen (world still renders behind, dimmed).
                    cutscene.render((float) ww[0], (float) wh[0]);
                } else {
                    hud.renderHUD(camera, ww[0], wh[0]);
                    hud.renderTargetCracks(camera, ww[0], wh[0]);
                    if (showDebug)       hud.renderDebugMenu();
                    if (showNoiseViewer) noiseVis.renderWindow(player);
                    if (showChat || !chatHistory.isEmpty()) hud.renderChatBox(wh[0]);
                    if (isPaused)         hud.renderPauseMenu(ww[0], wh[0]);
                    if (showHelp)         hud.renderHelpScreen((float)ww[0], (float)wh[0]);
                    if (showUnlockCard)         hud.renderUnlockCard((float)ww[0], (float)wh[0]);
                    if (practiceAbility != null) hud.renderPractice((float)ww[0], (float)wh[0]);
                    if (showDeathScreen)         hud.renderDeathScreen((float)ww[0], (float)wh[0]);
                    hud.renderChocolateDiscoConsole();
                    // (The Orbital Annihilation cinematic is now drawn as real 3D
                    //  geometry during the world pass — see renderOrbital3D.)
                    // Screen flash overlay (snipe, explosion, melee hit, etc.)
                    ScreenEffectManager.INSTANCE.renderFlash(ww[0], wh[0]);
                    // Snow particle overlay — drawn on top of world, under flash
                    hud.renderSnowEffect(ww[0], wh[0], snowTimeAccum,
                                         snowIntensity, windGustStrength, windGustAngle);
                }
            }

            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        // ── CLEANUP ───────────────────────────────────────────────────────────
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.opaqueMesh      != null) chunk.opaqueMesh.cleanup();
            if (chunk.transparentMesh != null) chunk.transparentMesh.cleanup();
        }
        for (Mesh m : itemMeshes.values()) m.cleanup();
        shader.cleanup();
        imguiGl3.dispose();
        noiseVis.cleanup();
        imguiGlfw.dispose();
        ImGui.destroyContext();
        if (remotePlayer != null) remotePlayer.cleanup();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SMASH IMPACT HANDLER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called each frame after player.update(). Checks player.smashImpactX to
     * detect a fresh smash landing and responds:
     *   1. Carves the crater in the world.
     *   2. Spawns ejecta DroppedItems in a radial burst.
     *   3. Starts the screen shake timer.
     *   4. Sends the CRATER network message to the remote peer if multiplayer.
     */
    private void handleSmashImpact(Camera camera) {
        if (player.smashImpactX == Integer.MIN_VALUE) return; // no impact this frame

        int ix = player.smashImpactX;
        int iy = player.smashImpactY;
        int iz = player.smashImpactZ;
        int r  = player.currentSmashRadius; // Read the dynamic radius

        // 1. Carve crater
        world.createImpactCrater(ix, iy, iz, r);

        // 2. Ejecta burst
        spawnCraterEjecta(ix, iy, iz, r);

        // 3. Splash damage + knockback for nearby enemies
        enemyManager.processSmashKnockback(ix, iy, iz, r);

        // 4. Dynamic Screen Shake scaling based on the radius size
        AudioManager.play("fall_smash");
        ScreenEffectManager.INSTANCE.hitStop(2);
        ScreenEffectManager.INSTANCE.flashExplosion();
        float scaleFactor = (float) r / GameConfig.smashCraterRadius;
        activeShakeDuration  = GameConfig.smashShakeDuration * Math.min(2.5f, scaleFactor);
        activeShakeAmplitude = GameConfig.smashShakeAmplitude * Math.min(3.0f, scaleFactor);
        smashShakeTimer      = activeShakeDuration;

        // 5. Network sync
        if (network != null && network.connected) {
            network.sendCrater(ix, iy, iz, r);
        }
    }

    /**
     * Spawns a burst of DroppedItems flying outward from the impact point.
     * These use the new DroppedItem velocity field added for crater ejecta.
     * Only blocks that were actually solid at the impact site are sampled;
     * if the crater is in empty air (unlikely) we default to GRAVEL.
     */
    private void spawnCraterEjecta(int ix, int iy, int iz, int radius) {
        // Scale ejecta particle count with crater size
        int ejectedCount = Math.min(96, 6 * radius);
        // smashImpactY is the player's feet level (first AIR block) — sample one block down for the actual ground material.
        Block ejectBlock = world.getBlock(ix, iy - 1, iz);
        if (ejectBlock == Block.AIR || !ejectBlock.isSolid()) ejectBlock = Block.GRAVEL;

        for (int i = 0; i < ejectedCount; i++) {
            double azimuth  = shakeRng.nextDouble() * 2.0 * Math.PI;
            double elevation = shakeRng.nextDouble() * Math.PI * 0.5 + 0.1;
            float vx = (float)(Math.cos(azimuth) * Math.cos(elevation));
            float vy = (float)(Math.sin(elevation));
            float vz = (float)(Math.sin(azimuth) * Math.cos(elevation));

            // Scale speed so that higher falls launch particles faster and wider
            float speedScale = 0.6f + 0.4f * ((float) radius / GameConfig.smashCraterRadius);
            float ejectionSpeed = (18f + shakeRng.nextFloat() * 22f) * speedScale;
            Vector3f launchVel = new Vector3f(vx, vy, vz).mul(ejectionSpeed);

            int ox = ix + (int)(vx * (radius + 1));
            int oy = iy + (int)(vy * (radius + 1));
            int oz = iz + (int)(vz * (radius + 1));

            droppedItems.add(new DroppedItem(ox, oy, oz, ejectBlock, launchVel));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SCREEN SHAKE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a camera-position offset for the current frame.
     * Damped sinusoidal: amplitude decays linearly from smashShakeAmplitude → 0
     * over smashShakeDuration seconds.
     *
     * The caller adds this to camera.position before building the view matrix,
     * then subtracts it afterward — player position accounting is unaffected.
     *
     * @param rawDt raw (unscaled) frame time, used to tick the shake timer
     * @return offset vector (zero when no shake active)
     */
    private Vector3f computeShakeOffset(float rawDt) {
        if (smashShakeTimer <= 0f) return new Vector3f(0f);

        smashShakeTimer = Math.max(0f, smashShakeTimer - rawDt);

        float progress    = smashShakeTimer / activeShakeDuration; // Dynamic duration
        float amplitude   = progress * activeShakeAmplitude;       // Dynamic amplitude
        float timeSecs    = (float)glfwGetTime();
        float freq        = GameConfig.smashShakeFrequency;

        float shakeX = amplitude * (float)Math.sin(timeSecs * freq * 1.0);
        float shakeY = amplitude * (float)Math.sin(timeSecs * freq * 1.3 + 0.7);

        return new Vector3f(shakeX, shakeY, 0f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  KNIFE VIEW MODEL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders a simple knife placeholder in the bottom-right of the player's view.
     * Position is computed in world-space as a fixed offset from the camera,
     * which effectively makes it look attached to the hand.
     *
     * When a knife swing is in progress (knifeSwingPhase > 0), the blade
     * rotates around the look axis to simulate a slashing arc.
     */
    private void renderKnifeViewModel(Shader shader, Matrix4f projection, Matrix4f view,
                                      com.leaf.game.util.Camera camera) {
        // Only visible while actively swinging — not a permanent view model
        if (player.attacks.knifeSwingPhase < 0.05f) return;

        // ── Camera basis vectors ───────────────────────────────────────────────
        Vector3f look  = camera.getLookDirection();
        Vector3f right = camera.getRight();
        // True up = right × look (right-handed, pointing upward in camera space)
        Vector3f up = new Vector3f(right).cross(look).normalize();

        // ── Knife rest position (world-space) ─────────────────────────────────
        // Right side of view, below eye level, pushed forward for good size
        float restRight   = 0.28f;
        float restDown    = -0.24f;
        float restForward = 0.50f;

        Vector3f knifePos = new Vector3f(camera.position)
                .add(new Vector3f(right).mul(restRight))
                .add(new Vector3f(up).mul(restDown))
                .add(new Vector3f(look).mul(restForward));

        // ── Swing animation ────────────────────────────────────────────────────
        float swing = player.attacks.knifeSwingPhase;    // 0..1
        float swDir = player.attacks.knifeSwingDir;      // +1 or -1
        // Rotate the knife around the look axis (roll) based on swing phase
        float rollAngle = swDir * swing * (float) (Math.PI * 0.90);   // ±162° dramatic sweep
        // Forward lunge + downward dip during swing for a powerful slash feel
        knifePos.add(new Vector3f(look).mul(swing * 0.18f));
        knifePos.add(new Vector3f(up).mul(-swing * 0.12f));

        // ── Build model matrix ─────────────────────────────────────────────────
        float yaw   = camera.yaw;
        float pitch = camera.pitch;

        // Scale pulses slightly bigger during a swing (makes each hit feel weighty)
        float baseW = 0.15f, baseH = 0.58f, baseD = 0.08f;
        float swingScale = 1f + swing * 0.25f;

        Matrix4f knifeMat = new Matrix4f()
                .translate(knifePos.x, knifePos.y, knifePos.z)
                .rotateY(-yaw - (float)(Math.PI / 2))
                .rotateX(-pitch)
                .rotateZ(rollAngle)
                .scale(baseW * swingScale, baseH * swingScale, baseD * swingScale);

        // ── Render without writing to depth (stays on top of close geometry) ──
        glDepthMask(false);
        // Bright silver-steel sheen: stronger overlay so it reads clearly
        float shimmer = 0.18f + swing * 0.22f;   // glints brighter mid-swing
        shader.setUniform("overlayVignetteStrength", shimmer);
        shader.setUniform("overlayVignetteColor", new Vector3f(0.95f, 0.95f, 1.0f));
        shader.setUniform("alphaMultiplier", 0.95f);
        shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(knifeMat));
        getItemMesh(Block.STAR_IRON).render();   // metallic grey/silver look
        glDepthMask(true);
        shader.setUniform("overlayVignetteStrength", 0f);
        shader.setUniform("overlayVignetteColor", new Vector3f(0f, 0f, 0f));
        shader.setUniform("alphaMultiplier", 1.0f);
    }

    private void renderAbilityGhosts(Shader shader, Matrix4f projection, Matrix4f view) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false); // don't write to depth buffer for ghosts

        Mesh ghostMesh = getItemMesh(Block.SNOW); // clean white-ish block colour
        Mesh arcDotMesh = getItemMesh(Block.CRATER_BLOOM); // orange/gold dot

        // ── DASH GHOST TRAIL ─────────────────────────────────────────────────
        // Show last dashTrail.size() positions as fading cyan ghosts.
        // Alpha: 0.05 at oldest, 0.35 at newest.
        List<Vector3f> dashT = player.abilities.dashTrail;
        if (!dashT.isEmpty()) {
            float ageBoost = Math.max(0f, 1f - player.abilities.dashTrailAge * 2.5f);
            for (int i = 0; i < dashT.size(); i++) {
                float t      = (float)(i + 1) / dashT.size();
                float alpha  = (0.05f + t * 0.30f) * ageBoost;
                if (alpha < 0.01f) continue;
                Vector3f pos = dashT.get(i);
                Matrix4f ghostModel = new Matrix4f()
                        .translate(pos.x - 0.2f, pos.y + 0.6f, pos.z - 0.2f)
                        .scale(0.4f, 0.9f, 0.4f);
                shader.setUniform("alphaMultiplier", alpha);
                shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(ghostModel));
                ghostMesh.render();
            }
        }

        // ── REWIND TRAIL ─────────────────────────────────────────────────────
        // Always visible (very faint) so player can see their history.
        // Becomes brighter and blue-shifted when actively rewinding.
        List<Vector3f> rewindT = player.abilities.rewindTrail;
        if (!rewindT.isEmpty()) {
            boolean rewinding = player.abilities.isRewinding;
            for (int i = 0; i < rewindT.size(); i++) {
                float t     = (float)(i + 1) / rewindT.size(); // 0=oldest, 1=newest
                float alpha = rewinding ? (0.08f + t * 0.25f) : (0.02f + t * 0.06f);
                if (alpha < 0.01f) continue;
                Vector3f pos = rewindT.get(i);
                Matrix4f ghostModel = new Matrix4f()
                        .translate(pos.x - 0.15f, pos.y + 0.5f, pos.z - 0.15f)
                        .scale(0.3f, 0.8f, 0.3f);
                shader.setUniform("alphaMultiplier", alpha);
                // Rewind ghosts use CRYSTAL_AMETHYST (blue-purple) for clear identification
                shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(ghostModel));
                getItemMesh(Block.CRYSTAL_AMETHYST).render();
            }
        }

        // ── BLINK TRAIL ──────────────────────────────────────────────────────
        // For blinkFlashDecay seconds after a blink: show dots along the blink line.
        if (player.abilities.blinkFlashTimer > 0f) {
            float progress = player.abilities.blinkFlashTimer / GameConfig.blinkFlashDecay;
            Vector3f o = player.abilities.blinkOrigin;
            Vector3f d = player.abilities.blinkDest;
            for (int i = 0; i <= 6; i++) {
                float t   = (float)i / 6f;
                float alpha = progress * (0.1f + t * 0.3f);
                Vector3f pos = new Vector3f(o).lerp(d, t);
                Matrix4f ghostModel = new Matrix4f()
                        .translate(pos.x, pos.y + 0.9f, pos.z)
                        .scale(0.25f, 0.25f, 0.25f);
                shader.setUniform("alphaMultiplier", alpha);
                shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(ghostModel));
                getItemMesh(Block.CRYSTAL_QUARTZ).render(); // white/clear
            }
        }

        // ── CANNONBALL TRAJECTORY ARC ─────────────────────────────────────────
        // Show predicted ballistic path when charging. Dots fade from bright at
        // player to dim at end. Alternating size gives dotted-line appearance.
        List<Vector3f> arc = player.abilities.trajectoryArc;
        if (!arc.isEmpty()) {
            for (int i = 0; i < arc.size(); i++) {
                float t      = (float)i / arc.size();
                float alpha  = 0.7f - t * 0.5f;
                float scale  = (i % 2 == 0) ? 0.18f : 0.10f;
                Vector3f pos = arc.get(i);
                Matrix4f dotModel = new Matrix4f()
                        .translate(pos.x, pos.y, pos.z)
                        .scale(scale, scale, scale);
                shader.setUniform("alphaMultiplier", alpha);
                shader.setUniform("mvp", new Matrix4f(projection).mul(view).mul(dotModel));
                arcDotMesh.render();
            }
        }

        // Restore defaults
        shader.setUniform("alphaMultiplier", 1.0f);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /**
     * Renders four ability cooldown icons (Q / E / G / Z) in the bottom-right
     * corner of the screen. Each icon shows a coloured fill based on the
     * ability's ready fraction: full = coloured, on-cooldown = grey fill.
     */

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when there is at least one solid block directly above the
     * player's head within {@code maxCheck} blocks. Used as a cheap "indoors /
     * cave" test to decide whether to enable cave reverb.
     *
     * Tune: raise maxCheck to react to a higher ceiling; lower it so only
     * tight spaces (narrow caves) trigger reverb. Default 12 feels like a
     * low cave without being too eager on flat terrain with a single floating
     * block overhead.
     */
    private boolean isUnderRoof(int maxCheck) {
        int px = (int) Math.floor(player.position.x);
        int py = (int) Math.floor(player.position.y) + 2;   // start just above head
        int pz = (int) Math.floor(player.position.z);
        for (int y = py; y < py + maxCheck; y++) {
            if (world.getBlock(px, y, pz).isSolid()) return true;
        }
        return false;
    }

    private boolean playerOccupies(int bx, int by, int bz) {
        float px = player.position.x, py = player.position.y, pz = player.position.z;
        return px + 0.3f > bx && px - 0.3f < bx + 1
                && py + 1.8f > by && py < by + 1
                && pz + 0.3f > bz && pz - 0.3f < bz + 1;
    }

    private boolean remotePlayerOccupies(int bx, int by, int bz) {
        if (network == null || !network.connected) return false;
        float px = remotePlayer.x, py = remotePlayer.y, pz = remotePlayer.z;
        return px + 0.3f > bx && px - 0.3f < bx + 1
                && py + 1.8f > by && py < by + 1
                && pz + 0.3f > bz && pz - 0.3f < bz + 1;
    }

    private Mesh getItemMesh(Block block) {
        return itemMeshes.computeIfAbsent(block, b -> {
            List<Float>   verts   = new ArrayList<>();
            List<Integer> idx     = new ArrayList<>();
            int[]         vIndex  = {0};
            float w   = 0.12f;
            float[] col = {b.r, b.g, b.b};
            addBox(verts, idx, vIndex, -w, -w, -w, w, w, w, col);
            float[] vArr = new float[verts.size()];
            for (int i = 0; i < verts.size(); i++) vArr[i] = verts.get(i);
            int[] iArr = new int[idx.size()];
            for (int i = 0; i < idx.size(); i++) iArr[i] = idx.get(i);
            return new Mesh(vArr, iArr);
        });
    }

    private void addBox(List<Float> verts, List<Integer> idx, int[] vIndex,
                        float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float[] col) {
        float[][] corners = {
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ},
                {maxX, minY, minZ}, {minX, minY, minZ}, {minX, maxY, minZ}, {maxX, maxY, minZ},
                {minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ},
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, minY, minZ}, {minX, minY, minZ},
                {maxX, minY, maxZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ},
                {minX, minY, minZ}, {minX, minY, maxZ}, {minX, maxY, maxZ}, {minX, maxY, minZ}
        };
        for (int face = 0; face < 6; face++) {
            float shade = (face == 2) ? 1.0f : (face == 3 ? 0.5f : 0.8f);
            for (int i = 0; i < 4; i++) {
                float[] corner = corners[face * 4 + i];
                verts.add(corner[0]); verts.add(corner[1]); verts.add(corner[2]);
                verts.add(col[0]*shade); verts.add(col[1]*shade); verts.add(col[2]*shade);
                verts.add(1.0f);
                verts.add(0f); verts.add(1f); verts.add(0f);
            }
            int b = vIndex[0];
            idx.add(b); idx.add(b+1); idx.add(b+2);
            idx.add(b+2); idx.add(b+3); idx.add(b);
            vIndex[0] += 4;
        }
    }

    // ── Frustum culling ───────────────────────────────────────────────────────

    private float[] extractFrustumPlanes(Matrix4f vp) {
        float[] planes = new float[24];
        planes[0]  = vp.m03() + vp.m00(); planes[1]  = vp.m13() + vp.m10();
        planes[2]  = vp.m23() + vp.m20(); planes[3]  = vp.m33() + vp.m30();
        planes[4]  = vp.m03() - vp.m00(); planes[5]  = vp.m13() - vp.m10();
        planes[6]  = vp.m23() - vp.m20(); planes[7]  = vp.m33() - vp.m30();
        planes[8]  = vp.m03() + vp.m01(); planes[9]  = vp.m13() + vp.m11();
        planes[10] = vp.m23() + vp.m21(); planes[11] = vp.m33() + vp.m31();
        planes[12] = vp.m03() - vp.m01(); planes[13] = vp.m13() - vp.m11();
        planes[14] = vp.m23() - vp.m21(); planes[15] = vp.m33() - vp.m31();
        planes[16] = vp.m03() + vp.m02(); planes[17] = vp.m13() + vp.m12();
        planes[18] = vp.m23() + vp.m22(); planes[19] = vp.m33() + vp.m32();
        planes[20] = vp.m03() - vp.m02(); planes[21] = vp.m13() - vp.m12();
        planes[22] = vp.m23() - vp.m22(); planes[23] = vp.m33() - vp.m32();
        for (int i = 0; i < 6; i++) {
            float len = (float)Math.sqrt(
                    planes[i*4] * planes[i*4] + planes[i*4+1] * planes[i*4+1]
                            + planes[i*4+2] * planes[i*4+2]);
            planes[i*4] /= len; planes[i*4+1] /= len;
            planes[i*4+2] /= len; planes[i*4+3] /= len;
        }
        return planes;
    }

    private boolean isAabbInFrustum(float[] planes, Chunk chunk) {
        if (chunk.minBlockY > chunk.maxBlockY) return false;
        float minX = chunk.cx * Chunk.SIZE;
        float minZ = chunk.cz * Chunk.SIZE;
        float minY = chunk.cy * Chunk.HEIGHT + chunk.minBlockY;
        float maxX = minX + Chunk.SIZE;
        float maxZ = minZ + Chunk.SIZE;
        float maxY = chunk.cy * Chunk.HEIGHT + chunk.maxBlockY + 1;
        for (int i = 0; i < 6; i++) {
            int p = i * 4;
            float px = planes[p]   > 0 ? maxX : minX;
            float py = planes[p+1] > 0 ? maxY : minY;
            float pz = planes[p+2] > 0 ? maxZ : minZ;
            if (planes[p]*px + planes[p+1]*py + planes[p+2]*pz + planes[p+3] < 0) return false;
        }
        return true;
    }

    /**
     * Maps a collected block to an empty hotbar slot.
     * Preserved from the original codebase.
     */
    void addBlockToHotbar(Block block) {
        if (block == Block.AIR) return;
        for (Block b : hotbar) { if (b == block) return; }
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] == Block.AIR || inventory.getCount(hotbar[i]) <= 0) {
                hotbar[i] = block; return;
            }
        }
    }


    // ── BLOCK SOUND HELPERS ───────────────────────────────────────────────────
    // Place and break use the same file per material (block_stone/soil/sand/crystal).
    // Dig sounds fire periodically while holding the break key; crystals use a
    // shuffled sequence of four clank notes instead of a single looped file.

    static String blockPlaceSound(Block b) {
        if (b == null) return null;
        return switch (b) {
            case STONE, ISLAND_STONE, FOSSIL_STONE, SCORCHED_STONE,
                 MEGALITH, MEGALITH_CARVED, MOSSY_MEGALITH,
                 CRYSTAL_BASE, STAR_IRON,
                 OAK_LOG, OAK_LEAVES, PETRIFIED_WOOD,
                 PETRIFIED_BARK, HANGING_ROOT                    -> "block_stone";
            case DIRT, GRASS, MUD, ANCIENT_SOIL,
                 MESA_GRASS, MESA_DIRT,
                 MESA_BLUE_SNOW, MESA_BLUE_SOIL                  -> "block_soil";
            case SAND, RED_SAND, GRAVEL, SNOW, MESA_SAND         -> "block_sand";
            case CRYSTAL_AMETHYST, CRYSTAL_QUARTZ,
                 CRYSTAL_CITRINE, CRYSTAL_ROSE                   -> "block_crystal";
            default                                              -> "block_stone";
        };
    }

    static String blockBreakSound(Block b) {
        if (b == null) return null;
        return switch (b) {
            case STONE, ISLAND_STONE, FOSSIL_STONE, SCORCHED_STONE,
                 MEGALITH, MEGALITH_CARVED, MOSSY_MEGALITH,
                 CRYSTAL_BASE, STAR_IRON,
                 OAK_LOG, OAK_LEAVES, PETRIFIED_WOOD,
                 PETRIFIED_BARK, HANGING_ROOT                    -> "block_stone";
            case DIRT, GRASS, MUD, ANCIENT_SOIL,
                 MESA_GRASS, MESA_DIRT,
                 MESA_BLUE_SNOW, MESA_BLUE_SOIL                  -> "block_soil";
            case SAND, RED_SAND, GRAVEL, SNOW, MESA_SAND         -> "block_sand";
            case CRYSTAL_AMETHYST, CRYSTAL_QUARTZ,
                 CRYSTAL_CITRINE, CRYSTAL_ROSE                   -> "block_crystal";
            default                                              -> "block_stone";
        };
    }

    /**
     * Returns the dig sound to play while the player is actively breaking a block.
     * Crystal returns the special sentinel {@code "crystal_clank_seq"} — the caller
     * should call {@link #nextCrystalClank()} to get the actual shuffled sound name.
     */
    static String blockDigSound(Block b) {
        if (b == null) return null;
        return switch (b) {
            case STONE, ISLAND_STONE, FOSSIL_STONE, SCORCHED_STONE,
                 MEGALITH, MEGALITH_CARVED, MOSSY_MEGALITH,
                 CRYSTAL_BASE, STAR_IRON,
                 OAK_LOG, OAK_LEAVES, PETRIFIED_WOOD,
                 PETRIFIED_BARK, HANGING_ROOT                    -> "stone_digging";
            case DIRT, GRASS, MUD, ANCIENT_SOIL,
                 MESA_GRASS, MESA_DIRT,
                 MESA_BLUE_SNOW, MESA_BLUE_SOIL                  -> "soil_digging";
            case SAND, RED_SAND, GRAVEL, SNOW,
                 MESA_SAND, MESA_BLUE_LIGHT                      -> "sand_digging";
            case CRYSTAL_AMETHYST, CRYSTAL_QUARTZ,
                 CRYSTAL_CITRINE, CRYSTAL_ROSE                   -> "crystal_clank_seq";
            default                                              -> "stone_digging";
        };
    }

    /**
     * Returns the next crystal-clank sound name from a shuffled sequence.
     * When the sequence is exhausted it reshuffles so consecutive plays never
     * sound machine-gunned.
     */
    String nextCrystalClank() {
        if (crystalClankIdx >= CRYSTAL_CLANKS.length) {
            // Fisher-Yates shuffle
            for (int i = CRYSTAL_CLANKS.length - 1; i > 0; i--) {
                int j = (int)(Math.random() * (i + 1));
                int tmp = crystalClankOrder[i];
                crystalClankOrder[i] = crystalClankOrder[j];
                crystalClankOrder[j] = tmp;
            }
            crystalClankIdx = 0;
        }
        return CRYSTAL_CLANKS[crystalClankOrder[crystalClankIdx++]];
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  NON-EUCLIDEAN "LAYERED ROOMS"  (F6)
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // One physical 2×2 building with a solid central cross-pillar and four
    // doorways arranged as a clockwise ring around the centre:
    //
    //        NW ── door ── NE          quadrant indices (clockwise):
    //        │              │              0 = NW   1 = NE
    //       door   PILLAR  door             3 = SW   2 = SE
    //        │              │
    //        SW ── door ── SE
    //
    // Walking clockwise you go NW→NE→SE→SW→NW physically, but logically the room
    // number keeps climbing (1,2,3,4,5,6…). Each quadrant is skinned with a room
    // colour. The visible rooms (current + its two neighbours) always show three
    // consecutive numbers; the DIAGONAL quadrant is fully hidden behind the solid
    // pillar, so when you round a corner we silently repaint that hidden room to
    // be the next colour in the sequence. You never witness the swap.

    /** Relative interior bounds {rxLo,rxHi,rzLo,rzHi} of a quadrant (0=NW 1=NE 2=SE 3=SW). */
    private int[] nerQuadBounds(int quad) {
        int lo = 1, hi = NER_ROOM;                      // 1..5  (west / north interior)
        int eLo = NER_ROOM + 2, eHi = 2 * NER_ROOM + 1; // 7..11 (east / south interior)
        return switch (quad) {
            case 0 -> new int[]{ lo,  hi,  lo,  hi  };  // NW
            case 1 -> new int[]{ eLo, eHi, lo,  hi  };  // NE
            case 2 -> new int[]{ eLo, eHi, eLo, eHi };  // SE
            default-> new int[]{ lo,  hi,  eLo, eHi };  // SW
        };
    }

    /** Palette colour for a logical room number (cycles; handles negatives). */
    private Block nerPaletteFor(int room) {
        int n = NER_PALETTE.length;
        int i = ((room - 1) % n + n) % n;
        return NER_PALETTE[i];
    }

    /** Logical room number shown in the quadrant that is {@code offset} clockwise
     *  steps ahead of the player's current quadrant. The counter-clockwise
     *  neighbour (offset 3) shows N-1 — the room you just came from. */
    private int nerLogicalRoom(int offset, int base) {
        return switch (offset) {
            case 1 -> base + 1;   // clockwise neighbour (ahead)
            case 2 -> base + 2;   // diagonal (hidden — becomes "ahead" next step)
            case 3 -> base - 1;   // counter-clockwise neighbour (behind)
            default-> base;       // current room
        };
    }

    /** Paint a quadrant's floor + ceiling with its room colour (no remesh here). */
    private void nerThemeQuadrant(int quad, Block block) {
        int[] q = nerQuadBounds(quad);
        int yFloor = NER_Y0;
        int yCeil  = NER_Y0 + NER_HT + 1;
        for (int rx = q[0]; rx <= q[1]; rx++) {
            for (int rz = q[2]; rz <= q[3]; rz++) {
                world.setBlockWithMeta(NER_X0 + rx, yFloor, NER_Z0 + rz, block, (byte) 0, false);
                world.setBlockWithMeta(NER_X0 + rx, yCeil,  NER_Z0 + rz, block, (byte) 0, false);
            }
        }
        nerQuadBlock[quad] = block;
    }

    /** Rebuild the chunk meshes the complex occupies (it fits in ~1 chunk). */
    private void nerRebuildChunks() {
        int cxLo = Math.floorDiv(NER_X0, Chunk.SIZE);
        int cxHi = Math.floorDiv(NER_X0 + NER_SPAN - 1, Chunk.SIZE);
        int czLo = Math.floorDiv(NER_Z0, Chunk.SIZE);
        int czHi = Math.floorDiv(NER_Z0 + NER_SPAN - 1, Chunk.SIZE);
        int cyLo = Math.floorDiv(NER_Y0, Chunk.HEIGHT);
        int cyHi = Math.floorDiv(NER_Y0 + NER_HT + 1, Chunk.HEIGHT);
        for (int cx = cxLo; cx <= cxHi; cx++)
            for (int cz = czLo; cz <= czHi; cz++)
                for (int cy = cyLo; cy <= cyHi; cy++) {
                    Chunk c = world.getOrCreateChunk(cx, cy, cz);
                    c.noEvict = true;
                    world.buildChunkMeshes(c);
                    c.state = Chunk.ChunkState.MESHED;
                }
    }

    /** Recompute every quadrant's target colour; repaint+remesh only those that
     *  changed (in practice just the hidden diagonal). This is the seamless swap. */
    private void nerApplyThemes() {
        boolean changed = false;
        for (int j = 0; j < 4; j++) {
            int offset = (j - nerQuad + 4) % 4;
            Block want = nerPaletteFor(nerLogicalRoom(offset, nerRoom));
            if (nerQuadBlock[j] != want) { nerThemeQuadrant(j, want); changed = true; }
        }
        if (changed) nerRebuildChunks();
    }

    /** Construct the sealed 2×2 complex once. */
    private void buildLayeredRooms() {
        // Raw geometry: outer shell, floor, ceiling, central cross with 4 door gaps.
        for (int rx = 0; rx < NER_SPAN; rx++) {
            for (int rz = 0; rz < NER_SPAN; rz++) {
                for (int ry = 0; ry <= NER_HT + 1; ry++) {
                    boolean floor   = (ry == 0);
                    boolean ceiling = (ry == NER_HT + 1);
                    boolean outer   = (rx == 0 || rx == NER_SPAN - 1 || rz == 0 || rz == NER_SPAN - 1);
                    boolean divider = (rx == NER_SPAN / 2 || rz == NER_SPAN / 2);
                    // Door gaps in the divider, one per clockwise arm (3 tall).
                    boolean door =
                           (rx == 6 && rz == 3)   // NW ↔ NE
                        || (rz == 6 && rx == 9)   // NE ↔ SE
                        || (rx == 6 && rz == 9)   // SE ↔ SW
                        || (rz == 6 && rx == 3);  // SW ↔ NW
                    boolean doorOpen = door && ry >= 1 && ry <= 3;

                    Block b;
                    if (floor || ceiling)      b = Block.STONE;          // re-skinned per room
                    else if (outer)            b = Block.STONE;          // sealed shell
                    else if (divider)          b = doorOpen ? Block.AIR : Block.STONE;
                    else                       b = Block.AIR;            // walkable interior

                    world.setBlockWithMeta(NER_X0 + rx, NER_Y0 + ry, NER_Z0 + rz, b, (byte) 0, false);
                }
            }
        }
        // Initial colours for room 1, player starting in NW.
        nerQuad = 0; nerRoom = 1;
        java.util.Arrays.fill(nerQuadBlock, null);
        nerApplyThemes();   // themes all four + meshes the chunk
        nerBuilt = true;
    }

    /** Which quadrant the player is well inside (0..3), or -1 while in a doorway. */
    private int nerQuadrantAt(float px, float pz) {
        float drx = px - NER_CX;
        float drz = pz - NER_CZ;
        if (Math.abs(drx) < 1.5f || Math.abs(drz) < 1.5f) return -1; // transition zone
        boolean east = drx > 0, south = drz > 0;
        if (!east && !south) return 0; // NW
        if ( east && !south) return 1; // NE
        if ( east &&  south) return 2; // SE
        return 3;                      // SW
    }

    /** F6 with the anomaly closed: build it (once), remember where we were, warp in. */
    private void enterLayeredRooms(Camera camera) {
        nerPrevX = player.position.x; nerPrevY = player.position.y; nerPrevZ = player.position.z;
        nerPrevYaw = camera.yaw; nerPrevPitch = camera.pitch;

        // Always (re)build: it's a single chunk and guarantees the geometry exists
        // even if the chunk was evicted between sessions. Resets to room 1 / NW.
        buildLayeredRooms();

        // Stand in the centre of the NW quadrant, facing +X toward the NE doorway.
        player.position.x = NER_X0 + 3 + 0.5f;
        player.position.y = NER_Y0 + 1.0f;
        player.position.z = NER_Z0 + 3 + 0.5f;
        player.setVelocityY(0f);
        player.highestY = player.position.y;   // no phantom fall damage on arrival
        camera.yaw   = 0f;   // +X (east)
        camera.pitch = 0f;
        nerActive = true;

        if (enemyManager != null) {
            nerPrevWaves = enemyManager.wavesEnabled;
            enemyManager.wavesEnabled = false;   // keep the anomaly calm
        }
        hintText  = "LAYERED ROOMS  —  walk CLOCKWISE around the pillar.   (F6 to exit)";
        hintTimer = 7f;
    }

    /** F6 while inside: drop the player back exactly where they came from. */
    private void exitLayeredRooms(Camera camera) {
        player.position.x = nerPrevX; player.position.y = nerPrevY; player.position.z = nerPrevZ;
        player.setVelocityY(0f);
        player.highestY = nerPrevY;
        camera.yaw = nerPrevYaw; camera.pitch = nerPrevPitch;
        nerActive = false;
        if (enemyManager != null) enemyManager.wavesEnabled = nerPrevWaves;
        hintText  = "Left the anomaly.";
        hintTimer = 2f;
    }

    /** Per-frame while inside: detect rounding the pillar and advance the sequence. */
    private void updateLayeredRooms() {
        int q = nerQuadrantAt(player.position.x, player.position.z);
        if (q < 0 || q == nerQuad) return;

        int diff = (q - nerQuad + 4) % 4;
        if      (diff == 1) nerRoom++;   // clockwise  → next room
        else if (diff == 3) nerRoom--;   // counter-cw → previous room
        // diff == 2 (straight across the solid centre) can't happen by walking — ignore.
        nerQuad = q;
        nerApplyThemes();                // repaints only the now-hidden diagonal room

        hintText  = "Room " + nerRoom;
        hintTimer = 2.5f;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  "ORBITAL ANNIHILATION"  (F7)
    // ═══════════════════════════════════════════════════════════════════════════

    /** F7: pick an epicentre and start the cinematic. */
    private void startOrbitalStrike(Camera camera) {
        if (lastTarget != null && lastTarget.hit) {
            orbEpiX = lastTarget.hitX + 0.5f;
            orbEpiY = lastTarget.hitY + 0.5f;
            orbEpiZ = lastTarget.hitZ + 0.5f;
        } else {
            // No block targeted — drop it ~14 blocks ahead, on the first surface below.
            float dirx = (float) Math.cos(camera.yaw), dirz = (float) Math.sin(camera.yaw);
            float tx = player.position.x + dirx * 14f;
            float tz = player.position.z + dirz * 14f;
            int bx = (int) Math.floor(tx), bz = (int) Math.floor(tz);
            float ey = player.position.y;
            for (int y = (int) player.position.y + 6; y >= 1; y--) {
                if (world.getBlock(bx, y, bz).isSolid()) { ey = y + 0.5f; break; }
            }
            orbEpiX = tx; orbEpiY = ey; orbEpiZ = tz;
        }
        orbitalT = 0f; orbCarved = false; orbStruck = false; orbitalActive = true;
        orbParticles.clear();
        AudioManager.play("laser_gun", 1.0f);   // starts charging immediately on F7
        hintText = "ORBITAL ANNIHILATION  —  stand back."; hintTimer = 3f;
    }

    /** Drive the scan/blackout parameters, shake, particles, one-shots; end it. */
    private void updateOrbitalStrike(float dt) {
        orbitalT += dt;
        float t = orbitalT;
        final float MAXR = 85f;   // world radius the lidar wavefront reaches

        orbScanRadiusW = 0f; orbScanIntensity = 0f; orbFlashAmt = 0f;
        // Blackout from a hair after fire, lifting partway through the laser.
        orbDark = (t >= ORB_DARK_START) && (t < ORB_LASER + 1.6f);

        if (t < ORB_CHARGE) {
            // CHARGE: gyroscope spins up; gentle, building rumble.
            orbShake(0.16f, 0.02f + 0.05f * (t / ORB_CHARGE));
        } else if (t < ORB_IMPLODE) {
            // IMPLODE: rings whip inward; rumble tightens toward the collision.
            float p = (t - ORB_CHARGE) / (ORB_IMPLODE - ORB_CHARGE);
            orbShake(0.14f, 0.05f + 0.18f * p);
        } else if (t < ORB_SCANOUT) {
            // SCANOUT: detonation has fired the wavefront; it sweeps the terrain.
            orbScanIntensity = 2.3f;
            float p = (t - ORB_IMPLODE) / (ORB_SCANOUT - ORB_IMPLODE);
            orbScanRadiusW = p * MAXR;
            spawnEmbers(dt);                         // anti-gravity embers fill the volume
        } else if (t < ORB_CARVE) {
            // The wavefront implodes back toward the centre, searing brighter.
            float p = (t - ORB_SCANOUT) / (ORB_CARVE - ORB_SCANOUT);
            orbScanIntensity = 2.3f + p * 3f;
            orbScanRadiusW = (1f - p) * MAXR;
        } else if (t < ORB_LASER) {
            // Carve the world, then a short, dark beat before the strike.
            if (!orbCarved) {
                carveOrbitalCrater();
                orbCarved = true;
                orbShake(0.6f, 0.55f);
                ScreenEffectManager.INSTANCE.flash(0.4f, 1f, 0.5f, 0.4f, 0.16f);
            }
        } else {
            // LASER: fire the 10-second laser_gun audio on the frame it begins,
            // then the environmental flash sears the dead world and the beam fades.
            float p = (t - ORB_LASER) / (ORB_END - ORB_LASER);
            orbFlashAmt = Math.max(0f, 1.7f * (1f - p * 2.4f));
            orbShake(0.22f, 0.36f);
            if (!orbStruck) {
                orbStruck = true;
                ScreenEffectManager.INSTANCE.flash(1f, 1f, 1f, 0.9f, 0.22f);
                spawnDebris();                                  // voxel debris bursts from impact
            }
        }

        // Detonation kick the instant the implosion rings collide.
        if (Math.abs(t - ORB_IMPLODE) < dt * 1.5f) {
            orbShake(0.45f, 0.42f);
            ScreenEffectManager.INSTANCE.flash(0.7f, 1f, 0.8f, 0.55f, 0.14f);
        }

        updateOrbParticles(dt);

        if (t >= ORB_END) {
            orbitalActive = false; orbDark = false; orbFlashAmt = 0f; orbParticles.clear();
            // Restore clean audio state BEFORE spawning the tinnitus source so:
            //   • masterGain = 1.0  → tinnitus one-shot created at full gain (6.0×1.0=6.0)
            //   • muffle = 0        → routeSource attaches NO lowpass to the tinnitus source
            // The arc then uses setMasterGain to suppress world loops without touching
            // the already-spawned tinnitus one-shot (setMasterGain only re-applies to loops).
            AudioManager.setMasterGain(1.0f);
            AudioManager.setListenerMuffle(0.0f);
            AudioManager.play("tinnitus", 6.0f, 2.2f);
            tinnitusTimer = TINNITUS_DUR;
        }
    }

    /** Continuously spawn slow-rising green embers within the crater radius. */
    private void spawnEmbers(float dt) {
        int n = Math.min(3, orbParticles.size() < 90 ? 3 : 0);   // cap the population
        for (int i = 0; i < n; i++) {
            OrbParticle p = new OrbParticle();
            double a = Math.random() * Math.PI * 2;
            float  rad = (float) Math.sqrt(Math.random()) * (ORB_CRATER_R + 4);
            p.x = orbEpiX + (float) Math.cos(a) * rad;
            p.z = orbEpiZ + (float) Math.sin(a) * rad;
            p.y = orbEpiY - 2f + (float) Math.random() * 3f;
            p.vx = (float) (Math.random() - 0.5) * 0.4f;
            p.vz = (float) (Math.random() - 0.5) * 0.4f;
            p.vy = 1.5f + (float) Math.random() * 2.5f;          // anti-gravity: float UP
            p.maxLife = p.life = 2.5f + (float) Math.random() * 2f;
            p.size = 0.12f + (float) Math.random() * 0.18f;
            p.r = 0.25f; p.g = 1f; p.b = 0.35f;
            orbParticles.add(p);
        }
    }

    /** Burst of glowing voxel debris flying outward+up from the impact. */
    private void spawnDebris() {
        for (int i = 0; i < 60; i++) {
            OrbParticle p = new OrbParticle();
            double a = Math.random() * Math.PI * 2;
            float  out = 6f + (float) Math.random() * 10f;
            p.x = orbEpiX; p.y = orbEpiY; p.z = orbEpiZ;
            p.vx = (float) Math.cos(a) * out;
            p.vz = (float) Math.sin(a) * out;
            p.vy = 8f + (float) Math.random() * 14f;
            p.maxLife = p.life = 1.2f + (float) Math.random() * 1.3f;
            p.size = 0.3f + (float) Math.random() * 0.5f;
            p.r = 0.4f; p.g = 1f; p.b = 0.5f;
            orbParticles.add(p);
        }
    }

    private void updateOrbParticles(float dt) {
        boolean debrisPhase = orbitalT >= ORB_LASER;
        for (int i = orbParticles.size() - 1; i >= 0; i--) {
            OrbParticle p = orbParticles.get(i);
            p.life -= dt;
            if (p.life <= 0f) { orbParticles.remove(i); continue; }
            if (debrisPhase) p.vy -= 22f * dt;   // debris falls under gravity
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt;
        }
    }

    private void orbShake(float dur, float amp) {
        activeShakeDuration  = dur;
        activeShakeAmplitude = amp;
        smashShakeTimer      = Math.max(smashShakeTimer, dur);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  "THE WORLD"  (F8)
    // ═══════════════════════════════════════════════════════════════════════════

    /** F8: anchor the domain, pause all world audio, start the ZA WARUDO sequence. */
    private void startTimeStop() {
        tsCenterX = player.position.x;
        tsCenterY = player.position.y + 1.0f;
        tsCenterZ = player.position.z;
        timeStopT = 0f; tsRadiusNow = 0f; timeStopActive = true;

        // Pause every currently-playing sound before the time_stop sfx fires.
        // This silences ambient loops, enemy sounds, and ongoing one-shots so
        // only the signature audio plays in a clean silence.
        AudioManager.pauseAll();
        AudioManager.play("copyrighted/time_stop", 1.0f);
        AudioManager.playContinuous("clock_ticking", 0.65f);

        ScreenEffectManager.INSTANCE.flash(0.45f, 0.7f, 1.0f, 0.7f, 0.18f);
        orbShake(0.25f, 0.18f);
        hintText = "THE WORLD  —  time has stopped."; hintTimer = 3f;
    }

    /** Drive the expand → hold → collapse radius; deactivate and restore audio at the end. */
    private void updateTimeStop(float dt) {
        timeStopT += dt;
        float t = timeStopT;
        float hold = tsHold();

        if (t < TS_EXPAND) {
            float p = t / TS_EXPAND;
            tsRadiusNow = TS_MAXR * (1f - (1f - p) * (1f - p) * (1f - p));
        } else if (t < TS_EXPAND + hold) {
            tsRadiusNow = TS_MAXR;
        } else if (t < tsEnd()) {
            float p = (t - TS_EXPAND - hold) / TS_SHRINK;
            tsRadiusNow = TS_MAXR * (1f - p * p);

            // One-shot at the moment collapse begins: stop ticking, resume world.
            if (Math.abs(t - (TS_EXPAND + hold)) < dt * 1.5f) {
                AudioManager.stopContinuous("clock_ticking");
                AudioManager.resumeAll();
                AudioManager.play("copyrighted/resume_time", 1.0f);
                ScreenEffectManager.INSTANCE.flash(0.5f, 0.7f, 1.0f, 0.4f, 0.2f);
            }
        } else {
            timeStopActive = false; tsRadiusNow = 0f;
        }
    }

    /** Drive the one-shot radar scan: fade in → scan (rotating arm) → fade out. */
    private void updateVoxelLines(float dt) {
        vlT += dt;
        float t = vlT;
        vlRadiusNow = VL_MAXR;                                  // full scope immediately
        vlSweepNow  = (vlSweepNow + VL_SWEEP_SPEED * dt) % 6.2831853f;  // rotate the arm
        if (t < VL_RAMP) {
            vlAmountNow = t / VL_RAMP;
        } else if (t < VL_RAMP + VL_HOLD) {
            vlAmountNow = 1f;
        } else if (t < VL_END) {
            vlAmountNow = 1f - (t - VL_RAMP - VL_HOLD) / VL_RETURN;
        } else {
            vlActive = false; vlAmountNow = 0f; vlRadiusNow = 0f;
        }
    }

    /** Erase a vertical cylinder of voxels at the epicentre and remesh the hit chunks. */
    private void carveOrbitalCrater() {
        int cxB = (int) Math.floor(orbEpiX);
        int czB = (int) Math.floor(orbEpiZ);
        int R = ORB_CRATER_R;
        // Full column: sky limit → void.  Every block in the cylinder is erased,
        // exposing the void below bedrock so falling in is instant death.
        int yBot = 0;
        int yTop = Chunk.HEIGHT - 1;

        java.util.Set<Long> cols = new java.util.HashSet<>();
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (dx * dx + dz * dz > R * R) continue;
                int wx = cxB + dx, wz = czB + dz;
                for (int y = yBot; y <= yTop; y++) {
                    if (world.getBlock(wx, y, wz) == Block.AIR) continue;
                    world.setBlockWithMeta(wx, y, wz, Block.AIR, (byte) 0, false);
                }
                int ccx = Math.floorDiv(wx, Chunk.SIZE), ccz = Math.floorDiv(wz, Chunk.SIZE);
                for (int ax = -1; ax <= 1; ax++)
                    for (int az = -1; az <= 1; az++)
                        cols.add(((long) (ccx + ax + 32768) << 20) | (ccz + az + 32768));
            }
        }
        // Rebuild every vertical chunk slab in the affected columns.
        int cyLo = 0, cyHi = Math.floorDiv(yTop, Chunk.HEIGHT);
        for (long key : cols) {
            int kcx = (int) ((key >> 20) & 0xFFFFF) - 32768;
            int kcz = (int) (key & 0xFFFFF) - 32768;
            for (int cy = cyLo; cy <= cyHi; cy++) {
                Chunk c = world.getChunk(kcx, cy, kcz);
                if (c != null) world.buildChunkMeshes(c);
            }
        }
    }

    // ── 3D effect geometry ──────────────────────────────────────────────────────

    /** Draw one unit mesh with a model transform and an emissive colour×intensity. */
    private void orbDraw(com.leaf.game.render.Shader shader, Matrix4f pv,
                         com.leaf.game.render.Mesh m, Matrix4f model, float r, float g, float b) {
        shader.setUniform("mvp", new Matrix4f(pv).mul(model));
        shader.setUniform("emissiveTint", new Vector3f(r, g, b));
        m.render();
    }

    /** Radar 3D layer: a soft vertical "arm" curtain sweeping around (opaque→
     *  transparent afterglow) + a small centre pylon. The flat ground sweep, rings
     *  and spokes live in the terrain shader; enemies are wireframed in the enemy
     *  render pass (see the radar gate there). Kept deliberately DIM — additive
     *  green that glows without blowing out to white. */
    private void renderRadar3D(com.leaf.game.render.Shader shader,
                               Matrix4f projection, Matrix4f view, Matrix4f renderMvp) {
        if (radarFan  == null) radarFan  = orbBuildCurtain(1.7f, 28);  // ~97° afterglow curtain
        if (orbSphere == null) orbSphere = orbBuildSphere(10, 14);

        float amt = vlAmountNow;
        if (amt < 0.01f) return;
        Matrix4f pv = new Matrix4f(projection).mul(view);
        float cx = vlCx, cy = vlCy, cz = vlCz;
        float sweep = vlSweepNow;
        float armLen = Math.min(vlRadiusNow, 40f);   // a modest local arm, not the full scope

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);     // additive
        glDepthMask(false);
        glDepthFunc(GL_ALWAYS);          // draw over terrain like the shader scope
        shader.setUniform("emissiveMode", 1);

        // Vertical afterglow CURTAIN: a curved wall (radius 1, height 1) spanning
        // fan-angle [-span,0]; bright leading edge → transparent trailing.
        // rotateY(-sweep) maps local fan-angle a → world bearing (a + sweep).
        // DIM — a soft sheet of light, not a searing wall.
        Matrix4f curtain = new Matrix4f().translate(cx, cy - 1.0f, cz)
                .rotateY(-sweep)
                .scale(armLen, 4.0f, armLen);
        orbDraw(shader, pv, radarFan, curtain, 0.03f * amt, 0.34f * amt, 0.14f * amt);

        // Small centre pylon (gentle).
        orbDraw(shader, pv, orbSphere,
                new Matrix4f().translate(cx, cy, cz).scale(0.5f),
                0.12f * amt, 0.55f * amt, 0.22f * amt);

        shader.setUniform("emissiveMode", 0);
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /** Vertical afterglow curtain: a curved wall of radius 1, y in [0,1], spanning
     *  fan-angle [-span, 0]. Vertex brightness 1 at the leading edge (angle 0) → 0
     *  trailing, so additive blending fades it opaque→transparent behind the arm. */
    private com.leaf.game.render.Mesh orbBuildCurtain(float span, int segs) {
        float[] v = new float[(segs + 1) * 2 * 10];
        int vi = 0;
        for (int i = 0; i <= segs; i++) {
            float a = -span * (float) i / segs;       // 0 → -span
            float b = 1f - (float) i / segs;           // 1 → 0 brightness
            float cxr = (float) Math.cos(a), czr = (float) Math.sin(a);
            int o = (vi++) * 10;                       // bottom vertex
            v[o]=cxr; v[o+1]=0; v[o+2]=czr; v[o+3]=b; v[o+4]=b; v[o+5]=b; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
            o = (vi++) * 10;                           // top vertex
            v[o]=cxr; v[o+1]=1; v[o+2]=czr; v[o+3]=b; v[o+4]=b; v[o+5]=b; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
        }
        int[] idx = new int[segs * 6];
        int ii = 0;
        for (int i = 0; i < segs; i++) {
            int a = i*2, bt = i*2+1, c = i*2+2, dt = i*2+3;
            idx[ii++]=a; idx[ii++]=c; idx[ii++]=bt;  idx[ii++]=bt; idx[ii++]=c; idx[ii++]=dt;
        }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    /** The whole 3D set-piece: gyroscope, implosion rings, core, embers, laser. */
    private void renderOrbital3D(com.leaf.game.render.Shader shader,
                                 Matrix4f projection, Matrix4f view, Matrix4f renderMvp) {
        if (orbTorus == null) {
            orbTorus  = orbBuildTorus(0.05f, 72, 8);
            orbSphere = orbBuildSphere(14, 20);
            orbCyl    = orbBuildCylinder(28);
            orbCube   = orbBuildCube();
        }
        float t  = orbitalT;
        float ex = orbEpiX, ey = orbEpiY, ez = orbEpiZ;
        Matrix4f pv = new Matrix4f(projection).mul(view);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glDepthMask(false);
        glDepthFunc(GL_ALWAYS);
        shader.setUniform("emissiveMode", 1);

        if (t < ORB_LASER) {
            float pulse  = 0.55f + 0.18f * (float) Math.sin(t * 8.0);
            float coreR  = 0.7f * pulse + 0.2f;
            float bright = 1.4f;
            if (Math.abs(t - ORB_IMPLODE) < 0.3f) bright = 3.4f;
            orbDraw(shader, pv, orbSphere,
                    new Matrix4f().translate(ex, ey, ez).scale(coreR), bright, bright, bright * 0.9f);
        }

        if (t < ORB_IMPLODE) {
            float gs = 3.6f;
            if (t > ORB_CHARGE) {
                float p = (t - ORB_CHARGE) / (ORB_IMPLODE - ORB_CHARGE);
                gs *= (1f - p * p * p);
            }
            orbDraw(shader, pv, orbTorus,
                    new Matrix4f().translate(ex, ey, ez).rotateX(t * 2.0f).scale(gs),
                    0.6f, 1f, 0.7f);
            orbDraw(shader, pv, orbTorus,
                    new Matrix4f().translate(ex, ey, ez).rotateZ(t * 2.7f).scale(gs * 0.85f),
                    0.7f, 1f, 0.85f);
            orbDraw(shader, pv, orbTorus,
                    new Matrix4f().translate(ex, ey, ez).rotateY(t * 1.6f).rotateX(0.5f).scale(gs * 1.15f),
                    0.5f, 1f, 0.6f);
        }

        if (t < ORB_IMPLODE) {
            for (int k = 0; k < 6; k++) {
                float tk = k * 0.45f;
                if (t < tk) continue;
                float r;
                if (t < ORB_CHARGE) {
                    r = 6f * (t - tk);
                } else {
                    float rAt = 6f * (ORB_CHARGE - tk);
                    float p   = (t - ORB_CHARGE) / (ORB_IMPLODE - ORB_CHARGE);
                    r = rAt * (1f - p * p * p);
                }
                if (r < 0.4f) continue;
                float b = 0.8f + (t >= ORB_CHARGE ? 1.6f * (t - ORB_CHARGE) / (ORB_IMPLODE - ORB_CHARGE) : 0f);
                orbDraw(shader, pv, orbTorus,
                        new Matrix4f().translate(ex, ey + 0.3f, ez).scale(r, 1f, r),
                        b * 0.7f, b, b * 0.8f);
            }
        }

        for (OrbParticle p : orbParticles) {
            float lf = Math.max(0f, p.life / p.maxLife);
            orbDraw(shader, pv, orbCube,
                    new Matrix4f().translate(p.x, p.y, p.z).scale(p.size),
                    p.r * (0.4f + lf), p.g * (0.4f + lf), p.b * (0.4f + lf));
        }

        if (t >= ORB_LASER) {
            float p     = (t - ORB_LASER) / (ORB_END - ORB_LASER);
            float fade  = (p < 0.7f) ? 1f : Math.max(0f, 1f - (p - 0.7f) / 0.3f);
            float flick = 0.85f + 0.30f * (float) Math.sin(t * 50.0);
            float top   = ey + 150f, bot = ey - 50f, hgt = top - bot;

            orbDraw(shader, pv, orbCyl,
                    new Matrix4f().translate(ex, bot, ez).scale(3.5f * fade, hgt, 3.5f * fade),
                    1.2f * flick, 1.2f * flick, 1.2f * flick);
            orbDraw(shader, pv, orbCyl,
                    new Matrix4f().translate(ex, bot, ez).scale(1.0f * fade, hgt, 1.0f * fade),
                    3.0f * flick, 3.0f * flick, 3.0f * flick);
            orbDraw(shader, pv, orbCyl,
                    new Matrix4f().translate(ex, bot, ez).scale(0.25f * fade, hgt, 0.25f * fade),
                    6f, 6f, 6f);

            for (int s = 0; s < 4; s++) {
                float ang = t * 4.5f + s * (float) (Math.PI / 2);
                float ox  = (float) Math.cos(ang) * 4.8f, oz = (float) Math.sin(ang) * 4.8f;
                orbDraw(shader, pv, orbCyl,
                        new Matrix4f().translate(ex + ox, bot, ez + oz).scale(0.5f * fade, hgt, 0.5f * fade),
                        0.4f, 1.5f, 0.6f);
            }

            float swb = Math.max(0f, 1.6f * (1f - p * 3f));
            if (swb > 0.01f) {
                float swr = Math.min(1f, p * 3f) * (ORB_CRATER_R + 6);
                orbDraw(shader, pv, orbTorus,
                        new Matrix4f().translate(ex, ey + 0.4f, ez).scale(swr, 1f, swr),
                        0.4f * swb, 1.6f * swb, 0.6f * swb);
            }

            float fr = Math.max(0f, 1f - p * 4f) * 9f;
            if (fr > 0.3f) {
                orbDraw(shader, pv, orbSphere,
                        new Matrix4f().translate(ex, ey, ez).scale(fr), 3f, 3f, 2.8f);
            }
        }

        shader.setUniform("emissiveMode", 0);
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    private com.leaf.game.render.Mesh orbBuildTorus(float mr, int nMaj, int nMin) {
        float[] v = new float[nMaj * nMin * 10];
        int[]   idx = new int[nMaj * nMin * 6];
        int vi = 0, ii = 0;
        for (int i = 0; i < nMaj; i++) {
            double th = 2 * Math.PI * i / nMaj, ct = Math.cos(th), st = Math.sin(th);
            for (int j = 0; j < nMin; j++) {
                double ph = 2 * Math.PI * j / nMin;
                float rr = (float) (1 + mr * Math.cos(ph));
                int o = (vi++) * 10;
                v[o]=(float)(ct*rr); v[o+1]=(float)(mr*Math.sin(ph)); v[o+2]=(float)(st*rr);
                v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
            }
        }
        for (int i = 0; i < nMaj; i++)
            for (int j = 0; j < nMin; j++) {
                int a = i*nMin+j, b = ((i+1)%nMaj)*nMin+j,
                        c = ((i+1)%nMaj)*nMin+(j+1)%nMin, dd = i*nMin+(j+1)%nMin;
                idx[ii++]=a; idx[ii++]=b; idx[ii++]=c; idx[ii++]=a; idx[ii++]=c; idx[ii++]=dd;
            }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    private com.leaf.game.render.Mesh orbBuildSphere(int rings, int sectors) {
        float[] v = new float[(rings + 1) * (sectors + 1) * 10];
        int vi = 0;
        for (int i = 0; i <= rings; i++) {
            double lat = Math.PI * i / rings, y = Math.cos(lat), rr = Math.sin(lat);
            for (int j = 0; j <= sectors; j++) {
                double lon = 2 * Math.PI * j / sectors;
                int o = (vi++) * 10;
                v[o]=(float)(rr*Math.cos(lon)); v[o+1]=(float)y; v[o+2]=(float)(rr*Math.sin(lon));
                v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
            }
        }
        int[] idx = new int[rings * sectors * 6];
        int ii = 0, stride = sectors + 1;
        for (int i = 0; i < rings; i++)
            for (int j = 0; j < sectors; j++) {
                int a = i*stride+j, b = a+stride;
                idx[ii++]=a; idx[ii++]=b; idx[ii++]=a+1; idx[ii++]=a+1; idx[ii++]=b; idx[ii++]=b+1;
            }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    private com.leaf.game.render.Mesh orbBuildCylinder(int seg) {
        float[] v = new float[(seg + 1) * 2 * 10];
        int vi = 0;
        for (int j = 0; j <= seg; j++) {
            double a = 2 * Math.PI * j / seg; float cx = (float) Math.cos(a), cz = (float) Math.sin(a);
            int o = (vi++) * 10;
            v[o]=cx; v[o+1]=0; v[o+2]=cz; v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=cx; v[o+8]=0; v[o+9]=cz;
            o = (vi++) * 10;
            v[o]=cx; v[o+1]=1; v[o+2]=cz; v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=cx; v[o+8]=0; v[o+9]=cz;
        }
        int[] idx = new int[seg * 6];
        int ii = 0;
        for (int j = 0; j < seg; j++) {
            int a = j*2, b = j*2+1, c = j*2+2, d = j*2+3;
            idx[ii++]=a; idx[ii++]=c; idx[ii++]=b; idx[ii++]=b; idx[ii++]=c; idx[ii++]=d;
        }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DEPRIVATION DOMAIN — WATER GOD STANCE  (' key, KeyBindings.DEPRIVATION_DOMAIN)
    // ══════════════════════════════════════════════════════════════════════════
    //  The player locks in place. The world-shader tints the interior gold and the
    //  exterior cool/dark — an absolute domain, a silent standoff. Any entity that
    //  moves inside the radius is instantly cut apart: a chaotic "slash storm" of
    //  anime crescents erupts around the player (Soul-Knight-style omni-swing) and
    //  the victim bursts into white-hot voxel gibs that char as they fall.

    private void startDeprivationDomain() {
        depActive = true;
        depT      = 0f;
        depX = player.position.x;
        depY = player.position.y;
        depZ = player.position.z;
        depStrike = 0f;
        depDetectTimer = 0f;
        depPrevPos.clear();
        depSlashFx.clear();
        depGibs.clear();
        if (depCrescent == null) depCrescent = buildSlashCrescent(22);
        hintText  = "DEPRIVATION DOMAIN — perfect stillness, absolute death · ['] to exit";
        hintTimer = 5f;
        // AUDIO HOOK: add your own domain-enter cue here (user-supplied).
        ScreenEffectManager.INSTANCE.flash(1f, 0.88f, 0.28f, 0.30f, 0.40f);
    }

    private void stopDeprivationDomain() {
        depActive = false;
        depCooldown = GameConfig.depCooldownSecs;
        depPrevPos.clear();
        depSlashFx.clear();
        depGibs.clear();
        // AUDIO HOOK: add your own domain-exit cue here (user-supplied).
    }

    private void updateDeprivationDomain(float dt, World world) {
        depT += dt;

        // Age slash crescents (expand + fade in ~0.2 s)
        for (java.util.Iterator<float[]> it = depSlashFx.iterator(); it.hasNext(); ) {
            float[] s = it.next(); s[6] += dt; if (s[6] >= s[7]) it.remove();
        }
        // Age + integrate voxel gibs (gravity, drag, fade)
        for (java.util.Iterator<float[]> it = depGibs.iterator(); it.hasNext(); ) {
            float[] g = it.next();
            g[8] += dt;
            if (g[8] >= g[9]) { it.remove(); continue; }
            g[4] -= 26f * dt;                      // gravity on vy
            g[0] += g[3] * dt; g[1] += g[4] * dt; g[2] += g[5] * dt;
            g[3] *= 0.96f; g[5] *= 0.96f;          // horizontal air drag
        }

        if (depT >= GameConfig.depDuration) { stopDeprivationDomain(); return; }

        // Movement detection (sampled every depDetectTick for perf)
        depDetectTimer += dt;
        if (depDetectTimer < GameConfig.depDetectTick) return;
        float tick = depDetectTimer;
        depDetectTimer = 0f;

        float radSq  = GameConfig.depRadius * GameConfig.depRadius;
        float minMov = GameConfig.depDetectMinVel * tick;  // blocks moved this tick
        if (enemyManager == null) return;

        boolean struck = false;

        // ── Enemy movement detection → SLICE ─────────────────────────────────
        for (com.leaf.game.entity.Enemy e : enemyManager.getEnemies()) {
            if (!e.alive) continue;
            float dx = e.position.x - depX, dz = e.position.z - depZ;
            if (dx * dx + dz * dz > radSq) {
                depPrevPos.remove(System.identityHashCode(e));
                continue;
            }
            org.joml.Vector3f prev = depPrevPos.get(System.identityHashCode(e));
            if (prev != null) {
                float moved = (float) Math.hypot(e.position.x - prev.x, e.position.z - prev.z);
                if (moved >= minMov) {
                    float ex = e.position.x, ey = e.position.y + 1.0f, ez = e.position.z;
                    spawnSlashBurst(ex, ey, ez, 2, 0.6f, 1.4f);  // the cut itself
                    spawnSliceGibs(ex, ey, ez);                  // body falls apart
                    e.applyDamage(GameConfig.depDamage);
                    struck = true;
                }
            }
            depPrevPos.put(System.identityHashCode(e), new org.joml.Vector3f(e.position));
        }

        // ── Projectile nullification → split & drop ──────────────────────────
        for (com.leaf.game.entity.EnemyManager.EnemyProjectile proj : enemyManager.projectiles) {
            if (!proj.alive) continue;
            float dx = proj.pos.x - depX, dz = proj.pos.z - depZ;
            if (dx * dx + dz * dz > radSq) continue;
            if (proj.vel.length() > 0.1f) {
                spawnSlashBurst(proj.pos.x, proj.pos.y, proj.pos.z, 1, 0.4f, 1.0f);
                spawnSliceGibs(proj.pos.x, proj.pos.y, proj.pos.z);
                proj.alive = false;
                struck = true;
            }
        }

        // ── THE SLASH STORM — omnidirectional dome of slashes on top of the player ──
        if (struck) {
            int n = 5 + (int) (Math.random() * 6);   // 5–10 crescents
            spawnSlashBurst(depX, depY + 1.1f, depZ, n, 1.0f, 2.0f);
            depStrike = Math.min(1f, depStrike + 0.6f);
            activeShakeAmplitude = 0.08f;
            activeShakeDuration  = 0.14f;
            smashShakeTimer      = Math.max(smashShakeTimer, 0.14f);
            ScreenEffectManager.INSTANCE.flash(1f, 0.90f, 0.35f, 0.25f, 0.10f);
            // AUDIO HOOK: add your own slash / cut cue here (user-supplied).
        }
    }

    /** Spawn a burst of randomly-oriented slash crescents centred at (cx,cy,cz). */
    private void spawnSlashBurst(float cx, float cy, float cz, int count,
                                 float startMin, float startMax) {
        for (int i = 0; i < count; i++) {
            if (depSlashFx.size() > 140) break;
            float yaw   = (float) (Math.random() * Math.PI * 2.0);
            float pitch = (float) (Math.random() * Math.PI * 2.0);
            float roll  = (float) (Math.random() * Math.PI * 2.0);
            float s0    = startMin + (float) Math.random() * (startMax - startMin);
            float s1    = s0 + 2.0f + (float) Math.random() * 1.2f;   // expand ~2–3 blocks
            float life  = 0.16f + (float) Math.random() * 0.07f;       // ~0.2 s
            depSlashFx.add(new float[]{cx, cy, cz, yaw, pitch, roll, 0f, life, s0, s1});
        }
    }

    /** Spawn voxel gib debris: two halves split across a random vertical plane,
     *  flung apart with an upward pop. They start white-hot gold and char as they fall. */
    private void spawnSliceGibs(float cx, float cy, float cz) {
        float ang = (float) (Math.random() * Math.PI);
        float nx = (float) Math.cos(ang), nz = (float) Math.sin(ang);   // cut-plane normal (XZ)
        int chunks = 10 + (int) (Math.random() * 6);
        for (int i = 0; i < chunks; i++) {
            if (depGibs.size() > 260) break;
            float side = (i % 2 == 0) ? 1f : -1f;                       // alternate halves
            float px = cx + (float) (Math.random() - 0.5) * 0.5f;
            float py = cy + (float) (Math.random() - 0.5) * 1.2f;
            float pz = cz + (float) (Math.random() - 0.5) * 0.5f;
            float spd = 2.5f + (float) Math.random() * 3.5f;
            float vx = nx * side * spd + (float) (Math.random() - 0.5) * 1.5f;
            float vz = nz * side * spd + (float) (Math.random() - 0.5) * 1.5f;
            float vy = 2.5f + (float) Math.random() * 4.0f;
            float size = 0.12f + (float) Math.random() * 0.18f;
            float life = 0.9f + (float) Math.random() * 0.6f;
            depGibs.add(new float[]{px, py, pz, vx, vy, vz, size, 0f, life});
        }
    }

    private void renderDeprivationDomain(com.leaf.game.render.Shader shader,
                                         Matrix4f projection, Matrix4f view,
                                         Matrix4f renderMvp) {
        if (!depActive) return;
        if (depCrescent == null) depCrescent = buildSlashCrescent(22);
        if (orbCube == null) orbCube = orbBuildCube();

        Matrix4f pv = new Matrix4f(projection).mul(view);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);   // additive — everything glows + blooms
        glDepthMask(false);
        glDepthFunc(GL_LEQUAL);        // terrain naturally occludes the slashes
        glDisable(GL_CULL_FACE);
        shader.setUniform("emissiveMode", 1);

        // ── THE SLASH STORM: expanding crescents, white-hot gold ─────────────
        for (float[] s : depSlashFx) {
            float f    = s[6] / s[7];                       // 0→1 over its life
            float ease = 1f - (1f - f) * (1f - f);          // easeOut for the expansion
            float scale = s[8] + (s[9] - s[8]) * ease;
            float rise = (f < 0.20f) ? f / 0.20f : 1f;      // snap to full brightness
            float fall = (f > 0.50f) ? (1f - (f - 0.50f) / 0.50f) : 1f;  // then fade out
            float br   = rise * fall * 4.5f;                // HDR — feeds the bloom
            if (br <= 0.01f) continue;
            Matrix4f m = new Matrix4f().translate(s[0], s[1], s[2])
                    .rotateY(s[3]).rotateX(s[4]).rotateZ(s[5])
                    .scale(scale);
            orbDraw(shader, pv, depCrescent, m, br * 1.35f, br * 1.0f, br * 0.32f);
        }

        // ── VOXEL GIBS: sliced bodies, white-hot gold → charred ──────────────
        for (float[] g : depGibs) {
            float f   = g[8] / g[9];
            float hot = 1f - f;                              // 1 fresh → 0 cold
            float r  = 2.6f * hot + 0.14f;                   // searing gold → dark char
            float gg = 2.0f * hot + 0.11f;
            float b  = 0.7f * hot + 0.09f;
            orbDraw(shader, pv, orbCube,
                    new Matrix4f().translate(g[0], g[1], g[2]).scale(g[6]),
                    r, gg, b);
        }

        // Restore GL state — IMPORTANT: leave GL_CULL_FACE DISABLED (terrain needs it off;
        // it is never enabled at init, so re-enabling here would hide terrain top faces).
        shader.setUniform("emissiveMode", 0);
        shader.setUniform("emissiveTint", new Vector3f(1f, 1f, 1f));
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
    }

    /**
     * Build a unit anime sword-sweep crescent in the local XY plane.
     * An arc spanning [-A, A] at centreline radius 1, with a half-thickness that
     * tapers to sharp points at both tips (the classic slash shape). Vertex colour
     * is white (×emissiveTint at draw), dimmer toward the tips for soft ends.
     */
    private com.leaf.game.render.Mesh buildSlashCrescent(int seg) {
        final float A = 1.15f;     // arc half-angle (radians) — ~66°
        final float R = 1.0f;      // centreline radius
        final float wMax = 0.20f;  // max half-thickness of the blade
        float[] v = new float[(seg + 1) * 2 * 10];
        int vi = 0;
        for (int i = 0; i <= seg; i++) {
            float t = (float) i / seg;            // 0..1
            float a = -A + 2f * A * t;            // -A..A
            float dirX = (float) Math.sin(a), dirY = (float) Math.cos(a);
            float taper = 1f - (a / A) * (a / A); // 1 at centre, 0 at tips
            taper = (float) Math.pow(Math.max(0f, taper), 0.6);
            float w = wMax * taper;
            float bright = 0.40f + 0.60f * taper; // soft tips, hot centre
            int o = (vi++) * 10;                   // inner edge vertex
            v[o]   = (R - w) * dirX; v[o+1] = (R - w) * dirY; v[o+2] = 0f;
            v[o+3] = bright; v[o+4] = bright; v[o+5] = bright; v[o+6] = 1f;
            v[o+7] = 0f; v[o+8] = 0f; v[o+9] = 1f;
            o = (vi++) * 10;                       // outer edge vertex
            v[o]   = (R + w) * dirX; v[o+1] = (R + w) * dirY; v[o+2] = 0f;
            v[o+3] = bright; v[o+4] = bright; v[o+5] = bright; v[o+6] = 1f;
            v[o+7] = 0f; v[o+8] = 0f; v[o+9] = 1f;
        }
        int[] idx = new int[seg * 6];
        int ii = 0;
        for (int i = 0; i < seg; i++) {
            int a = i*2, b = i*2+1, c = i*2+2, d = i*2+3;
            idx[ii++] = a; idx[ii++] = c; idx[ii++] = b;
            idx[ii++] = b; idx[ii++] = c; idx[ii++] = d;
        }
        return new com.leaf.game.render.Mesh(v, idx);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CHOCOLATE DISCO — FLAT HOLOGRAM METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private void spawnDiscoGrid(Camera cam, World world) {
        Vector3f ld = cam.getLookDirection();

        // Spawn ~15 blocks ahead of the player
        cdGridX = player.position.x + ld.x * 15f;
        cdGridZ = player.position.z + ld.z * 15f;
        // Flat, rigid waist-level hologram plane
        cdGridY = (int) Math.floor(player.position.y) + 0.1f;

        cdActive = true;
        showDiscoUI = true;
        cdSpawnT = 0f;
        cdMeshDirty = true;
        cdHoverR = -1; cdHoverC = -1;
        for (boolean[] row : cdMarked)  java.util.Arrays.fill(row, false);
        for (float[]   row : cdDetT)    java.util.Arrays.fill(row, 0f);
        for (float[]   row : cdRingT)   java.util.Arrays.fill(row, 0f);
        for (boolean[] row : cdBlasted) java.util.Arrays.fill(row, false);

        // Free mouse for the 2D ImGui console
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        firstMouse[0] = true;
    }

    void dismissDiscoGrid() {
        showDiscoUI = false;
        cdSpawnT = -0.7f; // Start despawn countdown
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); // Re-lock mouse
        firstMouse[0] = true;
    }

    private void updateDiscoGrid(float dt, Camera cam, World world) {
        if (cdSpawnT >= 0f && cdSpawnT < 1f) {
            cdSpawnT = Math.min(1f, cdSpawnT + dt / CD_SPAWN);
            cdMeshDirty = true;
        }

        // Flash + shockwave-ring timers
        boolean anyActive = false;
        for (int r = 0; r < CD_ROWS; r++) {
            for (int c = 0; c < CD_COLS; c++) {
                if (cdDetT[r][c] > 0f) {
                    anyActive = true; cdDetT[r][c] -= dt; cdMeshDirty = true;
                    if (cdDetT[r][c] <= 0f) { cdDetT[r][c] = 0f; cdMarked[r][c] = false; }
                }
                if (cdRingT[r][c] > 0f) { anyActive = true; cdRingT[r][c] = Math.max(0f, cdRingT[r][c] - dt); }
                if (cdMarked[r][c]) anyActive = true;
            }
        }

        // Auto-despawn when finished exploding
        if (!anyActive && cdSpawnT > 0f && !showDiscoUI) {
            boolean anyBlasted = false;
            for (boolean[] row : cdBlasted) for (boolean b : row) if (b) { anyBlasted = true; break; }
            if (anyBlasted) cdSpawnT = -0.7f;
        }

        if (cdSpawnT < 0f) {
            cdSpawnT += dt; cdMeshDirty = true;
            if (cdSpawnT >= 0f) {
                cdActive = false;
                if (cdMesh != null) { cdMesh.cleanup(); cdMesh = null; }
                for (boolean[] row : cdBlasted) java.util.Arrays.fill(row, false);
            }
        }
    }

    void detonateDiscoGrid(World world) {
        boolean any = false;
        for (int r = 0; r < CD_ROWS; r++) {
            for (int c = 0; c < CD_COLS; c++) {
                if (cdMarked[r][c] && cdDetT[r][c] <= 0f) {
                    cdDetT[r][c]   = CD_DET_DUR;
                    cdRingT[r][c]  = CD_RING_DUR;
                    cdBlasted[r][c] = true;
                    cdDetonateCell(r, c, world);
                    any = true;
                }
            }
        }
        if (any) {
            cdMeshDirty = true;
            activeShakeDuration  = 0.45f;
            activeShakeAmplitude = 0.24f;
            smashShakeTimer      = activeShakeDuration;
            com.leaf.game.core.ScreenEffectManager.INSTANCE.flash(1f, 0.82f, 0.35f, 0.45f, 0.22f);
            com.leaf.game.core.AudioManager.play("paper_explode", 1f);
        }

        // Hide UI, return camera control to watch the fireworks
        showDiscoUI = false;
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        firstMouse[0] = true;
    }

    private void cdDetonateCell(int row, int col, World world) {
        float x0 = cdGridX - CD_HALF + col * CD_CELL;
        float z0 = cdGridZ - CD_HALF + row * CD_CELL;
        // Crush blocks below and above the grid plane
        int y0 = Math.max(2, (int)cdGridY - 2);
        int y1 = Math.min(Chunk.HEIGHT - 1, (int)cdGridY + CD_CRUSH_H);

        for (int bx = (int) x0; bx < (int) x0 + CD_CELL; bx++) {
            for (int bz = (int) z0; bz < (int) z0 + CD_CELL; bz++) {
                for (int by = y0; by <= y1; by++) {
                    Block b = world.getBlock(bx, by, bz);
                    if (b != Block.AIR && b.hardness < 9f) {
                        world.setBlock(bx, by, bz, Block.AIR);
                    }
                }
            }
        }

        if (enemyManager != null) {
            for (com.leaf.game.entity.Enemy e : enemyManager.getEnemies()) {
                if (!e.alive) continue;
                float ex = e.position.x, ez = e.position.z;
                if (ex >= x0 && ex <= x0 + CD_CELL && ez >= z0 && ez <= z0 + CD_CELL) {
                    e.health = 0; e.alive = false;
                }
            }
        }
    }

    private void renderDiscoGrid(com.leaf.game.render.Shader shader,
                                 Matrix4f projection, Matrix4f view,
                                 Matrix4f renderMvp) {
        float spawnFade = Math.max(0f, Math.min(1f, cdSpawnT < 0f ? 1f + cdSpawnT / 0.7f : cdSpawnT));
        if (spawnFade < 0.01f) return;

        if (cdMeshDirty || cdMesh == null) {
            if (cdMesh != null) cdMesh.cleanup();
            cdMesh = cdBuildMesh(spawnFade);
            cdMeshDirty = false;
        }

        Matrix4f pv = new Matrix4f(projection).mul(view);
        if (orbCyl   == null) orbCyl   = orbBuildCylinder(28);
        if (orbTorus == null) orbTorus = orbBuildTorus(0.05f, 72, 8);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glDepthMask(false);
        glDepthFunc(GL_ALWAYS); // <--- X-RAY EFFECT! Slices straight through mountains like a hologram.
        glDisable(GL_CULL_FACE);
        shader.setUniform("emissiveMode", 1);

        float pulse = 0.92f + 0.08f * (float) Math.sin(glfwGetTime() * 3.0);
        shader.setUniform("emissiveTint", new Vector3f(pulse, pulse, pulse));
        shader.setUniform("mvp", pv);
        cdMesh.render();

        for (int r = 0; r < CD_ROWS; r++) {
            for (int c = 0; c < CD_COLS; c++) {
                float cx = cdGridX - CD_HALF + (c + 0.5f) * CD_CELL;
                float cz = cdGridZ - CD_HALF + (r + 0.5f) * CD_CELL;
                float cy = cdGridY;

                float dt = cdDetT[r][c];
                if (dt > 0f) {
                    float frac = dt / CD_DET_DUR;
                    float peak = 1f - Math.abs(frac - 0.5f) * 2f;
                    float br   = 5f + peak * 14f;
                    float pR   = CD_CELL * 0.42f;
                    float pH   = 30f + peak * 55f;
                    orbDraw(shader, pv, orbCyl,
                            new Matrix4f().translate(cx, cy, cz).scale(pR * 0.28f, pH, pR * 0.28f),
                            br, br, br * 0.9f);
                    orbDraw(shader, pv, orbCyl,
                            new Matrix4f().translate(cx, cy, cz).scale(pR, pH * 0.7f, pR),
                            br * 0.30f, br * 0.26f, br * 0.12f);
                }
                float rt = cdRingT[r][c];
                if (rt > 0f) {
                    float rf = 1f - rt / CD_RING_DUR;
                    float ringR = CD_CELL * (0.4f + rf * 2.6f);
                    float ringB = (1f - rf) * 6f;
                    orbDraw(shader, pv, orbTorus,
                            new Matrix4f().translate(cx, cy + 0.2f, cz).scale(ringR, 0.6f, ringR),
                            ringB, ringB * 0.8f, ringB * 0.3f);
                }
            }
        }

        shader.setUniform("emissiveMode", 0);
        shader.setUniform("emissiveTint", new Vector3f(1f, 1f, 1f));
        shader.setUniform("mvp", renderMvp);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);  // leave culling OFF — global default; terrain needs it off
        glDisable(GL_BLEND);
    }

    private com.leaf.game.render.Mesh cdBuildMesh(float spawnFade) {
        java.util.ArrayList<Float>   vl = new java.util.ArrayList<>(4096);
        java.util.ArrayList<Integer> il = new java.util.ArrayList<>(4096);

        float baseX = cdGridX - CD_HALF, baseZ = cdGridZ - CD_HALF;
        float lw = CD_LW * spawnFade;
        final float GR = 1.30f, GG = 0.85f, GB = 0.22f;

        // Long flat ribbons spanning the whole 3D grid
        for (int i = 0; i <= CD_ROWS; i++) {
            int lz = i * CD_CELL;
            float k = (i == 0 || i == CD_ROWS) ? 1.0f : 0.5f;
            addCDGroundRibbon(vl, il,
                    baseX,           cdGridY, baseZ + lz,
                    baseX + CD_SPAN, cdGridY, baseZ + lz,
                    GR * k * spawnFade, GG * k * spawnFade, GB * k * spawnFade, lw);
        }
        for (int j = 0; j <= CD_COLS; j++) {
            int lx = j * CD_CELL;
            float k = (j == 0 || j == CD_COLS) ? 1.0f : 0.5f;
            addCDGroundRibbon(vl, il,
                    baseX + lx, cdGridY, baseZ,
                    baseX + lx, cdGridY, baseZ + CD_SPAN,
                    GR * k * spawnFade, GG * k * spawnFade, GB * k * spawnFade, lw);
        }

        float wh = CD_WALL * spawnFade;
        for (int r = 0; r < CD_ROWS; r++) {
            for (int c = 0; c < CD_COLS; c++) {
                boolean isHover  = (r == cdHoverR && c == cdHoverC && !cdMarked[r][c]);
                boolean isMarked = cdMarked[r][c] && cdDetT[r][c] <= 0f;
                boolean isDet    = cdDetT[r][c] > 0f;
                if (!isHover && !isMarked && !isDet) continue;

                float cellR, cellG, cellB, edgeBright, boxH = wh;
                if (isDet) {
                    float frac = cdDetT[r][c] / CD_DET_DUR;
                    float peak = 1f - Math.abs(frac - 0.5f) * 2f;
                    edgeBright = 3.0f + peak * 7.0f;
                    cellR = 1f; cellG = 1f; cellB = 0.9f;
                    boxH  = wh * (1f + peak * 2.5f);
                } else if (isMarked) {
                    edgeBright = 1.5f; cellR = 1.30f; cellG = 0.80f; cellB = 0.15f;
                } else {
                    edgeBright = 1.1f; cellR = 0.30f; cellG = 0.85f; cellB = 1.00f;
                }
                float er = cellR * edgeBright * spawnFade;
                float eg = cellG * edgeBright * spawnFade;
                float eb = cellB * edgeBright * spawnFade;

                float x0 = baseX + c * CD_CELL, x1 = x0 + CD_CELL;
                float z0 = baseZ + r * CD_CELL, z1 = z0 + CD_CELL;
                float y0 = cdGridY, y1 = y0 + boxH;

                addCDEdgeX(vl, il, x0, x1, y0, z0, er, eg, eb, lw);
                addCDEdgeX(vl, il, x0, x1, y0, z1, er, eg, eb, lw);
                addCDEdgeZ(vl, il, z0, z1, y0, x0, er, eg, eb, lw);
                addCDEdgeZ(vl, il, z0, z1, y0, x1, er, eg, eb, lw);
                addCDEdgeX(vl, il, x0, x1, y1, z0, er, eg, eb, lw);
                addCDEdgeX(vl, il, x0, x1, y1, z1, er, eg, eb, lw);
                addCDEdgeZ(vl, il, z0, z1, y1, x0, er, eg, eb, lw);
                addCDEdgeZ(vl, il, z0, z1, y1, x1, er, eg, eb, lw);
                addCDEdgeY(vl, il, y0, y1, x0, z0, er, eg, eb, lw);
                addCDEdgeY(vl, il, y0, y1, x1, z0, er, eg, eb, lw);
                addCDEdgeY(vl, il, y0, y1, x0, z1, er, eg, eb, lw);
                addCDEdgeY(vl, il, y0, y1, x1, z1, er, eg, eb, lw);

                float fa = isDet ? edgeBright * 0.05f : 0.10f;
                addCDQuad(vl, il,
                        x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,
                        er * fa, eg * fa, eb * fa, 1f, 0, 1, 0);
            }
        }

        // Float the text perfectly flat on the grid!
        float gw = 1.1f, gh = 1.7f;
        for (int c = 0; c < CD_COLS; c++) {
            float originX = baseX + (c + 0.5f) * CD_CELL - gw * 0.5f;
            float originZ = baseZ - 0.5f;
            addCDGlyph(vl, il, cdGlyph((char) ('1' + c)),
                    originX, cdGridY, originZ, gw, 0f, 0f, -gh,
                    GR * spawnFade, GG * spawnFade, GB * spawnFade, lw);
        }
        for (int r = 0; r < CD_ROWS; r++) {
            float originX = baseX - 0.5f - gw;
            float originZ = baseZ + (r + 0.5f) * CD_CELL + gh * 0.5f;
            addCDGlyph(vl, il, cdGlyph((char) ('A' + r)),
                    originX, cdGridY, originZ, gw, 0f, 0f, -gh,
                    GR * spawnFade, GG * spawnFade, GB * spawnFade, lw);
        }

        float[] va = new float[vl.size()];
        for (int i = 0; i < va.length; i++) va[i] = vl.get(i);
        int[] ia = new int[il.size()];
        for (int i = 0; i < ia.length; i++) ia[i] = il.get(i);
        return new com.leaf.game.render.Mesh(va, ia);
    }

    private void addCDGlyph(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                            float[] seg, float originX, float flatY, float originZ,
                            float uX, float uZ, float vX, float vZ,
                            float r, float g, float b, float lw) {
        for (int s = 0; s + 3 < seg.length; s += 4) {
            float u0 = seg[s], v0 = seg[s + 1], u1 = seg[s + 2], v1 = seg[s + 3];
            float wx0 = originX + u0 * uX + v0 * vX, wz0 = originZ + u0 * uZ + v0 * vZ;
            float wx1 = originX + u1 * uX + v1 * vX, wz1 = originZ + u1 * uZ + v1 * vZ;
            addCDGroundRibbon(vl, il, wx0, flatY, wz0, wx1, flatY, wz1, r, g, b, lw);
        }
    }

    /** Stroke font (segments u0,v0,u1,v1 in a unit cell) for digits 1–9 and letters A–I. */
    private static float[] cdGlyph(char ch) {
        switch (ch) {
            case '1': return new float[]{1,1, 1,0};
            case '2': return new float[]{0,1,1,1, 1,1,1,0.5f, 0,0.5f,1,0.5f, 0,0.5f,0,0, 0,0,1,0};
            case '3': return new float[]{0,1,1,1, 1,1,1,0.5f, 0,0.5f,1,0.5f, 1,0.5f,1,0, 0,0,1,0};
            case '4': return new float[]{0,1,0,0.5f, 1,1,1,0.5f, 0,0.5f,1,0.5f, 1,0.5f,1,0};
            case '5': return new float[]{0,1,1,1, 0,1,0,0.5f, 0,0.5f,1,0.5f, 1,0.5f,1,0, 0,0,1,0};
            case '6': return new float[]{0,1,1,1, 0,1,0,0, 0,0,1,0, 1,0,1,0.5f, 0,0.5f,1,0.5f};
            case '7': return new float[]{0,1,1,1, 1,1,1,0};
            case '8': return new float[]{0,1,1,1, 1,1,1,0, 0,1,0,0, 0,0,1,0, 0,0.5f,1,0.5f};
            case '9': return new float[]{0,1,1,1, 1,1,1,0, 0,0,1,0, 0,1,0,0.5f, 0,0.5f,1,0.5f};
            case 'A': return new float[]{0,0,0.5f,1, 0.5f,1,1,0, 0.25f,0.45f,0.75f,0.45f};
            case 'B': return new float[]{0,0,0,1, 0,1,1,1, 0,0.5f,1,0.5f, 0,0,1,0, 1,1,1,0.5f, 1,0.5f,1,0};
            case 'C': return new float[]{0,1,1,1, 0,1,0,0, 0,0,1,0};
            case 'D': return new float[]{0,0,0,1, 0,1,1,1, 1,1,1,0, 1,0,0,0};
            case 'E': return new float[]{0,0,0,1, 0,1,1,1, 0,0.5f,0.85f,0.5f, 0,0,1,0};
            case 'F': return new float[]{0,0,0,1, 0,1,1,1, 0,0.5f,0.8f,0.5f};
            case 'G': return new float[]{0,1,1,1, 0,1,0,0, 0,0,1,0, 1,0,1,0.5f, 1,0.5f,0.55f,0.5f};
            case 'H': return new float[]{0,0,0,1, 1,0,1,1, 0,0.5f,1,0.5f};
            case 'I': return new float[]{0.5f,0,0.5f,1, 0.2f,1,0.8f,1, 0.2f,0,0.8f,0};
            default:  return new float[0];
        }
    }

    // ── CHOCOLATE DISCO HELPERS ──────────────────────────────────────────────
    private static void addCDGroundRibbon(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                          float ax, float ay, float az,
                                          float bx, float by, float bz,
                                          float r, float g, float b, float lw) {
        float dx = bx - ax, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-5f) return;
        float px = -dz / len * lw * 0.5f, pz = dx / len * lw * 0.5f;
        addCDQuad(vl, il,
                ax + px, ay, az + pz,  bx + px, by, bz + pz,
                bx - px, by, bz - pz,  ax - px, ay, az - pz,
                r, g, b, 1f, 0, 1, 0);
    }

    private static void addCDQuad(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3,
                                  float r, float g, float b, float a,
                                  float nx, float ny, float nz) {
        int base = vl.size() / 10;
        float[] pts = {x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3};
        for (int i = 0; i < 4; i++) {
            vl.add(pts[i*3]); vl.add(pts[i*3+1]); vl.add(pts[i*3+2]);
            vl.add(r); vl.add(g); vl.add(b); vl.add(a);
            vl.add(nx); vl.add(ny); vl.add(nz);
        }
        il.add(base); il.add(base+1); il.add(base+2);
        il.add(base); il.add(base+2); il.add(base+3);
    }

    private static void addCDEdgeX(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                   float ax, float bx, float ey, float ez,
                                   float r, float g, float b, float lw) {
        float h = lw * 0.5f;
        addCDQuad(vl, il,
                ax, ey, ez-h,   bx, ey, ez-h,   bx, ey, ez+h,   ax, ey, ez+h,
                r, g, b, 1f, 0, 1, 0);
    }

    private static void addCDEdgeZ(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                   float az, float bz, float ey, float ex,
                                   float r, float g, float b, float lw) {
        float h = lw * 0.5f;
        addCDQuad(vl, il,
                ex-h, ey, az,   ex+h, ey, az,   ex+h, ey, bz,   ex-h, ey, bz,
                r, g, b, 1f, 0, 1, 0);
    }

    private static void addCDEdgeY(java.util.ArrayList<Float> vl, java.util.ArrayList<Integer> il,
                                   float ay, float by_, float ex, float ez,
                                   float r, float g, float b, float lw) {
        float h = lw * 0.5f;
        addCDQuad(vl, il,
                ex-h, ay, ez,   ex+h, ay, ez,   ex+h, by_, ez,   ex-h, by_, ez,
                r, g, b, 1f, 0, 0, 1);
        addCDQuad(vl, il,
                ex, ay, ez-h,   ex, ay, ez+h,   ex, by_, ez+h,   ex, by_, ez-h,
                r, g, b, 1f, 1, 0, 0);
    }

    private com.leaf.game.render.Mesh orbBuildCube() {
        float[] c = { -0.5f,-0.5f,-0.5f,  0.5f,-0.5f,-0.5f,  0.5f,0.5f,-0.5f,  -0.5f,0.5f,-0.5f,
                -0.5f,-0.5f, 0.5f,  0.5f,-0.5f, 0.5f,  0.5f,0.5f, 0.5f,  -0.5f,0.5f, 0.5f };
        float[] v = new float[8 * 10];
        for (int i = 0; i < 8; i++) {
            int o = i * 10;
            v[o]=c[i*3]; v[o+1]=c[i*3+1]; v[o+2]=c[i*3+2];
            v[o+3]=1; v[o+4]=1; v[o+5]=1; v[o+6]=1; v[o+7]=0; v[o+8]=1; v[o+9]=0;
        }
        int[] idx = {
                0,1,2, 0,2,3,  4,6,5, 4,7,6,  0,4,5, 0,5,1,
                3,2,6, 3,6,7,  1,5,6, 1,6,2,  0,3,7, 0,7,4 };
        return new com.leaf.game.render.Mesh(v, idx);
    }
}
