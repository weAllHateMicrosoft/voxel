package com.leaf.game.world;

import com.leaf.game.render.Mesh;

public class Chunk {
    public static final int SIZE = 16;
    public static final int HEIGHT = 512;

    public Mesh opaqueMesh;
    public Mesh transparentMesh;
    public boolean dirty;
    public boolean meshBuilt = false;

    /**
     * cx, cz — horizontal chunk coordinates (chunk units, not blocks).
     * cy — vertical chunk index: 0 = surface world (Y 0..511),
     *      -1 = first deep layer (Y -512..-1), -2 = second, etc.
     * worldY of a local y: worldY = cy * HEIGHT + localY
     */
    public final int cx, cy, cz;

    private final Block[][][] blocks = new Block[SIZE][HEIGHT][SIZE];
    private final byte[][][]  meta   = new byte[SIZE][HEIGHT][SIZE];

    public Chunk(int cx, int cy, int cz) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        for (Block[][] yz : blocks)
            for (Block[] z : yz)
                java.util.Arrays.fill(z, Block.AIR);
    }

    /** Convenience constructor for surface (cy=0) chunks. */
    public Chunk(int cx, int cz) { this(cx, 0, cz); }

    public Block getBlock(int lx, int ly, int lz) { return blocks[lx][ly][lz]; }
    public void setBlock(int lx, int ly, int lz, Block b) { blocks[lx][ly][lz] = b; }

    public byte getMeta(int lx, int ly, int lz) { return meta[lx][ly][lz]; }
    public void setMeta(int lx, int ly, int lz, byte m) { meta[lx][ly][lz] = m; }
}