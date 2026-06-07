#version 330 core
in vec3 vNormal;
out vec4 FragColor;

uniform float moonVisibility;
uniform float moonPhaseAngle;
uniform float lunarEclipseFactor;

void main() {
    vec3 n = normalize(vNormal);

    // We fake the light direction to simulate phases while the moon physically remains opposite the sun.
    vec3 virtualLight = normalize(vec3(sin(moonPhaseAngle), 0.0, cos(moonPhaseAngle)));

    // Clean, crisp terminator line
    float diff = max(0.0, dot(n, virtualLight));
    float lit = smoothstep(0.02, 0.1, diff);

    vec3 brightColor = vec3(0.95, 0.94, 0.90);
    vec3 darkColor = vec3(0.08, 0.12, 0.20) * 0.4; // Faint earthshine

    vec3 finalCol = mix(darkColor, brightColor, lit);

    // Add a tiny bit of spherical pop to the bright side
    finalCol *= (0.6 + 0.4 * diff);

    // Blood Moon Eclipse
    if (lunarEclipseFactor > 0.01) {
        vec3 bloodCol = vec3(0.6, 0.1, 0.05);
        finalCol = mix(finalCol, bloodCol, lunarEclipseFactor);
    }

    FragColor = vec4(finalCol, moonVisibility);
}