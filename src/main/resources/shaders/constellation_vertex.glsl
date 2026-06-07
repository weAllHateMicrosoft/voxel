#version 330 core
layout(location = 0) in vec3 aDir;   // unit direction on the celestial sphere

uniform mat4 invViewProj;

void main() {
    // Same infinitely-far technique as the star shader.
    vec4 clip = inverse(invViewProj) * vec4(aDir, 0.0);
    gl_Position = clip.xyww;   // pin to far plane, behind all terrain
}
