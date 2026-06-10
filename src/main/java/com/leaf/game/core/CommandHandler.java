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

            default:
                win.chatHistory.add("[System]: Unknown command. Type '/help' for options.");
                break;
        }
    }
}