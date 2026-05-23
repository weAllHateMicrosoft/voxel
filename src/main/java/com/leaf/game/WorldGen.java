package com.leaf.game;
import java.util.Random;


public class WorldGen {
    private Noise continentalness;  // large scale — ocean vs land
    private Noise erosion;          // medium scale — flat vs jagged
    private Noise peaksValleys;     // fine scale — mountain peaks


    public WorldGen(long seed){
        this.continentalness = new Noise(seed);
        this.erosion         = new Noise(seed + 1000L);
        this.peaksValleys    = new Noise(seed + 2000L);
    }

    public WorldGen(){
       this(System.currentTimeMillis());
    }

    public void generateChunk(Chunk chunk){
        //Convert chunk coorindate to world coorindate
        int worldX = chunk.cx * Chunk.SIZE;
        int worldZ = chunk.cz * Chunk.SIZE;

        //Iterate through every block in the chunk
        for (int lx = 0; lx < Chunk.SIZE; lx++){
            for (int lz = 0; lz < Chunk.SIZE; lz++){
                int wx = worldX + lx;
                int wz = worldZ + lz;

                float c  = continentalness.octave(wx * GameConfig.contFreq, wz * GameConfig.contFreq,
                        GameConfig.contOctaves,  GameConfig.contPersist);
                float e  = erosion.octave        (wx * GameConfig.erosFreq, wz * GameConfig.erosFreq,
                        GameConfig.erosOctaves,  GameConfig.erosPersist);
                float pv = peaksValleys.octave   (wx * GameConfig.pvFreq,   wz * GameConfig.pvFreq,
                        GameConfig.pvOctaves,    GameConfig.pvPersist);

                // Apply spline to continentalness → base height shape
                float baseHeight = continentalnessSpline(c);

                // Erosion reduces height variation: high erosion = flat, low erosion = jagged
                // Normalize erosion from [-1,1] to [0,1], use it to flatten
                float erosionFactor = 1.0f - ((e + 1.0f) / 2.0f);  // 0=flat, 1=jagged

                // Peaks & valleys adds fine detail on top
                float detail = (pv + 1.0f) / 2.0f;  // normalize to [0,1]

                // Combine: base shape + detail scaled by how jagged this region should be
                float finalHeight = baseHeight + (detail * 0.2f * erosionFactor);
                finalHeight = Math.max(0, Math.min(1, finalHeight));

                int surfaceY = GameConfig.heightBase + (int)(finalHeight * GameConfig.heightRange);
                for(int ly = 0; ly < Chunk.HEIGHT; ly++){
                    chunk.setBlock(lx, ly, lz, pickBlock(ly,surfaceY));
                }
            }
        }
        chunk.dirty = true;
    }

    private Block pickBlock(int y, int surfaceY){
        if (y < surfaceY)  return Block.STONE;
        if (y == surfaceY) return Block.GRASS;
        return Block.AIR;
    }

    // Remap a value from input range [inMin, inMax] to output range [outMin, outMax]
    private float remap(float val, float inMin, float inMax, float outMin, float outMax) {
        float t = (val - inMin) / (inMax - inMin);  // where are we in the input range?
        t = Math.max(0, Math.min(1, t));             // clamp to [0,1]
        return outMin + t * (outMax - outMin);
    }

    // Apply a spline to a continentalness value [-1, 1]
    // Returns a height contribution [0, 1]
    private float continentalnessSpline(float c) {
        // Ocean:  c in [-1.0, -0.2] → height stays very low (0.0 to 0.1)
        if (c < -0.2f) return remap(c, -1.0f, -0.2f, 0.0f, 0.1f);
        // Beach:  c in [-0.2, 0.0] → flat, barely above sea level
        if (c < 0.0f)  return remap(c, -0.2f,  0.0f, 0.1f, 0.25f);
        // Plains: c in [0.0,  0.4] → gentle rise
        if (c < 0.4f)  return remap(c,  0.0f,  0.4f, 0.25f, 0.35f);
        // CLIFF:  c in [0.4,  0.5] → dramatic jump! flat → mountain
        if (c < 0.5f)  return remap(c,  0.4f,  0.5f, 0.35f, 0.85f);
        // Peak:   c in [0.5,  1.0] → high plateau
        return remap(c, 0.5f, 1.0f, 0.85f, 1.0f);
    }

    public void resetSeed(long seed) {
        // Re-create all three noise objects with the new seed
        // (you'll need to make the noise fields non-final for this)
        this.continentalness = new Noise(seed);
        this.erosion         = new Noise(seed + 1000L);
        this.peaksValleys    = new Noise(seed + 2000L);
    }
}