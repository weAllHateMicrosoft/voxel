package com.leaf.game.anim;

/**
 * Defines one rigid box-part of a model (e.g. "body", "head", "arm_l").
 *
 * Coordinate conventions (same as Minecraft):
 *   +Y = up, +X = right, +Z = toward viewer.
 *
 * The pivot is the local point around which this part rotates, expressed
 * in the part's own local space.  It is most naturally at the "joint" end
 * — e.g. for an arm the pivot is at the shoulder (top), for a leg at the hip.
 *
 * origin (ox, oy, oz) positions the pivot in the PARENT's local space
 * (or world space for root parts).  Think of it as "where the shoulder sits
 * relative to the centre of the torso".
 */
public class PartDef {
    public String id;
    public String parent;  // null for root

    // Box dimensions (full width/height/depth in world units)
    public float w = 0.5f, h = 0.5f, d = 0.5f;

    // Pivot point in local space (the rotation axis origin).
    // (0, h/2, 0) = top edge; (0, -h/2, 0) = bottom edge; (0,0,0) = centre.
    public float pivotX = 0f, pivotY = 0f, pivotZ = 0f;

    // Origin of THIS part's pivot in PARENT local space
    public float ox = 0f, oy = 0f, oz = 0f;

    // Default (rest-pose) Euler angles in degrees
    public float defaultRx = 0f, defaultRy = 0f, defaultRz = 0f;

    // Display color (RGBA 0..1)
    public float cr = 0.7f, cg = 0.7f, cb = 0.7f, ca = 1f;

    public PartDef() {}
}
