package com.leaf.game.anim;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Converts a Blockbench project file (.bbmodel) into our {@link AnimModel} JSON.
 *
 * ── What it understands ────────────────────────────────────────────────────────
 *  • Bones (groups in the Blockbench "outliner")  → animatable transform nodes
 *  • Cubes (elements)                             → solid coloured boxes
 *  • Bone/cube rotation in the rest pose          → defaultR
 *  • Animations → rotation + position keyframes (degrees / blocks)
 *
 * ── Design ─────────────────────────────────────────────────────────────────────
 * Blockbench works in PIXELS where 16 px = 1 block. Our engine works in blocks,
 * so every length is divided by 16.
 *
 * Each Blockbench *group* becomes a zero-size "bone" PartDef whose origin is the
 * group's pivot — bones carry the animation. Each *cube* inside a group becomes a
 * separate geometry PartDef parented to that bone. This keeps the mapping simple
 * and correct no matter how many cubes a bone holds, and it means a bone's
 * rotation always pivots around the point you set in Blockbench.
 *
 * ── Coordinate maths (so future-me can follow it) ──────────────────────────────
 * Given our AnimPlayer transform  P(L) = origin + pivot + R·(L − pivot)  and our
 * BoxMesh which centres geometry at (−pivot):
 *   • a box's centre at rest sits at   origin − pivot
 *   • a part's rotation pivot sits at  origin + pivot
 * For a bone we want rotation about the group origin, so pivot = 0 and
 *   origin = (groupOrigin − parentGroupOrigin) / 16.
 * For a cube with no own rotation:  pivot = 0,  origin = (cubeCentre − groupOrigin)/16.
 * For a cube rotated about its own point C:
 *   pivot  = (C − cubeCentre) / 32
 *   origin = (cubeCentre + C) / 32  −  groupOrigin / 16
 *
 * ── CLI use ────────────────────────────────────────────────────────────────────
 *   java ...BlockbenchImporter  monster.bbmodel  [out.json]
 * If out.json is omitted it writes  src/main/resources/models/&lt;name&gt;.json
 */
public class BlockbenchImporter {

    /** 16 Blockbench pixels = 1 world block. */
    private static final float SCALE = 16f;

    /** The 8 Blockbench cube "marker" colours, approximated as solid RGB. */
    private static final float[][] MARKER = {
        {0.62f, 0.62f, 0.66f}, // 0 grey   (default)
        {0.86f, 0.36f, 0.36f}, // 1 red
        {0.90f, 0.62f, 0.32f}, // 2 orange
        {0.90f, 0.84f, 0.36f}, // 3 yellow
        {0.50f, 0.76f, 0.42f}, // 4 green
        {0.40f, 0.74f, 0.78f}, // 5 cyan
        {0.42f, 0.56f, 0.86f}, // 6 blue
        {0.72f, 0.46f, 0.82f}, // 7 purple
    };

    /** Conversion result + human-readable notes/warnings for the preview console. */
    public static class Result {
        public AnimModel model;
        public final List<String> warnings = new ArrayList<>();
        public final List<String> info     = new ArrayList<>();
    }

    // ── per-run state ────────────────────────────────────────────────────────
    private final Result res = new Result();
    private final AnimModel model = new AnimModel();
    private final Set<String> usedIds = new HashSet<>();
    private final Map<String, JsonObject> elements = new HashMap<>();    // element uuid → element
    private final Map<String, JsonObject> groupsById = new HashMap<>();  // group uuid → group metadata
    private final Map<String, String> uuidToBone = new HashMap<>();      // group uuid → bone part id
    private final Map<Integer, JsonObject> texturesByIndex = new HashMap<>(); // index → texture json
    private final Map<Integer, String> texPathCache = new HashMap<>();   // index → classpath path (saved)
    private final Set<String> usedTexNames = new HashSet<>();
    private boolean warnedCurve = false;

