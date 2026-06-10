#version 330 core
in vec4  vertexColor;
in vec3  vertexNormal;
in float vWorldY;        // world-space Y of this fragment (from vertex shader)
in vec2  vertexUV;       // texture coordinates (zero when not a ModelMesh)

uniform vec3  sunDirection;
uniform float sunStrength;
uniform float ambientStrength;
uniform vec3  sunColor;       // day/night light tint (warm noon → orange sunset → blue moon)
uniform vec3  ambientColor;   // ambient sky-bounce tint

// ── TORCH POINT LIGHTS (placed torches + held hand-light) ─────────────────────
#define MAX_TORCH 12
uniform int   torchCount;
uniform vec3  torchPos[MAX_TORCH];   // world-space positions
uniform vec3  torchCol[MAX_TORCH];   // colour × intensity
uniform float torchRad[MAX_TORCH];   // reach in blocks
uniform int   isUnderwater;
uniform float cameraY;        // camera eye world-Y, set each frame from Java

// ── TEXTURE SAMPLING ──────────────────────────────────────────────────────────
// Set useTexture = 1 and bind a texture to unit 0 to enable texture sampling.
// Set useTexture = 0 (default) to use pure vertex colour — all existing Mesh
// rendering is unaffected because this uniform defaults to 0.
uniform sampler2D texSampler;
uniform int       useTexture;   // 0 = vertex colour only, 1 = texture × vertex colour

// ── TIME DILATION VIGNETTE ────────────────────────────────────────────────────
uniform float timeVignetteStrength;
uniform vec3  timeVignetteColor;

// ── ABILITY OVERLAY VIGNETTE ──────────────────────────────────────────────────
// Used by dash (cyan), cannonball (orange), rewind (blue), blink (white flash).
// Set by Window.java from player.abilities.getOverlayStrength/Color().
uniform float overlayVignetteStrength;
uniform vec3  overlayVignetteColor;

// ── SNOW BIOME ATMOSPHERE ─────────────────────────────────────────────────────
uniform float snowAtmosphereStrength;  // 0=none; driven by altitude in Window.java

// ── SCREEN DESATURATION (ScreenEffectManager) ─────────────────────────────────
uniform float desaturate;   // 0=colour, 1=greyscale

// ── GHOST TRAIL TRANSPARENCY ──────────────────────────────────────────────────
// Multiplied into vertexColor.a when rendering ghost/trail meshes.
// Window.java sets this per-draw-call (0.05–0.40) then resets to 1.0.
uniform float alphaMultiplier;

// ── PORTAL RENDERING ─────────────────────────────────────────────────────────
// When portalMode == 1 the fragment samples texSampler using screen-space UVs
// (gl_FragCoord / viewportSize) and returns immediately.  This displays the
// FBO colour texture on a portal quad with perfect alignment.
// portalMode == 0 (default) → normal lit rendering, no change.
uniform int   portalMode;
uniform vec2  viewportSize;   // FBO dimensions (set to PORTAL_FBO_W/H)

// ── EMISSIVE (unlit) PASS ─────────────────────────────────────────────────────
// Used by the Orbital Annihilation 3D effect meshes (rings, core, beams, embers).
// When emissiveMode == 1 the fragment ignores ALL lighting/fog/scan and outputs
// vertexColor × emissiveTint directly, so the geometry glows at full intensity in
// the blackout and the bloom pass picks it up.
uniform int  emissiveMode;
uniform vec3 emissiveTint;   // per-object colour × intensity (additive-bloomed)

out vec4 FragColor;

