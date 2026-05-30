// --- FILE: src/main/java/com/leaf/game/render/BlockTextureAtlas.java ---
package com.leaf.game.render;

import com.leaf.game.world.Block;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL33.*;

public class BlockTextureAtlas {

    public static final int TILE_SIZE  = 16;
    public static final int ATLAS_COLS = 16;  // tiles per atlas row

    private static final int[][] CROSS = {
            {1, 0}, {1, 2}, {1, 1}, {1, 3}, {2, 1}, {0, 1}
    };

    private static int     textureId = 0;
    private static boolean loaded    = false;

    private static final Map<String, float[][]> faceUVs = new HashMap<>();
    private static float[] WHITE_UV;

    public static void load() {
        System.out.println("[Atlas] Starting atlas compilation...");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Block b : Block.values()) {
            if (b.texName != null) names.add(b.texName);
        }

        int totalSlots = 1 + names.size() * 6;
        int atlasRows  = (totalSlots + ATLAS_COLS - 1) / ATLAS_COLS;
        int atlasW     = ATLAS_COLS * TILE_SIZE;
        int atlasH     = atlasRows  * TILE_SIZE;
        byte[] atlas   = new byte[atlasW * atlasH * 4];

        System.out.println("[Atlas] Allocated RAM buffer: " + atlasW + "x" + atlasH);

        blitTile(atlas, atlasW, fillWhite(), 0, 0);
        float tileW = (float) TILE_SIZE / atlasW;
        float tileH = (float) TILE_SIZE / atlasH;
        WHITE_UV = uvFor(0, tileW, tileH);

        int slot = 1;
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            byte[][] faces = loadFaces(name, missing);
            float[][] uvTable = new float[6][];
            for (int f = 0; f < 6; f++) {
                int col = slot % ATLAS_COLS;
                int row = slot / ATLAS_COLS;
                blitTile(atlas, atlasW, faces[f], col, row);
                uvTable[f] = uvFor(slot, tileW, tileH);
                slot++;
            }
            faceUVs.put(name, uvTable);
        }

        if (!missing.isEmpty()) {
            System.out.println("[Atlas] Missing (flat colour fallback): " + missing);
        }

        System.out.println("[Atlas] Uploading to OpenGL...");
        ByteBuffer buf = BufferUtils.createByteBuffer(atlas.length);
        buf.put(atlas).flip();

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);

        // Use proper unpacking alignment just in case
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasW, atlasH,
                0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glBindTexture(GL_TEXTURE_2D, 0);

        loaded = true;
        System.out.println("[Atlas] Built " + atlasW + "x" + atlasH
                + " with " + names.size() + " block(s), " + slot + " tiles total.");
    }

    public static float[] getUV(String texName, int faceIndex) {
        if (texName == null) return WHITE_UV;
        float[][] table = faceUVs.get(texName);
        if (table == null) return WHITE_UV;
        return table[faceIndex];
    }

    public static boolean isLoaded()     { return loaded;    }
    public static int     getTextureId() { return textureId; }

    public static void cleanup() {
        if (loaded) { glDeleteTextures(textureId); loaded = false; }
        faceUVs.clear();
    }

    private static float[] uvFor(int slot, float tileW, float tileH) {
        float uMin = (slot % ATLAS_COLS) * tileW;
        float vMin = (slot / ATLAS_COLS) * tileH;
        return new float[]{ uMin, vMin, uMin + tileW, vMin + tileH };
    }

    private static byte[] fillWhite() {
        byte[] w = new byte[TILE_SIZE * TILE_SIZE * 4];
        Arrays.fill(w, (byte) 0xFF);
        return w;
    }

    private static byte[][] loadFaces(String name, List<String> missing) {
        System.out.println("[Atlas] Loading PNG: " + name + ".png");
        InputStream is = BlockTextureAtlas.class
                .getResourceAsStream("/textures/blocks/" + name + ".png");
        if (is == null) {
            missing.add(name);
            return sixWhite();
        }
        try {
            byte[]     raw  = is.readAllBytes();
            ByteBuffer ibuf = BufferUtils.createByteBuffer(raw.length);
            ibuf.put(raw).flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1),
                        c = stack.mallocInt(1);
                STBImage.stbi_set_flip_vertically_on_load(false);
                ByteBuffer px = STBImage.stbi_load_from_memory(ibuf, w, h, c, 4);

                if (px == null) {
                    String reason = STBImage.stbi_failure_reason();
                    System.err.println("[Atlas] Decode failed '" + name + "': " + (reason != null ? reason : "Unknown error"));
                    missing.add(name);
                    return sixWhite();
                }

                byte[] src = new byte[w.get(0) * h.get(0) * 4];
                px.get(src);
                STBImage.stbi_image_free(px);

                int iw = w.get(0), ih = h.get(0);
                System.out.println("[Atlas] Decoded " + name + " (" + iw + "x" + ih + ")");

                if (iw == TILE_SIZE && ih == TILE_SIZE) {
                    byte[][] faces = new byte[6][];
                    Arrays.fill(faces, src);
                    return faces;
                } else if (iw == TILE_SIZE * 3 && ih == TILE_SIZE * 4) {
                    byte[][] faces = new byte[6][];
                    for (int f = 0; f < 6; f++)
                        faces[f] = extractTile(src, iw, CROSS[f][0], CROSS[f][1]);
                    return faces;
                } else {
                    System.err.println("[Atlas] '" + name + "' is " + iw + "x" + ih
                            + " — expected 48x64 or 16x16. Using flat colour fallback.");
                    missing.add(name);
                    return sixWhite();
                }
            }
        } catch (Exception e) {
            System.err.println("[Atlas] Exception '" + name + "': " + e.getMessage());
            missing.add(name);
            return sixWhite();
        }
    }

    private static byte[] extractTile(byte[] src, int srcW, int col, int row) {
        byte[] tile = new byte[TILE_SIZE * TILE_SIZE * 4];
        for (int ty = 0; ty < TILE_SIZE; ty++) {
            for (int tx = 0; tx < TILE_SIZE; tx++) {
                int sx  = col * TILE_SIZE + tx;
                int sy  = row * TILE_SIZE + ty;
                int si  = (sy * srcW + sx) * 4;
                int di  = (ty * TILE_SIZE + tx) * 4;
                tile[di]     = src[si];
                tile[di + 1] = src[si + 1];
                tile[di + 2] = src[si + 2];
                tile[di + 3] = src[si + 3];
            }
        }
        return tile;
    }

    private static void blitTile(byte[] atlas, int atlasW,
                                 byte[] tile, int col, int row) {
        for (int ty = 0; ty < TILE_SIZE; ty++) {
            for (int tx = 0; tx < TILE_SIZE; tx++) {
                int si  = (ty * TILE_SIZE + tx) * 4;
                int di  = ((row * TILE_SIZE + ty) * atlasW + col * TILE_SIZE + tx) * 4;
                atlas[di]     = tile[si];
                atlas[di + 1] = tile[si + 1];
                atlas[di + 2] = tile[si + 2];
                atlas[di + 3] = tile[si + 3];
            }
        }
    }

    private static byte[][] sixWhite() {
        byte[][] w = new byte[6][];
        Arrays.fill(w, fillWhite());
        return w;
    }
}