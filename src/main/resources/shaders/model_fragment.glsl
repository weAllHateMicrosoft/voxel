#version 330 core

in  vec4 vColor;
in  vec3 vNormal;
in  vec2 vUV;
out vec4 FragColor;

uniform vec3  lightDir;   // normalized world-space light direction
uniform float ambient;    // 0..1

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
    FragColor = vec4(rgb, base.a);
}
