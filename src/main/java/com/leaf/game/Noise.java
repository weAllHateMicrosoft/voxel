package com.leaf.game;

import java.util.Random;

/**
 * 2D Perlin Noise generator.
 *
 * As the video explains: raw randomness gives you TV static (a block is height 50,
 * its neighbor is height 100). Perlin noise ensures neighbors are always close in
 * value — height 50 next to 51 — producing smooth, rolling hills.
 *
 * KEY CONCEPT: The permutation table is just a shuffled list of 0-255.
 * The seed controls the shuffle, so different seeds → different worlds.
 * All the "magic" is in using that table to produce smooth gradients.
 */
public class Noise {

    // The permutation table — shuffled by seed, doubled to avoid overflow when indexing.
    private final int[] perm = new int[512];

    // 2D gradient directions (8 possible directions a gradient can point)
    // Perlin noise works by dotting a random gradient with the distance to a corner.
    private static final int[][] GRAD2D = {
            { 1,  1}, {-1,  1}, { 1, -1}, {-1, -1},
            { 1,  0}, {-1,  0}, { 0,  1}, { 0, -1}
    };

    public Noise(long seed) {
        // Start with an ordered array [0, 1, 2, ..., 255]
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;

        // Shuffle it using the seed (Fisher-Yates shuffle)
        // This is what makes each seed produce a unique world.
        Random rng = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }

        // Double it so we never worry about bounds when indexing perm[x] + perm[z]
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    // --- CORE PERLIN NOISE ---

    /**
     * Returns a smooth noise value for position (x, z), in the range roughly [-1, 1].
     * Points close together will always have similar values — that's the whole point.
     */
    public float get(float x, float z) {
        // Find the integer grid cell this point falls into
        int xi = (int) Math.floor(x) & 255;
        int zi = (int) Math.floor(z) & 255;

        // Fractional position within the cell [0, 1]
        float xf = x - (float) Math.floor(x);
        float zf = z - (float) Math.floor(z);

        // Fade: smooth the fractional position so edges blend, not jump.
        // This is the "5t³(6t² - 15t + 10)" curve Ken Perlin invented.
        // It makes the interpolation look natural rather than blocky.
        float u = fade(xf);
        float v = fade(zf);

        // Look up gradient hashes for all 4 corners of the cell
        int aa = perm[perm[xi    ] + zi    ];
        int ab = perm[perm[xi    ] + zi + 1];
        int ba = perm[perm[xi + 1] + zi    ];
        int bb = perm[perm[xi + 1] + zi + 1];

        // Interpolate the 4 corner gradients
        float x1 = lerp(grad(aa, xf,     zf),     grad(ba, xf - 1,     zf), u);
        float x2 = lerp(grad(ab, xf,     zf - 1), grad(bb, xf - 1,     zf - 1), u);
        return lerp(x1, x2, v);
    }

    // --- OCTAVE NOISE (Technique 2 from the video) ---

    /**
     * Layered noise — this is what makes terrain look fractal and natural.
     *
     * Each "octave" adds a layer of noise at a higher frequency (more detail)
     * but lower amplitude (less influence):
     *   - Octave 1: huge mountains and valleys (low frequency, high amplitude)
     *   - Octave 2: hills on those mountains (medium)
     *   - Octave 3: bumps and rocks (high frequency, small amplitude)
     *
     * @param x, z      world position
     * @param octaves   how many layers (3–5 is typical)
     * @param persistence how much each octave's amplitude shrinks (0.5 = halves each time)
     */
    public float octave(float x, float z, int octaves, float persistence) {
        float value    = 0;
        float amplitude = 1;
        float frequency = 1;
        float maxValue  = 0; // for normalization

        for (int i = 0; i < octaves; i++) {
            value    += get(x * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence; // each layer has less influence
            frequency *= 2;           // each layer is more detailed
        }

        // Normalize back to [-1, 1]
        return value / maxValue;
    }

    // --- HELPERS ---

    /** Ken Perlin's smoothstep curve: makes interpolation look organic, not linear */
    private float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    private float grad(int hash, float x, float z) {
        int[] g = GRAD2D[hash & 7];
        return g[0] * x + g[1] * z;
    }
}