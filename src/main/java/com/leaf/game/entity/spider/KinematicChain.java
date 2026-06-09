package com.leaf.game.entity.spider;

import com.leaf.game.util.SpiderMath;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class KinematicChain {

    public Vector3f root;
    public List<ChainSegment> segments;

    public int maxIterations = 20;
    public float tolerance = 0.01f;

    public KinematicChain(Vector3f root, List<ChainSegment> segments) {
        this.root = new Vector3f(root);
        this.segments = segments;
    }

    /**
     * Executes the FABRIK algorithm to make the chain reach towards a target point.
     */
    public void fabrik(Vector3f target) {
        for (int i = 0; i < maxIterations; i++) {
            fabrikForward(target);
            fabrikBackward();

            if (getEndEffector().distanceSquared(target) < tolerance) {
                break;
            }
        }
    }

    /**
     * Straightens the entire chain perfectly along a specified rotation.
     */
    public void straightenDirection(Quaternionf rotation) {
        Vector3f position = new Vector3f(root);
        for (ChainSegment segment : segments) {
            Vector3f initDirection = new Vector3f(segment.initDirection).rotate(rotation);
            position.add(initDirection.mul(segment.length));
            segment.position.set(position);
        }
    }

    /**
     * FABRIK Pass 1: Pulls the end effector to the target, then works backwards to the root.
     */
    public void fabrikForward(Vector3f newPosition) {
        ChainSegment lastSegment = segments.get(segments.size() - 1);
        lastSegment.position.set(newPosition);

        for (int i = segments.size() - 1; i >= 1; i--) {
            ChainSegment previousSegment = segments.get(i);
            ChainSegment segment = segments.get(i - 1);
            moveSegment(segment.position, previousSegment.position, previousSegment.length);
        }
    }

    /**
     * FABRIK Pass 2: Snaps the root back to the attachment point, then works forwards to the end.
     */
    public void fabrikBackward() {
        moveSegment(segments.get(0).position, root, segments.get(0).length);

        for (int i = 1; i < segments.size(); i++) {
            ChainSegment previousSegment = segments.get(i - 1);
            ChainSegment segment = segments.get(i);
            moveSegment(segment.position, previousSegment.position, segment.length);
        }
    }

    /**
     * Moves `point` so it sits exactly `segmentLength` units away from `pullTowards`.
     */
    public void moveSegment(Vector3f point, Vector3f pullTowards, float segmentLength) {
        Vector3f direction = new Vector3f(pullTowards).sub(point).normalize();
        point.set(pullTowards).sub(direction.mul(segmentLength));
    }

    public Vector3f getEndEffector() {
        return segments.get(segments.size() - 1).position;
    }

    /**
     * Gets the sequence of raw directional vectors for each segment in the chain.
     */
    public List<Vector3f> getVectors() {
        List<Vector3f> vectors = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Vector3f previous = (i == 0) ? root : segments.get(i - 1).position;
            vectors.add(new Vector3f(segments.get(i).position).sub(previous));
        }
        return vectors;
    }

    /**
     * Gets the rotation of each joint relative to the previous joint.
     */
    public List<Quaternionf> getRelativeRotations(Quaternionf pivot) {
        List<Vector3f> vectors = getVectors();
        List<Quaternionf> rotations = new ArrayList<>(vectors.size());

        if (vectors.isEmpty()) return rotations;

        Vector3f firstEuler = SpiderMath.getRotationAroundAxis(vectors.get(0), pivot);
        Quaternionf firstRotation = new Quaternionf(pivot).rotateYXZ(firstEuler.y, firstEuler.x, 0f);

        for (int i = 0; i < vectors.size(); i++) {
            Vector3f current = vectors.get(i);
            if (i == 0) {
                rotations.add(new Quaternionf(firstRotation));
            } else {
                Vector3f previous = vectors.get(i - 1);
                rotations.add(new Quaternionf().rotationTo(previous, current));
            }
        }

        return rotations;
    }

    /**
     * Gets the absolute world rotation of each joint (for rendering the 3D models).
     */
    public List<Quaternionf> getRotations(Quaternionf pivot) {
        List<Quaternionf> rotations = getRelativeRotations(pivot);
        cumulateRotations(rotations);
        return rotations;
    }

    private void cumulateRotations(List<Quaternionf> rotations) {
        for (int i = 1; i < rotations.size(); i++) {
            rotations.get(i).mul(rotations.get(i - 1));
        }
    }
}