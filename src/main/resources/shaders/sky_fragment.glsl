#version 330 core
in  vec2 TexCoord;
out vec4 FragColor;

uniform mat4  invViewProj;
uniform vec3  sunDir;
uniform vec3  moonDir;
uniform vec3  skyZenith;
uniform vec3  skyHorizon;
uniform float dayFactor;
uniform float nightFactor;
uniform float sunsetFactor;

// New lunar uniforms
uniform float moonPhaseAngle;      // 0=New, PI=Full
uniform float moonBrightLimbAngle; // Orientation of the bright limb

void main() {
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
        float sunUp = clamp(sunDir.y * 8.0 + 0.25, 0.0, 1.0);
        float disc  = smoothstep(0.9982, 0.9990, sd);

        // RESTORED: The wide pow(5.0) atmospheric glow that flares up perfectly at sunset!
        float glow  = pow(sd, 250.0) * 1.3
                    + pow(sd, 22.0)  * 0.55
                    + pow(sd, 5.0)   * (0.20 + 0.55 * sunsetFactor);

        vec3  sunCol = mix(vec3(1.0, 0.96, 0.85), vec3(1.0, 0.45, 0.16), sunsetFactor);
        col += sunUp * (disc * 5.0 + glow) * sunCol;
        // ── MOON: Phase-Aware Atmospheric Glow ──
            float md = max(dot(ray, moonDir), 0.0);

            // Calculate how "Full" the moon is (1.0 = Full Moon, 0.0 = New Moon)
            float phaseFrac = 0.5 * (1.0 - dot(sunDir, moonDir));

            // Much tighter, fainter, and more realistic halo (no giant dome wash)
            // pow(120) creates a soft, narrow aureole; pow(500) creates a subtle edge bleed
            float moonGlow = pow(md, 120.0) * 0.15
                           + pow(md, 500.0) * 0.40;

            float moonVis = nightFactor * clamp(moonDir.y * 10.0 + 0.4, 0.0, 1.0);

            // Desaturated slate-blue color (moonlight is almost monochrome/silver to human eyes)
            vec3 moonGlowCol = vec3(0.24, 0.28, 0.38) * moonGlow * phaseFrac;

            col += moonGlowCol * moonVis;

    // (Moon is rendered as a 3D sphere in Window.java — no 2D overlay here.)
    // (Stars are rendered as GL_POINTS in Window.java.)
    FragColor = vec4(col, 1.0);
}