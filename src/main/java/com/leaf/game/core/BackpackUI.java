package com.leaf.game.core;

import com.leaf.game.world.Block;
import imgui.ImGui;
import static org.lwjgl.glfw.GLFW.*;

/**
 * BackpackUI — an ImGui-powered in-game inventory window.
 *
 * <p>Toggled with {@code Left Alt}.  While the backpack is open the mouse cursor
 * is freed and the player cannot move or use abilities.  Displays the player's
 * collected blocks so they can be inspected before placing in the hotbar.
 *
 * <p>Rendering is driven by {@link #render(float, float)} inside the ImGui pass
 * in {@link Window}; toggling is driven by the GLFW key callback via {@link #toggle()}.
 */
public class BackpackUI {

    private final Window win;
    public boolean isOpen = false;

    public BackpackUI(Window window) {
        this.win = window;
    }

    /**
     * Triggered directly by the GLFW Event Queue.
     * Guarantees instantaneous, lag-free response on macOS.
     */
    public void toggle() {
        if (!win.showChat && !win.isPaused && !win.showHelp) {
            isOpen = !isOpen;
            // Free or lock cursor depending on state
            glfwSetInputMode(win.window, GLFW_CURSOR, isOpen ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
        }
    }

    /** Called inside the ImGui render loop. */
    public void render(float w, float h) {
        if (!isOpen) return;

        ImGui.setNextWindowPos(w / 2f - 250f, h / 2f - 200f, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(500f, 400f);

        if (ImGui.begin("Your Backpack / Block Selector", imgui.flag.ImGuiWindowFlags.NoCollapse)) {
            ImGui.textColored(1.0f, 0.85f, 0.3f, 1.0f, "Select a block to equip to your Hotbar:");
            ImGui.separator();
            ImGui.spacing();

            // Render blocks in a clean 3-column grid
            ImGui.columns(3, "backpack_grid", true);

            for (Block b : Block.values()) {
                if (b == Block.AIR) continue;

                int count = win.inventory.getCount(b);
                // Always-available utility tools.
                boolean alwaysTool = (b == Block.TORCH || b == Block.TELESCOPE || b == Block.GRAPPLING_HOOK);
                // Earned weapons — only appear once the player actually owns one (granted
                // on ability unlock), so the hotbar never spoils a future surprise.
                boolean weapon = (b == Block.GATLING_GUN || b == Block.WPN_SNIPER
                        || b == Block.WPN_ORBITAL || b == Block.WPN_TIMESTOP || b == Block.WPN_STONE_CANNON);
                boolean isTool = alwaysTool || (weapon && count > 0);

                // Show block if player has a quantity of it, or if it is an always-on tool.
                if (count > 0 || alwaysTool) {
                    String label = prettyName(b) + (isTool ? " (Weapon)" : " (" + count + ")");

                    if (ImGui.button(label, -1f, 32f)) {
                        ImGui.openPopup("equip_popup_" + b.name());
                    }

                    // Assignment Popup
                    if (ImGui.beginPopup("equip_popup_" + b.name())) {
                        ImGui.text("Equip " + b.name() + " to which Hotbar Slot?");
                        ImGui.separator();

                        for (int slot = 0; slot < 9; slot++) {
                            Block currentInSlot = win.hotbar[slot];
                            String slotLabel = "Slot " + (slot + 1) + ": " + (currentInSlot == Block.AIR ? "Empty" : currentInSlot.name());

                            if (ImGui.button(slotLabel, 240f, 22f)) {
                                win.hotbar[slot] = b;
                                ImGui.closeCurrentPopup();
                                isOpen = false; // Close backpack
                                glfwSetInputMode(win.window, GLFW_CURSOR, GLFW_CURSOR_DISABLED); // Restore game focus
                            }
                        }
                        ImGui.endPopup();
                    }
                    ImGui.nextColumn();
                }
            }

            ImGui.columns(1);
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            if (ImGui.button("Close Backpack", -1f, 35f)) {
                isOpen = false;
                glfwSetInputMode(win.window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
        }
        ImGui.end();
    }

    /** Human-readable label for a block/weapon (avoids raw enum names like WPN_SNIPER). */
    private static String prettyName(Block b) {
        return switch (b) {
            case WPN_SNIPER      -> "Sniper";
            case WPN_ORBITAL     -> "Orbital Annihilation";
            case WPN_TIMESTOP    -> "Time Domain";
            case WPN_STONE_CANNON-> "Stone Cannon";
            case GATLING_GUN     -> "Gatling Gun";
            case GRAPPLING_HOOK  -> "Grappling Hook";
            case TELESCOPE       -> "Telescope";
            case TORCH           -> "Torch";
            default -> {
                String s = b.name().toLowerCase().replace('_', ' ');
                yield Character.toUpperCase(s.charAt(0)) + s.substring(1);
            }
        };
    }
}