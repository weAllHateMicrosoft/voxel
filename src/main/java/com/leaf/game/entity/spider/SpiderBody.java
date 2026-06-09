package com.leaf.game.entity.spider;

import com.leaf.game.util.SpiderMath;
import com.leaf.game.world.World;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Faithful Java port of SpiderBody.kt + Behaviour.kt (rotateTowards / walkAt)
 * + polygons.kt (pointInPolygon / nearestPointInPolygon).
 *
 * This class owns the full physics simulation of the spider's body:
 *   • gravity, drag, bounce
 *   • rotational velocity & drag
 *   • body-height correction (legs push body up to preferred height)
 *   • normal-force calculation from grounded leg polygon
 *   • leg update ordering and delegation
 *
 * Driving it each tick:
 *   1. Call rotateTowards(targetDir)  — sets rotational acceleration
 *   2. Call walkAt(targetVelocity)    — sets linear acceleration
 *   3. Call update()                  — runs full physics + all leg updates
 *
 * For a chase behaviour (equivalent to TargetBehaviour):
 *   Vector3f dir = new Vector3f(target).sub(position).normalize();
 *   body.rotateTowards(dir);
 *   float decel = (vel*vel)/(2*gait.moveAcceleration);
 *   if (horizontalDist > stopDist + decel) body.walkAt(new Vector3f(dir).mul(gait.maxSpeed));
 *   else                                   body.walkAt(new Vector3f(0,0,0));
 *   body.update();
 */
public class SpiderBody {

    // ── World & configuration ─────────────────────────────────────────────────
    public final World world;
    public final Vector3f position;
    public final Quaternionf orientation;
    public BodyPlan bodyPlan;
    public Gait walkGait;
    public Gait gallopGait;

    // ── Runtime params ────────────────────────────────────────────────────────
    public boolean gallop = false;
    public Gait gait() { return gallop ? gallopGait : walkGait; }

    // ── Debug ─────────────────────────────────────────────────────────────────
    /** Set true to skip the FABRIK solve (useful while debugging leg positions). */
    public boolean disableFabrik = false;

    // ── Legs ──────────────────────────────────────────────────────────────────
    public List<Leg> legs = new ArrayList<>();

    /** Called when the body hits the ground after a fall. Wire up your impact sound here. */
    public interface HitGroundListener { void onHitGround(SpiderBody spider); }
    private HitGroundListener hitGroundListener;
    public void setHitGroundListener(HitGroundListener l) { this.hitGroundListener = l; }

    // ── Physics state ─────────────────────────────────────────────────────────
    public boolean onGround = false;
    public NormalInfo normal = null;
    public final Vector3f velocity           = new Vector3f();
    public final Vector3f rotationalVelocity = new Vector3f();   // (pitch, yaw, roll) Euler rates
    public final Vector3f normalAcceleration = new Vector3f();

    // ── Orientation memos (recomputed each tick) ───────────────────────────────
    public float preferredPitch;
    public float preferredRoll;
    public Quaternionf preferredOrientation;

    // ── Locomotion state ──────────────────────────────────────────────────────
    public boolean isWalking      = false;
    public boolean isRotatingYaw  = false;

    // ── Construction ──────────────────────────────────────────────────────────

    public SpiderBody(World world, Vector3f position, Quaternionf orientation,
                      BodyPlan bodyPlan, Gait walkGait, Gait gallopGait) {
        this.world       = world;
        this.position    = new Vector3f(position);
        this.orientation = new Quaternionf(orientation);
        this.bodyPlan    = bodyPlan;
        this.walkGait    = walkGait;
        this.gallopGait  = gallopGait;

        Vector3f euler = orientation.getEulerAnglesYXZ(new Vector3f());
        this.preferredPitch       = euler.x;
        this.preferredRoll        = euler.z;
        this.preferredOrientation = new Quaternionf(orientation);
    }

    /**
     * Convenience constructor: build from a world position + yaw angle (radians).
     */
    public static SpiderBody fromPosition(World world, Vector3f position, float yawRadians,
                                          BodyPlan bodyPlan, Gait walkGait, Gait gallopGait) {
        Quaternionf orientation = new Quaternionf().rotationYXZ(yawRadians, 0f, 0f);
        return new SpiderBody(world, position, orientation, bodyPlan, walkGait, gallopGait);
    }

    // ── Public utils ──────────────────────────────────────────────────────────

