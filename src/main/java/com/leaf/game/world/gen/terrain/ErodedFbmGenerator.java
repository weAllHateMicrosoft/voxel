package com.leaf.game.world.gen.terrain;
import com.leaf.game.world.gen.math.AnalyticalNoise2D;
/**
 * Derivative-attenuated Fractional Brownian Motion (fBm) terrain generator.
 *
 * WHAT THIS IS:
 *   Standard fBm just sums octaves of noise. The result is mathematically
 *   uniform — every patch looks equally detailed. Real mountains are not like that:
 *   valleys are smooth (sediment fills detail), ridges are sharp (erosion exposes it).
 *
 * THE SLOPE TRICK (Inigo Quilez):
 *   We accumulate the slope vector d = (dx, dz) across all octaves.
 *   For octave i, the weight is:  W = amplitude / (1 + |d|²)
 *   On steep terrain, |d|² >> 0, so W → 0: fine detail is suppressed.
 *   In valleys/crests where slopes cancel, |d|² ≈ 0, so W ≈ amplitude: full detail.
 *   This looks like natural erosion without simulating any particles.
 *
 * OCTAVE ROTATION:
 *   Each octave rotates the coordinate frame by ~37° (irrational angle).
 *   Without this, all octaves stack in the same direction → grid artifacts.
 *   With rotation, each octave adds structure in a different direction → complex.
 *
 * OUTPUT:
 *   getHeight(wx, wz)       → normalized height [0, 1]   (for HeightmapGenerator interface)
 *   sampleFull(wx, wz)      → float[] {height_01, slopeX, slopeZ}  (for BlockCladder)
 */
public class ErodedFbmGenerator implements HeightmapGenerator {

    // Rotation by ~37.3° — irrational multiple of 90° prevents directional banding
    // cos(37.3°) ≈ 0.7954,  sin(37.3°) ≈ 0.6060
    private static final float COS_A = 0.7954f;
    private static final float SIN_A = 0.6060f;

    private final AnalyticalNoise2D noise;
    private final int   octaves;
    private final float frequency;   // world-space frequency: 1/featureSize
    private final float lacunarity;  // frequency multiplier per octave (usually 2.0)
    private final float gain;        // amplitude multiplier per octave (usually 0.5)

    /**
     * @param seed        unique seed for this generator
     * @param octaves     number of layers (7 gives rich mountain detail)
     * @param frequency   1 / feature_size_in_blocks. E.g. 1/500 for 500-block base features
     * @param lacunarity  frequency scale per octave (2.0 = doubles each octave)
     * @param gain        amplitude scale per octave (0.5 = halves each octave)
     */
    public ErodedFbmGenerator(long seed, int octaves, float frequency, float lacunarity, float gain) {
        this.noise     = new AnalyticalNoise2D(seed);
        this.octaves   = octaves;
        this.frequency = frequency;
        this.lacunarity = lacunarity;
        this.gain      = gain;
    }

    // ── PUBLIC API ────────────────────────────────────────────────────────────

    /**
     * Returns normalized height [0, 1] at world position (wx, wz).
     * 0 = at or below base. 1 = maximum mountain height.
     */
    @Override
    public float getHeight(int wx, int wz) {
        return sampleFull(wx, wz)[0];
    }

    /**
     * Returns { normalizedHeight [0,1], slopeMagnitude [0..∞] }.
     *
     * slopeMagnitude is the accumulated |d| = sqrt(dx²+dz²) at the end of all octaves.
     * BlockCladder uses this to decide: cliff vs snow cap vs grass.
     *   slopeMag > 0.85 → steep cliff face → STONE
     *   slopeMag < 0.4 at altitude → flat summit → SNOW
     */
    public float[] sampleFull(int wx, int wz) {
        float x = wx * frequency;
        float z = wz * frequency;

        float value    = 0;
        float totalW   = 0;
        float amplitude = 1;
        float freqScale = 1;        // tracks current octave's frequency multiplier

        float dx = 0, dz = 0;       // accumulated slope vector

        // Start at the rotated coordinate frame
        float rx = x, rz = z;

        for (int i = 0; i < octaves; i++) {
            float[] s  = noise.sample(rx, rz);
            float   n  = s[0];
            float   ndx = s[1] * freqScale;   // scale derivatives by frequency for correct world slope
            float   ndz = s[2] * freqScale;

            // Erosion weight: suppress detail on steep slopes
            float slopeSq = dx * dx + dz * dz;
            float w       = amplitude / (1f + slopeSq);

            value  += n * w;
            totalW += w;

            // Accumulate slope for next octave
            dx += ndx * w;
            dz += ndz * w;

            // Rotate for next octave (prevents directional banding)
            float nx =  COS_A * rx - SIN_A * rz;
            float nz =  SIN_A * rx + COS_A * rz;
            rx = nx * lacunarity;
            rz = nz * lacunarity;

            amplitude *= gain;
            freqScale *= lacunarity;
        }

        // Normalize: raw value is in roughly [-1, 1]
        float raw        = totalW > 0 ? value / totalW : 0;
        float normalized = (raw + 1f) * 0.5f;   // → [0, 1]

        float slopeMag = (float) Math.sqrt(dx * dx + dz * dz);

        return new float[]{ normalized, slopeMag };
    }
}