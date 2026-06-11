# DESCENT

**Survive. Learn. Escape.**

DESCENT is a 3D high-mobility voxel survival game. You awaken in a procedurally generated world and must survive escalating waves of enemies. As you clear each wave, a mysterious Chakra Crystal bonds with you and unlocks a new anime-inspired combat ability — culminating in time-manipulation, inter-dimensional phases, and high-speed parkour.

---

## How to Run

### macOS
```bash
bash play-mac.sh
```

### Windows
```
play-windows.bat
```

### Build from Source
```bash
./gradlew build
java -jar build/libs/game.jar
```

> **Note:** The game uses a custom 3D engine (LWJGL + OpenGL 3.3) as a teacher-approved
> alternative to Greenfoot. It is a standalone `.jar` — no Greenfoot install needed.

---

## How to Play

Click **PLAY** on the title screen. The built-in tutorial will guide you through the controls
step-by-step — teaching one mechanic at a time and waiting for you to do it before moving on.

### Quick-Start

1. Load the game and press **Play**.
2. A short tutorial teaches movement and your first weapon — the **Sniper** (hold `C` or
   Right-Click to charge, release to fire) — then grants **FLIGHT**.
3. **Follow the beam of light.** The crystal shattered and its shards scattered across the
   land. A glowing beacon always marks your next shard, with an on-screen waypoint and a
   "fly here" banner — you can never get lost. Fly to it, claim the shard, and the crystal
   **forges it into a weapon**:
   - **Crystal Fields** → Gatling Gun
   - **Glowing Groves** → Radar Sweep
   - **Floating Isles** (fly up!) → Stone Cannon
   - **The Ashlands** → defeat the **Inferno Tower**, then claim the Molten Core → **Orbital
     Annihilation** (the laser)
   - Reunite all shards → **The World** (stop time itself)
4. Press `F1` at any time for the full in-game reference card.

> **For grading / testing:** type `/showcase` in chat (`T`) to instantly unlock everything,
> `/skip` to skip the tutorial, or `/biome <name>` to warp to any biome. See *Developer
> Cheat Codes* below.

### Core Controls

| Key | Action |
|---|---|
| `W` `A` `S` `D` | Move |
| `Space` | Jump |
| Double-tap `W` | Sprint |
| Double-tap `Space` | Toggle flight · `V` to cycle modes (after FLIGHT unlocks) |
| Mouse | Look around |
| Left Click (hold) | Mine block / fire Gatling Gun |
| **Right Click** | **Fire the selected weapon** (Sniper, Orbital, Time Stop, Stone Cannon) |
| `1`–`9` | Select hotbar slot |
| `Left Alt` | Open backpack (re-equip earned weapons to hotbar) |
| `T` | Open chat (type commands) |
| `F1` | Open the in-game Master Ability Guide |

### The Voyage — gather shards, forge your arsenal

The crystal shattered; its shards fused to far-off places. You fly out, follow the beam of
light to each one, and the crystal forges it into a weapon. The route is strictly ordered and
always sign-posted (beacon + on-screen waypoint + "fly here" banner) so the journey feels
purposeful, never aimless:

| Destination | What to do | Forges |
|---|---|---|
| Start | Quick tutorial → **Flight** + Sniper, Slash, Dash | your travelling kit |
| **Crystal Fields** | follow the violet beam, fly into it | **Gatling Gun** (the gun) |
| **Glowing Groves** | follow the green beam | **Radar Sweep** (`F10`, see through walls) |
| **Floating Isles** | **fly UP** into the sky beam | **Stone Cannon** |
| **The Ashlands** | destroy the **Inferno Tower**, then claim the core | **Orbital Annihilation** (the laser) |
| — all shards reunited — | automatic | **The World** — stop time itself |

The Inferno Tower is deliberately the late, hard fight — you'll have the Gatling, Radar, and
Stone Cannon before you face it. Each weapon appears in your hotbar with a screen-flash and a
lore line the moment it's forged. Enemies roam the world freely as you travel — fight or fly
past them, your choice.

---

## Rubric Checklist

This section maps every grading criterion directly to the code that fulfils it.

---

### Title Screen & End Screen

