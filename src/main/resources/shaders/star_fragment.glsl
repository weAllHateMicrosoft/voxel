#version 330 core
in vec3  vColor;
in float vBright;
in float vSeed;
in float vMag;
out vec4 FragColor;

uniform float time;
uniform float nightFactor;

void main() {
    // Point coordinates map [0,1] across the sprite. Remap to [-1, 1]
    vec2  c  = gl_PointCoord * 2.0 - 1.0;
    float d2 = dot(c, c); // squared distance from center

    // Circular clipping
    if (d2 > 1.0) discard;

    // Normalize brightness factor to control the Gaussian spread
    float bFactor = clamp(vBright / 2.2, 0.0, 1.0);

    // 1. Core Gaussian (sharp, intensely bright centre)
    float core = exp(-d2 * (16.0 - bFactor * 8.0));

    // 2. Bloom Gaussian (wide, faint halo that makes bright stars look luminous)
    float bloom = exp(-d2 * (4.0 - bFactor * 2.0)) * 0.35 * bFactor;

    float profile = core + bloom;

    // Subtle twinkling (only bright stars twinkle noticeably)
    float twinkleAmp = clamp((3.0 - vMag) * 0.05, 0.0, 0.15);
    float tw = 1.0 - twinkleAmp + twinkleAmp * sin(time * (1.8 + vSeed * 3.0) + vSeed * 30.0);

    float alpha = profile * vBright * tw * nightFactor;

    FragColor = vec4(vColor * alpha, alpha);
}