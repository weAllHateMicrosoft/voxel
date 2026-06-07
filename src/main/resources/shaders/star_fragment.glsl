#version 330 core
in vec3  vColor;
in float vBright;
in float vSeed;
out vec4 FragColor;

uniform float time;
uniform float nightFactor;

void main() {
    // Soft gaussian core point sprite.
    vec2  c   = gl_PointCoord * 2.0 - 1.0;
    float d2  = dot(c, c);
    if (d2 > 1.0) discard;
    float core = exp(-d2 * 3.5);

    // Per-star scintillation: each star twinkles at its own rate + phase.
    float tw = 0.62 + 0.38 * sin(time * (2.0 + vSeed * 4.0) + vSeed * 30.0);

    float a = core * vBright * tw * nightFactor;
    FragColor = vec4(vColor * a, a);   // additive blend (set in Window)
}
