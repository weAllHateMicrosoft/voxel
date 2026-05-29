package com.leaf.game.anim;

import com.leaf.game.render.Mesh;

/**
 * Generates a Mesh for one box-part (w × h × d), centred at the origin.
 * The box origin is at (0,0,0); the geometry spans [-w/2..+w/2] on X,
 * [-h/2..+h/2] on Y, [-d/2..+d/2] on Z  — UNLESS a pivot offset is given,
 * in which case the box is shifted so the pivot sits at the origin.
 *
 * Vertex format (matches Mesh): x y z  r g b a  nx ny nz  — 10 floats
 */
public class BoxMesh {

    /**
     * Build a box centred at (0,0,0) with the given colour (rgba 0..1).
     * pivotX/Y/Z shifts the geometry so the pivot point sits at origin.
     */
    public static Mesh build(PartDef p) {
        float hw = p.w * 0.5f, hh = p.h * 0.5f, hd = p.d * 0.5f;
        // Offset geometry so the pivot is at origin
        float bx = -p.pivotX, by = -p.pivotY, bz = -p.pivotZ;

        float r = p.cr, g = p.cg, b = p.cb, a = p.ca;

        // Face normals
        float[] UP    = {  0,  1,  0 };
        float[] DOWN  = {  0, -1,  0 };
        float[] NORTH = {  0,  0, -1 };
        float[] SOUTH = {  0,  0,  1 };
        float[] EAST  = {  1,  0,  0 };
        float[] WEST  = { -1,  0,  0 };

        // Darken side/bottom faces slightly for natural depth cues (fixed shading override)
        // The model shader also applies directional light; these multipliers only tweak vertex color.
        float[] verts = buildFace6(bx, by, bz, hw, hh, hd, r, g, b, a);

        // Box has 6 faces × 4 vertices × 10 floats = 240 floats total
        // Build face by face
        float[] vertices = new float[6 * 4 * 10];
        int vi = 0;

        // ── TOP (y = +hh) ────────────────────────────────────────────────────
        vi = addFace(vertices, vi,
            bx-hw, by+hh, bz-hd,  r, g, b, a,  UP[0], UP[1], UP[2],
            bx+hw, by+hh, bz-hd,  r, g, b, a,  UP[0], UP[1], UP[2],
            bx+hw, by+hh, bz+hd,  r, g, b, a,  UP[0], UP[1], UP[2],
            bx-hw, by+hh, bz+hd,  r, g, b, a,  UP[0], UP[1], UP[2]);

        // ── BOTTOM (y = -hh) ─────────────────────────────────────────────────
        float db = 0.7f;
        vi = addFace(vertices, vi,
            bx-hw, by-hh, bz+hd,  r*db, g*db, b*db, a,  DOWN[0], DOWN[1], DOWN[2],
            bx+hw, by-hh, bz+hd,  r*db, g*db, b*db, a,  DOWN[0], DOWN[1], DOWN[2],
            bx+hw, by-hh, bz-hd,  r*db, g*db, b*db, a,  DOWN[0], DOWN[1], DOWN[2],
            bx-hw, by-hh, bz-hd,  r*db, g*db, b*db, a,  DOWN[0], DOWN[1], DOWN[2]);

        // ── SOUTH (+z face) ──────────────────────────────────────────────────
        float ds = 0.85f;
        vi = addFace(vertices, vi,
            bx-hw, by-hh, bz+hd,  r*ds, g*ds, b*ds, a,  SOUTH[0], SOUTH[1], SOUTH[2],
            bx+hw, by-hh, bz+hd,  r*ds, g*ds, b*ds, a,  SOUTH[0], SOUTH[1], SOUTH[2],
            bx+hw, by+hh, bz+hd,  r*ds, g*ds, b*ds, a,  SOUTH[0], SOUTH[1], SOUTH[2],
            bx-hw, by+hh, bz+hd,  r*ds, g*ds, b*ds, a,  SOUTH[0], SOUTH[1], SOUTH[2]);

        // ── NORTH (-z face) ──────────────────────────────────────────────────
        vi = addFace(vertices, vi,
            bx+hw, by-hh, bz-hd,  r*ds, g*ds, b*ds, a,  NORTH[0], NORTH[1], NORTH[2],
            bx-hw, by-hh, bz-hd,  r*ds, g*ds, b*ds, a,  NORTH[0], NORTH[1], NORTH[2],
            bx-hw, by+hh, bz-hd,  r*ds, g*ds, b*ds, a,  NORTH[0], NORTH[1], NORTH[2],
            bx+hw, by+hh, bz-hd,  r*ds, g*ds, b*ds, a,  NORTH[0], NORTH[1], NORTH[2]);

        // ── EAST (+x face) ───────────────────────────────────────────────────
        float de = 0.9f;
        vi = addFace(vertices, vi,
            bx+hw, by-hh, bz+hd,  r*de, g*de, b*de, a,  EAST[0], EAST[1], EAST[2],
            bx+hw, by-hh, bz-hd,  r*de, g*de, b*de, a,  EAST[0], EAST[1], EAST[2],
            bx+hw, by+hh, bz-hd,  r*de, g*de, b*de, a,  EAST[0], EAST[1], EAST[2],
            bx+hw, by+hh, bz+hd,  r*de, g*de, b*de, a,  EAST[0], EAST[1], EAST[2]);

        // ── WEST (-x face) ───────────────────────────────────────────────────
        vi = addFace(vertices, vi,
            bx-hw, by-hh, bz-hd,  r*de, g*de, b*de, a,  WEST[0], WEST[1], WEST[2],
            bx-hw, by-hh, bz+hd,  r*de, g*de, b*de, a,  WEST[0], WEST[1], WEST[2],
            bx-hw, by+hh, bz+hd,  r*de, g*de, b*de, a,  WEST[0], WEST[1], WEST[2],
            bx-hw, by+hh, bz-hd,  r*de, g*de, b*de, a,  WEST[0], WEST[1], WEST[2]);

        // Indices: 6 faces × 2 triangles × 3 indices = 36
        int[] indices = new int[36];
        int ii = 0;
        for (int f = 0; f < 6; f++) {
            int base = f * 4;
            indices[ii++] = base;     indices[ii++] = base + 1; indices[ii++] = base + 2;
            indices[ii++] = base + 2; indices[ii++] = base + 3; indices[ii++] = base;
        }

        return new Mesh(vertices, indices);
    }

