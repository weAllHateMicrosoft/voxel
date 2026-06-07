#version 330 core
in  vec3 vNormal;
out vec4 FragColor;

uniform vec3  sunDir;
uniform float moonVisibility;

void main() {
    // Normal of the 3D sphere
    vec3 n = normalize(vNormal);
    // Direction TO the sun
    vec3 L = normalize(sunDir);

    // Standard 3D lighting naturally creates the crescent shape!
    float diff = dot(n, L);

    // Smooth step creates a crisp, clean terminator line (no noise/craters)
    float lit = smoothstep(-0.02, 0.05, diff);

    // Clean colors: Bright pale-yellow for the lit side, dark blue-grey for the unlit side
    vec3 moonBase = vec3(0.95, 0.95, 0.92);
    vec3 moonDark = vec3(0.08, 0.08, 0.12);

    vec3 col = mix(moonDark, moonBase, lit);

    // Give the bright crescent a very slight emissive pop
    if (lit > 0.5) {
        col *= 1.1;
    }

    // IMPORTANT: No 'discard'.
    // The dark side of the moon is solid rock; it physically blocks the stars behind it.
    FragColor = vec4(col, moonVisibility);
}