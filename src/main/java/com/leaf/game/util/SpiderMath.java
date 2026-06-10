package com.leaf.game.util;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.List;

public final class SpiderMath {

    public static Vector3f DOWN_VECTOR = new Vector3f(0f, -1f, 0f);
    public static Vector3f UP_VECTOR = new Vector3f(0f, 1f, 0f);
    public static Vector3f FORWARD_VECTOR = new Vector3f(0f, 0f, 1f);
    public static Vector3f BACKWARD_VECTOR = new Vector3f(0f, 0f, -1f);
    public static Vector3f LEFT_VECTOR = new Vector3f(-1f, 0f, 0f);
    public static Vector3f RIGHT_VECTOR = new Vector3f(1f, 0f, 0f);

    private SpiderMath() {} // Static utility class

    // ── Vector Pitch & Yaw ──────────────────────────────────────────────────

    public static float pitch(Vector3f v) {
        return (float) -Math.atan2(v.y, Math.sqrt(v.x * v.x + v.z * v.z));
    }

    public static float yaw(Vector3f v) {
        return (float) -Math.atan2(-v.x, v.z);
    }

    // ── Vector Rotations & Transforms ───────────────────────────────────────

    public static Vector3f rotateAroundY(Vector3f v, double angle, Vector3f origin) {
        Vector3f temp = new Vector3f(v).sub(origin);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float rx = temp.x * cos - temp.z * sin;
        float rz = temp.x * sin + temp.z * cos;
        v.set(rx, temp.y, rz).add(origin);
        return v;
    }

    public static Vector3f getYXZRelative(Quaternionf current, Quaternionf pivot) {
        Quaternionf relative = new Quaternionf(pivot).difference(current);
        return relative.getEulerAnglesYXZ(new Vector3f());
    }

    // ── Vector Distances & Lengths ──────────────────────────────────────────

    public static float verticalDistance(Vector3f a, Vector3f b) {
        return Math.abs(a.y - b.y);
    }

    public static float horizontalDistance(Vector3f a, Vector3f b) {
        float x = a.x - b.x;
        float z = a.z - b.z;
        return (float) Math.sqrt(x * x + z * z);
    }

    public static float horizontalLength(Vector3f v) {
        return (float) Math.sqrt(v.x * v.x + v.z * v.z);
    }

    // ── Linear Interpolations & Clamping ────────────────────────────────────

    public static float lerp(float start, float end, float t) {
        return start * (1f - t) + end * t;
    }

    public static double lerp(double start, double end, double t) {
        return start * (1.0 - t) + end * t;
    }

    public static int lerpSafely(int start, int end, float t) {
        if (end == start) return start;
        int result = (int) lerp((float) start, (float) end, t);
        if (result == start && t != 0f) return moveTowards(start, end, 1);
        return result;
    }

    public static Vector3f lerp(Vector3f current, Vector3f other, float t) {
        current.x = current.x + (other.x - current.x) * t;
        current.y = current.y + (other.y - current.y) * t;
        current.z = current.z + (other.z - current.z) * t;
        return current;
    }

    public static Vector3f moveTowards(Vector3f current, Vector3f target, float speed) {
        Vector3f diff = new Vector3f(target).sub(current);
        float distance = diff.length();
        if (distance <= speed) {
            current.set(target);
        } else {
            current.add(diff.mul(speed / distance));
        }
        return current;
    }

    public static float moveTowards(float current, float target, float speed) {
        float distance = target - current;
        return Math.abs(distance) < speed ? target : current + speed * Math.signum(distance);
    }

    public static double moveTowards(double current, double target, double speed) {
        double distance = target - current;
        return Math.abs(distance) < speed ? target : current + speed * Math.signum(distance);
    }

    public static int moveTowards(int current, int target, int speed) {
        int distance = target - current;
        return Math.abs(distance) < speed ? target : current + speed * (int) Math.signum(distance);
    }

    // ── Conversions & Normalization ─────────────────────────────────────────

    public static float normalize(float value, float min, float max) {
        return (value - min) / (max - min);
    }

    public static float denormalize(float value, float min, float max) {
        return value * (max - min) + min;
    }

    public static double normalize(double value, double min, double max) {
        return (value - min) / (max - min);
    }

    public static double denormalize(double value, double min, double max) {
        return value * (max - min) + min;
    }

    public static float eased(float t) {
        return t * t * (3f - 2f * t);
    }

    // ── Matrix & Transform Helpers ──────────────────────────────────────────

    public static Vector3f getNormalFromTransformation(Matrix4f matrix) {
        Quaternionf rotation = new Quaternionf();
        matrix.getNormalizedRotation(rotation);
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(rotation);
        return forward.normalize();
    }

    public static Matrix4f shearMatrix(float xy, float xz, float yx, float yz, float zx, float zy) {
        return new Matrix4f(
                1f,  yx,  zx,  0f,
                xy,  1f,  zy,  0f,
                xz,  yz,  1f,  0f,
                0f,  0f,  0f,  1f
        );
    }

