package com.leaf.game;

public class Noise {

    // --- 2D GRADIENTS (8 directions) ---
    private static final int[][] GRAD2D = {
            { 1,  1}, {-1,  1}, { 1, -1}, {-1, -1},
            { 1,  0}, {-1,  0}, { 0,  1}, { 0, -1}
    };

    // --- 3D GRADIENTS (12 edge-midpoints of a cube — standard Perlin set) ---
    private static final int[][] GRAD3D = {
            { 1,  1,  0}, {-1,  1,  0}, { 1, -1,  0}, {-1, -1,  0},
            { 1,  0,  1}, {-1,  0,  1}, { 1,  0, -1}, {-1,  0, -1},
            { 0,  1,  1}, { 0, -1,  1}, { 0,  1, -1}, { 0, -1, -1}
    };

    private final int[] perm = new int[512];

    public Noise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;

        java.util.Random rng = new java.util.Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }

        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    // -------------------------------------------------------------------------
    // 2D NOISE  — returns roughly [-1, 1]
    // -------------------------------------------------------------------------

    public float get(float x, float z) {
        int xi = (int) Math.floor(x) & 255;
        int zi = (int) Math.floor(z) & 255;

        float xf = x - (float) Math.floor(x);
        float zf = z - (float) Math.floor(z);

        float u = fade(xf);
        float v = fade(zf);

        int aa = perm[perm[xi    ] + zi    ];
        int ab = perm[perm[xi    ] + zi + 1];
        int ba = perm[perm[xi + 1] + zi    ];
        int bb = perm[perm[xi + 1] + zi + 1];

        float x1 = lerp(grad2D(aa, xf,     zf    ), grad2D(ba, xf - 1,     zf    ), u);
        float x2 = lerp(grad2D(ab, xf,     zf - 1), grad2D(bb, xf - 1,     zf - 1), u);
        return lerp(x1, x2, v);
    }

    public float octave(float x, float z, int octaves, float persistence) {
        float value     = 0;
        float amplitude = 1;
        float frequency = 1;
        float maxValue  = 0;

        for (int i = 0; i < octaves; i++) {
            value    += get(x * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= 2;
        }

        return value / maxValue;
    }

    // -------------------------------------------------------------------------
    // 3D NOISE  — returns roughly [-1, 1]
    // -------------------------------------------------------------------------

    public float get3D(float x, float y, float z) {
        // Which unit cube contains the point?
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        int zi = (int) Math.floor(z) & 255;

        // Fractional position inside the cube
        float xf = x - (float) Math.floor(x);
        float yf = y - (float) Math.floor(y);
        float zf = z - (float) Math.floor(z);

        // Fade curves
        float u = fade(xf);
        float v = fade(yf);
        float w = fade(zf);

        // Hash the 8 corners of the cube
        int aaa = perm[perm[perm[xi    ] + yi    ] + zi    ];
        int aba = perm[perm[perm[xi    ] + yi + 1] + zi    ];
        int aab = perm[perm[perm[xi    ] + yi    ] + zi + 1];
        int abb = perm[perm[perm[xi    ] + yi + 1] + zi + 1];
        int baa = perm[perm[perm[xi + 1] + yi    ] + zi    ];
        int bba = perm[perm[perm[xi + 1] + yi + 1] + zi    ];
        int bab = perm[perm[perm[xi + 1] + yi    ] + zi + 1];
        int bbb = perm[perm[perm[xi + 1] + yi + 1] + zi + 1];

        // Interpolate across X, then Y, then Z
        float x1 = lerp(grad3D(aaa, xf,     yf,     zf    ),
                grad3D(baa, xf - 1, yf,     zf    ), u);
        float x2 = lerp(grad3D(aba, xf,     yf - 1, zf    ),
                grad3D(bba, xf - 1, yf - 1, zf    ), u);
        float x3 = lerp(grad3D(aab, xf,     yf,     zf - 1),
                grad3D(bab, xf - 1, yf,     zf - 1), u);
        float x4 = lerp(grad3D(abb, xf,     yf - 1, zf - 1),
                grad3D(bbb, xf - 1, yf - 1, zf - 1), u);

        float y1 = lerp(x1, x2, v);
        float y2 = lerp(x3, x4, v);

        return lerp(y1, y2, w);
    }

    public float octave3D(float x, float y, float z, int octaves, float persistence) {
        float value     = 0;
        float amplitude = 1;
        float frequency = 1;
        float maxValue  = 0;

        for (int i = 0; i < octaves; i++) {
            value    += get3D(x * frequency, y * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= 2;
        }

        return value / maxValue;
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    private float grad2D(int hash, float x, float z) {
        int[] g = GRAD2D[hash & 7];
        return g[0] * x + g[1] * z;
    }

    private float grad3D(int hash, float x, float y, float z) {
        int[] g = GRAD3D[hash % 12];
        return g[0] * x + g[1] * y + g[2] * z;
    }
}