| Feature | Implementation |
|---|---|
| Title screen | [`WindowHud.java`](src/main/java/com/leaf/game/core/WindowHud.java) — rendered with Dear ImGui; shows game title and a **Play Game** button |
| Intro cutscene | [`CutsceneManager.java`](src/main/java/com/leaf/game/core/CutsceneManager.java) — letterbox bars + typewriter text plays once when the world finishes loading |
| End screen (win) | Same — ending cutscene triggers after clearing Wave 9, then shows final stats |
| Death screen | [`RunRecords.java`](src/main/java/com/leaf/game/core/RunRecords.java) + `WindowHud` — shows wave reached, time played, and all-time bests |

---

### Instructions / Tutorial

| Feature | Implementation |
|---|---|
| Staged tutorial | [`TutorialManager.java`](src/main/java/com/leaf/game/core/TutorialManager.java) — teaches one concept per step (movement → melee → ranged → flight) and waits for the player to *actually do* each action before advancing |
| Ability practice | [`AbilityPractice.java`](src/main/java/com/leaf/game/core/AbilityPractice.java) — per-ability mini-sessions that play automatically after each wave unlock |
| In-game help | Press `F1` at any time for the full ability reference card |
| Skip option | Type `/skip` in chat to skip the tutorial instantly (useful for grading) |

---

### Scoring & Achievement System

| Feature | Implementation |
|---|---|
| Best wave record | [`RunRecords.java`](src/main/java/com/leaf/game/core/RunRecords.java) — persists best wave reached, total deaths, and run time to `descent_records.txt` |
| Ability unlock progression | [`Progression.java`](src/main/java/com/leaf/game/core/Progression.java) — 18 unlockable abilities accumulate across runs; unlocked set is saved to disk |
| End-of-run stats | Death screen + ending cutscene show: wave reached, deaths, total run time, lifetime best wave |
| Secret achievement | **Kamui** — a hidden ability that only unlocks after dying exactly 3 times |

---

### Motivation to Keep Playing

- **Escalating waves:** each wave spawns more enemies with harder types (`EnemyManager.java`)
- **18 progressive ability unlocks:** a new combat power is revealed on a cinematic card after every wave
- **Persistent records:** `descent_records.txt` tracks your best wave across sessions — beat your own high score
- **Hidden content:** the Kamui ability is only unlocked through dying 3 times, rewarding experimentation
- **Story:** an intro and ending cutscene give the run context and a goal to reach

---

### Sound & Sound Effects

[`AudioManager.java`](src/main/java/com/leaf/game/core/AudioManager.java) — a complete OpenAL 3D spatial audio engine.

- **60+ original sound effects** covering: every ability, movement states (walking, running, landing, water), block break types (stone, soil, sand, crystal), and ambient wind/water environments
- Sounds are positional in 3D space — distant audio fades correctly by distance
- Underwater reverb filter activates when the camera enters water

---

### Animation Using Arrays

The animation system uses arrays as its core data structure throughout:

| Class | Array usage |
|---|---|
| [`AnimClip.java`](src/main/java/com/leaf/game/anim/AnimClip.java) | Stores a `Map<String, List<Keyframe>>` — an array of `Keyframe` objects per named bone |
| [`AnimPlayer.java`](src/main/java/com/leaf/game/anim/AnimPlayer.java) | Iterates the keyframe array each frame to interpolate transforms; outputs a `Map<String, Matrix4f>` (one pose matrix per part) that the renderer applies |
| [`AnimModel.java`](src/main/java/com/leaf/game/anim/AnimModel.java) | Holds an array of `PartDef` (one per bone/cube) defining the skeleton |
| [`BlockbenchImporter.java`](src/main/java/com/leaf/game/anim/BlockbenchImporter.java) | Parses `.bbmodel` files from Blockbench and populates those arrays at load time |
| [`ModelRenderer.java`](src/main/java/com/leaf/game/anim/ModelRenderer.java) | Walks the part array in hierarchy order to compute final world-space transforms |

Enemy models (Golem, Slime) are all animated using this system. Each live enemy owns its own `AnimPlayer` instance so they animate independently.

---

### Source Code Quality

