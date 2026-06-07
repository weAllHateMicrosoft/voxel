#version 330 core
in  vec3 vNormal;
out vec4 FragColor;

uniform vec3  sunDir;           // world-space direction toward the sun
uniform float moonVisibility;   // nightFactor * horizon_fade; fades during day / below horizon

// Phase uniforms — computed in DayNight.java, uploaded by Window.java
uniform float moonPhaseAngle;      // 0 = New Moon, PI = Full Moon
uniform float moonBrightLimbAngle; // angle in the moon's local disc plane toward the sun

void main() {
    vec3 n = normalize(vNormal);

    // ── Build the moon's local disc coordinate frame ──────────────────────────
    // The disc plane is defined by the sphere's surface normal (= view direction
    // for a billboard, or world normal for a real sphere mesh).
    // We need two tangent axes in that plane to reconstruct the 2-D disc position.
    vec3 up    = vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(n, up));      // horizontal axis on disc
    vec3 mUp   = normalize(cross(right, n));   // vertical axis on disc

    // Project this fragment's normal onto the disc plane → 2-D disc coords [-1,1]
    float dx = dot(n, right);
    float dy = dot(n, mUp);

    // ── Phase / crescent mask ─────────────────────────────────────────────────
    // The terminator is a great circle whose orientation is moonBrightLimbAngle.
    // In the disc plane, the bright limb direction is (cos(angle), sin(angle)).
    float bx = cos(moonBrightLimbAngle);
    float by = sin(moonBrightLimbAngle);

    // Signed distance from the terminator line in the disc plane.
    // Positive = on the illuminated side of the terminator.
    float terminatorDist = dx * bx + dy * by;

    // Phase angle: 0=New(dark), PI=Full(bright).
    // cos(phaseAngle): +1 at New (terminator hides everything),
    //                  -1 at Full (terminator behind, all lit).
    // The terminator shifts: at half-phase it's at the centre (x=0),
    // so we offset terminatorDist by cos(phaseAngle).
    float phaseOffset = cos(moonPhaseAngle);   // +1 new, 0 quarter, -1 full
    float lit = smoothstep(-0.02, 0.02, terminatorDist + phaseOffset);

    // Discard the truly dark hemisphere of the sphere mesh (back face of moon)
    if (dot(n, -normalize(sunDir)) < -0.85) discard;

    // ── Surface shading ───────────────────────────────────────────────────────
    vec3 regolith = vec3(0.88, 0.87, 0.82);   // warm grey regolith

    // Lambertian diffuse from sun (on the lit portion)
    float diff = max(0.0, dot(n, normalize(sunDir)));

    // Limb darkening: disc edge is dimmer
    float limb  = 0.60 + 0.40 * diff;
    float bright = diff * 0.94 * limb;

    // Earthshine: faint blue-ish glow on the dark side, strongest near new moon
    float earthshine = 0.045 * (1.0 - lit) * (1.0 - cos(moonPhaseAngle) * 0.5) * moonVisibility;

    vec3 col = regolith * bright * lit
             + vec3(0.08, 0.12, 0.22) * earthshine;

    // Discard fully dark pixels — lets sky / stars show through the crescent gap
    if (lit < 0.01 && earthshine < 0.005) discard;

    FragColor = vec4(col, moonVisibility * max(lit, earthshine * 8.0));
}