    // Texture output config (set by importFile). When texturesDir is null no PNGs
    // are written and parts stay untextured (used by the in-memory preview).
    private Path   texturesDir       = null;             // e.g. .../resources/models
    private String texClasspathPrefix = "/models/";       // + <name>/<tex>.png

    private BlockbenchImporter(String name) { model.name = name; }

    // ── Public entry points ───────────────────────────────────────────────────

    public static Result importFile(String path) throws IOException {
        return importFile(path, null);
    }

    /**
     * Import a .bbmodel and, when {@code texturesDir} is non-null, extract its
     * embedded PNG textures into {@code texturesDir/<modelName>/} so the model
     * renders with the real Blockbench textures.  Pass null to skip texture
     * extraction (parts stay untextured — used by the live preview).
     */
    public static Result importFile(String path, Path texturesDir) throws IOException {
        String json = Files.readString(Path.of(path));
        String fileName = Path.of(path).getFileName().toString();
        String base = fileName.toLowerCase().endsWith(".bbmodel")
                ? fileName.substring(0, fileName.length() - 8) : fileName;
        String name = sanitize(base);
        BlockbenchImporter imp = new BlockbenchImporter(name);
        imp.texturesDir        = texturesDir;
        imp.texClasspathPrefix = "/models/" + name + "/";
        imp.run(json);
        imp.res.model = imp.model;
        return imp.res;
    }

    public static Result importJson(String json, String modelName) {
        BlockbenchImporter imp = new BlockbenchImporter(modelName);
        imp.run(json);
        imp.res.model = imp.model;
        return imp.res;
    }

    // ── Conversion ─────────────────────────────────────────────────────────────

    private void run(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // 1a. Index every element by uuid.
        if (root.has("elements")) {
            for (JsonElement el : root.getAsJsonArray("elements")) {
                JsonObject c = el.getAsJsonObject();
                if (c.has("uuid")) elements.put(c.get("uuid").getAsString(), c);
            }
        }
        res.info.add("Found " + elements.size() + " element(s).");

        // 1b. Index group metadata by uuid (newer bbmodel format stores name/origin here,
        //     not inline in the outliner).
        if (root.has("groups")) {
            for (JsonElement g : root.getAsJsonArray("groups")) {
                JsonObject go = g.getAsJsonObject();
                if (go.has("uuid")) groupsById.put(go.get("uuid").getAsString(), go);
            }
        }

        // 1c. Index textures by their array position (faces reference them by index).
        if (root.has("textures")) {
            JsonArray texArr = root.getAsJsonArray("textures");
            for (int i = 0; i < texArr.size(); i++) texturesByIndex.put(i, texArr.get(i).getAsJsonObject());
        }

        // 2. Walk the outliner (bone hierarchy). Top level parents = none.
        if (root.has("outliner")) {
            float[] worldOrigin = {0, 0, 0};
            for (JsonElement node : root.getAsJsonArray("outliner")) {
                processNode(node, null, worldOrigin);
            }
        } else {
            res.warnings.add("No 'outliner' found — model has no bones to convert.");
        }
        res.info.add("Created " + model.parts.size() + " part(s).");

        // 3. Animations.
        if (root.has("animations")) {
            for (JsonElement a : root.getAsJsonArray("animations")) {
                convertAnimation(a.getAsJsonObject());
            }
        }
        if (model.animations.isEmpty()) {
            res.info.add("No animations in file — added a static 'idle' pose.");
            AnimClip idle = new AnimClip("idle");
            idle.duration = 1f; idle.loop = true;
            model.animations.put("idle", idle);
        }
        res.info.add("Converted " + model.animations.size() + " animation(s).");
    }

