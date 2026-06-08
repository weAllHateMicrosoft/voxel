package com.leaf.game.core;

import com.leaf.game.world.Block;
import imgui.ImGui;
import static org.lwjgl.glfw.GLFW.*;

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
                boolean isTool = (b == Block.GATLING_GUN || b == Block.TORCH || b == Block.TELESCOPE || b == Block.GRAPPLING_HOOK);

                // Show block if player has a quantity of it, or if it is a tool
                if (count > 0 || isTool) {
                    String label = b.name() + (isTool ? " (Tool)" : " (" + count + ")");

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
}