    // Helper: write 4 vertices of a quad into the flat array
    private static int addFace(float[] v, int i,
            float x0, float y0, float z0, float r0, float g0, float b0, float a0, float nx, float ny, float nz,
            float x1, float y1, float z1, float r1, float g1, float b1, float a1, float _nx, float _ny, float _nz,
            float x2, float y2, float z2, float r2, float g2, float b2, float a2, float nx2, float ny2, float nz2,
            float x3, float y3, float z3, float r3, float g3, float b3, float a3, float nx3, float ny3, float nz3) {
        v[i++]=x0; v[i++]=y0; v[i++]=z0; v[i++]=r0; v[i++]=g0; v[i++]=b0; v[i++]=a0; v[i++]=nx;  v[i++]=ny;  v[i++]=nz;
        v[i++]=x1; v[i++]=y1; v[i++]=z1; v[i++]=r1; v[i++]=g1; v[i++]=b1; v[i++]=a1; v[i++]=_nx; v[i++]=_ny; v[i++]=_nz;
        v[i++]=x2; v[i++]=y2; v[i++]=z2; v[i++]=r2; v[i++]=g2; v[i++]=b2; v[i++]=a2; v[i++]=nx2; v[i++]=ny2; v[i++]=nz2;
        v[i++]=x3; v[i++]=y3; v[i++]=z3; v[i++]=r3; v[i++]=g3; v[i++]=b3; v[i++]=a3; v[i++]=nx3; v[i++]=ny3; v[i++]=nz3;
        return i;
    }

    // unused overload suppressor
    private static float[] buildFace6(float bx, float by, float bz, float hw, float hh, float hd,
                                       float r, float g, float b, float a) { return new float[0]; }
}
