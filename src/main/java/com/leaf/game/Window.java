package com.leaf.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Window {
    private long window;

    // PHASE 2: Mouse tracking state
    // Arrays instead of plain floats because lambdas can only capture final/effectively-final variables.
    // A final array reference is legal; its contents can still change.
    private final double[] lastMouseX = {640.0};
    private final double[] lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};  // ignore the first delta (it's huge)

    public void run() {
        init();
        loop();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE,              GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE,            GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,        GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "Minecraft Clone", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        // Escape still closes the window
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(win, true);
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
    }

    private void setupMouseLook(Camera camera) {
        // PHASE 2: Lock cursor to window, hide it
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        // PHASE 2: Mouse movement callback — fires whenever mouse moves
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {

            // On first callback, just store position without moving camera.
            // The first event can have a huge delta (cursor jumps to center).
            if (firstMouse[0]) {
                lastMouseX[0] = xpos;
                lastMouseY[0] = ypos;
                firstMouse[0] = false;
                return;
            }

            // How much did the mouse move since last callback?
            float dx = (float)(xpos - lastMouseX[0]);
            float dy = (float)(ypos - lastMouseY[0]);
            lastMouseX[0] = xpos;
            lastMouseY[0] = ypos;

            // Apply rotation to camera
            // dx positive = mouse moved right = yaw increases (turn right)
            camera.yaw   += dx * GameConfig.mouseSensitivity;

            // dy positive = mouse moved down (screen Y goes top-to-bottom)
            //   = looking down = pitch decreases
            camera.pitch -= dy * GameConfig.mouseSensitivity;

            // Prevent flipping upside-down
            camera.clampPitch();
        });
    }

    // PHASE 2: Called every frame — reads keyboard, moves camera
    private void processInput(Camera camera, float deltaTime) {
        // glfwGetKey returns GLFW_PRESS if the key is currently held down.
        // This is checked every frame, not event-based.

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            // Move in the forward direction (flat, ignores pitch)
            Vector3f forward = camera.getForward();
            camera.position.add(forward.mul(GameConfig.moveSpeed * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            Vector3f forward = camera.getForward();
            camera.position.sub(forward.mul(GameConfig.moveSpeed * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            Vector3f right = camera.getRight();
            camera.position.add(right.mul(GameConfig.moveSpeed * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            Vector3f right = camera.getRight();
            camera.position.sub(right.mul(GameConfig.moveSpeed * deltaTime));
        }

        // Bonus: Space = fly up, Left Shift = fly down
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            camera.position.y += GameConfig.moveSpeed * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            camera.position.y -= GameConfig.moveSpeed * deltaTime;
        }
    }

    private void loop() {
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl"
        );

        Camera camera = new Camera();
        setupMouseLook(camera);

        // Player spawns up in the air
        Player player = new Player(16.0f, 60.0f, 16.0f);

        World world = new World();
        WorldGen worldGen = new WorldGen();

        Matrix4f model = new Matrix4f(); // Identity matrix (all blocks are built at world coordinates)

        double lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            double now       = glfwGetTime();
            float  deltaTime = (float)(now - lastTime);
            lastTime = now;

            player.update(window, camera, world, deltaTime);
            world.updateChunks(world, worldGen, player);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            shader.bind();

            Matrix4f view       = camera.getViewMatrix();
            Matrix4f projection = camera.getProjectionMatrix();

            // ── CHUNK RENDERING ──
            // Loop through all loaded chunks and render them individually
            for (Chunk chunk : world.getAllChunks()) {

                // If the chunk is dirty, rebuild its GPU Mesh
                if (chunk.dirty) {
                    if (chunk.mesh != null) {
                        chunk.mesh.cleanup(); // Clean up old GPU memory first!
                    }
                    chunk.mesh = world.buildChunkMesh(chunk);
                    chunk.dirty = false;
                }

                // If the chunk has a valid mesh, render it
                if (chunk.mesh != null) {
                    Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
                    shader.setUniform("mvp", mvp);
                    chunk.mesh.render();
                }
            }

            shader.unbind();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        // Clean up GPU memory for all chunks at exit
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) {
                chunk.mesh.cleanup();
            }
        }
        shader.cleanup();
    }
}