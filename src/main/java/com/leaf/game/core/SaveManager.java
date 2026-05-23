// --- FILE: src/main/java/com/leaf/game/core/SaveManager.java ---
package com.leaf.game.core;

import com.leaf.game.entity.Inventory;
import com.leaf.game.entity.Player;
import com.leaf.game.world.Block;
import com.leaf.game.world.World;

import java.io.*;
import java.util.Map;
import java.util.HashMap;

public class SaveManager {

    private static final String SAVE_FILE = "savegame.txt";

    public static void saveGame(World world, Player player, Inventory inv) {
        try (PrintWriter out = new PrintWriter(new FileWriter(SAVE_FILE))) {
            // 1. Save Seed
            out.println("SEED:" + GameConfig.seed);

            // 2. Save Player State
            out.println("PLAYER:" + player.position.x + "," + player.position.y + "," + player.position.z + "," + player.health);

            // 3. Save Inventory
            out.print("INV:");
            for (Block b : Block.values()) {
                if (b != Block.AIR && inv.getCount(b) > 0) {
                    out.print(b.name() + "," + inv.getCount(b) + ";");
                }
            }
            out.println();

            // 4. Save World Modifications (placed/broken blocks)
            out.println("MODS_START");
            for (Map.Entry<Long, Map<Integer, Block>> chunkEntry : world.getModifiedBlocksMap().entrySet()) {
                long chunkKey = chunkEntry.getKey();
                for (Map.Entry<Integer, Block> blockEntry : chunkEntry.getValue().entrySet()) {
                    int localIdx = blockEntry.getKey();
                    Block b = blockEntry.getValue();
                    out.println(chunkKey + ":" + localIdx + ":" + b.name());
                }
            }
            out.println("MODS_END");

            System.out.println("Game Saved Successfully!");
        } catch (IOException e) {
            System.err.println("Failed to save game: " + e.getMessage());
        }
    }

    public static boolean loadGame(World world, Player player, Inventory inv) {
        File file = new File(SAVE_FILE);
        if (!file.exists()) return false;

        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("SEED:")) {
                    GameConfig.seed = Long.parseLong(line.substring(5));
                } else if (line.startsWith("PLAYER:")) {
                    String[] p = line.substring(7).split(",");
                    player.position.set(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                    player.health = Float.parseFloat(p[3]);
                    player.highestY = player.position.y;
                } else if (line.startsWith("INV:")) {
                    String[] items = line.substring(4).split(";");
                    for (String item : items) {
                        if (item.isBlank()) continue;
                        String[] parts = item.split(",");
                        Block b = Block.valueOf(parts[0]);
                        int count = Integer.parseInt(parts[1]);
                        for (int i = 0; i < count; i++) inv.addBlock(b);
                    }
                } else if (line.equals("MODS_START")) {
                    while (!(line = in.readLine()).equals("MODS_END")) {
                        String[] parts = line.split(":");
                        long chunkKey = Long.parseLong(parts[0]);
                        int localIdx = Integer.parseInt(parts[1]);
                        Block b = Block.valueOf(parts[2]);

                        world.getModifiedBlocksMap()
                                .computeIfAbsent(chunkKey, k -> new HashMap<>())
                                .put(localIdx, b);
                    }
                }
            }
            System.out.println("Game Loaded Successfully!");
            return true;
        } catch (Exception e) {
            System.err.println("Failed to load game: " + e.getMessage());
            return false;
        }
    }

    public static boolean saveExists() {
        return new File(SAVE_FILE).exists();
    }
}