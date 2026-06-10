package com.leaf.game.entity.spider;

import com.leaf.game.util.SpiderMath;
import com.leaf.game.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Faithful Java port of Leg.kt.
 *
 * Represents a single procedurally-animated leg on the spider.
 * Each leg owns its KinematicChain, its end-effector position,
 * its step target, and the state machine that decides when to step.
 *
 * Call order every tick (driven by SpiderBody.update):
 *   1. updateMemo()   — recompute rest/trigger/scan positions from current spider state
 *   2. update()       — run movement state machine, then rebuild IK chain
 */
public class Leg {

    // ── References ────────────────────────────────────────────────────────────
    public final SpiderBody spider;
    public LegPlan legPlan;

    /** Called when this leg completes a step (touches ground after moving). */
    public interface StepListener {
        void onStep(Leg leg);
    }
    private StepListener stepListener;
    public void setStepListener(StepListener l) { this.stepListener = l; }

    // ── Memo (recomputed every tick in updateMemo) ────────────────────────────
    public SpiderMath.SplitDistanceZone triggerZone;
    public SpiderMath.SplitDistanceZone comfortZone;

    public Vector3f groundPosition      = null;   // nullable
    public Vector3f restPosition        = new Vector3f();
    public Vector3f lookAheadPosition   = new Vector3f();
    public Vector3f scanStartPosition   = new Vector3f();
    public Vector3f scanVector          = new Vector3f();
    public Vector3f attachmentPosition  = new Vector3f();

    // ── State ─────────────────────────────────────────────────────────────────
    public LegTarget target;
    public Vector3f  endEffector;
    public Vector3f  previousEndEffector;
    public KinematicChain chain;

    public boolean touchingGround  = true;
    public boolean isMoving        = false;
    public int     timeSinceBeginMove = 0;
    public int     timeSinceStopMove  = 0;

    public boolean isDisabled = false;
    public boolean isPrimary  = false;
    public boolean canMove    = false;

    // ── Construction ─────────────────────────────────────────────────────────

    public Leg(SpiderBody spider, LegPlan legPlan) {
        this.spider  = spider;
        this.legPlan = legPlan;

        // Mirror Kotlin init block: updateMemo first, then initialise state from it
        updateMemo();

        LegTarget initialTarget = locateGround();
        if (initialTarget == null) initialTarget = strandedTarget();
        this.target              = initialTarget;
        this.endEffector         = new Vector3f(target.position);
        this.previousEndEffector = new Vector3f(endEffector);
        this.chain               = new KinematicChain(new Vector3f(), new ArrayList<>());
    }

    // ── Public utils ──────────────────────────────────────────────────────────

    public boolean isOutsideTriggerZone() { return !triggerZone.contains(endEffector); }
    public boolean isUncomfortable()      { return !comfortZone.contains(endEffector); }

    public boolean isGrounded() {
        return touchingGround && !isMoving && !isDisabled;
    }

    // ── Tick entry points (called by SpiderBody) ──────────────────────────────

