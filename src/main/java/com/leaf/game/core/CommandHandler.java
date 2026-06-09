package com.leaf.game.core;

import com.leaf.game.entity.Enemy;
import com.leaf.game.world.Block;

public class CommandHandler {

    private final Window win;

    public CommandHandler(Window window) {
        this.win = window;
    }

    /** Executes a debug developer/sandbox chat command. */
    public void execute(String commandLine) {
        // Strip the '/' and trim spaces
        String clean = commandLine.substring(1).trim();
        String[] parts = clean.split("\\s+");
        if (parts.length == 0) return;

        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "help":
                win.chatHistory.add("[System] Commands:");
                win.chatHistory.add("  /skip - Skip onboarding AND all wave practices.");
                win.chatHistory.add("  /give <block_name> [amt] - Give specific block.");
                win.chatHistory.add("  /give all [amt] - Fill hotbar with all building blocks.");
                win.chatHistory.add("  /god - Toggle invincibility.");
                break;

            case "skip":
            case "skiptutorial":
                // 1. Skip core onboarding tutorial
                if (win.tutorial != null) {
                    win.tutorial.skip();
                }

                // 2. Clear out between-wave practice queues and cards
                win.practiceQueue.clear();
                win.practiceAbility = null;
                win.practiceSteps = null;
                win.practiceStepDone = false;
                win.practiceCelebration = 0f;
                win.showUnlockCard = false;

                // 3. Clean up active practice dummies and boot up the spawner
                if (win.enemyManager != null) {
                    win.enemyManager.getEnemies().removeIf(e -> e.type == Enemy.Type.DUMMY);
                    win.enemyManager.wavesEnabled = true;
                    win.enemyManager.beginNextWave(); // Force start next wave immediately
                }

                win.chatHistory.add("[System]: Entire onboarding & practice sequence bypassed! Endless waves are now active.");
                break;

            case "god":
                if (win.player != null) {
                    if (win.immunityTimer > 0f) {
                        win.immunityTimer = 0f;
                        win.chatHistory.add("[System]: Godmode DISABLED.");
                    } else {
                        win.immunityTimer = 99999f;
                        win.chatHistory.add("[System]: Godmode ENABLED.");
                    }
                }
                break;

            case "give":
                if (parts.length < 2) {
                    win.chatHistory.add("[System]: Usage: /give <block_name_or_all> [amount]");
                    break;
                }

                String target = parts[1].toLowerCase();
                int amount = 256;
                if (parts.length >= 3) {
                    try {
                        amount = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        win.chatHistory.add("[System]: Invalid amount. Defaulting to 256.");
                    }
                }

                if (target.equals("all")) {
                    for (Block b : Block.values()) {
                        if (b != Block.AIR && b != Block.GATLING_GUN && b != Block.TELESCOPE
                                && b != Block.TORCH && b != Block.GRAPPLING_HOOK) {
                            win.inventory.addBlockAmount(b, amount);
                            win.addBlockToHotbar(b);
                        }
                    }
                    win.chatHistory.add("[System]: Granted " + amount + " of all building blocks!");
                } else {
                    Block foundBlock = null;
                    String sanitizedTarget = target.replace("_", "").replace(" ", "");
                    for (Block b : Block.values()) {
                        String bName = b.name().toLowerCase().replace("_", "");
                        if (bName.equals(sanitizedTarget)) {
                            foundBlock = b;
                            break;
                        }
                    }

                    if (foundBlock != null) {
                        win.inventory.addBlockAmount(foundBlock, amount);
                        win.addBlockToHotbar(foundBlock);
                        win.chatHistory.add("[System]: Granted " + amount + "x " + foundBlock.name());
                    } else {
                        win.chatHistory.add("[System]: Block '" + parts[1] + "' not found.");
                    }
                }
                break;
            case "spider":
                if (parts.length < 2) {
                    win.chatHistory.add("[System]: Usage: /spider [spawn|laser|ride]");
                    win.chatHistory.add("          /spider spawn [legs: 4|6|8] [scale: float]");
                    break;
                }
                if (parts[1].equals("spawn")) {
                    int legs = 6;      // Default to 6 legs
                    float scale = 1.0f; // Default to 1.0 scale

                    if (parts.length >= 3) {
                        try {
                            legs = Integer.parseInt(parts[2]);
                            if (legs != 4 && legs != 6 && legs != 8) {
                                win.chatHistory.add("[System]: Leg options are only 4, 6, or 8. Defaulting to 6.");
                                legs = 6;
                            }
                        } catch (NumberFormatException e) {
                            win.chatHistory.add("[System]: Invalid legs format. Defaulting to 6.");
                        }
                    }
                    if (parts.length >= 4) {
                        try {
                            scale = Float.parseFloat(parts[3]);
                            scale = Math.max(0.3f, Math.min(4.0f, scale)); // Safe limits (0.3x to 4.0x)
                        } catch (NumberFormatException e) {
                            win.chatHistory.add("[System]: Invalid scale format. Defaulting to 1.0.");
                        }
                    }

                    Enemy e = win.enemyManager.spawnAt(win.player.position.x + 3, win.player.position.y + 1, win.player.position.z, Enemy.Type.SPIDER);
                    if (e instanceof com.leaf.game.entity.spider.SpiderEnemy) {
                        com.leaf.game.entity.spider.SpiderEnemy se = (com.leaf.game.entity.spider.SpiderEnemy) e;
                        se.customLegCount = legs;
                        se.customScale = scale;
                        se.mode = com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.IDLE;
                        win.chatHistory.add("[System]: Spawned a friendly spider (Legs: " + legs + ", Scale: " + scale + "x).");
                    }
                } else if (parts[1].equals("laser")) {
                    win.spiderLaserActive = !win.spiderLaserActive;
                    for (Enemy e : win.enemyManager.getEnemies()) {
                        if (e instanceof com.leaf.game.entity.spider.SpiderEnemy) {
                            com.leaf.game.entity.spider.SpiderEnemy se = (com.leaf.game.entity.spider.SpiderEnemy) e;
                            if (se.mode != com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.HOSTILE) {
                                se.mode = win.spiderLaserActive ?
                                        com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.FOLLOW_TARGET :
                                        com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.IDLE;
                            }
                        }
                    }
                    win.chatHistory.add("[System]: Spider laser pointer " + (win.spiderLaserActive ? "ON" : "OFF"));
                } else if (parts[1].equals("ride")) {
                    if (win.riddenSpider != null) {
                        win.riddenSpider.mode = com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.IDLE;
                        win.riddenSpider = null;
                        win.chatHistory.add("[System]: Dismounted spider.");
                    } else {
                        com.leaf.game.entity.spider.SpiderEnemy best = null;
                        float bestDist = 10f;
                        for (Enemy e : win.enemyManager.getEnemies()) {
                            if (e instanceof com.leaf.game.entity.spider.SpiderEnemy && e.alive) {
                                com.leaf.game.entity.spider.SpiderEnemy se = (com.leaf.game.entity.spider.SpiderEnemy) e;
                                if (se.mode != com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.HOSTILE) {
                                    float d = se.position.distance(win.player.position);
                                    if (d < bestDist) {
                                        bestDist = d;
                                        best = se;
                                    }
                                }
                            }
                        }
                        if (best != null) {
                            win.riddenSpider = best;
                            best.mode = com.leaf.game.entity.spider.SpiderEnemy.BehaviorMode.RIDDEN;
                            win.spiderLaserActive = false;
                            win.chatHistory.add("[System]: Mounted spider! Use WASD to steer.");
                        } else {
                            win.chatHistory.add("[System]: No friendly spider nearby to mount.");
                        }
                    }
                }
                break;

            default:
                win.chatHistory.add("[System]: Unknown command. Type '/help' for options.");
                break;
        }
    }
}