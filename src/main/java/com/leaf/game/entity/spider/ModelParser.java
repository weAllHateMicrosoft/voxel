package com.leaf.game.entity.spider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class ModelParser {

    /**
     * Converts raw Minecraft /summon block_display commands directly into
     * our DisplayModel structure while maintaining precise row-major to column-major mapping.
     */
    public static DisplayModel parseModelFromCommand(String command) {
        List<BlockDisplayModelPiece> pieces = new ArrayList<>();

        String prefix = "/summon block_display ~-0.5 ~ ~-0.5 ";
        String json = command;
        if (command.startsWith(prefix)) {
            json = command.substring(prefix.length());
        }

        // Strip float literal markers ('f') to produce valid JSON before parsing
        json = json.replaceAll("([0-9]*\\.*[0-9]+)f", "$1");

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        JsonArray passengers = parsed.getAsJsonArray("Passengers");

        for (int i = 0; i < passengers.size(); i++) {
            JsonObject passenger = passengers.get(i).getAsJsonObject();
            JsonObject blockState = passenger.getAsJsonObject("block_state");
            String blockName = blockState.get("Name").getAsString();

            if (blockName.startsWith("minecraft:")) {
                blockName = blockName.substring("minecraft:".length());
            }

            JsonArray transArr = passenger.getAsJsonArray("transformation");
            float[] t = new float[16];
            for (int j = 0; j < 16; j++) {
                t[j] = transArr.get(j).getAsFloat();
            }

            // Convert Minecraft row-major transform matrices into JOML column-major layout
            Matrix4f matrix = new Matrix4f(
                    t[0],  t[1],  t[2],  t[3],
                    t[4],  t[5],  t[6],  t[7],
                    t[8],  t[9],  t[10], t[11],
                    t[12], t[13], t[14], t[15]
            ).transpose();

            List<String> tags = new ArrayList<>();
            if (passenger.has("Tags")) {
                JsonArray tagsArr = passenger.getAsJsonArray("Tags");
                for (int j = 0; j < tagsArr.size(); j++) {
                    tags.add(tagsArr.get(j).getAsString());
                }
            }

            pieces.add(new BlockDisplayModelPiece(blockName, matrix, tags));
        }

        return new DisplayModel(pieces);
    }
}