| Requirement | Status |
|---|---|
| All classes capitalised | Yes — every class follows PascalCase |
| Class-level Javadoc (`/** */` comments) | Yes — all major classes have API descriptions |
| Public method Javadoc (`@param`, `@return`) | Yes — all public methods documented |
| Good variable / method / class names | Yes — descriptive names throughout |
| Arrays used correctly | Yes — keyframe arrays, part arrays, enemy lists, hotbar array |
| Organised and correctly formatted | Yes — consistent indentation, grouped sections with divider comments |
| Comments on complex code | Yes — non-obvious logic (A\* pathfinding, FABRIK IK, star coordinate transforms) is explained inline |

**Key classes to sample for marking:**

- [`AnimPlayer.java`](src/main/java/com/leaf/game/anim/AnimPlayer.java) — clean array iteration and interpolation
- [`EnemyManager.java`](src/main/java/com/leaf/game/entity/EnemyManager.java) — wave spawning with clear Javadoc
- [`Progression.java`](src/main/java/com/leaf/game/core/Progression.java) — ability unlock schedule, well-commented enum
- [`TutorialManager.java`](src/main/java/com/leaf/game/core/TutorialManager.java) — multi-step tutorial logic with step-by-step documentation

---

## Developer Cheat Codes

Want to see the whole arsenal without playing all 11 waves? `/showcase` unlocks everything.

| Key / Command | What it does |
|---|---|
| `/showcase` (chat) | **Unlock everything** — every ability + all weapons in slots 1-5, god-mode on |
| `/showcase combat` (chat) | Spawn 7 enemies in a ring around you |
| `/showcase horde` (chat) | Spawn 14 enemies |
| `/showcase volcanic` (chat) | Warp to volcanic biome + erect Inferno Tower |
| `/showcase sakura` (chat) | Warp to cherry-blossom grove |
| `/showcase off` (chat) | Turn demo mode off |
| `/biome <name>` (chat) | Warp to any biome (forest, desert, crystal, autumn, mushroom, volcanic…) — handy to jump straight to a Voyage beacon |
| `/skip` (chat) | Skip the tutorial → the Voyage begins immediately |
| `F3` | Open debug overlay (FPS, player coords, time scale) |
| `P` | Spawn a basic enemy at your crosshair |
| `0` (zero) | Spawn a boss-tier Guardian Golem |

**The god-tier weapons (instant via `/showcase`, or forged on the Voyage):**

| Slot / Key | Weapon | How to use |
|---|---|---|
| Hotbar slot 1 | **Gatling Gun** (Crystal Fields) | hold Left Click to rapid-fire |
| Hotbar slot 2 | **Sniper** (start) | hold RMB → charge, release → crystal bolt explosion |
| Hotbar slot 3 / `F7` | **Orbital Annihilation** (Ashlands tower) | RMB → volumetric cinematic strike |
| Hotbar slot 4 / `F8` | **The World / Time Stop** (all shards) | RMB → freeze all enemies |
| Hotbar slot 5 / `I` | **Stone Cannon** (Floating Isles) | hold RMB near stone → absorb → boulder |
| `F10` | **Radar Sweep** (Glowing Groves) | ping every enemy through walls |
| `R` / `Y` | Slow / speed up time | (Time Dilation) |
| `'` (apostrophe) | **Deprivation Domain** — golden hemisphere auto-slasher | |
| `,` (comma) | **Quantum Bullet** — phase-shift projectile through walls | |
| `/god` (chat) | Toggle invincibility |
| `/give all` (chat) | Fill hotbar with all building blocks |
| `/spawn spider` (chat) | Spawn a procedural IK spider enemy |
| `/spawn treant` (chat) | Spawn a Treant (disguised tree enemy) |

---

## Full Ability Reference

### Combat & Offensive

| Ability | Key | Description |
|---|---|---|
| Sniper | `C` / RMB | Hold to charge a crystal bolt; longer charge = bigger explosion |
| Runic Cleave (Slash) | `F` | Wide melee swing that shatters blocks in a 3D crescent |
| Gatling Gun | Hotbar slot 5 | Hold Left Click to rapid-fire and vaporise terrain |
| Lightning | `U` | Strike your target; double-tap for AoE; chains between enemies in water |
| Stone Canon | `I` | Stand near stone, hold to absorb it into a boulder, release to fire |
| Grab & Slam | `O` | Grab an enemy overhead and slam them into the ground |
| Knife Combo | `;` | Three rapid high-damage melee slashes |