    /** A node is either a group (JsonObject) or a bare cube uuid (JsonPrimitive). */
    private void processNode(JsonElement node, String parentBoneId, float[] parentOrigin) {
        if (node.isJsonObject()) {
            JsonObject g = node.getAsJsonObject();

            // Newer bbmodel format: the outliner group node only has uuid+children;
            // name and origin live in the top-level 'groups' array keyed by uuid.
            JsonObject meta = g;
            if (g.has("uuid") && !g.has("name")) {
                JsonObject looked = groupsById.get(g.get("uuid").getAsString());
                if (looked != null) meta = looked;
            }

            String name = (meta.has("name") && !meta.get("name").isJsonNull())
                    ? meta.get("name").getAsString() : "bone";
            String boneId = uniqueId(sanitize(name));
            if (g.has("uuid")) uuidToBone.put(g.get("uuid").getAsString(), boneId);

            float[] origin = arr3(meta, "origin", new float[]{0, 0, 0});

            // Bone = zero-size transform node, rotates about the group origin.
            PartDef bone = new PartDef();
            bone.id = boneId;
            bone.parent = parentBoneId;
            bone.w = bone.h = bone.d = 0f;
            bone.ox = (origin[0] - parentOrigin[0]) / SCALE;
            bone.oy = (origin[1] - parentOrigin[1]) / SCALE;
            bone.oz = (origin[2] - parentOrigin[2]) / SCALE;
            if (g.has("rotation")) {
                float[] r = arr3el(g.get("rotation"), new float[]{0, 0, 0});
                bone.defaultRx = r[0]; bone.defaultRy = r[1]; bone.defaultRz = r[2];
            }
            model.parts.add(bone);

            // Children: nested groups recurse; element uuids become geometry.
            if (g.has("children")) {
                int elIdx = 0;
                for (JsonElement child : g.getAsJsonArray("children")) {
                    if (child.isJsonPrimitive()) {
                        JsonObject el = elements.get(child.getAsString());
                        if (el != null) emitGeometry(el, boneId, origin, boneId + "_g" + elIdx);
                        elIdx++;
                    } else {
                        processNode(child, boneId, origin);
                    }
                }
            }
        } else if (node.isJsonPrimitive()) {
            // Loose element not inside any group — attach to current parent bone.
            JsonObject el = elements.get(node.getAsString());
            if (el != null) {
                String nm = el.has("name") ? sanitize(el.get("name").getAsString()) : "geo";
                emitGeometry(el, parentBoneId, parentOrigin, nm);
            }
        }
    }
    /**
     * Convert one Blockbench element (cube or mesh) into textured-triangle parts.
     * Faces are grouped by the texture they use, so each emitted PartDef carries a
     * single texture; positions are baked into bone-local world units.
     */
    private void emitGeometry(JsonObject el, String boneId, float[] boneOrigin, String idBase) {
        String type = el.has("type") ? el.get("type").getAsString() : "cube";
        float[] erot    = arr3(el, "rotation", new float[]{0, 0, 0});
        float[] eorigin = arr3(el, "origin",   new float[]{0, 0, 0});

        // textureIndex → list of triangles (each = 15 floats: 3 verts × {x,y,z,u,v})
        Map<Integer, List<float[]>> byTex = new LinkedHashMap<>();

        if ("cube".equals(type)) {
            float[] from = arr3(el, "from", null), to = arr3(el, "to", null);
            if (from == null || to == null) {
                res.warnings.add("Skipped cube '" + idBase + "': missing from/to."); return;
            }
            addCubeFaces(el, from, to, eorigin, erot, boneOrigin, byTex);
        } else if ("mesh".equals(type)) {
            addMeshFaces(el, eorigin, erot, boneOrigin, byTex);
        } else {
            res.warnings.add("Skipped element '" + idBase + "': type '" + type + "' not supported.");
            return;
        }
        if (byTex.isEmpty()) return;

        int g = 0;
        boolean multi = byTex.size() > 1;
        for (Map.Entry<Integer, List<float[]>> e : byTex.entrySet()) {
            List<float[]> tris = e.getValue();
            if (tris.isEmpty()) continue;
            float[] geo = new float[tris.size() * 15];
            int gi = 0;
            for (float[] t : tris) { System.arraycopy(t, 0, geo, gi, 15); gi += 15; }

            PartDef p = new PartDef();
            p.id     = uniqueId(multi ? idBase + "_t" + g : idBase);
            p.parent = boneId;
            p.geo    = geo;
            p.tex    = ensureTextureSaved(e.getKey());
            if (p.tex == null) { // untextured face group → solid marker colour fallback
                int ci = el.has("color") ? el.get("color").getAsInt() : 0;
                float[] col = MARKER[((ci % MARKER.length) + MARKER.length) % MARKER.length];
                p.cr = col[0]; p.cg = col[1]; p.cb = col[2];
            }
            model.parts.add(p);
            g++;
        }
    }

