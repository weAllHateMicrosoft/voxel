package com.leaf.game.entity.spider;

public class SpiderOptions {
    public Gait walkGait = Gait.defaultWalk();
    public Gait gallopGait = Gait.defaultGallop();
    public BodyPlan bodyPlan = new BodyPlan();

    public boolean renderDebugVisuals = false;
    public boolean disableFabrik = false;
}