package com.leaf.game.world.gen.math;

import java.util.Random;

public class AnalyticalNoise2D {

    private final int[] perm = new int[512];

    public AnalyticalNoise2D(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        Random rng = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = p[i]; p[i] = p[j]; p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private float cornerValue(int xi, int zi) {
        int h = perm[(perm[xi & 255] + zi) & 255];
        // STRICT [0, 1] mapping (Crucial for Inigo Quilez's erosion trick)
        return h / 255.0f;
    }

    private float fade(float t)  { return t * t * t * (t * (t * 6 - 15) + 10); }
    private float dfade(float t) { return 30 * t * t * (t * (t - 2) + 1); }

    public float[] sample(float x, float z) {
        int   xi = (int) Math.floor(x);
        int   zi = (int) Math.floor(z);
        float xf = x - xi;
        float zf = z - zi;

        float a = cornerValue(xi,     zi    );
        float b = cornerValue(xi + 1, zi    );
        float c = cornerValue(xi,     zi + 1);
        float d = cornerValue(xi + 1, zi + 1);

        float k0 = a;
        float k1 = b - a;
        float k2 = c - a;
        float k3 = a - b - c + d;

        float ux  = fade(xf),  uz  = fade(zf);
        float dux = dfade(xf), duz = dfade(zf);

        float value = k0 + k1 * ux + k2 * uz + k3 * ux * uz;
        float dx    = (k1 + k3 * uz) * dux;
        float dz    = (k2 + k3 * ux) * duz;

        // Returns Value [0,1], and raw derivatives
        return new float[]{ value, dx, dz };
    }
}