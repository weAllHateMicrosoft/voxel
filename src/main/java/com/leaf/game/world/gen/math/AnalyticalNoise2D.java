package com.leaf.game.world.gen.math;

import java.util.Random;

/**
 * 2D Value Noise that returns the noise VALUE and its partial SPATIAL DERIVATIVES
 * (∂n/∂x, ∂n/∂z) in a single evaluation pass.
 *
 * Why value noise instead of Perlin here?
 *   The derivative formula for bilinear-interpolated value noise is simple
 *   and exact. This makes it possible to compute slope correctly without
 *   sampling the noise three times (finite differences).
 *
 * Why do we need derivatives?
 *   ErodedFbmGenerator accumulates the slope vector across octaves. On steep
 *   terrain, (|d|^2) is large, so the weight W = 1/(1+|d|^2) becomes small,
 *   suppressing fine detail. This is what produces the "smooth valleys, sharp
 *   ridges" look. Without analytical derivatives you lose this effect.
 *
 * Output: float[3] = { value, ∂n/∂x, ∂n/∂z }
 *   - value is in roughly [-1, 1]
 *   - derivatives are raw (not normalized by cell size — caller handles scaling)
 */
public class AnalyticalNoise2D {

    // Hash table: shuffled [0..255] lookup, doubled to avoid modulo on index+1
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

    /**
     * Returns a pseudo-random float in [-1, 1] for grid corner (xi, zi).
     * Deterministic: same (xi, zi) always returns the same value.
     */
    private float cornerValue(int xi, int zi) {
        int h = perm[(perm[xi & 255] + zi) & 255];
        return (h / 127.5f) - 1.0f; // maps [0,255] → [-1, 1]
    }

    // Quintic fade: 6t^5 - 15t^4 + 10t^3
    // This is C² continuous (first AND second derivatives are smooth),
    // which prevents visual artifacts at grid borders.
    private float fade(float t)  { return t * t * t * (t * (t * 6 - 15) + 10); }

    // Derivative of the quintic fade: 30t^4 - 60t^3 + 30t^2 = 30t^2(t-1)^2
    private float dfade(float t) { return 30 * t * t * (t * (t - 2) + 1); }

    /**
     * Sample value noise and its spatial derivatives at (x, z).
     *
     * Math derivation:
     *   Given corner values a=f(0,0), b=f(1,0), c=f(0,1), d=f(1,1),
     *   bilinear interpolation gives:
     *     h = k0 + k1*ux + k2*uz + k3*ux*uz   where
     *     k0 = a, k1 = b-a, k2 = c-a, k3 = a-b-c+d
     *   Derivatives (chain rule through the fade function):
     *     ∂h/∂x = (k1 + k3*uz) * u'x
     *     ∂h/∂z = (k2 + k3*ux) * u'z
     *
     * @return float[3] = { value, ∂n/∂x_local, ∂n/∂z_local }
     *         (derivatives in local grid space, ~[-1,1] range)
     */
    public float[] sample(float x, float z) {
        int   xi = (int) Math.floor(x);
        int   zi = (int) Math.floor(z);
        float xf = x - xi;          // fractional part [0, 1)
        float zf = z - zi;

        // Four corner values of the grid cell
        float a = cornerValue(xi,     zi    );  // (0,0)
        float b = cornerValue(xi + 1, zi    );  // (1,0)
        float c = cornerValue(xi,     zi + 1);  // (0,1)
        float d = cornerValue(xi + 1, zi + 1);  // (1,1)

        // Bilinear interpolation coefficients
        float k0 = a;
        float k1 = b - a;
        float k2 = c - a;
        float k3 = a - b - c + d;  // = (d-c) - (b-a)

        // Fade values and their derivatives
        float ux  = fade(xf),  uz  = fade(zf);
        float dux = dfade(xf), duz = dfade(zf);

        float value = k0 + k1 * ux + k2 * uz + k3 * ux * uz;
        float dx    = (k1 + k3 * uz) * dux;   // ∂value/∂x (local grid coords)
        float dz    = (k2 + k3 * ux) * duz;   // ∂value/∂z (local grid coords)

        return new float[]{ value, dx, dz };
    }
}