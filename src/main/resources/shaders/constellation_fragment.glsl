#version 330 core
out vec4 FragColor;

uniform float nightFactor;    // fade lines during the day
uniform float lineAlpha;      // base opacity (user-toggled brightness)

// Pass altitude from vertex shader so we can fade near horizon.
// See constellation_vertex.glsl — needs a new "vAlt" varying.
in float vAlt;

void main() {
    // Faint blue-white lines.
    vec3 col = vec3(0.55, 0.70, 1.0);

    // Fade out near the horizon (< 10°) to avoid cluttering the ground boundary.
    float horizonFade = smoothstep(0.0, 0.12, vAlt);   // vAlt is sin(altitude), 0 at horizon

    float alpha = lineAlpha * nightFactor * horizonFade;
    if (alpha < 0.005) discard;

    FragColor = vec4(col, alpha);
}