    /**
     * Recompute all positional memos that depend on the spider's current state.
     * Must be called before update() each tick.
     */
    public void updateMemo() {
        LerpGait lerpedGait  = spider.lerpedGait();
        Quaternionf scanPivot = spider.gait().scanPivotMode.get(spider);

        // upVector = world-up rotated by scan pivot
        Vector3f upVector = new Vector3f(0f, 1f, 0f).rotate(scanPivot);

        Vector3f scanStartAxis = new Vector3f(upVector).mul((float)(lerpedGait.bodyHeight * 1.6f));
        Vector3f scanAxis      = new Vector3f(upVector).mul((float)(-lerpedGait.bodyHeight * 3.5f));

        // ── rest position ────────────────────────────────────────────────────
        // restPosition = legPlan.restPosition - upVector*bodyHeight, rotated by
        // spider orientation, then translated to spider position
        restPosition = new Vector3f(legPlan.restPosition)
                .add(new Vector3f(upVector).mul(-(float) lerpedGait.bodyHeight))
                .rotate(spider.orientation)
                .add(spider.position);

        // ── trigger zone ────────────────────────────────────────────────────
        triggerZone = new SpiderMath.SplitDistanceZone(restPosition, lerpedGait.triggerZone.clone());

        // ── comfort zone ────────────────────────────────────────────────────
        // Centre sits halfway between restPosition.y and spider.position.y.
        // Vertical extent grows when the spider is above its rest positions.
        Vector3f comfortCenter = new Vector3f(restPosition);
        comfortCenter.y = SpiderMath.lerp(restPosition.y, spider.position.y, 0.5f);

        float extraVertical = Math.max(0f, spider.position.y - restPosition.y);
        SpiderMath.SplitDistance comfortSize = new SpiderMath.SplitDistance(
                spider.gait().comfortZone.horizontal,
                spider.gait().comfortZone.vertical + extraVertical
        );
        comfortZone = new SpiderMath.SplitDistanceZone(comfortCenter, comfortSize);

        // ── look-ahead ───────────────────────────────────────────────────────
        lookAheadPosition = computeLookAheadPosition(restPosition, triggerZone.size.horizontal);

        // ── scan ─────────────────────────────────────────────────────────────
        scanStartPosition = new Vector3f(lookAheadPosition).add(scanStartAxis);
        scanVector        = new Vector3f(scanAxis);

        // ── attachment position ───────────────────────────────────────────────
        attachmentPosition = new Vector3f(legPlan.attachmentPosition)
                .rotate(spider.orientation)
                .add(spider.position);
    }

    /**
     * Run the movement state machine then rebuild the IK chain.
     * Call after updateMemo().
     */
    public void update() {
        // refresh legPlan in case BodyPlan was mutated
        int idx = spider.legs.indexOf(this);
        if (idx >= 0 && idx < spider.bodyPlan.legs.size()) {
            legPlan = spider.bodyPlan.legs.get(idx);
        }

        updateMovement();
        chain = buildChain();
    }

    // ── Private: movement state machine ──────────────────────────────────────

    private void updateMovement() {
        previousEndEffector.set(endEffector);

        Gait gait = spider.gait();
        boolean didStep = false;

        timeSinceBeginMove++;
        timeSinceStopMove++;

        // ── update target ────────────────────────────────────────────────────
        LegTarget ground = locateGround();
        groundPosition = (ground != null) ? new Vector3f(ground.position) : null;

        if (isDisabled) {
            target = disabledTarget();
        } else {
            if (ground != null) target = ground;

            if (!target.isGrounded || !comfortZone.contains(target.position)) {
                target = strandedTarget();
            }
        }

        // ── inherit parent velocity ───────────────────────────────────────────
        // When not grounded, the foot drifts with the body so it doesn't
        // get left behind while the spider moves.
        if (!isGrounded()) {
            endEffector.add(spider.velocity);
            SpiderMath.rotateAroundY(endEffector, spider.rotationalVelocity.y, spider.position);
        }

        // ── resolve ground collision for the end-effector ────────────────────
        // FIX (Bug 2 equivalent for the foot): use a very short downward cast,
        // identical to the original Kotlin: resolveCollision(endEffector, DOWN_VECTOR).
        // The original passes DOWN_VECTOR (not the orientation-rotated down), so the
        // foot collision is always world-space downward — matches Kotlin exactly.
        if (!touchingGround) {
            Vector3f downDir = new Vector3f(0f, -1f, 0f);  // world-space down, not orientation-rotated
            SpiderWorldAdapter.CollisionResult footCollision =
                    SpiderWorldAdapter.resolveCollision(spider.world, endEffector, downDir);
            if (footCollision != null) {
                didStep        = true;
                touchingGround = true;
                endEffector.y  = footCollision.position.y;
            }
        }

        // ── moving state ──────────────────────────────────────────────────────
        if (isMoving) {
            SpiderMath.moveTowards(endEffector, target.position, gait.legMoveSpeed);

            // Arc the foot upward while it still has horizontal distance to cover
            float targetY   = target.position.y + gait.legLiftHeight;
            float hDistance = SpiderMath.horizontalDistance(endEffector, target.position);
            if (hDistance > gait.legDropDistance) {
                endEffector.y = SpiderMath.moveTowards(
                        endEffector.y, targetY, gait.legMoveSpeed);
            }

            // FIX (Bug in original port): original Kotlin uses linear distance < 0.0001,
            // which is equivalent to distanceSquared < 1e-8. Both are correct here —
            // kept as distanceSquared for consistency, value is correct.
            if (endEffector.distanceSquared(target.position) < 0.0001f * 0.0001f) {
                isMoving         = false;
                timeSinceStopMove = 0;

                touchingGround = checkTouchingGround();
                didStep        = touchingGround;
            }

        } else {
            // ── idle state: decide whether to begin a step ───────────────────
            canMove = spider.gait().type.canMoveLeg(this, spider);

            if (canMove) {
                isMoving           = true;
                timeSinceBeginMove = 0;
            }
        }

        if (didStep && stepListener != null) {
            stepListener.onStep(this);
        }
    }