### Movement & Mobility

| Ability | Key | Description |
|---|---|---|
| Flight | Double-tap `Space` | Take to the skies; press `V` to cycle Skim / Soar / Grapple modes |
| Dash | `Q` | Instant burst in your movement direction |
| Blink | `E` | Teleport up to 22 blocks to your crosshair |
| Cannonball | Hold `G` | Charge up, preview your trajectory arc, then launch yourself as a projectile |
| Stone Pillar | `K` | Erupt a stone spire beneath your feet and launch skyward |
| Ground Smash | `Shift` (in air) | Slam into the earth creating a crater; bigger from higher falls |

### Tactical & Utility

| Ability | Key | Description |
|---|---|---|
| Position Swap | `J` | Instantly swap places with the nearest visible enemy |
| Minato's Seal | `H` / `B` / `N` | Throw a warp anchor (`H`), teleport to it (`B`), recall it (`N`) |
| Kamui | `Z` | Phase into another dimension — invincible; hold Left Click to vortex-suck enemies |
| Paper Substitute | Hold `V` | Prime a decoy; the next hit is absorbed, you blink back, decoy explodes |
| Quagmire | `M` | Fire a mud wave along the ground that traps enemies |
| Time Dilation | `R` / `Y` | Slow time (`R`) or speed it up (`Y`) |
| Heal | `L` | Channel healing over time (you cannot move while channelling) |
| Manhattan Transfer | `X` / `Tab` | Deploy a combat drone that auto-fires; `Tab` to pilot it yourself |

---

## Architecture Overview

The game is built on a **custom 3D engine** (teacher-approved):

| Layer | Key classes |
|---|---|
| Engine core | `Window.java` — game loop, input routing, render orchestration |
| Voxel world | `World.java`, `Chunk.java`, `WorldGen.java` — procedural generation + streaming |
| Player | `Player.java` + 7 sub-controllers (`AbilityController`, `AttackController`, `FlightController`, `StandController`, `SealController`, `LightningController`, `GrabController`) |
| Enemies | `Enemy.java`, `EnemyManager.java` — AI state machine, A\* pathfinding, wave spawning |
| Spider | `SpiderEnemy.java` + FABRIK IK system — procedural leg placement on any surface |
| Skeletal animation | `AnimPlayer.java`, `AnimModel.java`, `AnimClip.java` — array-based keyframe interpolation |
| Rendering | `Shader.java`, `ModelRenderer.java`, `ChunkMesher.java`, `BlockTextureAtlas.java` |
| Audio | `AudioManager.java` — OpenAL 3D spatial audio with EFX reverb |
| Sky / Astronomy | `DayNight.java`, `Astronomy.java`, `CelestialNav.java` — real BSC5 star catalogue |
| Progression | `Progression.java`, `RunRecords.java` — unlock system + persistent high-score records |
| HUD / UI | `WindowHud.java`, `CutsceneManager.java`, `TutorialManager.java`, `BackpackUI.java` |

---
## Credits

- **Engine:** Java 17 + LWJGL 3 (OpenGL 3.3)
- **Math:** JOML (Java OpenGL Math Library)
- **UI:** Dear ImGui (via imgui-java)
- **Audio:** OpenAL + Java Sound SPI
- **3D Models & Animations:** Blockbench (`.bbmodel` → custom JSON pipeline)
- **Star data:** Yale Bright Star Catalogue (BSC5)
- **Procedural Spider Kinematics:** Inverse Kinematics (IK) and leg-gait algorithm principles adapted from [TheCymaera's Minecraft Spider](https://github.com/TheCymaera/minecraft-spider)
- **Canyon Generation:** Sedimentary mesa noise algorithms adapted from [GelamiSalami's Hybrid SDF-Voxel Traversal](https://www.shadertoy.com/view/dtVSzw)
- **Mountain Generation:** Analytical derivative FBM and terrain erosion algorithms adapted from [Inigo Quilez's Elevated](https://www.shadertoy.com/view/MdX3Rr)