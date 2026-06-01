package com.leaf.game.anim;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;

/**
 * A complete character model: a list of box-parts and a set of named animation clips.
 *
 * ── File format ──────────────────────────────────────────────────────────────
 * Models live in  src/main/resources/models/<name>.json
 * (also accessible at runtime as /models/<name>.json on the classpath).
 *
 * Minimal example:
 * {
 *   "name": "enemy_basic",
 *   "parts": [
 *     {"id":"body", "w":0.8,"h":1.0,"d":0.5, "pivotY":0.5, "cr":0.4,"cg":0.7,"cb":0.4},
 *     {"id":"head", "parent":"body", "w":0.7,"h":0.7,"d":0.7, "oy":0.85, "cr":0.9,"cg":0.7,"cb":0.5}
 *   ],
 *   "animations": {
 *     "idle":  {"duration":2.0,"loop":true, "keyframes":{"body":[{"t":0,"ty":0},{"t":1,"ty":-0.03},{"t":2,"ty":0}]}},
 *     "walk":  {"duration":0.6,"loop":true, "keyframes":{
 *        "arm_l":[{"t":0,"rx":30},{"t":0.3,"rx":-30},{"t":0.6,"rx":30}]
 *     }}
 *   }
 * }
 */
public class AnimModel {

    public String name = "unnamed";
    public List<PartDef>        parts      = new ArrayList<>();
    public Map<String, AnimClip> animations = new LinkedHashMap<>();

    // ── Constructors ─────────────────────────────────────────────────────────

    public AnimModel() {}
    public AnimModel(String name) { this.name = name; }

    // ── Lookup helpers ───────────────────────────────────────────────────────

    public PartDef getPart(String id) {
        for (PartDef p : parts) if (id.equals(p.id)) return p;
        return null;
    }

    public List<PartDef> childrenOf(String parentId) {
        List<PartDef> out = new ArrayList<>();
        for (PartDef p : parts) if (Objects.equals(p.parent, parentId)) out.add(p);
        return out;
    }

    // ── JSON I/O ─────────────────────────────────────────────────────────────

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Load a model from the classpath (/models/<name>.json). Returns null if not found. */
    public static AnimModel loadFromClasspath(String name) {
        String path = "/models/" + name + ".json";
        try (InputStream is = AnimModel.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return fromJson(new String(is.readAllBytes()));
        } catch (Exception e) {
            System.err.println("[AnimModel] Failed to load " + path + ": " + e.getMessage());
            return null;
        }
    }

    /** Load from an explicit file path on disk. Returns null on error. */
    public static AnimModel loadFromFile(String filePath) {
        try {
            String json = Files.readString(Path.of(filePath));
            return fromJson(json);
        } catch (Exception e) {
            System.err.println("[AnimModel] Failed to load " + filePath + ": " + e.getMessage());
            return null;
        }
    }

    /** Save to disk at the given path. */
    public void saveToFile(String filePath) {
        try {
            Files.writeString(Path.of(filePath), toJson());
        } catch (Exception e) {
            System.err.println("[AnimModel] Failed to save " + filePath + ": " + e.getMessage());
        }
    }

    public String toJson() {
        return GSON.toJson(buildJsonTree());
    }

    // ── JSON serialisation (hand-rolled so the format stays human-friendly) ──

