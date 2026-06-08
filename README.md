# DESCENT

**Survive. Learn. Escape.**

**DESCENT** is a 3D high-mobility voxel survival game. You awaken in a procedurally generated world with a mysterious Chakra Crystal. As you survive escalating waves of enemies, the crystal bonds with you, unlocking devastating anime-inspired combat abilities, time-manipulation, and high-speed parkour mechanics.

### 🎮 How to Get Started (Gameplay)
The game features a seamless, built-in tutorial to get you started! Just run the game and click **PLAY**.

**Basic Controls:**
* **Movement:** `W` `A` `S` `D`
* **Jump:** `Space`
* **Sprint:** Double-tap `W`
* **Look:** Mouse
* **Mine Block:** Hold `Left Click`
* **Place Block:** `Right Click` (Select blocks with `1`-`9`)

*Note: The game will teach you your abilities organically as you clear waves. If you ever forget your controls, press **[F1]** in-game to open the Master Ability Guide!*

---

### 📝 Teacher Notes & Grading Guide
*This game features a custom 3D engine built using LWJGL and OpenGL, extending far beyond standard 2D frameworks.*

To help you grade the project quickly without having to grind through all 10 waves of enemies, we have included a suite of **Developer Cheat Codes**.

**General Debug & Grading:**
* `F3` - Opens the Debug Menu (shows coordinates, FPS, and Time Scale).
* `F9` - **Wave Skip:** Instantly kills all enemies and clears the current wave so you can quickly unlock the next ability and see the between-wave UI.
* `P` - Instantly spawn a test enemy at your crosshair.
* `0` (Zero) - Spawn a boss-level Guardian Golem.

**SAbilities :**
We implemented several highly complex "Ultimate" abilities for extra technical demonstration. You can trigger these at any time:
* `F7` - **Orbital Annihilation:** Aim at a block and press F7 to trigger a massive 3D cinematic volumetric laser strike.
* `F8` - **The World (Time Stop):** Freezes all enemies, inverts screen colors, and halts time physics.
* `F10` - **Radar Sweep:** Projects a 3D radar scope onto the world geometry to scan for enemies through walls.
* `.` (Period) - **Chocolate Disco:** Spawns a 9x9 interactive hologram grid. Use Left-Click to mark squares, and press `.` again to detonate them.
* `'` (Apostrophe) - **Deprivation Domain:** Locks the player in place and creates a golden hemisphere. Any enemy that enters is instantly auto-slashed.
* `,` (Comma) - **Quantum Bullet:** Fires a projectile that ripples the screen space and phases through solid walls.
* 
# DESCENT - Complete Ability Guide

A breakdown of every ability currently implemented in **DESCENT**, mapped to their default keybindings.

---

## ⚔️ Combat & Offensive

* **Void Shard (Snipe) (`[C]`)** - Hold to charge and fire an explosive crystal bolt. Longer hold increases damage and blast radius.
* **Runic Cleave (Slash) (`[F]`)** - A wide melee swing that shatters blocks in a 3D crescent and knocks back enemies.
* **Gatling Gun (`[Hotbar]`)** - Hold Left-Click to rapidly shred through enemies and vaporize terrain.
* **Lightning (`[U]`)** - Call down a lightning strike on your target. Double-tap `[U]` for an AoE blast. Automatically chains between enemies standing in water.
* **Stone Canon (`[I]`)** - Stand near stone and hold to absorb blocks into a massive boulder, then launch it.
* **Grab & Slam (`[O]`)** - Grab a nearby enemy, hoist them overhead, and slam them into the ground to create an impact crater.
* **Knife Combo (`[;]`)** - Three rapid, high-damage melee slashes (does not destroy terrain).

---

## 🏃 Movement & Mobility

* **Flight (`[Double Space]`)** - Take to the skies. Press `[V]` to cycle between Skim (low-altitude glide), Soar (3D free-flight), and Grapple (hook-and-swing).
* **Dash (`[Q]`)** - Instant horizontal burst in your movement direction, leaving a trail of fading ghost duplicates.
* **Blink (`[E]`)** - Teleport along a straight, fast-travel path directly to your crosshair (up to 22 blocks).
* **Cannonball (`[Hold G]`)** - Charge up, view your ballistic trajectory, and launch yourself as a highly destructive projectile.
* **Stone Pillar (`[K]`)** - Erupt a stone spire beneath your feet, launching yourself high into the air.
* **Ground Smash (`[Shift in air]`)** - Slam down to the earth, creating a massive crater and shockwave scaled by your fall height.

---

## 🌀 Tactical & Utility

* **Position Swap (`[J]`)** - Instantly swap places with the nearest visible enemy.
* **Minato's Seal (`[H] / [B] / [N]`)** - Press `[H]` to throw an anchor seal, `[B]` to warp to it, and `[N]` to reclaim it to your inventory.
* **Kamui (`[Z]`)** - Phase into another dimension to become completely invincible. Hold Left-Click to charge a vortex and suck enemies into the void.
* **Paper Substitute (`[Hold V]`)** - Hold to prime. The next hit you take is negated, blinking you backward and leaving behind an exploding decoy.
* **Quagmire (`[M]`)** - Fire a wave of mud along the ground that traps enemies and stamps out

### 🏆 Features Included from Rubric
* **Title/End Screens:** Implemented via ImGui overlays (`WindowHud.java` and `CutsceneManager.java`).
* **Tutorial/Intuitive UI:** Implemented via `TutorialManager.java` which guides the player step-by-step upon spawning.
* **Motivation/Progression:** Endless wave spawner (`EnemyManager.java`) that introduces harder enemies (Golems) over time, rewarding the player with new abilities via a pop-up card system.
* **Array Animation:** Skeletal hierarchical animation using arrays/matrices parsed from Blockbench (`AnimPlayer.java`).
* **Sound Effects:** 3D spatial audio system utilizing OpenAL (`AudioManager.java`).

### 📚 Credits & Assets
* Engine built using Java, LWJGL 3, and JOML (Java OpenGL Math Library).
* UI powered by ImGui.
* Audio decoded via Java Sound SPI and managed via OpenAL.
* 3D Models animated via Blockbench.