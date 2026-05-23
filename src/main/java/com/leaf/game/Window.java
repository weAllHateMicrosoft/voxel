package com.leaf.game;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;

public class Window {
    private long window;
    private Player player;
    private World world;

    private final double[]  lastMouseX = {640.0};
    private final double[]  lastMouseY = {360.0};
    private final boolean[] firstMouse = {true};

    // ImGui — glfw can init early, gl3 must wait until GL.createCapabilities() has run
    private final ImGuiImplGlfw imguiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imguiGl3  = new ImGuiImplGl3();
    private boolean showDebug = false;

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
        glfwWindowHint(GLFW_VISIBLE,               GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE,             GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,        GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "Minecraft Clone", NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(win, true);
            }
            if (key == GLFW_KEY_F3 && action == GLFW_RELEASE) {
                showDebug = !showDebug;
                glfwSetInputMode(window, GLFW_CURSOR,
                        showDebug ? GLFW_CURSOR_NORMAL : GLFW_CURSOR_DISABLED);
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        // ImGui context + GLFW backend can start now (no GL calls yet)
        ImGui.createContext();
        imguiGlfw.init(window, true); // 'true' = chain our key callback above
    }

    private void setupMouseLook(Camera camera) {
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (showDebug) return; // don't rotate camera while menu is open

            if (firstMouse[0]) {
                lastMouseX[0] = xpos;
                lastMouseY[0] = ypos;
                firstMouse[0] = false;
                return;
            }

            float dx = (float)(xpos - lastMouseX[0]);
            float dy = (float)(ypos - lastMouseY[0]);
            lastMouseX[0] = xpos;
            lastMouseY[0] = ypos;

            camera.yaw   += dx * GameConfig.mouseSensitivity;
            camera.pitch -= dy * GameConfig.mouseSensitivity;
            camera.clampPitch();
        });
    }

    private void loop() {
        // GL capabilities must be created before any GL call — including ImGui's GL3 backend
        GL.createCapabilities();
        imguiGl3.init("#version 330"); // <-- HERE, after createCapabilities(), not in init()

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.5f, 0.7f, 0.9f, 1.0f);

        Shader shader = new Shader(
                "src/main/resources/shaders/vertex.glsl",
                "src/main/resources/shaders/fragment.glsl"
        );

        Camera camera = new Camera();
        setupMouseLook(camera);

        this.player  = new Player(16.0f, 60.0f, 16.0f);
        this.world   = new World();
        WorldGen worldGen = new WorldGen();
        world.updateChunks(world, worldGen, player);

        Matrix4f model    = new Matrix4f();
        double   lastTime = glfwGetTime();

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

            for (Chunk chunk : world.getAllChunks()) {
                if (chunk.dirty) {
                    if (chunk.mesh != null) chunk.mesh.cleanup();
                    chunk.mesh = world.buildChunkMesh(chunk);
                    chunk.dirty = false;
                }
                if (chunk.mesh != null) {
                    Matrix4f mvp = new Matrix4f(projection).mul(view).mul(model);
                    shader.setUniform("mvp", mvp);
                    chunk.mesh.render();
                }
            }
            shader.unbind();

            // ImGui frame
            imguiGlfw.newFrame();
            ImGui.newFrame();
            if (showDebug) renderDebugMenu();
            ImGui.render();
            imguiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        // Cleanup
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) chunk.mesh.cleanup();
        }
        shader.cleanup();

        imguiGl3.dispose();   // <-- dispose(), not shutdown()
        imguiGlfw.dispose();  // <-- dispose(), not shutdown()
        ImGui.destroyContext();
    }

    private void renderDebugMenu() {
        ImGui.begin("Settings");  // Simple window title

        // ── FOV SLIDER ───────────────────────────────────────────
        float[] fov = { GameConfig.fov };
        if (ImGui.sliderFloat("FOV", fov, 30f, 120f)) {
            GameConfig.fov = fov[0];
        }

        // ── RENDER DISTANCE SLIDER ──────────────────────────────────
        int[] rd = { GameConfig.renderDistance };
        if (ImGui.sliderInt("Render Distance", rd, 2, 16)) {
            GameConfig.renderDistance = rd[0];
        }

        ImGui.end();
    }

    private void regenerateWorld(World world, WorldGen worldGen) {
        for (Chunk chunk : world.getAllChunks()) {
            if (chunk.mesh != null) chunk.mesh.cleanup();
        }
        world.clearAllChunks();
        worldGen.resetSeed(GameConfig.seed);
        player.position.y = 60.0f;
    }
}