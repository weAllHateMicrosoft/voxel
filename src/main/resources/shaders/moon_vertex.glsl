#version 330 core
layout(location = 0) in vec3 aPos;      // unit sphere surface point
layout(location = 2) in vec3 aNormal;   // = aPos for a unit sphere

out vec3 vNormal;   // world-space normal (= local normal; no rotation applied)

uniform mat4 mvp;

void main() {
    gl_Position = mvp * vec4(aPos, 1.0);
    vNormal     = normalize(aNormal);   // pass through (translate+scale preserves direction)
}
