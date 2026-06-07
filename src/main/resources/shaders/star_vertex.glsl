#version 330 core
layout(location = 0) in vec3 aDir;     // unit direction on the celestial sphere
layout(location = 1) in vec2 aMagBv;   // x = visual magnitude, y = B-V colour index

out vec3  vColor;
out float vBright;
out float vSeed;
out float vMag;

// The forward view*projection matrix (NOT inverted).
// In Window.java upload as:  starShader.setMat4("viewProj", viewProjMatrix);
// Your original code uploaded the INVERSE and called inverse() again in the shader
// (double-inversion = correct result, but wasteful). This is the clean version.
uniform mat4 viewProj;

// B-V colour index → approximate star colour (blue-white → white → orange → red).
vec3 bvToRGB(float bv) {
    float t = clamp((bv + 0.4) / 2.0, 0.0, 1.0);
    vec3 blue   = vec3(0.70, 0.80, 1.00);
    vec3 white  = vec3(1.00, 1.00, 0.98);
    vec3 orange = vec3(1.00, 0.82, 0.62);
    vec3 red    = vec3(1.00, 0.62, 0.45);
    if (t < 0.33) return mix(blue,  white,  t / 0.33);
    if (t < 0.66) return mix(white, orange, (t - 0.33) / 0.33);
    return mix(orange, red, (t - 0.66) / 0.34);
}

void main() {
    // Stars are at infinite distance: project the direction (w=0) onto the far plane.
    // w=0 means translation in the view matrix has no effect (correct for sky).
    vec4 clip   = viewProj * vec4(aDir, 0.0);
    gl_Position = clip.xyww;   // force z/w = 1.0 → far plane, behind all terrain

    float mag = aMagBv.x;

    // Point size: bright stars (low/negative mag) get larger sprites.
    // Sirius (mag -1.46) → ~9px,  Polaris (2.0) → ~5.8px,  limit (6.0) → ~1.5px
     gl_PointSize = clamp(14.0 - mag * 1.5, 3.0, 16.0);

    // Perceptual brightness (Pogson-ish, compressed for display)
    vBright = clamp(pow(2.512, (2.5 - mag) * 0.55), 0.08, 2.2);

    vColor = bvToRGB(aMagBv.y);
    vSeed  = fract(sin(dot(aDir.xy, vec2(91.34, 47.21))) * 4521.17);
    vMag   = mag;
}
