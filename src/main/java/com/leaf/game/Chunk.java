package com.leaf.game;

public class Chunk {
    public static final int SIZE = 16;
    public static final int HEIGHT = 64;

    public Mesh mesh;       // the rendered geometry for this chunk
    public boolean dirty;   // true = mesh needs rebuilding

    public final int cx, cz; //chunk cooridnates

    //All blocks in the chunk: 16 x 16 x 64
    private final Block[][][] blocks = new Block[SIZE][HEIGHT][SIZE];

    public Chunk (int cx, int cz){
        this.cx = cx;
        this.cz = cz;
        //Fil in AIR by default
        for (Block[][] yz : blocks)
            for (Block[] z : yz)
                java.util.Arrays.fill(z, Block.AIR);
    }

    public Block getBlock (int lx, int ly, int lz){
        return blocks[lx][ly][lz];
    }

    public void setBlock(int lx, int ly, int lz, Block b){
        blocks[lx][ly][lz] = b;
    }
}