    /** Synthesise the 6 box faces (with per-face UV rectangles) of a cube element. */
    private void addCubeFaces(JsonObject el, float[] from, float[] to, float[] eorigin,
                              float[] erot, float[] boneOrigin, Map<Integer, List<float[]>> byTex) {
        if (!el.has("faces")) return;
        JsonObject faces = el.getAsJsonObject("faces");
        float fx = Math.min(from[0], to[0]), fy = Math.min(from[1], to[1]), fz = Math.min(from[2], to[2]);
        float tx = Math.max(from[0], to[0]), ty = Math.max(from[1], to[1]), tz = Math.max(from[2], to[2]);

        // 4 corners per face in UV order [TL, TR, BR, BL] (standard Minecraft mapping).
        Map<String, float[][]> corners = new LinkedHashMap<>();
        corners.put("north", new float[][]{{tx,ty,fz},{fx,ty,fz},{fx,fy,fz},{tx,fy,fz}});
        corners.put("south", new float[][]{{fx,ty,tz},{tx,ty,tz},{tx,fy,tz},{fx,fy,tz}});
        corners.put("east",  new float[][]{{tx,ty,tz},{tx,ty,fz},{tx,fy,fz},{tx,fy,tz}});
        corners.put("west",  new float[][]{{fx,ty,fz},{fx,ty,tz},{fx,fy,tz},{fx,fy,fz}});
        corners.put("up",    new float[][]{{fx,ty,fz},{tx,ty,fz},{tx,ty,tz},{fx,ty,tz}});
        corners.put("down",  new float[][]{{fx,fy,tz},{tx,fy,tz},{tx,fy,fz},{fx,fy,fz}});

        for (Map.Entry<String, float[][]> fe : corners.entrySet()) {
            if (!faces.has(fe.getKey())) continue;
            JsonObject face = faces.getAsJsonObject(fe.getKey());
            float[] uv = arr4(face.has("uv") ? face.get("uv") : null);
            if (uv == null) continue;
            int texIdx = (face.has("texture") && !face.get("texture").isJsonNull())
                    ? face.get("texture").getAsInt() : -1;
            float[] tsz = texUvSize(texIdx);
            float[][] uvc = {{uv[0],uv[1]},{uv[2],uv[1]},{uv[2],uv[3]},{uv[0],uv[3]}};
            float[][] pos = new float[4][], uvn = new float[4][];
            for (int i = 0; i < 4; i++) {
                pos[i] = bake(fe.getValue()[i], eorigin, erot, boneOrigin, false);
                // V is flipped: Texture.load loads PNGs upside-down (top row → GL v=1),
                // while Blockbench measures UV-V from the top.
                uvn[i] = new float[]{uvc[i][0] / tsz[0], 1f - uvc[i][1] / tsz[1]};
            }
            addTri(byTex, texIdx, pos[0],uvn[0], pos[1],uvn[1], pos[2],uvn[2]);
            addTri(byTex, texIdx, pos[2],uvn[2], pos[3],uvn[3], pos[0],uvn[0]);
        }
    }

