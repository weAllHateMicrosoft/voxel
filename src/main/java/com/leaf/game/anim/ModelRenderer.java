package com.leaf.game.anim;

import com.leaf.game.render.Mesh;
import com.leaf.game.render.Shader;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * Renders a posed AnimModel into whatever framebuffer is currently bound.
 *
 * Call order:
 *   ModelRenderer.init()               — once, after GL context exists
 *   ModelRenderer.render(model, pose, world, view, projection)   — each frame
 *   ModelRenderer.cleanup()            — on shutdown
 *
 * The renderer keeps a Mesh cache keyed by (modelName + partId).
 * Meshes are regenerated if the PartDef's colour changes.
 */
public class ModelRenderer {

    private static Shader shader;

    // Cache: "modelName:partId" -> Mesh
    private static final Map<String, Mesh> meshCache = new HashMap<>();

    public static void init() {
        shader = new Shader(
                "src/main/resources/shaders/model_vertex.glsl",
                "src/main/resources/shaders/model_fragment.glsl");
    }

    /**
     * Render a posed model.
     *
     * @param model      the AnimModel definition
     * @param pose       part-id → local-space transform matrix (from AnimPlayer.getPose())
     * @param worldMat   where the model sits in the game world
     * @param view       camera view matrix
     * @param projection camera projection matrix
     */
    public static void render(AnimModel model, Map<String, Matrix4f> pose,
                               Matrix4f worldMat, Matrix4f view, Matrix4f projection) {
        if (shader == null) return;

        shader.bind();
        shader.setUniform("lightDir", new Vector3f(0.6f, 1f, 0.4f).normalize());
        shader.setUniform("ambient", 0.35f);

        glEnable(GL_DEPTH_TEST);

        for (PartDef part : model.parts) {
            Mesh mesh = getMesh(model, part);

            // MVP = projection × view × world × partPose
            Matrix4f partPose = pose.getOrDefault(part.id, new Matrix4f());
            Matrix4f mvp = new Matrix4f(projection).mul(view).mul(worldMat).mul(partPose);
            Matrix4f normalMat = new Matrix4f(worldMat).mul(partPose).invert().transpose();

            shader.setUniform("mvp", mvp);
            shader.setUniform("normalMat", normalMat);
            mesh.render();
        }

        shader.unbind();
    }

    private static Mesh getMesh(AnimModel model, PartDef part) {
        String key = model.name + ":" + part.id;
        return meshCache.computeIfAbsent(key, k -> BoxMesh.build(part));
    }

    /** Call when a part's colour or geometry changes (e.g. after editor edit). */
    public static void invalidateMesh(AnimModel model, String partId) {
        String key = model.name + ":" + partId;
        Mesh old = meshCache.remove(key);
        if (old != null) old.cleanup();
    }

    /** Invalidate all meshes for this model. */
    public static void invalidateModel(AnimModel model) {
        for (PartDef p : model.parts) invalidateMesh(model, p.id);
    }

    public static void cleanup() {
        for (Mesh m : meshCache.values()) m.cleanup();
        meshCache.clear();
        if (shader != null) { shader.cleanup(); shader = null; }
    }

    public static boolean isInitialised() { return shader != null; }
}
