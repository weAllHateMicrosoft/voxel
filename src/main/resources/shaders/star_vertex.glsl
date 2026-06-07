#version 330 core
layout(location = 0) in vec3 aDir;
layout(location = 1) in vec2 aMagBv;

out vec3  vColor;
out float vBright;
out float vSeed;
out float vMag;

// Upload the FORWARD view*projection matrix here (not inverted).
// Change Window.java upload to: starShader.setMat4("viewProj", viewProjMatrix);
// (See INTEGRATION NOTES — the old code accidentally double-inverted, which worked
//  but wasted GPU. This version is clean.)
uniform mat4 viewProj;

vec3 bvToRGB(float bv) {
    float t = clamp((bv + 0.4) / 2.0, 0.0, 1.0);
    // More saturated colours — real star colours pop more than the old version
    vec3 blue   = vec3(0.60, 0.75, 1.00);   // hot blue stars (O/B type)
    vec3 white  = vec3(1.00, 1.00, 0.97);   // A/F type — nearly white
    vec3 yellow = vec3(1.00, 0.95, 0.75);   // G type — sun-like, warm white
    vec3 orange = vec3(1.00, 0.78, 0.50);   // K type — orange giants
    vec3 red    = vec3(1.00, 0.55, 0.35);   // M type — red supergiants (Betelgeuse)
    if (t < 0.25) return mix(blue,   white,  t / 0.25);
    if (t < 0.50) return mix(white,  yellow, (t - 0.25) / 0.25);
    if (t < 0.75) return mix(yellow, orange, (t - 0.50) / 0.25);
    return mix(orange, red, (t - 0.75) / 0.25);
}

void main() {
    vec4 clip   = viewProj * vec4(aDir, 0.0);
    gl_Position = clip.xyww;   // pin to far plane (depth = 1.0)

    float mag = aMagBv.x;

    // Point size: substantially larger than before so stars have visual weight.
    // Sirius (mag -1.46) → 11px,  Vega (0.03) → 9px,  Polaris (2.0) → 6px,
    // naked-eye limit (6.0) → 2px. The fragment shader then sculpts each into
    // a gaussian + halo, so the actual *visible* star is smaller than the sprite.
    gl_PointSize = clamp(9.5 - mag * 1.25, 2.0, 12.0);

    // Brightness: Pogson scale, compressed for display
    // Extra boost for very bright stars so they genuinely dominate the sky
    vBright = clamp(pow(2.512, (2.8 - mag) * 0.58), 0.06, 3.0);

    vColor  = bvToRGB(aMagBv.y);
    vSeed   = fract(sin(dot(aDir.xy, vec2(91.34, 47.21))) * 4521.17);
    vMag    = mag;
}
