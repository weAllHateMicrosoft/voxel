#version 330 core
in  vec2 TexCoord;
out vec4 FragColor;

// Single-pass "searing" bloom used by the Orbital Annihilation cinematic.
// Samples the rendered scene, isolates the brightest pixels (the emissive voxel
// scan lines + laser flash), and bleeds them outward in a soft halo so the light
// physically blurs into the surrounding darkness — the hot, unstable-energy look.
uniform sampler2D screenTexture;
uniform vec2      texel;          // 1.0 / framebuffer size
uniform float     bloomStrength;  // overall bleed gain
uniform float     threshold;      // brightness above which a pixel blooms

void main() {
    vec3 base = texture(screenTexture, TexCoord).rgb;

    // Blur of the bright-pass. Wide spacing = soft, far-reaching glow.
    vec3  glow  = vec3(0.0);
    float total = 0.0;
    for (int i = -3; i <= 3; i++) {
        for (int j = -3; j <= 3; j++) {
            vec2 off = vec2(float(i), float(j)) * texel * 4.5;
            vec3 c   = texture(screenTexture, TexCoord + off).rgb;
            float b  = max(0.0, max(c.r, max(c.g, c.b)) - threshold);
            float w  = exp(-float(i * i + j * j) * 0.14);
            glow  += c * b * w;
            total += w;
        }
    }
    glow /= max(total, 0.0001);

    // Additive composite — bright stays bright, halo bleeds into the dark.
    vec3 col = base + glow * bloomStrength;
    FragColor = vec4(col, 1.0);
}