    /** Triangulate every polygon face of a mesh element (fan), with per-vertex UVs. */
    private void addMeshFaces(JsonObject el, float[] eorigin, float[] erot, float[] boneOrigin,
                              Map<Integer, List<float[]>> byTex) {
        if (!el.has("vertices") || !el.has("faces")) return;
        JsonObject vertsObj = el.getAsJsonObject("vertices");
        JsonObject faces    = el.getAsJsonObject("faces");
        for (Map.Entry<String, JsonElement> fe : faces.entrySet()) {
            JsonObject face = fe.getValue().getAsJsonObject();
            if (!face.has("vertices")) continue;
            JsonArray vk = face.getAsJsonArray("vertices");
            int n = vk.size();
            if (n < 3) continue;
            int texIdx = (face.has("texture") && !face.get("texture").isJsonNull())
                    ? face.get("texture").getAsInt() : -1;
            float[] tsz = texUvSize(texIdx);
            JsonObject uvObj = face.has("uv") && face.get("uv").isJsonObject()
                    ? face.getAsJsonObject("uv") : null;

            float[][] pos = new float[n][], uvn = new float[n][];
            for (int i = 0; i < n; i++) {
                String key = vk.get(i).getAsString();
                float[] local = arr3el(vertsObj.get(key), new float[]{0, 0, 0});
                pos[i] = bake(local, eorigin, erot, boneOrigin, true);
                float[] uvp = (uvObj != null && uvObj.has(key)) ? arr2(uvObj.get(key)) : new float[]{0, 0};
                // V flipped (see addCubeFaces): Texture.load loads PNGs upside-down.
                uvn[i] = new float[]{uvp[0] / tsz[0], 1f - uvp[1] / tsz[1]};
            }
            for (int i = 1; i < n - 1; i++)
                addTri(byTex, texIdx, pos[0],uvn[0], pos[i],uvn[i], pos[i+1],uvn[i+1]);
        }
    }

    /**
     * Bake a Blockbench point into bone-local world units.
     * @param relative true for mesh vertices (already relative to element origin),
     *                 false for cube corners (absolute model-space).
     */
    private static float[] bake(float[] v, float[] eorigin, float[] erot, float[] boneOrigin, boolean relative) {
        float lx = relative ? v[0] : v[0] - eorigin[0];
        float ly = relative ? v[1] : v[1] - eorigin[1];
        float lz = relative ? v[2] : v[2] - eorigin[2];
        float[] r = isZero(erot) ? new float[]{lx, ly, lz}
                : rotEulerXYZ(lx, ly, lz, erot[0], erot[1], erot[2]);
        float ax = eorigin[0] + r[0], ay = eorigin[1] + r[1], az = eorigin[2] + r[2];
        return new float[]{ (ax - boneOrigin[0]) / SCALE,
                            (ay - boneOrigin[1]) / SCALE,
                            (az - boneOrigin[2]) / SCALE };
    }

    /** Rotate a vector by Euler degrees, applying Z then Y then X (matches AnimPlayer order). */
    private static float[] rotEulerXYZ(float x, float y, float z, float dx, float dy, float dz) {
        double rx = Math.toRadians(dx), ry = Math.toRadians(dy), rz = Math.toRadians(dz);
        double cz = Math.cos(rz), sz = Math.sin(rz);
        double x1 = x*cz - y*sz, y1 = x*sz + y*cz, z1 = z;
        double cy = Math.cos(ry), sy = Math.sin(ry);
        double x2 = x1*cy + z1*sy, y2 = y1, z2 = -x1*sy + z1*cy;
        double cx = Math.cos(rx), sx = Math.sin(rx);
        double y3 = y2*cx - z2*sx, z3 = y2*sx + z2*cx;
        return new float[]{(float) x2, (float) y3, (float) z3};
    }

    private static void addTri(Map<Integer, List<float[]>> byTex, int texIdx,
            float[] a, float[] ua, float[] b, float[] ub, float[] c, float[] uc) {
        float[] t = { a[0],a[1],a[2],ua[0],ua[1],
                      b[0],b[1],b[2],ub[0],ub[1],
                      c[0],c[1],c[2],uc[0],uc[1] };
        byTex.computeIfAbsent(texIdx, k -> new ArrayList<>()).add(t);
    }

