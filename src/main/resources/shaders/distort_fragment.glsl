#version 330 core
in  vec2 TexCoord;
out vec4 FragColor;

uniform sampler2D screenTexture;
uniform float     time;
uniform float     kamuiCharge;       // 0 = idle Kamui, 1 = full absorption charge
uniform vec2      absorptionPos;     // normalised screen [0,1] of absorption target
uniform float     aspectRatio;       // screenW / screenH

void main() {
    vec2 uv = TexCoord;

    // ── Absorption vortex (ONLY while actively charging absorption) ──────────
    // When the player holds LMB to absorb an enemy, the scene spirals and is
    // sucked into that one point — like Obito's eye opening a portal.
    // This block does NOT run during idle Kamui, so the base FOV is untouched.
    if (kamuiCharge > 0.01) {
        vec2 absPos  = absorptionPos;
        vec2 toAbs   = uv - absPos;
        toAbs.x     *= aspectRatio;           // correct for non-square screen
        float absDist = length(toAbs);
        float absAng  = atan(toAbs.y, toAbs.x);

        // Tight falloff — only pixels near the target are strongly distorted
        float falloff = exp(-absDist * 3.5);

        // Spin: vortex rotates faster near the centre and as charge increases
        float spin = kamuiCharge * 6.5 * falloff
                   * (1.0 + 0.35 * sin(time * 5.0 - absDist * 8.0));
        absAng += spin;

        // Pull: UV converges toward absorption point
        float pull  = kamuiCharge * 0.28 * falloff;
        absDist     = max(0.0, absDist - pull);

        vec2 newDir = vec2(cos(absAng) / aspectRatio, sin(absAng)) * absDist;
        uv          = absPos + newDir;

        // Dark void at the very centre — the Kamui dimension opening
        float voidR    = kamuiCharge * 0.055;
        float voidDist = length((TexCoord - absPos) * vec2(aspectRatio, 1.0));
        if (voidDist < voidR) {
            float t = voidDist / voidR;
            // Smooth deep-blue core fading to normal at edge
            vec3 voidCol = mix(vec3(0.0, 0.0, 0.06), texture(screenTexture, uv).rgb, t * t);
            FragColor = vec4(voidCol, 1.0);
            return;
        }
    }

    uv = clamp(uv, 0.001, 0.999);
    vec4 col = texture(screenTexture, uv);

    // ── Pulsing purple vignette — the "heartbeat" of the Kamui dimension ────
    // Darkens the screen edges with a slow breathing rhythm.
    // Pure color overlay — zero UV distortion, FOV is completely unaffected.
    float pulse    = 0.5 + 0.5 * sin(time * 1.6);             // 0→1, ~4-second period
    vec2  vigUV    = TexCoord * 2.0 - 1.0;                    // remap [0,1]→[-1,1]
    float vigDist  = length(vigUV);                            // 0 = centre, ~1.41 = corner
    float vigStr   = smoothstep(0.45, 1.35, vigDist) * (0.28 + 0.14 * pulse);
    col.rgb        = mix(col.rgb, vec3(0.06, 0.0, 0.16), vigStr);

    // ── Subtle ambient purple tint — always active while in Kamui ────────────
    float tint = 0.07 + 0.025 * pulse;
    col.rgb = mix(col.rgb, vec3(0.15, 0.0, 0.30), tint);

    FragColor = col;
}