    /** The direction the spider is currently facing. */
    public Vector3f forwardDirection() {
        return new Vector3f(0f, 0f, 1f).rotate(orientation);
    }

    /**
     * Interpolated gait between stationary and moving, based on current speed.
     * While rotating the spider always uses the moving gait (wider trigger zones).
     */
    public LerpGait lerpedGait() {
        if (isRotatingYaw) return gait().moving.clone();
        float speedFraction = velocity.length() / gait().maxSpeed;
        speedFraction = Math.min(speedFraction, 1f);
        return gait().stationary.clone().lerp(gait().moving, speedFraction);
    }

    /**
     * Teleports the spider to a new position, dragging all end-effectors with it
     * so legs don't snap to the wrong place.
     */
    public void teleport(Vector3f newPosition) {
        Vector3f diff = new Vector3f(newPosition).sub(position);
        position.set(newPosition);
        for (Leg leg : legs) {
            leg.endEffector.add(diff);
        }
    }

    /**
     * Applies an angular impulse (used for knockback, trident hits, etc.).
     * axis: world-space rotation axis, angle: radians.
     */
    public void accelerateRotation(Vector3f axis, float angle) {
        Quaternionf acceleration = new Quaternionf().rotateAxis(angle, axis);
        Quaternionf oldVelocity  = new Quaternionf().rotationYXZ(
                rotationalVelocity.y, rotationalVelocity.x, rotationalVelocity.z);

        Quaternionf rotVelocity = acceleration.mul(oldVelocity);
        rotVelocity.getEulerAnglesYXZ(rotationalVelocity);
    }

    // ── Locomotion API (called before update() each tick) ────────────────────

    /**
     * Smoothly rotates the spider's orientation toward targetVector.
     * Clamps pitch/roll to preferred angles, respects leg comfort constraints.
     * Equivalent to the private rotateTowards extension in Behaviour.kt.
     */
    public void rotateTowards(Vector3f targetVector) {
        Vector3f currentEuler = orientation.getEulerAnglesYXZ(new Vector3f());

        Vector3f targetEuler = new Quaternionf()
                .rotationTo(new Vector3f(0f, 0f, 1f), targetVector)
                .getEulerAnglesYXZ(new Vector3f());

        // clamp pitch to preferred ± leeway
        Gait g = gait();
        targetEuler.x = Math.max(preferredPitch - g.preferredPitchLeeway,
                        Math.min(preferredPitch + g.preferredPitchLeeway, targetEuler.x));

        // clamp roll to preferred
        targetEuler.z = preferredRoll;

        // freeze yaw if any leg is uncomfortable and not currently stepping
        boolean anyUncomfortable = false;
        for (Leg leg : legs) {
            if (leg.isUncomfortable() && !leg.isMoving) { anyUncomfortable = true; break; }
        }
        if (anyUncomfortable) targetEuler.y = currentEuler.y;

        // compute euler diff with yaw wrap-around
        Vector3f diffEuler = new Vector3f(targetEuler).sub(currentEuler);
        if (diffEuler.y >  Math.PI) diffEuler.y -= (float)(2 * Math.PI);
        if (diffEuler.y < -Math.PI) diffEuler.y += (float)(2 * Math.PI);

        isRotatingYaw = Math.abs(diffEuler.x) + Math.abs(diffEuler.y) + Math.abs(diffEuler.z) > 0.001f;

        // lerp the diff toward zero (smoothing)
        diffEuler.lerp(new Vector3f(0f, 0f, 0f), g.rotationLerp);

        // convert to premultiplied rotational acceleration
        Quaternionf diff       = new Quaternionf().rotationYXZ(diffEuler.y, diffEuler.x, diffEuler.z);
        Quaternionf invOrient  = new Quaternionf(orientation).invert();
        Quaternionf conjugated = new Quaternionf(orientation).mul(diff).mul(invOrient);

        Vector3f conjugatedEuler = conjugated.getEulerAnglesYXZ(new Vector3f());

        int groundedCount = 0;
        for (Leg leg : legs) if (leg.isGrounded()) groundedCount++;
        float maxAcceleration = g.rotateAcceleration
                * (legs.isEmpty() ? 0f : (float) groundedCount / legs.size());

        SpiderMath.moveTowards(rotationalVelocity, conjugatedEuler, maxAcceleration);
    }