    /** UV pixel dimensions for a texture index (the space the face UVs are in). */
    private float[] texUvSize(int idx) {
        JsonObject t = texturesByIndex.get(idx);
        if (t == null) return new float[]{16f, 16f};
        float w = t.has("uv_width")  ? t.get("uv_width").getAsFloat()
                : t.has("width")     ? t.get("width").getAsFloat()  : 16f;
        float h = t.has("uv_height") ? t.get("uv_height").getAsFloat()
                : t.has("height")    ? t.get("height").getAsFloat() : 16f;
        return new float[]{ w <= 0 ? 16f : w, h <= 0 ? 16f : h };
    }

    /** Decode and write a texture's embedded PNG to disk; returns its classpath path (cached). */
    private String ensureTextureSaved(int idx) {
        if (idx < 0) return null;
        if (texPathCache.containsKey(idx)) return texPathCache.get(idx);
        JsonObject t = texturesByIndex.get(idx);
        if (t == null || texturesDir == null) { texPathCache.put(idx, null); return null; }
        String src = (t.has("source") && !t.get("source").isJsonNull()) ? t.get("source").getAsString() : null;
        int comma = src == null ? -1 : src.indexOf(',');
        if (src == null || !src.startsWith("data:") || comma < 0) {
            res.warnings.add("Texture " + idx + " has no embedded PNG — left untextured.");
            texPathCache.put(idx, null); return null;
        }
        byte[] png;
        try { png = Base64.getDecoder().decode(src.substring(comma + 1)); }
        catch (Exception ex) { res.warnings.add("Texture " + idx + " base64 decode failed."); texPathCache.put(idx, null); return null; }

        String nm = sanitize(t.has("name") ? t.get("name").getAsString() : ("tex" + idx));
        String fname = nm; int n = 2;
        while (usedTexNames.contains(fname)) fname = nm + "_" + (n++);
        usedTexNames.add(fname);
        try {
            Path dir = texturesDir.resolve(model.name);
            Files.createDirectories(dir);
            Files.write(dir.resolve(fname + ".png"), png);
        } catch (Exception ex) {
            res.warnings.add("Failed to write texture '" + fname + "': " + ex.getMessage());
            texPathCache.put(idx, null); return null;
        }
        String cp = texClasspathPrefix + fname + ".png";
        texPathCache.put(idx, cp);
        res.info.add("Saved texture " + fname + ".png");
        return cp;
    }

