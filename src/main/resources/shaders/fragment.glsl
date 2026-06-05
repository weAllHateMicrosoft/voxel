#version 330 core
in vec4  vertexColor;
in vec3  vertexNormal;
in float vWorldY;        // world-space Y of this fragment (from vertex shader)
in vec2  vertexUV;       // texture coordinates (zero when not a ModelMesh)
in vec3  vWorldPos;      // full world-space position (orbital scan)

uniform vec3  sunDirection;
uniform float sunStrength;
uniform float ambientStrength;
uniform int   isUnderwater;
uniform float cameraY;        // camera eye world-Y, set each frame from Java

// ── ORBITAL ANNIHILATION: lidar/topographic scan + environmental flash ────────
// An expanding wavefront that traces the 90° voxel edges in pitch darkness, plus
// a global white flash when the orbital laser strikes. All additive emission.
uniform int   orbActive;      // 1 = scan live
uniform vec3  orbEpicenter;   // world-space epicentre
uniform float orbRadius;      // current wavefront radius (world units, XZ)
uniform float orbWidth;       // wavefront band thickness
uniform float orbIntensity;   // emissive gain
uniform float orbFlash;       // environmental white flash (laser impact)

// ── TIME STOP: "negative film" domain (DIO's The World) ───────────────────────
uniform int   tsActive;       // 1 = domain live
uniform vec3  tsCenter;       // world-space domain centre (player's feet)
uniform float tsRadius;       // current domain radius (world units)
uniform float tsEdge;         // boundary fade / ring thickness

// ── RADAR SCOPE: range rings + bearing spokes + rotating sweep on the terrain ──
uniform int   vlActive;       // 1 = radar live
uniform vec3  vlCenter;       // scope origin (player position)
uniform float vlRadius;       // scope radius (world units)
uniform float vlAmount;       // 0→1→0 fade-in/out envelope
uniform float vlSweep;        // current sweep-arm angle (radians)