    private JsonObject buildJsonTree() {
        JsonObject root = new JsonObject();
        root.addProperty("name", name);

        JsonArray partsArr = new JsonArray();
        for (PartDef p : parts) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id);
            if (p.parent != null) o.addProperty("parent", p.parent);
            o.addProperty("w", p.w);
            o.addProperty("h", p.h);
            o.addProperty("d", p.d);
            if (p.pivotX != 0) o.addProperty("pivotX", p.pivotX);
            if (p.pivotY != 0) o.addProperty("pivotY", p.pivotY);
            if (p.pivotZ != 0) o.addProperty("pivotZ", p.pivotZ);
            if (p.ox != 0) o.addProperty("ox", p.ox);
            if (p.oy != 0) o.addProperty("oy", p.oy);
            if (p.oz != 0) o.addProperty("oz", p.oz);
            if (p.defaultRx != 0) o.addProperty("defaultRx", p.defaultRx);
            if (p.defaultRy != 0) o.addProperty("defaultRy", p.defaultRy);
            if (p.defaultRz != 0) o.addProperty("defaultRz", p.defaultRz);
            o.addProperty("cr", p.cr);
            o.addProperty("cg", p.cg);
            o.addProperty("cb", p.cb);
            if (p.tex != null) o.addProperty("tex", p.tex);
            if (p.geo != null) {
                JsonArray geoArr = new JsonArray();
                for (float f : p.geo) geoArr.add(f);
                o.add("geo", geoArr);
            }
            partsArr.add(o);
        }
        root.add("parts", partsArr);

        JsonObject animsObj = new JsonObject();
        for (Map.Entry<String, AnimClip> entry : animations.entrySet()) {
            AnimClip clip = entry.getValue();
            JsonObject clipObj = new JsonObject();
            clipObj.addProperty("duration", clip.duration);
            clipObj.addProperty("loop", clip.loop);
            JsonObject kfsObj = new JsonObject();
            for (Map.Entry<String, List<Keyframe>> track : clip.keyframes.entrySet()) {
                JsonArray trackArr = new JsonArray();
                for (Keyframe kf : track.getValue()) {
                    JsonObject kfObj = new JsonObject();
                    kfObj.addProperty("t", kf.t);
                    if (kf.rx != null) kfObj.addProperty("rx", kf.rx);
                    if (kf.ry != null) kfObj.addProperty("ry", kf.ry);
                    if (kf.rz != null) kfObj.addProperty("rz", kf.rz);
                    if (kf.tx != null) kfObj.addProperty("tx", kf.tx);
                    if (kf.ty != null) kfObj.addProperty("ty", kf.ty);
                    if (kf.tz != null) kfObj.addProperty("tz", kf.tz);
                    if (kf.sx != null) kfObj.addProperty("sx", kf.sx);
                    if (kf.sy != null) kfObj.addProperty("sy", kf.sy);
                    if (kf.sz != null) kfObj.addProperty("sz", kf.sz);
                    if (!"linear".equals(kf.easing)) kfObj.addProperty("easing", kf.easing);
                    trackArr.add(kfObj);
                }
                kfsObj.add(track.getKey(), trackArr);
            }
            clipObj.add("keyframes", kfsObj);
            animsObj.add(entry.getKey(), clipObj);
        }
        root.add("animations", animsObj);
        return root;
    }

    private static AnimModel fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        AnimModel m = new AnimModel();
        if (root.has("name")) m.name = root.get("name").getAsString();

        if (root.has("parts")) {
            for (JsonElement el : root.getAsJsonArray("parts")) {
                JsonObject o = el.getAsJsonObject();
                PartDef p = new PartDef();
                p.id   = o.get("id").getAsString();
                p.parent = o.has("parent") ? o.get("parent").getAsString() : null;
                p.w = getF(o, "w", 0.5f); p.h = getF(o, "h", 0.5f); p.d = getF(o, "d", 0.5f);
                p.pivotX = getF(o, "pivotX", 0f); p.pivotY = getF(o, "pivotY", 0f); p.pivotZ = getF(o, "pivotZ", 0f);
                p.ox = getF(o, "ox", 0f); p.oy = getF(o, "oy", 0f); p.oz = getF(o, "oz", 0f);
                p.defaultRx = getF(o, "defaultRx", 0f);
                p.defaultRy = getF(o, "defaultRy", 0f);
                p.defaultRz = getF(o, "defaultRz", 0f);
                p.cr = getF(o, "cr", 0.7f); p.cg = getF(o, "cg", 0.7f); p.cb = getF(o, "cb", 0.7f);
                p.ca = getF(o, "ca", 1f);
                if (o.has("tex") && !o.get("tex").isJsonNull()) p.tex = o.get("tex").getAsString();
                if (o.has("geo") && o.get("geo").isJsonArray()) {
                    JsonArray g = o.getAsJsonArray("geo");
                    p.geo = new float[g.size()];
                    for (int i = 0; i < g.size(); i++) p.geo[i] = g.get(i).getAsFloat();
                }
                m.parts.add(p);
            }
        }

        if (root.has("animations")) {
            for (Map.Entry<String, JsonElement> ae : root.getAsJsonObject("animations").entrySet()) {
                JsonObject co = ae.getValue().getAsJsonObject();
                AnimClip clip = new AnimClip(ae.getKey());
                clip.duration = getF(co, "duration", 1f);
                clip.loop     = co.has("loop") && co.get("loop").getAsBoolean();
                if (co.has("keyframes")) {
                    for (Map.Entry<String, JsonElement> te : co.getAsJsonObject("keyframes").entrySet()) {
                        List<Keyframe> track = clip.getOrCreateTrack(te.getKey());
                        for (JsonElement ke : te.getValue().getAsJsonArray()) {
                            JsonObject ko = ke.getAsJsonObject();
                            Keyframe kf = new Keyframe(getF(ko, "t", 0f));
                            if (ko.has("rx")) kf.rx = ko.get("rx").getAsFloat();
                            if (ko.has("ry")) kf.ry = ko.get("ry").getAsFloat();
                            if (ko.has("rz")) kf.rz = ko.get("rz").getAsFloat();
                            if (ko.has("tx")) kf.tx = ko.get("tx").getAsFloat();
                            if (ko.has("ty")) kf.ty = ko.get("ty").getAsFloat();
                            if (ko.has("tz")) kf.tz = ko.get("tz").getAsFloat();
                            if (ko.has("easing")) kf.easing = ko.get("easing").getAsString();
                            if (ko.has("sx")) kf.sx = ko.get("sx").getAsFloat();
                            if (ko.has("sy")) kf.sy = ko.get("sy").getAsFloat();
                            if (ko.has("sz")) kf.sz = ko.get("sz").getAsFloat();
                            track.add(kf);
                        }
                    }
                }
                clip.sortTracks();
                m.animations.put(ae.getKey(), clip);
            }
        }
        return m;
    }

    private static float getF(JsonObject o, String key, float def) {
        return o.has(key) ? o.get(key).getAsFloat() : def;
    }

    /** Scan the resources/models directory on disk and return all .json filenames (without extension). */
    public static List<String> listModelFiles() {
        List<String> names = new ArrayList<>();
        File dir = new File("src/main/resources/models");
        if (dir.isDirectory()) {
            for (File f : Objects.requireNonNull(dir.listFiles()))
                if (f.getName().endsWith(".json"))
                    names.add(f.getName().replace(".json", ""));
        }
        return names;
    }
}