    private void convertAnimation(JsonObject anim) {
        String name = anim.has("name") ? sanitize(anim.get("name").getAsString()) : "anim";
        name = uniqueAnimName(name);

        AnimClip clip = new AnimClip(name);
        clip.duration = anim.has("length") ? anim.get("length").getAsFloat() : 0f;
        String loopMode = anim.has("loop") ? anim.get("loop").getAsString() : "once";
        clip.loop = "loop".equalsIgnoreCase(loopMode);

        float maxT = 0f;

        if (anim.has("animators")) {
            for (Map.Entry<String, JsonElement> e : anim.getAsJsonObject("animators").entrySet()) {
                String boneId = uuidToBone.get(e.getKey());
                if (boneId == null) continue; // e.g. an "effects" animator — skip silently
                JsonObject animator = e.getValue().getAsJsonObject();
                if (!animator.has("keyframes")) continue;

                List<Keyframe> track = clip.getOrCreateTrack(boneId);
                for (JsonElement kfe : animator.getAsJsonArray("keyframes")) {
                    JsonObject kfo = kfe.getAsJsonObject();
                    String channel = kfo.has("channel") ? kfo.get("channel").getAsString() : "";
                    float t = kfo.has("time") ? kfo.get("time").getAsFloat() : 0f;
                    maxT = Math.max(maxT, t);
                    String easing = mapEasing(kfo);

                    float[] v = firstDataPoint(kfo);
                    Keyframe kf = new Keyframe(t);
                    kf.easing = easing;
                    if ("rotation".equals(channel)) {
                        kf.rx = v[0]; kf.ry = v[1]; kf.rz = v[2];
                    } else if ("position".equals(channel)) {
                        kf.tx = v[0] / SCALE; kf.ty = v[1] / SCALE; kf.tz = v[2] / SCALE;
                    } else if ("scale".equals(channel)) {
                        kf.sx = v[0]; kf.sy = v[1]; kf.sz = v[2];
                    } else {
                        continue;
                    }
                    track.add(kf);
                }
            }
        }
        if (clip.duration <= 0f) clip.duration = Math.max(0.001f, maxT);
        clip.sortTracks();
        model.animations.put(name, clip);
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private String mapEasing(JsonObject kf) {
        String interp = kf.has("interpolation") ? kf.get("interpolation").getAsString() : "linear";
        switch (interp) {
            case "step":   return "step";
            case "linear": return "linear";
            case "smooth": return "ease_in_out";
            case "catmullrom":
            case "bezier":
                if (!warnedCurve) {
                    res.warnings.add("Curved interpolation ('" + interp + "') was approximated as linear.");
                    warnedCurve = true;
                }
                return "linear";
            default: return "linear";
        }
    }

    private static float[] firstDataPoint(JsonObject kf) {
        float[] out = {0, 0, 0};
        if (kf.has("data_points")) {
            JsonArray dp = kf.getAsJsonArray("data_points");
            if (dp.size() > 0) {
                JsonObject p = dp.get(0).getAsJsonObject();
                out[0] = parseNum(p.get("x"));
                out[1] = parseNum(p.get("y"));
                out[2] = parseNum(p.get("z"));
            }
        }
        return out;
    }

    /** Data points may be numbers OR strings ("0", "1.5", or even math expressions). */
    private static float parseNum(JsonElement el) {
        if (el == null || el.isJsonNull()) return 0f;
        try {
            return el.getAsFloat();
        } catch (Exception ex) {
            try { return Float.parseFloat(el.getAsString().trim()); }
            catch (Exception ex2) { return 0f; } // expression we can't evaluate → 0
        }
    }

    private static float[] arr3(JsonObject o, String key, float[] def) {
        if (!o.has(key)) return def;
        return arr3el(o.get(key), def);
    }

    private static float[] arr3el(JsonElement el, float[] def) {
        if (el == null || !el.isJsonArray()) return def;
        JsonArray a = el.getAsJsonArray();
        if (a.size() < 3) return def;
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static float[] arr4(JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        JsonArray a = el.getAsJsonArray();
        if (a.size() < 4) return null;
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(),
                           a.get(2).getAsFloat(), a.get(3).getAsFloat()};
    }

    private static float[] arr2(JsonElement el) {
        if (el == null || !el.isJsonArray()) return new float[]{0, 0};
        JsonArray a = el.getAsJsonArray();
        if (a.size() < 2) return new float[]{0, 0};
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat()};
    }

    private static boolean isZero(float[] v) {
        return v[0] == 0f && v[1] == 0f && v[2] == 0f;
    }

    private static String sanitize(String s) {
        String out = s.toLowerCase().trim().replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_");
        out = out.replaceAll("^_|_$", "");
        return out.isEmpty() ? "part" : out;
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (usedIds.contains(id)) id = base + "_" + (n++);
        usedIds.add(id);
        return id;
    }

    private String uniqueAnimName(String base) {
        String id = base;
        int n = 2;
        while (model.animations.containsKey(id)) id = base + "_" + (n++);
        return id;
    }

    // ── CLI ────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: BlockbenchImporter <input.bbmodel> [output.json]");
            return;
        }
        String out = args.length >= 2 ? args[1] : null;
        // Textures are written under <models dir>/<modelName>/  next to the JSON.
        Path modelsDir = out != null ? Path.of(out).toAbsolutePath().getParent()
                                      : Path.of("src/main/resources/models");
        Result r = importFile(args[0], modelsDir);
        if (out == null) out = "src/main/resources/models/" + r.model.name + ".json";
        r.model.saveToFile(out);

        for (String s : r.info)     System.out.println("  • " + s);
        for (String w : r.warnings) System.out.println("  ! " + w);
        System.out.println("Wrote " + out);
    }
}