void main() {
    // ── DEPRIVATION DOME force-field (hex energy shield) ──────────────────────
    if (domeMode == 1) {
        vec3 nrm     = normalize(vWorldPos - domeCenter);
        vec3 viewDir = normalize(camPos - vWorldPos);
        float fres   = pow(1.0 - abs(dot(nrm, viewDir)), 3.0);   // bright at the silhouette rim

        // Hex grid in (azimuth, latitude) space, scrolling slowly upward.
        float u = atan(nrm.z, nrm.x);
        float v = acos(clamp(nrm.y, -1.0, 1.0));                 // 0 pole → ~1.57 base (ground)
        vec2  huv = vec2(u * 2.6, (v - domeTime * 0.10) * 5.2);
        vec2  rr = vec2(1.0, 1.7320508);
        vec2  hh = rr * 0.5;
        vec2  pa = mod(huv, rr) - hh;
        vec2  pb = mod(huv - hh, rr) - hh;
        vec2  gv = dot(pa, pa) < dot(pb, pb) ? pa : pb;
        vec2  ag = abs(gv);
        float hd = max(dot(ag, normalize(vec2(1.0, 1.7320508))), ag.x);  // 0 centre → ~0.5 edge
        // THICK, solid hexagon outline (a wide bright band along each cell edge).
        float line = smoothstep(0.32, 0.46, hd);
        // Bright ring where the dome meets the ground (so it clearly "ends at the ground").
        float baseRing = smoothstep(1.40, 1.54, v);

        float pulse = 0.75 + 0.25 * sin(v * 6.0 - domeTime * 2.0);
        vec3  gold  = vec3(1.30, 0.92, 0.30);
        // A SOLID hex wireframe cage: thick glowing cell edges + a ground ring, fully
        // transparent between the lines (no fill → not a screen-filter you sit inside).
        float bright = line * 1.5 * pulse        // the solid hexagon wireframe
                     + baseRing * 2.2            // ground ring
                     + fres * 0.5                // faint silhouette so the dome reads
                     + depStrike * 1.0;
        if (bright <= 0.04) discard;                     // truly see-through between the lines
        FragColor = vec4(gold * bright, 1.0);
        return;
    }

    // ── FBO PORTAL PASSTHROUGH ────────────────────────────────────────────────
    if (portalMode == 1) {
        vec2 screenUV = gl_FragCoord.xy / viewportSize;
        FragColor = texture(texSampler, screenUV);
        return;
    }

    // ── EMISSIVE (unlit) ──────────────────────────────────────────────────────
    if (emissiveMode == 1) {
        FragColor = vec4(vertexColor.rgb * emissiveTint, 1.0);
        return;
    }

    float diffuse = max(0.0, dot(normalize(vertexNormal), normalize(sunDirection)));
    // Coloured lighting: ambient sky-bounce + tinted directional luminary (sun/moon).
    vec3 lit = ambientColor * ambientStrength + sunColor * (sunStrength * diffuse);

    // ── Torch point lights — warm pools of light that pop at night ─────────────
    vec3 nN = normalize(vertexNormal);
    for (int i = 0; i < torchCount; i++) {
        vec3  d    = torchPos[i] - vWorldPos;
        float dist = length(d);
        float att  = clamp(1.0 - dist / torchRad[i], 0.0, 1.0);
        att = att * att;                                   // soft quadratic falloff
        float ndl  = max(0.0, dot(nN, d / max(dist, 0.001)));
        lit += torchCol[i] * att * (0.35 + 0.65 * ndl);
    }

    // ── Base colour: vertex colour or texture × vertex colour ─────────────────
    vec4 baseColor;
    if (useTexture == 1) {
        vec4 texColor = texture(texSampler, vertexUV);
        baseColor = texColor * vertexColor;
    } else {
        baseColor = vertexColor;
    }

    vec3 color          = baseColor.rgb * lit;
    vec3 gammaCorrected = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 1.2));

    // ── ABYSS DEPTH DARKNESS ──────────────────────────────────────────────────
    float distBelow = cameraY - vWorldY;
    if (distBelow > 80.0) {
        float t         = (distBelow - 80.0) * 0.0055;
        float abyssFog  = clamp(1.0 - exp(-t), 0.0, 0.92);
        vec3  abyssColor = vec3(0.012, 0.006, 0.022);
        gammaCorrected  = mix(gammaCorrected, abyssColor, abyssFog);
    }

    // ── UNDERWATER FOG ────────────────────────────────────────────────────────
    if (isUnderwater == 1) {
        float depthDist    = gl_FragCoord.z / gl_FragCoord.w;
        float fogFactor    = 1.0 - exp(-depthDist * 0.15);
        vec3  waterFogColor = vec3(0.05, 0.20, 0.55);
        gammaCorrected     = mix(gammaCorrected, waterFogColor,
                                 clamp(fogFactor + 0.3, 0.0, 1.0));
    }

    // ── SNOW BIOME ATMOSPHERE ─────────────────────────────────────────────────
    if (snowAtmosphereStrength > 0.001) {
        // Darken the haze with the sky's light level so mountains don't glow pale
        // white at night (it used to be a fixed bright colour at all hours).
        float skyLight = clamp(ambientStrength * 1.5 + sunStrength * 0.8, 0.06, 1.0);
        vec3 snowAtmColor = vec3(0.68, 0.82, 0.96) * skyLight;
        gammaCorrected = mix(gammaCorrected, snowAtmColor, snowAtmosphereStrength);
    }

    // ── TIME SCALE VIGNETTE ───────────────────────────────────────────────────
    if (timeVignetteStrength > 0.001) {
        gammaCorrected = mix(gammaCorrected, timeVignetteColor, timeVignetteStrength);
    }

    // ── ABILITY OVERLAY VIGNETTE ──────────────────────────────────────────────
    // Applied after time vignette so abilities visually "win" during activation.
    if (overlayVignetteStrength > 0.001) {
        gammaCorrected = mix(gammaCorrected, overlayVignetteColor, overlayVignetteStrength);
    }

    // ── IMPACT DESATURATION (explosions, slow-mo, near-death) ─────────────────
    if (desaturate > 0.001) {
        float lum = dot(gammaCorrected, vec3(0.299, 0.587, 0.114));
        gammaCorrected = mix(gammaCorrected, vec3(lum), desaturate);
    }

             FragColor = vec4(gammaCorrected, baseColor.a * alphaMultiplier);
         }