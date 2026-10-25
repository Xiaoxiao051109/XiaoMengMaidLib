package io.github.tlm.converter.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.tlm.converter.bedrock.BedrockAnimation;
import io.github.tlm.converter.bedrock.BedrockGeometry;
import io.github.tlm.converter.molang.MolangNormalizer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bedrock 几何体 + 动画 → Blockbench {@code .bbmodel}（free 格式，format_version 5.0），供 BetterModel 加载。
 *
 * <p>坐标换算与 Blockbench 自带的 Bedrock 导入器一致：Bedrock 几何体相对 Java/Blockbench 是 X 轴镜像的，
 * 所以位置取 {@code -x}，旋转取 {@code (-x, -y, z)}。动画关键帧默认原样保留（见 {@link #MIRROR_KEYFRAMES}）。
 *
 * <p>两处刻意偏离 Blockbench 导入结果，都是为了让 BetterModel 生成的 Java 物品模型可用：
 * <ul>
 *   <li>Box UV 在这里展开为逐面 UV（含镜像处理），输出 {@code box_uv=false}，不依赖下游再推导；</li>
 *   <li>带自身旋转的立方体被包进一个以立方体旋转点为原点的子骨骼，立方体本身不旋转。
 *       Java 物品模型的元素只支持单轴受限角度旋转，而骨骼旋转由 display 实体的变换矩阵承担，
 *       角度任意。原模组渲染器对旋转立方体也是同样处理（单独建一个 BedrockPart）。</li>
 * </ul>
 */
public final class BbModelWriter {

    private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

    /** 关键帧是否做 X 轴镜像换算；默认关闭，待实机核对动画方向后确定（见 keyframe）。 */
    private static final boolean MIRROR_KEYFRAMES = false;

    private final String modelName;
    private final BedrockGeometry geometry;
    private final byte[] texturePng;
    private final List<BedrockAnimation> animations;

    /** 骨骼名 → 骨骼对应的 group uuid，动画按它挂接。 */
    private final Map<String, String> boneUuids = new HashMap<>();

    /** 关键帧数据点改写器；记录被替换成常量的引用，供清单汇总。 */
    private final MolangNormalizer molang = new MolangNormalizer();

    /**
     * 初始应隐藏的骨骼名（有效隐藏）。BetterModel 3.5.0 运行时不会把 bbmodel 的 group visibility 应用到
     * display 上（实测 cirno 隐藏 0 个），所以这份列表写进清单，由插件生成模型后主动隐藏。
     * 这里包含「自身被脚本隐藏」与「祖先被隐藏后继承」两种情况。
     */
    private final List<String> hiddenBones = new java.util.ArrayList<>();

    /**
     * 自身被脚本隐藏的骨骼 id（不含祖先继承）。运行时祖先恢复显示时，需要据此把只被继承隐藏的
     * 子部件重新显示（例如挂在 {@code chestPlate} 下的第二张脸）。
     */
    private final List<String> selfHiddenBones = new java.util.ArrayList<>();

    /** 同名骨骼去重改名表：原名 → 实际 id（只记录发生改名的项），运行时按它把动画骨骼名映射到实际骨骼。 */
    private final Map<String, String> boneAliases = new LinkedHashMap<>();

    public List<String> hiddenBones() {
        return hiddenBones;
    }

    public List<String> selfHiddenBones() {
        return selfHiddenBones;
    }

    public Map<String, String> boneAliases() {
        return boneAliases;
    }

    /**
     * 状态贴片前移量（模型像素）。眨眼/受伤/祈求/睡觉等贴片是零厚度平面，与脸面只差 0.001 像素：
     * 原版整模型同一矩阵绘制不会深度冲突；BetterModel 每根骨骼是独立 display 实体并逐骨插值，
     * 该间隙会被插值/浮点误差吃掉，表现为闭眼贴片与脸上眼睛互穿。沿模型 -Z 前移固定量规避。
     */
    static final float OVERLAY_Z_OFFSET = 0.20f;

    /** 需要前移的状态贴片骨骼（小写）。只处理零厚度平面立方体，避免误伤护甲/背包等实体部件。 */
    private static final java.util.Set<String> OVERLAY_BONES = java.util.Set.of(
            "blink", "blink2", "_blink", "_bink", "hurtblink", "begshow", "sleepshow");

    /** 本模型里被替换为常量的 Molang 引用（如 {@code ysm.head_yaw}），需要运行时程序化补回。 */
    public java.util.Set<String> replacedMolang() {
        return molang.replaced();
    }

    /** 模型声明的程序化动画（.js），用于推导骨骼初始可见性，见 {@link DefaultVisibility}。 */
    private final java.util.Set<String> scripts;

    /** 手持物骨骼名：ri_ / li_ 前缀是 BetterModel 的主手 / 副手物品标签。 */
    public static final String HAND_ITEM_RIGHT = "ri_tlm_item_right";
    public static final String HAND_ITEM_LEFT = "li_tlm_item_left";

    /** 是否生成手持物骨骼（只有女仆模型需要）。 */
    private boolean handItems;

    public BbModelWriter withHandItems() {
        this.handItems = true;
        return this;
    }

    public BbModelWriter(String modelName, BedrockGeometry geometry, byte[] texturePng,
                         List<BedrockAnimation> animations, java.util.Collection<String> scripts) {
        this.modelName = modelName;
        this.geometry = geometry;
        this.texturePng = texturePng;
        this.animations = animations;
        this.scripts = java.util.Set.copyOf(scripts);
    }

    public JsonObject write() {
        JsonObject root = new JsonObject();

        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "5.0");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);
        root.add("meta", meta);
        root.addProperty("name", modelName);

        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", geometry.textureWidth());
        resolution.addProperty("height", geometry.textureHeight());
        root.add("resolution", resolution);

        JsonArray elements = new JsonArray();
        JsonArray groups = new JsonArray();
        JsonArray outliner = buildHierarchy(elements, groups);
        root.add("elements", elements);
        root.add("groups", groups);
        root.add("outliner", outliner);

        JsonArray textures = new JsonArray();
        textures.add(texture());
        root.add("textures", textures);

        JsonArray animationArray = new JsonArray();
        for (BedrockAnimation animation : animations) {
            animationArray.add(animation(animation));
        }
        root.add("animations", animationArray);
        return root;
    }

    // ---------------------------------------------------------------- 层级

    private JsonArray buildHierarchy(JsonArray elements, JsonArray groups) {
        // 部分模型存在同名骨骼（如 kurokoma_saki 的两个 leftHair）。BetterModel 按 uuid 建索引，
        // 同名会撞 key。这里给后出现的同名骨骼加 _2、_3 后缀，几何体各自保留在原位；
        // 动画与子骨骼的 parent 引用按原名解析到最后一个同名骨骼（与原模组 HashMap 覆盖语义一致）。
        List<BedrockGeometry.Bone> bones = geometry.bones();
        String[] ids = new String[bones.size()];
        Map<String, Integer> seen = new HashMap<>();
        Map<String, String> lastIdByName = new HashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            String name = bones.get(i).name();
            int count = seen.merge(name, 1, Integer::sum);
            ids[i] = count == 1 ? name : name + "_" + count;
            lastIdByName.put(name, ids[i]);
        }
        for (Map.Entry<String, Integer> entry : seen.entrySet()) {
            if (entry.getValue() > 1) {
                boneAliases.put(entry.getKey(), lastIdByName.get(entry.getKey()));
            }
        }
        for (Map.Entry<String, String> entry : lastIdByName.entrySet()) {
            boneUuids.put(entry.getKey(), uuid("group:" + entry.getValue()));
        }

        boolean[] visible = effectiveVisibility(bones, ids, lastIdByName);
        for (int i = 0; i < ids.length; i++) {
            boolean self = DefaultVisibility.visible(bones.get(i).name(), scripts);
            if (!self) {
                selfHiddenBones.add(ids[i]);
            }
            if (!visible[i]) {
                hiddenBones.add(ids[i]);
            }
        }
        // YSM 模型常用 M 前缀镜像/发光层（MRoot 下整套重复骨骼）；默认隐藏它，避免尾巴/衣服叠两层。
        if (lastIdByName.containsKey("MRoot") && lastIdByName.containsKey("Root")) {
            String mirrorRoot = lastIdByName.get("MRoot");
            if (!selfHiddenBones.contains(mirrorRoot)) {
                selfHiddenBones.add(mirrorRoot);
            }
            if (!hiddenBones.contains(mirrorRoot)) {
                hiddenBones.add(mirrorRoot);
            }
        }

        // 先为每根骨骼建好 outliner 节点，再按 parent 挂接；与 JSON 中的出现顺序无关
        Map<String, JsonObject> nodes = new LinkedHashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            BedrockGeometry.Bone bone = bones.get(i);
            String groupUuid = uuid("group:" + ids[i]);
            groups.add(group(groupUuid, ids[i], bone.pivot(), bone.rotation(), visible[i]));

            JsonObject node = outlinerNode(groupUuid);
            JsonArray children = node.getAsJsonArray("children");
            int index = 0;
            boolean overlayBone = OVERLAY_BONES.contains(bone.name().toLowerCase(java.util.Locale.ROOT));
            for (BedrockGeometry.Cube cube : bone.cubes()) {
                String cubeName = ids[i] + "#" + index++;
                // 状态贴片（零厚度平面）与脸面共面，沿模型 -Z 前移固定量，避免独立 display 实体的深度冲突。
                boolean overlay = overlayBone && isFlat(cube);
                float[] pivot = cube.pivot() != null ? cube.pivot() : bone.pivot();
                if (overlay) {
                    // 贴片几何与旋转点一起前移，保证贴片绕正确枢轴旋转。
                    pivot = new float[]{pivot[0], pivot[1], pivot[2] - OVERLAY_Z_OFFSET};
                }
                // BetterModel 支持元素自身 rotation/origin，直接写进元素，避免为每个旋转立方体生成
                // 额外的 wrapper 骨骼；后者会让一个女仆模型膨胀到上千个 display 实体。
                children.add(cube(elements, cubeName, cube, pivot, overlay, cube.rotation()));
            }
            nodes.put(ids[i], node);
        }
        if (this.handItems) {
            addHandItem(groups, nodes, lastIdByName, true);
            addHandItem(groups, nodes, lastIdByName, false);
        }

        JsonArray outliner = new JsonArray();
        for (int i = 0; i < bones.size(); i++) {
            BedrockGeometry.Bone bone = bones.get(i);
            JsonObject node = nodes.get(ids[i]);
            String parentId = bone.parent() == null ? null : lastIdByName.get(bone.parent());
            JsonObject parent = parentId == null ? null : nodes.get(parentId);
            // 自己挂自己（骨骼 parent 写成自身名）会形成环，按根骨骼处理
            if (parent != null && parent != node) {
                parent.getAsJsonArray("children").add(node);
            } else {
                outliner.add(node);
            }
        }
        return outliner;
    }

    /**
     * 每根骨骼的实际初始可见性：自身按 {@link DefaultVisibility}，再继承祖先。
     * BetterModel 每根骨骼是独立的 display 实体，父骨骼隐藏不会连带子骨骼，所以这里显式向下传递。
     */
    private boolean[] effectiveVisibility(List<BedrockGeometry.Bone> bones, String[] ids, Map<String, String> lastIdByName) {
        Map<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            indexById.put(ids[i], i);
        }
        Boolean[] memo = new Boolean[bones.size()];
        boolean[] out = new boolean[bones.size()];
        for (int i = 0; i < bones.size(); i++) {
            out[i] = resolveVisible(i, bones, indexById, lastIdByName, memo, 0);
        }
        return out;
    }

    private boolean resolveVisible(int i, List<BedrockGeometry.Bone> bones, Map<String, Integer> indexById,
                                   Map<String, String> lastIdByName, Boolean[] memo, int depth) {
        if (memo[i] != null) {
            return memo[i];
        }
        BedrockGeometry.Bone bone = bones.get(i);
        boolean self = DefaultVisibility.visible(bone.name(), scripts);
        String parentId = bone.parent() == null ? null : lastIdByName.get(bone.parent());
        Integer parent = parentId == null ? null : indexById.get(parentId);
        // depth 上限防止异常数据形成的父子环
        boolean result = parent == null || parent == i || depth > 64
                ? self
                : self && resolveVisible(parent, bones, indexById, lastIdByName, memo, depth + 1);
        memo[i] = result;
        return result;
    }

    /**
     * 手持物骨骼：BetterModel 的 {@code ri_ / li_} 标签骨骼会显示实体主手 / 副手物品。
     * 挂在手部骨骼下随手臂运动，位置对照原模组渲染层（Bedrock 空间，单位像素，y 向上）：
     * <ul>
     *   <li>Gecko {@code RightHandLocator}：GeckoLayerMaidHeld 平移 (0, -1, -1.6) 后绕 X 转 -90°；</li>
     *   <li>{@code armRightPositioningBone}：LayerMaidHeldItem 换算后为 (0, -1, -2)；</li>
     *   <li>普通 {@code armRight}：LayerMaidHeldItem 换算后为 (∓1, -8.4, -2)，即手掌末端。</li>
     * </ul>
     * 物品朝向与 BetterModel 自带 steve 模型的 {@code pri_right_item} 一致（bbmodel 中 x 旋转 -90°）。
     */
    private void addHandItem(JsonArray groups, Map<String, JsonObject> nodes, Map<String, String> lastIdByName,
                             boolean right) {
        String side = right ? "Right" : "Left";
        String[][] candidates = {
                {side + "HandLocator", "0", "-1", "-1.6"},
                {"arm" + side + "PositioningBone", "0", "-1", "-2"},
                {"arm" + side, right ? "-1" : "1", "-8.4", "-2"},
        };
        for (String[] candidate : candidates) {
            String id = lastIdByName.get(candidate[0]);
            if (id == null) {
                continue;
            }
            BedrockGeometry.Bone hand = null;
            for (BedrockGeometry.Bone bone : geometry.bones()) {
                if (bone.name().equals(candidate[0])) {
                    hand = bone;
                }
            }
            if (hand == null || !nodes.containsKey(id)) {
                continue;
            }
            float[] pivot = {
                    hand.pivot()[0] + Float.parseFloat(candidate[1]),
                    hand.pivot()[1] + Float.parseFloat(candidate[2]),
                    hand.pivot()[2] + Float.parseFloat(candidate[3])
            };
            String name = right ? HAND_ITEM_RIGHT : HAND_ITEM_LEFT;
            String uuid = uuid("group:" + name);
            groups.add(group(uuid, name, pivot, new float[]{90, 0, 0}, true));
            nodes.get(id).getAsJsonArray("children").add(outlinerNode(uuid));
            return;
        }
    }

    private JsonObject group(String uuid, String name, float[] pivot, float[] rotation, boolean visible) {
        JsonObject group = new JsonObject();
        group.addProperty("uuid", uuid);
        group.addProperty("name", name);
        group.add("origin", vec(-pivot[0], pivot[1], pivot[2]));
        group.add("rotation", rotation == null ? vec(0, 0, 0) : vec(-rotation[0], -rotation[1], rotation[2]));
        group.addProperty("export", true);
        group.addProperty("visibility", visible);
        group.add("children", new JsonArray());
        return group;
    }

    private static JsonObject outlinerNode(String uuid) {
        JsonObject node = new JsonObject();
        node.addProperty("uuid", uuid);
        node.addProperty("isOpen", true);
        node.add("children", new JsonArray());
        return node;
    }

    // ---------------------------------------------------------------- 立方体

    /**
     * 写入一个立方体元素并返回其 uuid（outliner 用 uuid 字符串引用元素）。
     *
     * @param overlay 状态贴片：几何沿模型 -Z 前移 {@link #OVERLAY_Z_OFFSET}，避免与脸面共面
     */
    private String cube(JsonArray elements, String name, BedrockGeometry.Cube cube, float[] origin,
                        boolean overlay, float[] rotation) {
        float[] o = cube.origin();
        float[] s = cube.size();
        String uuid = uuid("cube:" + name);

        JsonObject element = new JsonObject();
        element.addProperty("uuid", uuid);
        element.addProperty("name", name);
        element.addProperty("type", "cube");
        element.addProperty("box_uv", false);
        // Bedrock 允许立方体边长为负（等价于沿该轴镜像），此时 origin+size 会小于 origin。
        // 直接当 to 用会让 to < from，UV 展开随之算出负坐标，客户端渲染成黑紫棋盘格，
        // 所以先按各轴取最小/最大值得到规范的 [from, to]。
        float[] from = {-Math.max(o[0], o[0] + s[0]), Math.min(o[1], o[1] + s[1]), Math.min(o[2], o[2] + s[2])};
        float[] to = {-Math.min(o[0], o[0] + s[0]), Math.max(o[1], o[1] + s[1]), Math.max(o[2], o[2] + s[2])};
        if (overlay) {
            // 模型前向为 -Z；从/到一起前移保持零厚度与 UV 不变。
            from[2] -= OVERLAY_Z_OFFSET;
            to[2] -= OVERLAY_Z_OFFSET;
        }
        element.add("from", vec(from[0], from[1], from[2]));
        element.add("to", vec(to[0], to[1], to[2]));
        element.add("origin", vec(-origin[0], origin[1], origin[2]));
        if (rotation != null && !isZero(rotation)) {
            // 与 group() 的 rotation 使用同一空间换算。
            element.add("rotation", vec(-rotation[0], -rotation[1], rotation[2]));
        }
        if (cube.inflate() != 0) {
            element.addProperty("inflate", cube.inflate());
        }
        element.add("faces", cube.faceUv() != null ? perFaceUv(cube) : boxUv(cube));
        elements.add(element);
        return uuid;
    }

    /** 零厚度平面（z 尺寸为 0）：只有这类状态贴片需要前移。 */
    private static boolean isFlat(BedrockGeometry.Cube cube) {
        return Math.abs(cube.size()[2]) < 1.0e-4f;
    }

    /**
     * Box UV 展开为逐面 UV，规则与 Blockbench 一致（宽高深取整，原模组渲染器同样用 floor）。
     * 镜像时东西两面互换，且每个面的 u 方向翻转。
     */
    private static JsonObject boxUv(BedrockGeometry.Cube cube) {
        float u = cube.uv()[0];
        float v = cube.uv()[1];
        // 尺寸可能为负（镜像立方体），展开用绝对值
        float w = Math.abs((float) Math.floor(cube.size()[0]));
        float h = Math.abs((float) Math.floor(cube.size()[1]));
        float d = Math.abs((float) Math.floor(cube.size()[2]));

        Map<String, float[]> rects = new LinkedHashMap<>();
        rects.put("north", new float[]{u + d, v + d, u + d + w, v + d + h});
        rects.put("east", new float[]{u, v + d, u + d, v + d + h});
        rects.put("south", new float[]{u + d + w + d, v + d, u + d + w + d + w, v + d + h});
        rects.put("west", new float[]{u + d + w, v + d, u + d + w + d, v + d + h});
        rects.put("up", new float[]{u + d + w, v + d, u + d, v});
        rects.put("down", new float[]{u + d + w + w, v, u + d + w, v + d});

        if (cube.mirror()) {
            float[] east = rects.get("east");
            rects.put("east", rects.get("west"));
            rects.put("west", east);
            for (float[] rect : rects.values()) {
                float t = rect[0];
                rect[0] = rect[2];
                rect[2] = t;
            }
        }

        JsonObject faces = new JsonObject();
        for (String face : FACES) {
            faces.add(face, face(rects.get(face)));
        }
        return faces;
    }

    /**
     * 逐面 UV：Bedrock 的 {uv, uv_size} → [u1, v1, u2, v2]。
     * BetterModel 要求六个面齐全（缺面会让整个加载流程中止），
     * 所以缺失或零尺寸的面也要输出，只是不挂贴图，不会被渲染。
     */
    private static JsonObject perFaceUv(BedrockGeometry.Cube cube) {
        JsonObject faces = new JsonObject();
        for (String face : FACES) {
            float[] uv = cube.faceUv().get(face);
            if (uv == null || (uv[2] == 0 && uv[3] == 0)) {
                faces.add(face, emptyFace());
                continue;
            }
            // uv_size 也可能为负（镜像面），归一化成左上/右下两个角
            faces.add(face, face(new float[]{
                    Math.min(uv[0], uv[0] + uv[2]), Math.min(uv[1], uv[1] + uv[3]),
                    Math.max(uv[0], uv[0] + uv[2]), Math.max(uv[1], uv[1] + uv[3])}));
        }
        return faces;
    }

    private static JsonObject emptyFace() {
        JsonObject face = new JsonObject();
        JsonArray uv = new JsonArray();
        for (int i = 0; i < 4; i++) {
            uv.add(0);
        }
        // 不写 texture 字段：BetterModel 自带的 steve.bbmodel 里无贴图面就是这种写法
        face.add("uv", uv);
        return face;
    }

    private static JsonObject face(float[] rect) {
        JsonObject face = new JsonObject();
        JsonArray uv = new JsonArray();
        for (float f : rect) {
            uv.add(f);
        }
        face.add("uv", uv);
        face.addProperty("texture", 0);
        return face;
    }

    // ---------------------------------------------------------------- 贴图

    private JsonObject texture() {
        JsonObject texture = new JsonObject();
        texture.addProperty("uuid", uuid("texture"));
        texture.addProperty("name", modelName + ".png");
        texture.addProperty("id", "0");
        texture.addProperty("width", geometry.textureWidth());
        texture.addProperty("height", geometry.textureHeight());
        texture.addProperty("uv_width", geometry.textureWidth());
        texture.addProperty("uv_height", geometry.textureHeight());
        texture.addProperty("render_mode", "default");
        texture.addProperty("visible", true);
        texture.addProperty("internal", true);
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(texturePng));
        return texture;
    }

    // ---------------------------------------------------------------- 动画

    private JsonObject animation(BedrockAnimation animation) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid("animation:" + animation.name()));
        json.addProperty("name", animation.name());
        json.addProperty("loop", switch (animation.loop()) {
            case LOOP -> "loop";
            case HOLD -> "hold";
            case ONCE -> "once";
        });
        json.addProperty("override", false);
        json.addProperty("length", animation.length());

        JsonObject animators = new JsonObject();
        for (Map.Entry<String, Map<String, List<BedrockAnimation.Keyframe>>> bone : animation.bones().entrySet()) {
            String groupUuid = boneUuids.get(bone.getKey());
            if (groupUuid == null) {
                // 动画引用了几何体里不存在的骨骼；Bedrock 端也是静默忽略
                continue;
            }
            JsonObject animator = new JsonObject();
            animator.addProperty("name", bone.getKey());
            animator.addProperty("type", "bone");
            JsonArray keyframes = new JsonArray();
            for (Map.Entry<String, List<BedrockAnimation.Keyframe>> channel : bone.getValue().entrySet()) {
                int index = 0;
                for (BedrockAnimation.Keyframe keyframe : channel.getValue()) {
                    keyframes.add(keyframe(animation.name(), bone.getKey(), channel.getKey(), index++, keyframe));
                }
            }
            animator.add("keyframes", keyframes);
            animators.add(groupUuid, animator);
        }
        json.add("animators", animators);
        return json;
    }

    private JsonObject keyframe(String animation, String bone, String channel, int index, BedrockAnimation.Keyframe keyframe) {
        // bbmodel 的关键帧沿用 Bedrock 约定存储（Blockbench 导出 animation.json 时原样写出），
        // 只有骨骼/立方体的静态变换才需要镜像换算，所以这里不做取反。
        // 若实机验证发现方向相反，打开 MIRROR_KEYFRAMES 即可，不必改其他地方。
        String[] value = keyframe.value();
        String x = value[0];
        String y = value[1];
        String z = value[2];
        if (MIRROR_KEYFRAMES) {
            switch (channel) {
                case "rotation" -> {
                    x = negate(x);
                    y = negate(y);
                }
                case "position" -> x = negate(x);
                default -> {
                    // 缩放不受镜像影响
                }
            }
        }

        JsonObject point = new JsonObject();
        point.addProperty("x", molang.normalize(x));
        point.addProperty("y", molang.normalize(y));
        point.addProperty("z", molang.normalize(z));
        JsonArray points = new JsonArray();
        points.add(point);

        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid("kf:" + animation + ":" + bone + ":" + channel + ":" + index));
        json.addProperty("channel", channel);
        json.add("data_points", points);
        json.addProperty("time", keyframe.time());
        json.addProperty("interpolation", switch (keyframe.interpolation()) {
            case "catmullrom" -> "catmullrom";
            case "step" -> "step";
            default -> "linear";
        });
        return json;
    }

    /** 取反：数字直接取反，Molang 表达式加括号取反。 */
    static String negate(String value) {
        String trimmed = value.trim();
        try {
            float number = Float.parseFloat(trimmed);
            return number == 0 ? "0" : stripTrailingZeros(-number);
        } catch (NumberFormatException e) {
            return trimmed.isEmpty() ? "0" : "-(" + trimmed + ")";
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 由名字派生的确定性 uuid：同一输入多次转换得到完全相同的文件，便于增量判断。 */
    private String uuid(String key) {
        return UUID.nameUUIDFromBytes((modelName + "|" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JsonArray vec(float x, float y, float z) {
        JsonArray array = new JsonArray();
        array.add(x == 0 ? 0f : x);
        array.add(y == 0 ? 0f : y);
        array.add(z == 0 ? 0f : z);
        return array;
    }

    private static boolean isZero(float[] values) {
        for (float value : values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String sanitize(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static String stripTrailingZeros(float value) {
        String text = Float.toString(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
