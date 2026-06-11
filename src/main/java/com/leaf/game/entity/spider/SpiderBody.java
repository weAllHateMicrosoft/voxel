package com.leaf.game.entity.spider;

import com.leaf.game.util.SpiderMath;
import com.leaf.game.world.World;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class SpiderBody {

    public final World world;
    public final Vector3f position;
    public final Quaternionf orientation;
    public BodyPlan bodyPlan;
    public Gait walkGait;
    public Gait gallopGait;
    public boolean gallop = false;
    public Gait gait() { return gallop ? gallopGait : walkGait; }

    public boolean disableFabrik = false;
    public List<Leg> legs = new ArrayList<>();

    public interface HitGroundListener { void onHitGround(SpiderBody spider); }
    private HitGroundListener hitGroundListener;
    public void setHitGroundListener(HitGroundListener l) { this.hitGroundListener = l; }

    public boolean onGround = false;
    public NormalInfo normal = null;
    public final Vector3f velocity           = new Vector3f();
    public final Vector3f rotationalVelocity = new Vector3f();
    public final Vector3f normalAcceleration = new Vector3f();

    public float preferredPitch;
    public float preferredRoll;
    public Quaternionf preferredOrientation;

    public boolean isWalking      = false;
    public boolean isRotatingYaw  = false;

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

    public static SpiderBody fromPosition(World world, Vector3f position, float yawRadians,
                                          BodyPlan bodyPlan, Gait walkGait, Gait gallopGait) {
        Quaternionf orientation = new Quaternionf().rotationYXZ(yawRadians, 0f, 0f);
        return new SpiderBody(world, position, orientation, bodyPlan, walkGait, gallopGait);
    }

    public Vector3f forwardDirection() {
        return new Vector3f(0f, 0f, 1f).rotate(orientation);
    }

    public LerpGait lerpedGait() {
        if (isRotatingYaw) return gait().moving.clone();
        float speedFraction = velocity.length() / gait().maxSpeed;
        speedFraction = Math.min(speedFraction, 1f);
        return gait().stationary.clone().lerp(gait().moving, speedFraction);
    }

    public void teleport(Vector3f newPosition) {
        Vector3f diff = new Vector3f(newPosition).sub(position);
        position.set(newPosition);
        for (Leg leg : legs) {
            leg.endEffector.add(diff);
        }
    }

    public void accelerateRotation(Vector3f axis, float angle) {
        Quaternionf acceleration = new Quaternionf().rotateAxis(angle, axis);
        Quaternionf oldVelocity  = new Quaternionf().rotationYXZ(
                rotationalVelocity.y, rotationalVelocity.x, rotationalVelocity.z);

        Quaternionf rotVelocity = acceleration.mul(oldVelocity);
        rotVelocity.getEulerAnglesYXZ(rotationalVelocity);
    }

    public void rotateTowards(Vector3f targetVector) {
        Vector3f currentEuler = orientation.getEulerAnglesYXZ(new Vector3f());
        Vector3f targetEuler = new Quaternionf()
                .rotationTo(new Vector3f(0f, 0f, 1f), targetVector)
                .getEulerAnglesYXZ(new Vector3f());

        Gait g = gait();
        targetEuler.x = Math.max(preferredPitch - g.preferredPitchLeeway,
                Math.min(preferredPitch + g.preferredPitchLeeway, targetEuler.x));
        targetEuler.z = preferredRoll;

        boolean anyUncomfortable = false;
        for (Leg leg : legs) {
            if (leg.isUncomfortable() && !leg.isMoving) { anyUncomfortable = true; break; }
        }
        if (anyUncomfortable) targetEuler.y = currentEuler.y;

        Vector3f diffEuler = new Vector3f(targetEuler).sub(currentEuler);
        if (diffEuler.y >  Math.PI) diffEuler.y -= (float)(2 * Math.PI);
        if (diffEuler.y < -Math.PI) diffEuler.y += (float)(2 * Math.PI);

        isRotatingYaw = Math.abs(diffEuler.x) + Math.abs(diffEuler.y) + Math.abs(diffEuler.z) > 0.001f;

        diffEuler.lerp(new Vector3f(0f, 0f, 0f), g.rotationLerp);

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
            isWalking = targetVelocity.x != 0f || targetVelocity.z != 0f;
        } else {
            target.y = velocity.y;
            SpiderMath.moveTowards(velocity, target, g.moveAcceleration);
            isWalking = targetVelocity.x != 0f || targetVelocity.z != 0f;
        }
    }

    public void update() {
        if (legs.isEmpty()) {
            initLegs();
            if (legs.isEmpty()) return;
        }

        updatePreferredAngles();

        int groundedCount = 0;
        for (Leg leg : legs) if (leg.isGrounded()) groundedCount++;
        float fractionGrounded = legs.isEmpty() ? 0f : (float) groundedCount / legs.size();

        Gait g = gait();

        // ── SURFACE-PROJECTED GRAVITY ──
        {
            Vector3f gravDir;
            boolean useProjected = false;

            if (this.normal != null && this.normal.normal != null) {
                Vector3f n = this.normal.normal;
                if (n.y < 0.866f) {
                    Vector3f rawGrav = new Vector3f(0f, -1f, 0f);
                    Vector3f projected = SpiderMath.projectOntoPlane(rawGrav, n);
                    float projLen = projected.length();
                    if (projLen > 1e-4f) {
                        gravDir      = projected.div(projLen);
                        useProjected = true;
                        velocity.add(new Vector3f(gravDir).mul(g.gravityAcceleration));
                    }
                }
            }

            if (!useProjected) {
                velocity.y -= g.gravityAcceleration;
            }

            velocity.y *= (1f - g.airDragCoefficient);
        }

        Quaternionf rotVel = new Quaternionf()
                .rotationYXZ(rotationalVelocity.y, rotationalVelocity.x, rotationalVelocity.z);
        orientation.set(rotVel.mul(orientation));

        if (!isWalking) {
            float legDrag = 1f - g.groundDragCoefficient * fractionGrounded;
            velocity.x *= legDrag;
            velocity.z *= legDrag;
        }

        float rotDrag = 1f - g.rotationalDragCoefficient * fractionGrounded;
        rotationalVelocity.mul(rotDrag);

        // ── FIX: CLAMP ROTATIONAL VELOCITY (Anti-Flip + Anti-Spin) ──
        // Cap the pitch and roll momentum so external impulses (hits) or strange
        // terrain geometry cannot send the spider tumbling completely out of control.
        float maxRot = 0.08f;
        rotationalVelocity.x = Math.max(-maxRot, Math.min(maxRot, rotationalVelocity.x));
        rotationalVelocity.z = Math.max(-maxRot, Math.min(maxRot, rotationalVelocity.z));
        // Yaw was previously UNCLAMPED — with the weak 120-TPS rotation drag this let
        // turning momentum accumulate and the spider would whirl in place / lose its
        // heading. Cap it generously (≈0.12 rad/tick ≈ 14 rad/s): fast enough to turn
        // and face the player normally, but it kills the runaway spin.
        float maxYaw = 0.12f;
        rotationalVelocity.y = Math.max(-maxYaw, Math.min(maxYaw, rotationalVelocity.y));

        if (onGround) {
            velocity.x      *= 0.5f;
            velocity.z      *= 0.5f;
            rotationalVelocity.mul(0.5f);
        }

        NormalInfo normalInfo = calcNormal();
        this.normal = normalInfo;

        normalAcceleration.set(0f, 0f, 0f);
        if (normalInfo != null) {
            float preferredY    = calcPreferredY();
            float t = walkGait.maxSpeed / 0.15f;
            float preferredYAcc = Math.max(0f, (preferredY - position.y) * t - velocity.y);

            float capableAcc    = g.bodyHeightCorrectionAcceleration * fractionGrounded;
            float accMag        = Math.min(preferredYAcc, capableAcc);

            normalAcceleration.set(normalInfo.normal).mul(accMag);

            float hLen = SpiderMath.horizontalLength(normalAcceleration);
            if (hLen > normalAcceleration.y) normalAcceleration.set(0f, 0f, 0f);

            velocity.add(normalAcceleration);
        }

        // ── ROBUST WALL COLLISION CHECK ──
        boolean onSteepSurface = (this.normal != null
                && this.normal.normal != null
                && this.normal.normal.y < 0.5f);

        float r = 0.9f;
        int cy1 = (int) Math.floor(position.y + 0.5f);
        int cy2 = (int) Math.floor(position.y + 1.5f);

        if (!onSteepSurface) {
            // Original flat-terrain AABB wall check
            if (Math.abs(velocity.x) > 0.001f) {
                int cx = (int) Math.floor(position.x + velocity.x + Math.signum(velocity.x) * r);
                int cz = (int) Math.floor(position.z);
                if (world.getBlock(cx, cy1, cz).isSolid() || world.getBlock(cx, cy2, cz).isSolid()) {
                    velocity.x = 0f;
                }
            }
            if (Math.abs(velocity.z) > 0.001f) {
                int cx = (int) Math.floor(position.x);
                int cz = (int) Math.floor(position.z + velocity.z + Math.signum(velocity.z) * r);
                if (world.getBlock(cx, cy1, cz).isSolid() || world.getBlock(cx, cy2, cz).isSolid()) {
                    velocity.z = 0f;
                }
            }
        } else if (this.normal != null && this.normal.normal != null) {
            // ── FIX: WALL ANTI-CLIPPING ──
            // If climbing a steep wall, strip out any velocity pointing INTO the wall
            // so the spider slides smoothly up/across instead of clipping through it.
            Vector3f n = this.normal.normal;
            float velIntoWall = velocity.x * n.x + velocity.z * n.z;

            if (velIntoWall < 0f) { // Negative dot product means moving into the wall
                velocity.x -= velIntoWall * n.x;
                velocity.z -= velIntoWall * n.z;
            }
        }

        position.add(velocity);

        // ── body/floor collision resolution ──
        float collisionDownY = Math.min(-1f, -Math.abs(velocity.y));
        Vector3f collisionDir = new Vector3f(0f, collisionDownY, 0f);

        SpiderWorldAdapter.CollisionResult collision = SpiderWorldAdapter.resolveCollision(world, position, collisionDir);

        if (collision != null) {
            onGround = true;
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

        List<Leg> updateOrder = g.type.getLegsInUpdateOrder(this);
        for (Leg leg : updateOrder) leg.updateMemo();
        for (Leg leg : updateOrder) leg.update();

        updatePreferredAngles();
    }

    private void initLegs() {
        legs = new ArrayList<>();
        for (LegPlan plan : bodyPlan.legs) {
            legs.add(new Leg(this, plan));
        }
    }

    private void updatePreferredAngles() {
        Vector3f currentEuler = orientation.getEulerAnglesYXZ(new Vector3f());

        if (gait().disableAdvancedRotation) {
            preferredPitch       = 0f;
            preferredRoll        = 0f;
            preferredOrientation = new Quaternionf().rotationYXZ(currentEuler.y, 0f, 0f);
            return;
        }

        if (legs.size() < 2) return;

        Vector3f frontLeft  = getLegPos(0);
        Vector3f frontRight = getLegPos(1);
        Vector3f backLeft   = getLegPos(legs.size() - 2);
        Vector3f backRight  = getLegPos(legs.size() - 1);

        if (frontLeft == null || frontRight == null || backLeft == null || backRight == null) return;

        Vector3f forwardLeft  = new Vector3f(frontLeft).sub(backLeft);
        Vector3f forwardRight = new Vector3f(frontRight).sub(backRight);
        Vector3f forward = new Vector3f(forwardLeft).add(forwardRight).mul(0.5f);

        Vector3f sideways = new Vector3f();
        for (int i = 0; i + 1 < legs.size(); i += 2) {
            Vector3f left  = getLegPos(i);
            Vector3f right = getLegPos(i + 1);
            if (left == null || right == null) continue;
            sideways.add(new Vector3f(right).sub(left));
        }

        Gait g = gait();
        float t = walkGait.maxSpeed / 0.15f;
        float rawPitch = SpiderMath.pitch(forward);
        float rawRoll  = SpiderMath.pitch(sideways);

        // ── PITCH/ROLL FLIP GUARD ──
        final float MAX_ANGLE_DELTA = (float)(Math.PI / 2.0);

        float pitchDelta = rawPitch - preferredPitch;
        if (pitchDelta >  MAX_ANGLE_DELTA) pitchDelta =  MAX_ANGLE_DELTA;
        if (pitchDelta < -MAX_ANGLE_DELTA) pitchDelta = -MAX_ANGLE_DELTA;
        float clampedPitch = preferredPitch + pitchDelta;

        float rollDelta = rawRoll - preferredRoll;
        if (rollDelta >  MAX_ANGLE_DELTA) rollDelta =  MAX_ANGLE_DELTA;
        if (rollDelta < -MAX_ANGLE_DELTA) rollDelta = -MAX_ANGLE_DELTA;
        float clampedRoll = preferredRoll + rollDelta;

        float newPitch = SpiderMath.lerp(clampedPitch, preferredPitch, g.preferredRotationLerpFraction * t);
        float newRoll  = SpiderMath.lerp(clampedRoll,  preferredRoll,  g.preferredRotationLerpFraction * t);

        if (newPitch < g.preferLevelBreakpoint) newPitch *= (1f - g.preferLevelBias);
        if (newRoll  < g.preferLevelBreakpoint) newRoll  *= (1f - g.preferLevelBias);

        preferredPitch       = newPitch;
        preferredRoll        = newRoll;
        preferredOrientation = new Quaternionf().rotationYXZ(currentEuler.y, preferredPitch, preferredRoll);
    }

    private Vector3f getLegPos(int i) {
        if (i < 0 || i >= legs.size()) return null;
        Leg leg = legs.get(i);
        return leg.groundPosition != null ? leg.groundPosition : leg.restPosition;
    }

    private float calcPreferredY() {
        Gait g = gait();
        LerpGait lg = lerpedGait();

        Vector3f lookAhead = new Vector3f(position).add(velocity);
        Vector3f downDir   = new Vector3f(0f, -1f, 0f).rotate(preferredOrientation);
        Vector3f groundHit = SpiderWorldAdapter.raycastGround(world, lookAhead, downDir, lg.bodyHeight + 2.0f);
        float groundY = (groundHit != null) ? groundHit.y : -Float.MAX_VALUE;

        float sumY = 0f;
        for (Leg leg : legs) sumY += leg.target.position.y;
        float averageY = (legs.isEmpty() ? position.y : sumY / legs.size()) + (float) lg.bodyHeight;

        Quaternionf pivot  = g.legChainPivotMode.get(this);
        Vector3f upOffset  = new Vector3f(0f, 1f, 0f).rotate(pivot).mul(g.maxBodyDistanceFromGround);
        float targetY      = Math.max(averageY, groundY + upOffset.y);

        float t = walkGait.maxSpeed / 0.15f;
        return SpiderMath.lerp(position.y, targetY, g.bodyHeightCorrectionFactor * t);
    }

    private List<Integer> legsInPolygonalOrder() {
        List<Integer> lefts  = new ArrayList<>();
        List<Integer> rights = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            if (LegLookUp.isLeftLeg(i)) lefts.add(i);
            else                         rights.add(i);
        }
        List<Integer> result = new ArrayList<>(lefts);
        for (int i = rights.size() - 1; i >= 0; i--) result.add(rights.get(i));
        return result;
    }

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

    private NormalInfo calcNormal() {
        if (gait().useLegacyNormalForce) return calcLegacyNormal();

        Vector3f centreOfMass = new Vector3f();
        for (Leg leg : legs) centreOfMass.add(leg.endEffector);
        if (!legs.isEmpty()) centreOfMass.div(legs.size());
        centreOfMass.lerp(position, 0.5f);
        centreOfMass.y += 0.01f;

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

        if (legsPolygon.size() == 1) {
            Vector3f origin = new Vector3f(legsPolygon.get(0));
            Vector3f normal = new Vector3f(centreOfMass).sub(origin).normalize();
            NormalInfo info = new NormalInfo(normal, origin, legsPolygon, centreOfMass);
            applyStabilization(info);
            return info;
        }

        List<Vector2f> polygon2D = new ArrayList<>();
        for (Vector3f v : legsPolygon) polygon2D.add(new Vector2f(v.x, v.z));
        Vector2f com2D = new Vector2f(centreOfMass.x, centreOfMass.z);

        if (pointInPolygon(com2D, polygon2D)) {
            Vector3f origin = new Vector3f(centreOfMass.x, polygonCenterY, centreOfMass.z);
            return new NormalInfo(new Vector3f(0f, 1f, 0f), origin, legsPolygon, centreOfMass);
        }

        Vector2f nearest2D = nearestPointInPolygon(com2D, polygon2D);
        Vector3f origin    = new Vector3f(nearest2D.x, polygonCenterY, nearest2D.y);
        Vector3f normal    = new Vector3f(centreOfMass).sub(origin).normalize();
        NormalInfo info    = new NormalInfo(normal, origin, legsPolygon, centreOfMass);
        applyStabilization(info);
        return info;
    }

    private void applyStabilization(NormalInfo info) {
        if (info.origin == null || info.centreOfMass == null) return;
        if (SpiderMath.horizontalDistance(info.origin, info.centreOfMass) < gait().polygonLeeway) {
            info.origin.x = info.centreOfMass.x;
            info.origin.z = info.centreOfMass.z;
        }
        Vector3f stabTarget = new Vector3f(info.origin);
        stabTarget.y = info.centreOfMass.y;

        float t = walkGait.maxSpeed / 0.15f;
        info.centreOfMass.lerp(stabTarget, gait().stabilizationFactor * t);

        info.normal.set(info.centreOfMass).sub(info.origin).normalize();
    }

    private static boolean pointInPolygon(Vector2f point, List<Vector2f> polygon) {
        int count = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Vector2f a = polygon.get(i);
            Vector2f b = polygon.get((i + 1) % polygon.size());
            boolean straddles = (a.y <= point.y && b.y > point.y) || (b.y <= point.y && a.y > point.y);
            if (straddles) {
                float slope     = (b.x - a.x) / (b.y - a.y);
                float intersect = a.x + (point.y - a.y) * slope;
                if (intersect < point.x) count++;
            }
        }
        return (count % 2) == 1;
    }

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