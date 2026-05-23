#version 330 core

in vec3 vertexColor;
out vec4 FragColor;

void main()
{
    // Apply gamma correction (typically 2.2) to make the lighting pop naturally
    vec3 gammaCorrected = pow(vertexColor, vec3(1.0 / 1.2));

    FragColor = vec4(gammaCorrected, 1.0);
}