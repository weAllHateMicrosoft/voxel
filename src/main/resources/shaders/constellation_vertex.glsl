#version 330 core
layout(location = 0) in vec3 aDir;   // unit direction on the celestial sphere

// Match the uniform name with star_vertex.glsl.
// Upload the forward viewProj matrix (same one used for stars).
uniform mat4 viewProj;

out float vAlt;   // sin(altitude above horizon) — used for horizon fade in fragment

void main() {
    vec4 clip   = viewProj * vec4(aDir, 0.0);
    gl_Position = clip.xyww;

    // aDir is a unit vector; its Y component = sin(altitude).
    // 0.0 = on the horizon, 1.0 = directly overhead.
    vAlt = aDir.y;
}
