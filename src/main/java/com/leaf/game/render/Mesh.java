package com.leaf.game.render;

import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

public class Mesh {
    private final int vaoId;
    private final int vboId;
    private final int eboId;
    private final int vertexCount;

    /** Standard mesh — 10 floats per vertex: x y z  r g b a  nx ny nz */
    public Mesh(float[] vertices, int[] indices) {
        this(vertices, indices, false);
    }

    /**
     * UV-aware mesh — 12 floats per vertex: x y z  r g b a  nx ny nz  u v
     * Pass hasUV = true when building chunk meshes that reference the block atlas.
     */
    public Mesh(float[] vertices, int[] indices, boolean hasUV) {
        this.vertexCount = indices.length;

        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        vboId = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        FloatBuffer verticesBuffer = MemoryUtil.memAllocFloat(vertices.length);
        verticesBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, verticesBuffer, GL_STATIC_DRAW);
        MemoryUtil.memFree(verticesBuffer);

        eboId = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboId);
        IntBuffer indicesBuffer = MemoryUtil.memAllocInt(indices.length);
        indicesBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indicesBuffer, GL_STATIC_DRAW);
        MemoryUtil.memFree(indicesBuffer);

        int stride = (hasUV ? 12 : 10) * Float.BYTES;

        // location 0 — position (3 floats)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);

        // location 1 — colour (4 floats, RGBA)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        // location 2 — normal (3 floats)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 7 * Float.BYTES);
        glEnableVertexAttribArray(2);

        if (hasUV) {
            // location 3 — UV (2 floats, atlas texture coordinates)
            glVertexAttribPointer(3, 2, GL_FLOAT, false, stride, 10 * Float.BYTES);
            glEnableVertexAttribArray(3);
        }

        glBindVertexArray(0);
    }

    public void render() {
        glBindVertexArray(vaoId);
        glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    public void cleanup() {
        glDeleteBuffers(vboId);
        glDeleteBuffers(eboId);
        glDeleteVertexArrays(vaoId);
    }
}