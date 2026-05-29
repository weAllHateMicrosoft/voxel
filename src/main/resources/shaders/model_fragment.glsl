#version 330 core

in  vec4 vColor;
in  vec3 vNormal;
out vec4 FragColor;

uniform vec3  lightDir;   // normalized world-space light direction
uniform float ambient;    // 0..1

void main() {
    float diff  = max(dot(vNormal, lightDir), 0.0);
    float light = ambient + (1.0 - ambient) * diff;
    FragColor   = vec4(vColor.rgb * light, vColor.a);
}