    public static Matrix4f shear(Matrix4f matrix, float xy, float xz, float yx, float yz, float zx, float zy) {
        return matrix.mul(shearMatrix(xy, xz, yx, yz, zx, zy));
    }

    // ── Collections Math ────────────────────────────────────────────────────

    public static Vector3f average(List<Vector3f> list) {
        Vector3f out = new Vector3f();
        if (list == null || list.isEmpty()) return out;
        for (Vector3f vector : list) {
            out.add(vector);
        }
        return out.mul(1f / list.size());
    }

    // ── Surface Projection ──────────────────────────────────────────────────

    /**
     * Projects {@code v} onto the plane whose normal is {@code surfaceNormal}.
     *
     * Result = v - (v · n̂) * n̂
     *
     * Used to redirect gravity/velocity so they slide along the terrain surface
     * rather than pulling the spider away from a wall it is currently climbing.
     *
     * Returns a NEW vector; neither input is modified.
     * If {@code surfaceNormal} has near-zero length the original vector is returned
     * unchanged, so the caller degrades gracefully back to world-space behaviour.
     */
    public static Vector3f projectOntoPlane(Vector3f v, Vector3f surfaceNormal) {
        float lenSq = surfaceNormal.lengthSquared();
        if (lenSq < 1e-6f) return new Vector3f(v);
        // normalise inline to avoid allocating a separate normalised copy
        float invLen = 1f / (float) Math.sqrt(lenSq);
        float nx = surfaceNormal.x * invLen;
        float ny = surfaceNormal.y * invLen;
        float nz = surfaceNormal.z * invLen;
        float dot = v.x * nx + v.y * ny + v.z * nz;
        return new Vector3f(v.x - dot * nx, v.y - dot * ny, v.z - dot * nz);
    }

    // ── Nested Classes (SplitDistance, SplitDistanceZone, Rect) ─────────────

    public static class SplitDistance {
        public float horizontal;
        public float vertical;

        public SplitDistance(float horizontal, float vertical) {
            this.horizontal = horizontal;
            this.vertical = vertical;
        }

        public SplitDistance clone() {
            return new SplitDistance(horizontal, vertical);
        }

        public SplitDistance scale(float factor) {
            return new SplitDistance(horizontal * factor, vertical * factor);
        }

        public SplitDistance lerp(SplitDistance target, float factor) {
            return new SplitDistance(
                    SpiderMath.lerp(this.horizontal, target.horizontal, factor),
                    SpiderMath.lerp(this.vertical, target.vertical, factor)
            );
        }
    }

    public static class SplitDistanceZone {
        public Vector3f center;
        public SplitDistance size;

        public SplitDistanceZone(Vector3f center, SplitDistance size) {
            this.center = new Vector3f(center);
            this.size = size;
        }

        public boolean contains(Vector3f point) {
            return horizontalDistance(center, point) <= size.horizontal
                    && verticalDistance(center, point) <= size.vertical;
        }

        public float getHorizontal() { return size.horizontal; }
        public float getVertical() { return size.vertical; }
    }

    public static class Rect {
        public float minX, minY, maxX, maxY;

        private Rect(float minX, float minY, float maxX, float maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        public static Rect fromMinMax(float minX, float minY, float maxX, float maxY) {
            return new Rect(minX, minY, maxX, maxY);
        }

        public static Rect fromCenter(float centerX, float centerY, float width, float height) {
            return new Rect(
                    centerX - width / 2f,
                    centerY - height / 2f,
                    centerX + width / 2f,
                    centerY + height / 2f
            );
        }

        public float getWidth() { return maxX - minX; }
        public float getHeight() { return maxY - minY; }

        public Rect clone() {
            return new Rect(minX, minY, maxX, maxY);
        }

        public Rect expand(float padding) {
            minX -= padding;
            minY -= padding;
            maxX += padding;
            maxY += padding;
            return this;
        }

        public Rect setYCenter(float center, float height) {
            minY = center - height / 2f;
            maxY = center + height / 2f;
            return this;
        }

        public Rect lerp(Rect other, float t) {
            minX = SpiderMath.lerp(minX, other.minX, t);
            minY = SpiderMath.lerp(minY, other.minY, t);
            maxX = SpiderMath.lerp(maxX, other.maxX, t);
            maxY = SpiderMath.lerp(maxY, other.maxY, t);
            return this;
        }

        public Rect set(Rect other) {
            minX = other.minX;
            minY = other.minY;
            maxX = other.maxX;
            maxY = other.maxY;
            return this;
        }
    }

    public static Vector3f getRotationAroundAxis(Vector3f v, Quaternionf pivot) {
        Quaternionf orientation = new Quaternionf().rotationTo(new Vector3f(0f, 0f, 1f), v);
        return getYXZRelative(orientation, pivot);
    }
}