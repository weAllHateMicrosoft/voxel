package com.leaf.game.entity;

import com.leaf.game.world.Block;

import java.util.HashMap;
import java.util.Map;

public class Inventory {

    // Maps block type to how many the player has
    private final Map<Block, Integer> items = new HashMap<>();

    // How many blocks the player starts with. There's no pickaxe, so the player
    // relies on these for cover/barricades during waves — start them generously.
    public Inventory() {
        items.put(Block.TELESCOPE, 1);
        items.put(Block.GRAPPLING_HOOK, 1);
        items.put(Block.CRYSTAL_AMETHYST, 256);
        items.put(Block.MEGALITH,  256);
        items.put(Block.ANCIENT_MARROW, 256);
        items.put(Block.ICE, 256);
        items.put(Block.CRYSTAL_CITRINE, 256);
        items.put(Block.CRYSTAL_ROSE, 256);
        items.put(Block.CRATER_BLOOM, 256);
        // Mesa / canyon blocks — warm + blue palettes
        items.put(Block.MESA_GRASS,       64);
        items.put(Block.MESA_CLAY,        64);
        items.put(Block.MESA_TERRACOTTA,  64);
        items.put(Block.MESA_SAND,        64);
        items.put(Block.MESA_STONE,       64);
        items.put(Block.MESA_BLUE_SNOW,   64);
        items.put(Block.MESA_BLUE_MID,    64);
        items.put(Block.MESA_BLUE_LIGHT,  64);
        items.put(Block.MESA_BLUE_DARK,   64);
        items.put(Block.MESA_BLUE_STONE,  64);
    }

    /** Add one block to the inventory. Called when a block is broken. */
    public void addBlock(Block block) {
        if (block == Block.AIR) return;
        items.merge(block, 1, Integer::sum);
    }

    /**
     * Remove one block from the inventory.
     * Returns true if the player had it and it was removed.
     * Returns false if the player is out.
     */
    public boolean useBlock(Block block) {
        int count = items.getOrDefault(block, 0);
        if (count <= 0) return false;
        if (count == 1) items.remove(block);
        else items.put(block, count - 1);
        return true;
    }

    public int getCount(Block block) {
        return items.getOrDefault(block, 0);
    }

    /** Returns a formatted string for the HUD display. */
    public String getHUDText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Block, Integer> e : items.entrySet()) {
            if (e.getValue() > 0) {
                sb.append(e.getKey().name())
                        .append(": ")
                        .append(e.getValue())
                        .append("  ");
            }
        }
        return sb.toString();
    }

    /** Add a specific quantity of a block to the inventory. */
    public void addBlockAmount(Block block, int amount) {
        if (block == Block.AIR || amount <= 0) return;
        items.merge(block, amount, Integer::sum);
    }
}