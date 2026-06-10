#version 330 core
in float vTrailFrac;
in float vBrightness;
out vec4 FragColor;

void main() {
    // Soft point sprite
    vec2  c  = gl_PointCoord * 2.0 - 1.0;
    float d2 = dot(c, c);
    if (d2 > 1.0) discard;
    float core = exp(-d2 * 3.0);

    // Trail fades from head (frac=0, bright) to tail (frac=1, dark)
    // Exponential fade so the head is distinct and tail is wispy
    float trailAlpha = exp(-vTrailFrac * 4.5) * (1.0 - vTrailFrac * 0.3);

    // Meteor colour depends on brightness:
    //   Faint (vBrightness < 0.3): white-grey  — just ablating rock
    //   Medium (0.3–0.7):          warm white/yellow — heated metals (Na, Fe)
    //   Bright (0.7–0.9):          yellow-green  — magnesium (Mg 518nm)
    //   Fireball (> 0.9):          green core + red edges — Mg + N2 emission
    vec3 faintCol    = vec3(0.90, 0.92, 1.00);
    vec3 mediumCol   = vec3(1.00, 0.95, 0.75);
    vec3 brightCol   = vec3(0.80, 1.00, 0.55);
    vec3 fireballCol = vec3(0.55, 1.00, 0.35);

    vec3 headCol;
    if (vBrightness < 0.33) {
        headCol = mix(faintCol, mediumCol, vBrightness / 0.33);
    } else if (vBrightness < 0.67) {
        headCol = mix(mediumCol, brightCol, (vBrightness - 0.33) / 0.34);
    } else {
        headCol = mix(brightCol, fireballCol, (vBrightness - 0.67) / 0.33);
    }

    // Tail slightly bluer/whiter than head (ionised gas cools)
    vec3 tailCol = mix(headCol, vec3(0.75, 0.80, 1.00), vTrailFrac * 0.6);

    vec3 col = mix(headCol, tailCol, vTrailFrac);

    // Persistent train: very bright meteors leave a glowing ionised tube
    // that persists 1–5 seconds. This fades in over the trail lifetime.
    // (Handled by MeteorSystem.java which keeps train particles alive after
    //  the meteor passes. This shader just renders the bright core.)

    float a = core * trailAlpha * (0.5 + vBrightness * 1.5);
    FragColor = vec4(col * a, a);   // additive blending
}