    /**
     * Accelerates the spider's horizontal velocity toward targetVelocity.
     * Slows down if legs are uncomfortable (outside comfort zone).
     * Equivalent to the private walkAt extension in Behaviour.kt.
     *
     * Pass new Vector3f(0,0,0) to decelerate to a stop.
     */
    public void walkAt(Vector3f targetVelocity) {
        Gait g = gait();

        boolean anyUncomfortable = false;
        for (Leg leg : legs) {
            if (leg.isUncomfortable() && !leg.isMoving) { anyUncomfortable = true; break; }
        }

        Vector3f target = new Vector3f(targetVelocity);

        if (anyUncomfortable) {
            target.y = velocity.y;
            target.mul(g.uncomfortableSpeedMultiplier);
            SpiderMath.moveTowards(velocity, target, g.moveAcceleration);
            isWalking = targetVelocity.x != 0f || targetVelocity.z != 0f;  // ← && and targetVelocity
        } else {
            target.y = velocity.y;
            SpiderMath.moveTowards(velocity, target, g.moveAcceleration);
            isWalking = targetVelocity.x != 0f || targetVelocity.z != 0f;  // ← && and targetVelocity
        }
    }

    // ── Main tick ─────────────────────────────────────────────────────────────

    /**
     * Run one full physics + IK tick.
     * Call rotateTowards() and walkAt() before this each frame.
     */
    public void update() {
        // Lazily initialise legs on the first tick
        if (legs.isEmpty()) {
            initLegs();
            if (legs.isEmpty()) return;   // no legs configured — nothing to do
        }

        updatePreferredAngles();

        int groundedCount = 0;
        for (Leg leg : legs) if (leg.isGrounded()) groundedCount++;
        float fractionGrounded = legs.isEmpty() ? 0f : (float) groundedCount / legs.size();

        // ── gravity & vertical air drag ──────────────────────────────────────
        Gait g = gait();
        velocity.y -= g.gravityAcceleration;
        velocity.y *= (1f - g.airDragCoefficient);

        // ── apply rotational velocity to orientation ──────────────────────────
        Quaternionf rotVel = new Quaternionf()
                .rotationYXZ(rotationalVelocity.y, rotationalVelocity.x, rotationalVelocity.z);
        orientation.set(rotVel.mul(orientation));

        // ── horizontal leg drag (only when not actively walking) ──────────────
        if (!isWalking) {
            float legDrag = 1f - g.groundDragCoefficient * fractionGrounded;
            velocity.x *= legDrag;
            velocity.z *= legDrag;
        }

        // ── rotational drag ───────────────────────────────────────────────────
        float rotDrag = 1f - g.rotationalDragCoefficient * fractionGrounded;
        rotationalVelocity.mul(rotDrag);

        // ── extra drag when body is resting on terrain ────────────────────────
        if (onGround) {
            velocity.x      *= 0.5f;
            velocity.z      *= 0.5f;
            rotationalVelocity.mul(0.5f);
        }

        // ── normal force: push body up toward preferred height ────────────────
        NormalInfo normalInfo = calcNormal();
        this.normal = normalInfo;

        normalAcceleration.set(0f, 0f, 0f);
        if (normalInfo != null) {
            float preferredY    = calcPreferredY();
            float preferredYAcc = Math.max(0f, preferredY - position.y - velocity.y);
            float capableAcc    = g.bodyHeightCorrectionAcceleration * fractionGrounded;
            float accMag        = Math.min(preferredYAcc, capableAcc);

            normalAcceleration.set(normalInfo.normal).mul(accMag);

            // If the correction would push sideways more than upward, abandon it —
            // the spider is tipping and correcting would make things worse.
            float hLen = SpiderMath.horizontalLength(normalAcceleration);
            if (hLen > normalAcceleration.y) normalAcceleration.set(0f, 0f, 0f);

            velocity.add(normalAcceleration);
        }

        // ── integrate velocity ────────────────────────────────────────────────
        position.add(velocity);

        // ── body/floor collision resolution ──────────────────────────────────
        // Use a downward direction vector that accounts for current velocity magnitude
        // (same as original: Vector(0, min(-1, -abs(vy)), 0))
        float collisionDownY = Math.min(-1f, -Math.abs(velocity.y));
        Vector3f collisionDir = new Vector3f(0f, collisionDownY, 0f);

        SpiderWorldAdapter.CollisionResult collision =
                SpiderWorldAdapter.resolveCollision(world, position, collisionDir);

        if (collision != null) {
            onGround = true;

            // Detect hard landing (offset larger than two gravity steps)
            float hardLandThreshold = (float)(g.gravityAcceleration * 2 * (1.0 - g.airDragCoefficient));
            if (collision.offset.length() > hardLandThreshold && hitGroundListener != null) {
                hitGroundListener.onHitGround(this);
            }

            position.y = collision.position.y;
            if (velocity.y < 0f) velocity.y *= -g.bounceFactor;
            if (velocity.y < g.gravityAcceleration) velocity.y = 0f;

        } else {
            Vector3f downDir = new Vector3f(0f, -1f, 0f).rotate(orientation);
            onGround = SpiderWorldAdapter.isOnGround(world, position, downDir);
        }

        // ── update all legs ───────────────────────────────────────────────────
        List<Leg> updateOrder = g.type.getLegsInUpdateOrder(this);
        for (Leg leg : updateOrder) leg.updateMemo();
        for (Leg leg : updateOrder) leg.update();

        // Recompute preferred angles after legs have settled
        updatePreferredAngles();
    }

