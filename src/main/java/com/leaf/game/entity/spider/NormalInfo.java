package com.leaf.game.entity.spider;

import org.joml.Vector3f;
import java.util.List;

/**
 * Holds the result of the normal-force calculation.
 * Most fields are nullable and only used for debug rendering.
 */
public class NormalInfo {
    /** The direction the body should accelerate toward (normalised). */
    public Vector3f       normal;
    /** The point on the support polygon the force is applied from. Nullable. */
    public Vector3f       origin;
    /** The 3D foot positions that form the support polygon. Nullable. */
    public List<Vector3f> contactPolygon;
    /** The computed centre of mass used for this calculation. Nullable. */
    public Vector3f       centreOfMass;

    public NormalInfo(Vector3f normal, Vector3f origin,
                      List<Vector3f> contactPolygon, Vector3f centreOfMass) {
        this.normal        = normal;
        this.origin        = origin;
        this.contactPolygon = contactPolygon;
        this.centreOfMass  = centreOfMass;
    }
}
