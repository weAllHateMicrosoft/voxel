#version 330 core
layout(location = 0) in vec3 aDir;     // unit direction on the celestial sphere
layout(location = 1) in vec2 aMagBv;   // x = visual magnitude, y = B-V colour index

out vec3  vColor;
out float vBright;
out float vSeed;

uniform mat4 invViewProj;              // inverse(view*proj); inverted back to VP here

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
    // Treat the star as infinitely far: project the direction (w = 0) and pin to the
    // far plane so it sits behind all geometry.
    vec4 clip = inverse(invViewProj) * vec4(aDir, 0.0);
    gl_Position = clip.xyww;

    float mag = aMagBv.x;
    // Brighter (lower magnitude) → larger point + more intensity (Pogson-ish).
    gl_PointSize = clamp(5.2 - mag * 0.62, 1.2, 6.0);
    vBright      = clamp(pow(2.0, (1.6 - mag) * 0.62), 0.07, 1.7);
    vColor       = bvToRGB(aMagBv.y);
    vSeed        = fract(sin(dot(aDir.xy, vec2(91.34, 47.21))) * 4521.17);
}
