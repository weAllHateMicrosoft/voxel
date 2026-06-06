#version 330 core
in  vec2 TexCoord;
out vec4 FragColor;

// Full-screen procedural sky for the day/night cycle: a vertical gradient
// (horizon → zenith), a glowing sun with a warm sunset halo, a pale moon, and
// twinkling night stars. All mood values come from the DayNight system.
uniform mat4  invViewProj;   // to reconstruct the world-space view ray per pixel
uniform vec3  sunDir;        // direction TO the sun
uniform vec3  moonDir;       // direction TO the moon
uniform vec3  skyZenith;     // colour straight up
uniform vec3  skyHorizon;    // colour at the horizon
uniform float dayFactor;     // 0 night .. 1 day
uniform float nightFactor;   // 1 night .. 0 day
uniform float sunsetFactor;  // 0..1 golden-hour glow
uniform float time;          // seconds (star twinkle)

float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

void main() {
    // ── reconstruct the world-space ray for this pixel ──
    vec2 ndc   = TexCoord * 2.0 - 1.0;
    vec4 nearP = invViewProj * vec4(ndc, -1.0, 1.0);
    vec4 farP  = invViewProj * vec4(ndc,  1.0, 1.0);
    vec3 ray   = normalize(farP.xyz / farP.w - nearP.xyz / nearP.w);

    // ── vertical gradient ──
    float t   = smoothstep(0.0, 0.55, ray.y);
    vec3  col = mix(skyHorizon, skyZenith, t);

    // ── warm sunset band hugging the horizon ──
    float band = exp(-abs(ray.y) * 5.5) * sunsetFactor;
    col = mix(col, vec3(1.0, 0.48, 0.26), band * 0.75);

    // ── SUN: bright disc + tight core glow + wide warm halo ──
    float sd    = max(dot(ray, sunDir), 0.0);
    float sunUp = clamp(sunDir.y * 8.0 + 0.25, 0.0, 1.0);   // fade out as it sets
    float disc  = smoothstep(0.9982, 0.9990, sd);
    float glow  = pow(sd, 250.0) * 1.3
                + pow(sd, 22.0)  * 0.55
                + pow(sd, 5.0)   * (0.20 + 0.55 * sunsetFactor);   // halo widens at sunset
    vec3  sunCol = mix(vec3(1.0, 0.96, 0.85), vec3(1.0, 0.45, 0.16), sunsetFactor);
    col += sunUp * (disc * 5.0 + glow) * sunCol;

    // ── MOON: pale disc + soft glow (night only) ──
    float md     = max(dot(ray, moonDir), 0.0);
    float moonUp = clamp(moonDir.y * 8.0 + 0.15, 0.0, 1.0);
    float mdisc  = smoothstep(0.9988, 0.9994, md);
    float mglow  = pow(md, 50.0) * 0.5 + pow(md, 8.0) * 0.12;
    col += nightFactor * moonUp * (mdisc * 3.0 + mglow) * vec3(0.82, 0.86, 1.0);

    // ── STARS: night only, above the horizon ──
    if (nightFactor > 0.05 && ray.y > 0.03) {
        vec2  sp   = ray.xz / (ray.y + 0.25);          // gnomonic-ish projection
        vec2  cell = floor(sp * 130.0);
        float h    = hash(cell);
        if (h > 0.991) {
            float tw = 0.55 + 0.45 * sin(time * 3.0 + h * 60.0);
            col += nightFactor * ((h - 0.991) / 0.009) * tw * vec3(0.9, 0.93, 1.0);
        }
    }

    FragColor = vec4(col, 1.0);
}
