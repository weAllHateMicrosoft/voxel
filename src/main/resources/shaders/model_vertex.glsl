#version 330 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in vec2 aUV;    // texture coords (zero for untextured box parts)

out vec4 vColor;
out vec3 vNormal;
out vec2 vUV;

uniform mat4 mvp;
uniform mat4 normalMat;

void main() {
    gl_Position = mvp * vec4(aPos, 1.0);
    vColor  = aColor;
    vNormal = normalize(mat3(normalMat) * aNormal);
    vUV     = aUV;
}