    // ── Private: IK chain builder ─────────────────────────────────────────────

    private KinematicChain buildChain() {
        // Initialise chain geometry if segment count changed
        // (happens on first call, or if BodyPlan is hot-swapped)
        if (chain.segments.size() != legPlan.segments.size()) {
            List<ChainSegment> segs = new ArrayList<>();
            float stride = 0f;
            Vector3f restDir = new Vector3f(legPlan.restPosition).normalize();
            for (SegmentPlan sp : legPlan.segments) {
                stride += sp.length;
                Vector3f pos = new Vector3f(spider.position).add(
                        new Vector3f(restDir).mul(stride));
                segs.add(new ChainSegment(pos, sp.length, new Vector3f(sp.initDirection)));
            }
            chain = new KinematicChain(new Vector3f(attachmentPosition), segs);
        }

        // Always update root to current attachment point
        chain.root.set(attachmentPosition);

        // ── optional: straighten legs before FABRIK ───────────────────────────
        // This gives FABRIK a better starting pose and produces the characteristic
        // mechanical "raised knee" look.
        if (spider.gait().straightenLegs) {
            Quaternionf pivot = new Quaternionf(spider.gait().legChainPivotMode.get(spider));

            Vector3f direction = new Vector3f(endEffector).sub(attachmentPosition);
            Vector3f rotation  = SpiderMath.getRotationAroundAxis(direction, pivot);

            rotation.x += spider.gait().legStraightenRotation;

            // Build orientation: pivot rotated by (yaw=rotation.y, pitch=rotation.x, roll=0)
            Quaternionf orientation = new Quaternionf(pivot).rotateYXZ(rotation.y, rotation.x, 0f);
            chain.straightenDirection(orientation);
        }

        // ── FABRIK solve ──────────────────────────────────────────────────────
        if (!spider.disableFabrik) {
            chain.fabrik(endEffector);
        }

        return chain;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean checkTouchingGround() {
        // FIX (Bug 4): original Kotlin uses isOnGround with DOWN_VECTOR (world-space),
        // not orientation-rotated. The 0.001 cast distance is set inside SpiderWorldAdapter.
        Vector3f downDir = new Vector3f(0f, -1f, 0f);  // world-space down
        return SpiderWorldAdapter.isOnGround(spider.world, endEffector, downDir);
    }

    /**
     * Computes look-ahead position: where the foot should aim when the spider
     * is moving. Biased forward in the direction of travel.
     */
    private Vector3f computeLookAheadPosition(Vector3f rest, float triggerRadius) {
        if (!spider.isWalking) return new Vector3f(rest);

        Vector3f direction;
        if (spider.velocity.lengthSquared() < 1e-6f) {
            direction = spider.forwardDirection();
        } else {
            direction = new Vector3f(spider.velocity).normalize();
        }

        Vector3f lookAhead = new Vector3f(direction)
                .mul(triggerRadius * spider.gait().legLookAheadFraction)
                .add(rest);

        SpiderMath.rotateAroundY(lookAhead, spider.rotationalVelocity.y, spider.position);
        return lookAhead;
    }

    /**
     * Casts a 3×3 grid of rays in the spider's local "down" direction around the
     * scan position to find the best ground candidate for this leg to step on.
     *
     * ── WALL-CLIMB FIX ────────────────────────────────────────────────────────
     * Previously the grid offsets were always expressed in world-XZ (flat-floor
     * assumption). On a steep wall the spider's local surface tangent plane is
     * nearly vertical, so world-XZ offsets gave nine almost-identical probe
     * positions and the spider couldn't find the wall surface.
     *
     * The fix: derive two orthogonal tangent vectors from the scan pivot's "up"
     * axis (which is already orientation-aware via updateMemo), then spread the
     * 3×3 grid along those tangent vectors. The ray direction (scanVector) is
     * unchanged — it was already orientation-aware. Only the grid start positions
     * are rotated into surface space.
     *
     * Safety: we keep the original world-XZ path as a fallback when the tangent
     * basis degenerates (length < ε). In practice this cannot happen for valid
     * orientations, but it prevents silent NaN propagation.
     * ─────────────────────────────────────────────────────────────────────────
     */
    private LegTarget locateGround() {
        float scanLength = scanVector.length();

        int[] idCounter = {0};

        // ── Build the surface-local tangent basis ──────────────────────────────
        //
        // scanPivot's "up" axis is the direction the spider considers "away from
        // the surface" (already orientation-aware). The two tangents that are
        // perpendicular to it span the surface plane we want to sample.
        //
        // tangentA ≈ local right on the surface
        // tangentB ≈ local forward on the surface
        //
        // We choose world-X as the reference for the first cross-product, falling
        // back to world-Z if the up vector is nearly parallel to world-X.

        Quaternionf scanPivot = spider.gait().scanPivotMode.get(spider);
        Vector3f surfaceUp = new Vector3f(0f, 1f, 0f).rotate(scanPivot).normalize();

        Vector3f worldRef = (Math.abs(surfaceUp.x) < 0.9f)
                ? new Vector3f(1f, 0f, 0f)
                : new Vector3f(0f, 0f, 1f);

        Vector3f tangentA = new Vector3f(surfaceUp).cross(worldRef);
        float tanALen = tangentA.length();
        if (tanALen < 1e-4f) {
            // Degenerate basis — fall back to world-XZ offsets (original behaviour).
            // This should never occur for non-zero orientations.
            tangentA = new Vector3f(1f, 0f, 0f);
            tanALen  = 1f;
        }
        tangentA.div(tanALen); // normalise

        Vector3f tangentB = new Vector3f(surfaceUp).cross(tangentA).normalize();

        // ── Main (centre) cast ────────────────────────────────────────────────
        LegTarget mainCandidate = rayCast3D(scanStartPosition, scanLength, idCounter);

        if (!spider.gait().legScanAlternativeGround) return mainCandidate;

        // Accept main candidate if it is within a comfortable step height
        if (mainCandidate != null) {
            float mainY      = mainCandidate.position.y;
            float lookAheadY = lookAheadPosition.y;
            if (mainY >= lookAheadY - 0.24f && mainY <= lookAheadY + 1.5f) {
                return mainCandidate;
            }
        }

        // ── 3×3 grid spread along the surface tangent plane ───────────────────
        //
        // Previously: offsets were in world-XZ (±floor(x)/ceil(x), ±floor(z)/ceil(z)).
        // Now: offsets are ±margin along tangentA and ±margin along tangentB so the
        // probe grid lies on the actual surface the spider is standing on.
        //
        // margin is kept identical to the original (2/16 blocks) so step-detection
        // sensitivity is unchanged on flat ground.
        float margin = 2f / 16f;

        // Compute the nine probe start positions in surface-local space.
        // Index layout matches the original array order so the selection logic
        // below (closest to preferredPosition) is unchanged.
        Vector3f[] offsets = {
                new Vector3f(tangentA).mul(-margin).add(new Vector3f(tangentB).mul(-margin)),
                new Vector3f(tangentA).mul(-margin),
                new Vector3f(tangentA).mul(-margin).add(new Vector3f(tangentB).mul( margin)),
                new Vector3f(tangentB).mul(-margin),
                new Vector3f(),   // centre (mainCandidate slot)
                new Vector3f(tangentB).mul( margin),
                new Vector3f(tangentA).mul( margin).add(new Vector3f(tangentB).mul(-margin)),
                new Vector3f(tangentA).mul( margin),
                new Vector3f(tangentA).mul( margin).add(new Vector3f(tangentB).mul( margin)),
        };

        LegTarget[] candidates = new LegTarget[9];
        for (int i = 0; i < 9; i++) {
            if (i == 4) {
                candidates[4] = mainCandidate; // keep already-computed centre
            } else {
                Vector3f probeStart = new Vector3f(scanStartPosition).add(offsets[i]);
                candidates[i] = rayCast3D(probeStart, scanLength, idCounter);
            }
        }

        // ── Height-bias preferred position if a step is imminent ──────────────
        Vector3f preferredPosition = new Vector3f(lookAheadPosition);

        Vector3f frontCheck = new Vector3f(spider.forwardDirection()).add(lookAheadPosition);
        int fbx = (int) Math.floor(frontCheck.x);
        int fby = (int) Math.floor(frontCheck.y);
        int fbz = (int) Math.floor(frontCheck.z);
        if (spider.world.getBlock(fbx, fby, fbz).isSolid()) {
            preferredPosition.y += spider.gait().legScanHeightBias;
        }

        // ── Pick the candidate closest to the preferred position ───────────────
        LegTarget best     = null;
        float     bestDist = Float.MAX_VALUE;
        for (LegTarget c : candidates) {
            if (c == null) continue;
            float d = c.position.distanceSquared(preferredPosition);
            if (d < bestDist) {
                bestDist = d;
                best     = c;
            }
        }

        // Discard if it falls outside the comfort zone
        if (best != null && !comfortZone.contains(best.position)) return null;
        return best;
    }

    /**
     * Casts a single ray from {@code start} in the scanVector direction.
     *
     * Unlike the old {@code rayCast(float x, float z, ...)}, this method takes a
     * full 3D start point so it works correctly on any surface orientation,
     * not just on floors where world-Y defines the start height.
     */
    private LegTarget rayCast3D(Vector3f start, float scanLength, int[] idCounter) {
        idCounter[0]++;
        Vector3f hit = SpiderWorldAdapter.raycastGround(
                spider.world, start, scanVector, scanLength);
        if (hit == null) return null;
        return new LegTarget(new Vector3f(hit), true, idCounter[0]);
    }

    private LegTarget strandedTarget() {
        return new LegTarget(new Vector3f(lookAheadPosition), false, -1);
    }

    private LegTarget disabledTarget() {
        LerpGait lerpedGait = spider.lerpedGait();
        Vector3f upVector   = new Vector3f(0f, 1f, 0f).rotate(spider.orientation);

        LegTarget t = strandedTarget();
        t.position.add(new Vector3f(upVector).mul((float)(lerpedGait.bodyHeight * 0.5f)));

        float minY = (groundPosition != null ? groundPosition.y : -Float.MAX_VALUE)
                + (float)(lerpedGait.bodyHeight * 0.1f);
        if (t.position.y < minY) t.position.y = minY;

        return t;
    }
}