    // ── Private: leg initialisation ──────────────────────────────────────────

    private void initLegs() {
        legs = new ArrayList<>();
        for (LegPlan plan : bodyPlan.legs) {
            legs.add(new Leg(this, plan));
        }
    }

    // ── Private: preferred pitch/roll ─────────────────────────────────────────

    /**
     * Infers the spider's desired tilt from the positions of its corner legs.
     * Front legs higher than rear → nose-up pitch; left legs higher than right → roll.
     * Result is stored in preferredPitch / preferredRoll / preferredOrientation.
     */
    private void updatePreferredAngles() {
        Vector3f currentEuler = orientation.getEulerAnglesYXZ(new Vector3f());

        if (gait().disableAdvancedRotation) {
            preferredPitch       = 0f;
            preferredRoll        = 0f;
            preferredOrientation = new Quaternionf().rotationYXZ(currentEuler.y, 0f, 0f);
            return;
        }

        if (legs.size() < 2) return;

        // Helper: use groundPosition if known, else restPosition
        // (mirrors the commented-out endEffector branch in the original)

        Vector3f frontLeft  = getLegPos(0);
        Vector3f frontRight = getLegPos(1);
        Vector3f backLeft   = getLegPos(legs.size() - 2);
        Vector3f backRight  = getLegPos(legs.size() - 1);

        if (frontLeft == null || frontRight == null || backLeft == null || backRight == null) return;

        Vector3f forwardLeft  = new Vector3f(frontLeft).sub(backLeft);
        Vector3f forwardRight = new Vector3f(frontRight).sub(backRight);
        // average of the two forward vectors
        Vector3f forward = new Vector3f(forwardLeft).add(forwardRight).mul(0.5f);

        Vector3f sideways = new Vector3f();
        for (int i = 0; i + 1 < legs.size(); i += 2) {
            Vector3f left  = getLegPos(i);
            Vector3f right = getLegPos(i + 1);
            if (left == null || right == null) continue;
            sideways.add(new Vector3f(right).sub(left));
        }

        Gait g = gait();
        float newPitch = SpiderMath.lerp(SpiderMath.pitch(forward),   preferredPitch, g.preferredRotationLerpFraction);
        float newRoll  = SpiderMath.lerp(SpiderMath.pitch(sideways),  preferredRoll,  g.preferredRotationLerpFraction);

        if (newPitch < g.preferLevelBreakpoint) newPitch *= (1f - g.preferLevelBias);
        if (newRoll  < g.preferLevelBreakpoint) newRoll  *= (1f - g.preferLevelBias);

        preferredPitch       = newPitch;
        preferredRoll        = newRoll;
        preferredOrientation = new Quaternionf().rotationYXZ(currentEuler.y, preferredPitch, preferredRoll);
    }

    /** Returns groundPosition if available, restPosition otherwise. */
    private Vector3f getLegPos(int i) {
        if (i < 0 || i >= legs.size()) return null;
        Leg leg = legs.get(i);
        return leg.groundPosition != null ? leg.groundPosition : leg.restPosition;
    }

    // ── Private: body height ──────────────────────────────────────────────────

