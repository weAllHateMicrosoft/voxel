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

    private static final float MOUSE_SENSITIVITY = 0.001f;
    private static final float MOVE_SPEED        = 2.0f;   // units per second

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
            camera.yaw   += dx * MOUSE_SENSITIVITY;

            // dy positive = mouse moved down (screen Y goes top-to-bottom)
            //   = looking down = pitch decreases
            camera.pitch -= dy * MOUSE_SENSITIVITY;

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
            camera.position.add(forward.mul(MOVE_SPEED * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            Vector3f forward = camera.getForward();
            camera.position.sub(forward.mul(MOVE_SPEED * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            Vector3f right = camera.getRight();
            camera.position.add(right.mul(MOVE_SPEED * deltaTime));
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            Vector3f right = camera.getRight();
            camera.position.sub(right.mul(MOVE_SPEED * deltaTime));
        }

        // Bonus: Space = fly up, Left Shift = fly down
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            camera.position.y += MOVE_SPEED * deltaTime;
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            camera.position.y -= MOVE_SPEED * deltaTime;
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

        // PHASE 3: Camera positioned outside the world looking in.
        // World is 32×16×32. Grass is at y=7. Standing above and in front.
        Camera camera = new Camera(new Vector3f(16.0f, 14.0f, 48.0f));
        setupMouseLook(camera);

        // PHASE 3: Build the world and its mesh
        World world = new World();
        Mesh worldMesh = world.buildMesh();

        // PHASE 3: Model matrix is identity — world blocks are already in world space
        Matrix4f model = new Matrix4f(); // identity by default

        double lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float deltaTime = (float)(now - lastTime);
            lastTime = now;

            processInput(camera, deltaTime);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            shader.bind();

            Matrix4f view       = camera.getViewMatrix();
            Matrix4f projection = camera.getProjectionMatrix();
            Matrix4f mvp        = new Matrix4f(projection).mul(view).mul(model);

            shader.setUniform("mvp", mvp);
            worldMesh.render();   // one draw call for the entire world
            shader.unbind();

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        worldMesh.cleanup();
        shader.cleanup();
    }
}