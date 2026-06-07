#version 330 core
in vec3  vColor;
in float vBright;
in float vSeed;
in float vMag;
out vec4 FragColor;

uniform float time;
uniform float nightFactor;

void main() {
    // Soft gaussian point sprite — discard outside the circle.
    vec2  c  = gl_PointCoord * 2.0 - 1.0;
    float d2 = dot(c, c);
    if (d2 > 1.0) discard;

    // Gaussian core — sharper centre, soft edge.
    float core = exp(-d2 * 4.0);

    // Subtle diffraction halo on bright stars only (mag < 2).
    // Gives a slight cross/ring that looks like a real bright star in atmosphere.
    float halo = 0.0;
    if (vMag < 2.0) {
        float haloStr = clamp((2.0 - vMag) * 0.25, 0.0, 0.5);
        halo = exp(-d2 * 1.2) * haloStr;
    }

    // Scintillation (twinkling):
    // - Only bright stars (mag < 3) twinkle meaningfully.
    // - Faint stars appear rock-steady (their light is too dim for atmospheric dance to matter
    //   for the player's eye; also avoids the "everything flickers" look).
    // - Amplitude is small (±12%) so stars never go dark mid-blink.
    float twinkleAmp = clamp((3.0 - vMag) * 0.04, 0.0, 0.12);
    float tw = 1.0 - twinkleAmp + twinkleAmp * sin(time * (1.8 + vSeed * 3.0) + vSeed * 30.0);

    float a = (core + halo) * vBright * tw * nightFactor;

    // Additive blending: colour pre-multiplied into alpha keeps stars from
    // looking grey at low brightness. (Requires glBlendFunc(GL_ONE, GL_ONE) or
    // glBlendFunc(GL_SRC_ALPHA, GL_ONE) in Window.java for the star pass.)
    FragColor = vec4(vColor * a, a);
}