    /**
     * Calculates the Y position the body should move toward.
     * Uses the average target leg height plus body-height, floored by a ground raycast.
     */
    private float calcPreferredY() {
        Gait g = gait();
        LerpGait lg = lerpedGait();

        // Raycast downward from a one-tick look-ahead position
        Vector3f lookAhead = new Vector3f(position).add(velocity);
        Vector3f downDir   = new Vector3f(0f, -1f, 0f).rotate(preferredOrientation);
        Vector3f groundHit = SpiderWorldAdapter.raycastGround(world, lookAhead, downDir, lg.bodyHeight);
        float groundY = (groundHit != null) ? groundHit.y : -Float.MAX_VALUE;

        // Average of leg target heights + body height offset
        float sumY = 0f;
        for (Leg leg : legs) sumY += leg.target.position.y;
        float averageY = (legs.isEmpty() ? position.y : sumY / legs.size()) + (float) lg.bodyHeight;

        // The "up" contribution from the chain pivot
        Quaternionf pivot  = g.legChainPivotMode.get(this);
        Vector3f upOffset  = new Vector3f(0f, 1f, 0f).rotate(pivot).mul(g.maxBodyDistanceFromGround);
        float targetY      = Math.max(averageY, groundY + upOffset.y);

        return SpiderMath.lerp(position.y, targetY, g.bodyHeightCorrectionFactor);
    }

    // ── Private: normal force ─────────────────────────────────────────────────

