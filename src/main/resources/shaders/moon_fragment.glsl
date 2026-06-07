#version 330 core
in  vec3 vNormal;
out vec4 FragColor;

uniform vec3  sunDir;          // world-space direction toward the sun
uniform float moonVisibility;  // nightFactor * horizon_fade; fades during day / below horizon

void main() {
    vec3 n    = normalize(vNormal);
    float d   = max(0.0, dot(n, normalize(sunDir)));   // lambertian: lit by the sun

    // Moon surface: warm grey with subtle mare tones
    vec3 regolith = vec3(0.88, 0.87, 0.82);

    // Limb darkening (edge of lit disc is slightly dimmer)
    float limb  = 0.60 + 0.40 * d;
    float bright = d * 0.94 * limb;

    // Tiny earthshine on the dark side: blue-ish reflected light from Earth
    float earthshine = 0.055 * max(0.0, -d) * moonVisibility;

    vec3 col = regolith * bright + vec3(0.08, 0.12, 0.22) * earthshine;

    // Dark side is truly dark — alpha=0 lets the sky and stars show through
    float alpha = step(0.0, d) * moonVisibility         // lit side: opaque
                + smoothstep(-0.03, 0.0, d) * 0.0;     // dark side: transparent (discard below)

    if (d < 0.0) discard;   // dark hemisphere: sky/stars show through → no yin-yang

    FragColor = vec4(col, moonVisibility);
}