// ── DEPRIVATION DOMAIN (Water God Stance) ─────────────────────────────────────
// Golden absolute-defence hemisphere. Inside: hyper-real desaturated gold tint.
// Outside: cooler, darker — the world beyond the domain is less real.
// Domain boundary glows bright HDR gold (bloom picks this up as a searing ring).
uniform int   depActive;      // 1 = domain live
uniform vec3  depCenter;      // player's locked world position (feet)
uniform float depRadius;      // domain detection + visual radius (blocks)
uniform float depStrike;      // 0→1 strike flash intensity (decays ~6×/sec)
uniform float depSweep;       // current radius of the repeating detection sweep ring

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
    float light   = ambientStrength + sunStrength * diffuse;

    // ── Base colour: vertex colour or texture × vertex colour ─────────────────
    vec4 baseColor;
    if (useTexture == 1) {
        vec4 texColor = texture(texSampler, vertexUV);
        baseColor = texColor * vertexColor;
    } else {
        baseColor = vertexColor;
    }

    vec3 color          = baseColor.rgb * light;
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
        vec3 snowAtmColor = vec3(0.68, 0.82, 0.96);
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

    // ── ORBITAL "LIDAR" SCAN (additive — traces voxel edges in the dark) ──────
    if (orbActive == 1) {
        vec3  wp    = vWorldPos;
        vec3  dEdge = 0.5 - abs(fract(wp) - 0.5);      // 0 at a block boundary, 0.5 at centre
        vec3  nA    = abs(normalize(vertexNormal));    // the face's normal axis
        float lw    = 0.07;                            // wireframe line thickness (world units)
        // Lines on the two IN-PLANE axes of this face (exclude the normal axis,
        // which is always sitting exactly on a boundary).
        float lx = (1.0 - nA.x) * (1.0 - smoothstep(0.0, lw, dEdge.x));
        float ly = (1.0 - nA.y) * (1.0 - smoothstep(0.0, lw, dEdge.y));
        float lz = (1.0 - nA.z) * (1.0 - smoothstep(0.0, lw, dEdge.z));
        float wire = clamp(lx + ly + lz, 0.0, 1.0);    // bright on every 90° block edge

        // Expanding wavefront ring on the XZ plane — sweeps out across the terrain.
        float dist   = length(wp.xz - orbEpicenter.xz);
        float ring   = 1.0 - smoothstep(0.0, orbWidth, abs(dist - orbRadius));
        // Faint persistent contour map left behind the wavefront.
        float inside = (dist < orbRadius) ? (0.30 * (1.0 - dist / max(orbRadius, 0.001))) : 0.0;
        float mask   = max(ring, inside);

        // Green wireframe, white-hot at the wavefront.
        vec3 scanCol  = mix(vec3(0.15, 1.0, 0.35), vec3(1.0, 1.0, 1.0), ring);
        vec3 emission = scanCol * (wire * mask) * orbIntensity;
        // The leading edge sears even across flat faces.
        emission += vec3(0.6, 1.0, 0.7) * (ring * ring) * 0.6 * orbIntensity;

        gammaCorrected += emission;
    }

    // ── ENVIRONMENTAL FLASH — the laser impact lights up the dead world ───────
    if (orbFlash > 0.001) {
        gammaCorrected += vec3(0.85, 1.0, 0.9) * orbFlash;
    }

    // ── TIME STOP — expanding photographic-negative domain, shifted to DIO blue ──
    if (tsActive == 1) {
        float d = length(vWorldPos - tsCenter);          // 3D sphere from the feet
        // Inversion fades in from the surface inward so the boundary sweeps colour.
        float invAmt = smoothstep(tsRadius, tsRadius - tsEdge, d);   // 1 inside → 0 at surface
        if (invAmt > 0.001) {
            vec3  inv = vec3(1.0) - gammaCorrected;       // photographic negative
            vec3  neg = inv * vec3(0.60, 0.85, 1.40);     // shove toward cyan/electric blue
            float lum = dot(inv, vec3(0.299, 0.587, 0.114));
            neg = mix(neg, vec3(lum) * vec3(0.45, 0.70, 1.35), 0.30);  // 30% toward blue-mono
            gammaCorrected = mix(gammaCorrected, neg, invAmt);
        }
        // Searing electric-blue boundary ring riding the domain surface.
        float ring = 1.0 - smoothstep(0.0, tsEdge * 1.5, abs(d - tsRadius));
        gammaCorrected += vec3(0.30, 0.70, 1.0) * ring * 1.8;
    }

    // ── RADAR SCOPE — projected onto the real terrain so it hugs the 3D world ──
    // The ground becomes a dark-green radar scope with range rings + bearing
    // spokes + a bright outer ring, and a rotating sweep arm whose afterglow
    // fades opaque→transparent behind it, "pinging" the terrain as it passes.
    if (vlActive == 1) {
        vec2  rel  = vWorldPos.xz - vlCenter.xz;
        float dist = length(rel);
        float inside = 1.0 - smoothstep(vlRadius - 2.0, vlRadius + 1.0, dist);
        float amt = vlAmount * inside;
        if (amt > 0.001) {
            float ang = atan(rel.y, rel.x);            // bearing of this fragment

            // Dark radar-scope base (keeps a hint of terrain shading).
            float lum   = dot(gammaCorrected, vec3(0.299, 0.587, 0.114));
            vec3  scope = vec3(0.0, 0.045, 0.02) + vec3(0.0, 0.16, 0.06) * lum;

            // Range rings every 12 blocks.
            float rn   = dist / 12.0;
            float ring = 1.0 - smoothstep(0.0, 0.05, abs(rn - floor(rn + 0.5)));
            // Bearing spokes — 12 of them (every 30°).
            float an   = ang / 0.5235988;
            float spoke= 1.0 - smoothstep(0.0, 0.04, abs(an - floor(an + 0.5)));
            // Bright outer boundary ring.
            float outer= 1.0 - smoothstep(0.0, 2.0, abs(dist - vlRadius));

            // Rotating sweep + trailing afterglow (opaque at the arm → 0 behind it).
            float behind = mod(vlSweep - ang, 6.2831853);     // angle behind the arm
            float sweep  = 1.0 - smoothstep(0.0, 1.7, behind); // ~97° afterglow
            sweep *= sweep;
            float arm    = 1.0 - smoothstep(0.0, 0.045, behind); // razor leading edge

            vec3 grid = vec3(0.10, 1.0, 0.35);
            vec3 radar = scope;
            radar += grid * (ring * 0.35 + spoke * 0.28 + outer * 0.8);
            radar += grid * sweep * 0.45;                       // the ping wash
            radar += vec3(0.3, 1.0, 0.45) * arm * 0.7;          // sweep line (green, not white)

            gammaCorrected = mix(gammaCorrected, radar, amt);
        }
    }

    // ── DEPRIVATION DOMAIN: special "absolute domain" lighting + colour grade ──
    // Inside = desaturated gold (hyper-real standoff) with every voxel EDGE traced
    // in glowing gold (the world looks razor-cut and lethal); a gold detection ring
    // sweeps outward on repeat; surfaces facing the player catch a gold sheen.
    // Boundary = searing HDR gold ring. Outside = cooler, distant.
    if (depActive == 1) {
        float d3d    = length(vWorldPos - depCenter);
        float inside = 1.0 - smoothstep(depRadius - 1.5, depRadius + 0.5, d3d);

        // Inside: desaturate + warm gold tint
        float lum   = dot(gammaCorrected, vec3(0.299, 0.587, 0.114));
        vec3 desat  = mix(gammaCorrected, vec3(lum * 0.90), inside * 0.52);
        vec3 goldIn = mix(desat, desat * vec3(1.32, 1.08, 0.60), inside * 0.40);

        // Outside: cool blue-grey tint — the world beyond feels muted and distant
        float outside = smoothstep(depRadius - 2.0, depRadius + 2.0, d3d);
        vec3 coolOut  = gammaCorrected * mix(vec3(1.0), vec3(0.78, 0.82, 0.92), outside * 0.48);

        // Boundary ring: HDR gold burst — bloom turns this into a glowing edge halo
        float edge    = smoothstep(depRadius - 3.5, depRadius - 0.5, d3d)
                      * smoothstep(depRadius + 1.0, depRadius - 1.0, d3d);
        vec3 edgeGlow = vec3(3.2, 2.3, 0.55) * edge;

        gammaCorrected = mix(coolOut, goldIn, inside) + edgeGlow;

        // ── Golden voxel-edge rim: trace the 90° block edges inside the domain ──
        // (Same edge test as the orbital lidar.) Edges flare on every strike.
        vec3  dE  = 0.5 - abs(fract(vWorldPos) - 0.5);
        vec3  nAx = abs(normalize(vertexNormal));
        float elw = 0.06;
        float wEx = (1.0 - nAx.x) * (1.0 - smoothstep(0.0, elw, dE.x));
        float wEy = (1.0 - nAx.y) * (1.0 - smoothstep(0.0, elw, dE.y));
        float wEz = (1.0 - nAx.z) * (1.0 - smoothstep(0.0, elw, dE.z));
        float wire = clamp(wEx + wEy + wEz, 0.0, 1.0);
        float rimI = inside * (0.40 + 1.10 * depStrike);
        gammaCorrected += vec3(1.50, 1.05, 0.30) * wire * rimI;

        // ── Repeating detection sweep ring expanding from the player ──
        float sweepRing = 1.0 - smoothstep(0.0, 1.6, abs(d3d - depSweep));
        float sweepFade = 1.0 - depSweep / max(depRadius, 0.001);   // dim as it nears the edge
        gammaCorrected += vec3(1.30, 0.95, 0.28) * sweepRing * sweepFade * inside * 0.70;

        // ── Radial domain light: surfaces facing the player catch a gold sheen ──
        vec3  toCtr  = normalize(depCenter - vWorldPos);
        float facing = max(0.0, dot(normalize(vertexNormal), toCtr));
        gammaCorrected += vec3(0.90, 0.65, 0.18) * facing * inside * 0.18;

        // Strike flash: white-gold pulse searing through the entire scene
        if (depStrike > 0.001) {
            gammaCorrected += vec3(2.5, 1.9, 0.6) * depStrike * 0.38;
        }
    }

    FragColor = vec4(gammaCorrected, baseColor.a * alphaMultiplier);
}
