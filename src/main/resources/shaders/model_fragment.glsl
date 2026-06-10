#version 330 core

in  vec4 vColor;
in  vec3 vNormal;
in  vec2 vUV;
in  vec3 vClipPos;
out vec4 FragColor;

uniform vec3  lightDir;   // normalized world-space light direction
uniform float ambient;    // 0..1

// ── SLICE: chop the model into one OCTANT (Deprivation Domain "大卸八块") ───────
// cutActive=1 keeps only the body-local octant selected by cutSign (each comp ±1)
// relative to cutCenter — 3 axis planes → 8 pieces. Freshly-exposed inner faces
// sear molten white-gold; sliceAlpha fades the piece as it dissolves.
uniform int   cutActive;
uniform vec3  cutCenter;   // body-frame centre the 3 cut planes pass through
uniform vec3  cutSign;     // (±1,±1,±1) → which of the 8 octants this draw keeps
uniform float sliceAlpha;

// Texture sampling: set useTexture = 1 and bind a texture to unit 0 to draw the
// Blockbench texture; useTexture = 0 (default) keeps the solid vertex-colour path.
uniform sampler2D tex;
uniform int       useTexture;

// Radar override: tint the model toward a colour and boost brightness so it can
// glow far past white (the sweep "ping"). Defaults (tintAmt 0, glow 1) = normal.
uniform vec3  tintColor;
uniform float tintAmt;
uniform float glow;

void main() {
    float diff  = max(dot(vNormal, lightDir), 0.0);
    float light = ambient + (1.0 - ambient) * diff;

    vec4 base = vColor;
    if (useTexture == 1) {
        base = texture(tex, vUV);
        if (base.a < 0.5) discard;   // cutout: don't let transparent texels occlude
    }
    vec3 rgb = base.rgb * light;
    rgb = mix(rgb, tintColor, tintAmt);   // recolour for the radar
    rgb *= glow;                          // ping brightness (can exceed 1 → searing)

    float outA = base.a;
    if (cutActive == 1) {
        vec3 rel = (vClipPos - cutCenter) * cutSign;        // >0 on the kept side of each plane
        if (rel.x < 0.0 || rel.y < 0.0 || rel.z < 0.0) discard;  // outside this octant
        // Molten cut surfaces: glow where this fragment hugs any of the 3 cut planes.
        vec3 dd  = abs(vClipPos - cutCenter);
        float face = max(max(1.0 - smoothstep(0.0, 1.2, dd.x),
                             1.0 - smoothstep(0.0, 1.2, dd.y)),
                             1.0 - smoothstep(0.0, 1.2, dd.z));
        rgb = mix(rgb, vec3(3.6, 2.5, 0.85), face * sliceAlpha);
        outA *= sliceAlpha;
    }
    FragColor = vec4(rgb, outA);
}
