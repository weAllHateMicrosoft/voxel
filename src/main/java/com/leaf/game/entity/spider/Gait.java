package com.leaf.game.entity.spider;

import com.leaf.game.util.SpiderMath;

public class Gait {
    public GaitType type;

    public LerpGait stationary = new LerpGait(1.1f, new SpiderMath.SplitDistance(0.25f, 1.5f));
    public LerpGait moving     = new LerpGait(1.1f, new SpiderMath.SplitDistance(0.8f, 1.5f));

    public float maxBodyDistanceFromGround = 0.25f;
    public float maxSpeed;
    public float moveAcceleration = 0.15f / 4f;

    public float rotateAcceleration = 0.15f / 4f;
    public float rotationalDragCoefficient = 0.2f;

    public float legMoveSpeed;
    public float legLiftHeight = 0.35f;
    public float legDropDistance = 0.35f;

    public SpiderMath.SplitDistance comfortZone = new SpiderMath.SplitDistance(1.2f, 1.6f);

    public float gravityAcceleration = 0.08f;
    public float airDragCoefficient = 0.02f;
    public float bounceFactor = 0.5f;

    public float bodyHeightCorrectionAcceleration = 0.08f * 4f;
    public float bodyHeightCorrectionFactor = 0.25f;

    public boolean legScanAlternativeGround = true;
    public float legScanHeightBias = 0.5f;

    public float tridentKnockBack = 0.3f;
    public float tridentRotationalKnockBack = 0.3f / 4f;
    public float legLookAheadFraction = 0.6f;
    public float groundDragCoefficient = 0.2f;

    public int samePairCooldown = 1;
    public int crossPairCooldown = 1;

    public boolean useLegacyNormalForce = false;
    public float polygonLeeway = 0.0f;
    public float stabilizationFactor = 0.0f;

    public float uncomfortableSpeedMultiplier = 0.0f;
    public boolean disableAdvancedRotation = false;
    public float preferredPitchLeeway = (float) Math.toRadians(10f);

    public boolean straightenLegs = true;
    public float legStraightenRotation = (float) Math.toRadians(-80f);

    public PivotMode scanPivotMode = PivotMode.Y_AXIS;
    public PivotMode legChainPivotMode = PivotMode.SPIDER_ORIENTATION;

    public float preferLevelBreakpoint = (float) Math.toRadians(45f);
    public float preferLevelBias = 0.0f;
    public float preferredRotationLerpFraction = 0.3f;
    public float rotationLerp = 0.3f;

    public Gait(float walkSpeed, GaitType type) {
        this.maxSpeed = walkSpeed;
        this.type = type;
        this.legMoveSpeed = walkSpeed * 2.5f;
    }

    public void scale(float scale) {
        stationary.scale(scale);
        moving.scale(scale);
        maxBodyDistanceFromGround *= scale;
        maxSpeed *= scale;
        moveAcceleration *= scale;
        legMoveSpeed *= scale;
        legLiftHeight *= scale;
        legDropDistance *= scale;
        comfortZone = comfortZone.scale(scale);
        legScanHeightBias *= scale;
        tridentRotationalKnockBack /= scale;
    }

    public static Gait defaultWalk() {
        return new Gait(0.15f, GaitType.WALK);
    }

    public static Gait defaultGallop() {
        Gait g = new Gait(0.4f, GaitType.GALLOP);
        g.moving.bodyHeight = 1.6f;
        g.legMoveSpeed = 0.5f;
        g.rotateAcceleration = 0.25f / 4f;
        g.uncomfortableSpeedMultiplier = 0.6f;
        g.samePairCooldown = 2;
        g.crossPairCooldown = 4;
        g.polygonLeeway = 0.5f;
        return g;
    }
}