#version 330 core
in vec3  vColor;
in float vBright;
in float vSeed;
in float vMag;
out vec4 FragColor;

uniform float time;
uniform float nightFactor;

void main() {
    vec2  c  = gl_PointCoord * 2.0 - 1.0;
    float d2 = dot(c, c);
    if (d2 > 1.0) discard;

    // ── Core: tight gaussian ──────────────────────────────────────────────────
    // Two gaussians: a sharp pinpoint core + a slightly wider halo
    // This makes stars look like they have real angular extent in atmosphere
    float core  = exp(-d2 * 5.5);                  // sharp spike
    float bloom = exp(-d2 * 1.6) * 0.22;           // soft halo around it
    float sprite = core + bloom;

    // ── Diffraction spikes (only for bright stars, mag < 1.5) ─────────────────
    // Real telescope / eye sees 4-point cross on very bright stars due to diffraction.
    // We approximate with two perpendicular "spike" functions.
    float spikes = 0.0;
    if (vMag < 1.5) {
        float spikeStr = clamp((1.5 - vMag) * 0.5, 0.0, 0.75);
        // Axis-aligned cross
        float sx = exp(-abs(c.y) * 18.0) * exp(-d2 * 0.3);   // horizontal spike
        float sy = exp(-abs(c.x) * 18.0) * exp(-d2 * 0.3);   // vertical spike
        // Diagonal spikes (45° rotated) — slightly weaker
        float sd1 = exp(-abs(c.x - c.y) * 14.0) * exp(-d2 * 0.3);
        float sd2 = exp(-abs(c.x + c.y) * 14.0) * exp(-d2 * 0.3);
        spikes = (sx + sy) * 0.4 + (sd1 + sd2) * 0.12;
        spikes *= spikeStr;
        // Spikes are whiter/cooler than the star colour
        sprite += spikes;
    }

    // ── Scintillation (atmospheric twinkling) ─────────────────────────────────
    // Only noticeably affects bright stars. Faint stars don't visibly twinkle.
    // Amplitude is small — never goes dark, just pulses ±10%.
    float twinkleAmp = clamp((3.0 - vMag) * 0.032, 0.0, 0.10);
    float tw = 1.0 - twinkleAmp
             + twinkleAmp * sin(time * (1.5 + vSeed * 3.2) + vSeed * 31.4)
             + twinkleAmp * 0.4 * sin(time * (3.3 + vSeed * 1.8) + vSeed * 17.2);

    // ── Colour: spikes slightly bluer/whiter than star body ──────────────────
    // Hot blue-white stars: their bloom should be slightly blue-shifted
    // Red/orange stars: their bloom is warm
    // We just tint the spike contribution toward white
    vec3 spikeColor = mix(vColor, vec3(1.0, 1.0, 0.95), 0.6);
    vec3 finalColor = vColor * (core + bloom) + spikeColor * spikes;

    float a = sprite * vBright * tw * nightFactor;

    // Additive blending — colour is pre-multiplied into alpha channel weight
    FragColor = vec4(finalColor * a, a);
}
