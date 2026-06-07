#version 330 core
out vec4 FragColor;

uniform float nightFactor;    // fade lines during the day
uniform float lineAlpha;      // base opacity (user-toggled brightness)

void main() {
    // Faint blue-white lines — visible but never dominating the sky.
    vec3 col = vec3(0.55, 0.70, 1.0);
    FragColor = vec4(col, lineAlpha * nightFactor);
}
