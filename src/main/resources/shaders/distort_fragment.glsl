#version 330 core
in  vec2 TexCoord;
out vec4 FragColor;

uniform sampler2D screenTexture;
uniform float     time;
uniform float     kamuiCharge;       // 0 = idle Kamui, 1 = full absorption charge
uniform vec2      absorptionPos;     // normalised screen [0,1] of absorption target
uniform float     aspectRatio;       // screenW / screenH

// Quantum Bullet: a localised ripple that WARPS the wall texture where a phasing
// bullet passes through (0 = off). qbCenter = bullet's screen UV.
uniform float     qbStrength;
uniform vec2      qbCenter;

void main() {
    vec2 uv = TexCoord;

    // ── QUANTUM SURFACE WARP — ripple the screen (the wall texture) around the
    //    phasing bullet, then return (no Kamui colour grade). ──────────────────
    if (qbStrength > 0.001) {
        vec2  d    = uv - qbCenter;  d.x *= aspectRatio;
        float dist = length(d);
        float rip  = sin(dist * 55.0 - time * 20.0) * exp(-dist * 9.0);
        float fade = smoothstep(0.45, 0.0, dist);
        vec2  dir  = d / max(dist, 1e-4);  dir.x /= aspectRatio;
        vec2  wuv  = clamp(uv + dir * rip * qbStrength * 0.06 * fade, 0.001, 0.999);
        FragColor = texture(screenTexture, wuv);
        return;
    }

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

        // Wide falloff — distortion spreads across the WHOLE screen, not just near target
        float falloff = exp(-absDist * 0.85);

        // Spin: vortex rotates across the screen, strongest at centre, gradual at edges
        float spin = kamuiCharge * 8.0 * falloff
                   * (1.0 + 0.40 * sin(time * 5.0 - absDist * 5.0));
        absAng += spin;

        // Pull: UV converges toward absorption point — stronger and wider now
        float pull  = kamuiCharge * 0.40 * falloff;
        absDist     = max(0.0, absDist - pull);

        vec2 newDir = vec2(cos(absAng) / aspectRatio, sin(absAng)) * absDist;
        uv          = absPos + newDir;

        // Dark void at the very centre — the Kamui dimension opening (larger, more dramatic)
        float voidR    = kamuiCharge * 0.12;
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