    /**
     * Builds the ordered list of leg indices that forms the support polygon
     * (left legs in forward order, right legs reversed — makes a convex hull).
     */
    private List<Integer> legsInPolygonalOrder() {
        List<Integer> lefts  = new ArrayList<>();
        List<Integer> rights = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            if (LegLookUp.isLeftLeg(i)) lefts.add(i);
            else                         rights.add(i);
        }
        List<Integer> result = new ArrayList<>(lefts);
        // rights reversed
        for (int i = rights.size() - 1; i >= 0; i--) result.add(rights.get(i));
        return result;
    }

    /**
     * Legacy normal: returns a straight-up normal whenever any diagonal leg pair
     * is fully grounded.  Used when gait.useLegacyNormalForce = true.
     */
    private NormalInfo calcLegacyNormal() {
        List<Integer> allIndices = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) allIndices.add(i);
        List<List<Integer>> pairs = LegLookUp.diagonalPairs(allIndices);

        for (List<Integer> pair : pairs) {
            boolean allGrounded = true;
            for (int idx : pair) {
                Leg l = (idx >= 0 && idx < legs.size()) ? legs.get(idx) : null;
                if (l == null || !l.isGrounded()) { allGrounded = false; break; }
            }
            if (allGrounded) return new NormalInfo(new Vector3f(0f, 1f, 0f), null, null, null);
        }
        return null;
    }

    /**
     * Polygon-based normal force calculation.
     *
     * • 0 grounded legs → null (free-fall)
     * • 1 grounded leg  → tilt toward centre of mass from that single contact
     * • CoM inside polygon → straight up
     * • CoM outside polygon → tilt from nearest polygon edge toward CoM
     */
    private NormalInfo calcNormal() {
        if (gait().useLegacyNormalForce) return calcLegacyNormal();

        // Centre of mass = average of end-effectors, lerped 50% toward body position
        Vector3f centreOfMass = new Vector3f();
        for (Leg leg : legs) centreOfMass.add(leg.endEffector);
        if (!legs.isEmpty()) centreOfMass.div(legs.size());
        centreOfMass.lerp(position, 0.5f);
        centreOfMass.y += 0.01f;   // tiny epsilon so it is never exactly on-plane

        // Collect grounded legs in polygonal order
        List<Integer> polyOrder = legsInPolygonalOrder();
        List<Leg> groundedLegs = new ArrayList<>();
        for (int idx : polyOrder) {
            Leg l = legs.get(idx);
            if (l.isGrounded()) groundedLegs.add(l);
        }
        if (groundedLegs.isEmpty()) return null;

        List<Vector3f> legsPolygon = new ArrayList<>();
        for (Leg l : groundedLegs) legsPolygon.add(new Vector3f(l.endEffector));

        float polygonCenterY = 0f;
        for (Vector3f v : legsPolygon) polygonCenterY += v.y;
        polygonCenterY /= legsPolygon.size();

        // ── single contact ────────────────────────────────────────────────────
        if (legsPolygon.size() == 1) {
            Vector3f origin = new Vector3f(legsPolygon.get(0));
            Vector3f normal = new Vector3f(centreOfMass).sub(origin).normalize();
            NormalInfo info = new NormalInfo(normal, origin, legsPolygon, centreOfMass);
            applyStabilization(info);
            return info;
        }

        // Project to 2D (XZ plane) for polygon tests
        List<Vector2f> polygon2D = new ArrayList<>();
        for (Vector3f v : legsPolygon) polygon2D.add(new Vector2f(v.x, v.z));

        Vector2f com2D = new Vector2f(centreOfMass.x, centreOfMass.z);

        // ── centre of mass inside support polygon → push straight up ──────────
        if (pointInPolygon(com2D, polygon2D)) {
            Vector3f origin = new Vector3f(centreOfMass.x, polygonCenterY, centreOfMass.z);
            return new NormalInfo(
                    new Vector3f(0f, 1f, 0f),
                    origin,
                    legsPolygon,
                    centreOfMass);
        }

        // ── centre of mass outside polygon → tilt toward nearest edge ─────────
        Vector2f nearest2D = nearestPointInPolygon(com2D, polygon2D);
        Vector3f origin    = new Vector3f(nearest2D.x, polygonCenterY, nearest2D.y);
        Vector3f normal    = new Vector3f(centreOfMass).sub(origin).normalize();
        NormalInfo info    = new NormalInfo(normal, origin, legsPolygon, centreOfMass);
        applyStabilization(info);
        return info;
    }

    /**
     * Pulls the normal toward vertical when the centre of mass is close to
     * the polygon boundary (within polygonLeeway).  Reduces jitter at the
     * tipping boundary.
     */
    private void applyStabilization(NormalInfo info) {
        if (info.origin == null || info.centreOfMass == null) return;

        if (SpiderMath.horizontalDistance(info.origin, info.centreOfMass) < gait().polygonLeeway) {
            info.origin.x = info.centreOfMass.x;
            info.origin.z = info.centreOfMass.z;
        }

        Vector3f stabTarget = new Vector3f(info.origin);
        stabTarget.y = info.centreOfMass.y; // Cleanly assign the float field directly
        info.centreOfMass.lerp(stabTarget, gait().stabilizationFactor);
        info.normal.set(info.centreOfMass).sub(info.origin).normalize();
    }

    // ── Polygon utilities (ported from polygons.kt) ───────────────────────────

    /**
     * Ray-casting point-in-polygon test (Jordan curve theorem).
     * Returns true when point is inside the 2D polygon.
     */
    private static boolean pointInPolygon(Vector2f point, List<Vector2f> polygon) {
        int count = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Vector2f a = polygon.get(i);
            Vector2f b = polygon.get((i + 1) % polygon.size());

            boolean straddles = (a.y <= point.y && b.y > point.y)
                             || (b.y <= point.y && a.y > point.y);
            if (straddles) {
                float slope     = (b.x - a.x) / (b.y - a.y);
                float intersect = a.x + (point.y - a.y) * slope;
                if (intersect < point.x) count++;
            }
        }
        return (count % 2) == 1;
    }

    /**
     * Returns the nearest point on any edge of the polygon to the given point.
     */
    private static Vector2f nearestPointInPolygon(Vector2f point, List<Vector2f> polygon) {
        Vector2f closest  = new Vector2f(polygon.get(0));
        float closestDist = point.distance(closest);

        for (int i = 0; i < polygon.size(); i++) {
            Vector2f a = polygon.get(i);
            Vector2f b = polygon.get((i + 1) % polygon.size());

            Vector2f candidate = nearestPointOnSegment(point, a, b);
            float dist = point.distance(candidate);
            if (dist < closestDist) {
                closestDist = dist;
                closest.set(candidate);
            }
        }
        return closest;
    }

    private static Vector2f nearestPointOnSegment(Vector2f point, Vector2f a, Vector2f b) {
        Vector2f ap = new Vector2f(point).sub(a);
        Vector2f ab = new Vector2f(b).sub(a);

        float dot      = ap.dot(ab);
        float lenSq    = ab.dot(ab);
        float t        = (lenSq > 0f) ? dot / lenSq : 0f;
        float tClamped = Math.max(0f, Math.min(1f, t));

        return new Vector2f(a).add(new Vector2f(ab).mul(tClamped));
    }
}
