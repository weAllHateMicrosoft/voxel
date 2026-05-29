#version 330 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aColor;
layout(location = 2) in vec3 aNormal;

out vec4 vColor;
out vec3 vNormal;

uniform mat4 mvp;
uniform mat4 normalMat;

void main() {
    gl_Position = mvp * vec4(aPos, 1.0);
    vColor  = aColor;
    vNormal = normalize(mat3(normalMat) * aNormal);
}
