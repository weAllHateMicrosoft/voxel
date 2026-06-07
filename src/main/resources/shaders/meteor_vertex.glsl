#version 330 core
// Each vertex of a meteor trail encodes:
//   location 0: vec3 position   (world-space direction, not normalised — we use distance from head)
//   location 1: float trailFrac (0.0 = meteor head, 1.0 = tail end)
//   location 2: float brightness (0.0 = faint, 1.0 = fireball)

layout(location = 0) in vec3  aPos;
layout(location = 1) in float aTrailFrac;
layout(location = 2) in float aBrightness;

out float vTrailFrac;
out float vBrightness;
out float vWidth;    // for geometry expansion (if using line trick — see note)

uniform mat4 viewProj;

void main() {
    vec4 clip   = viewProj * vec4(aPos, 0.0);   // w=0 = at infinity (sky)
    gl_Position = clip.xyww;

    vTrailFrac  = aTrailFrac;
    vBrightness = aBrightness;

    // Point size varies along trail: head is brightest and largest
    float headSize = mix(1.0, 6.0, aBrightness);   // fireballs get bigger heads
    gl_PointSize = headSize * (1.0 - aTrailFrac * 0.85);
}
