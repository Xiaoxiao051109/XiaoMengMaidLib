package io.github.tlm.converter.convert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tlm.converter.bedrock.BedrockAnimation;
import io.github.tlm.converter.bedrock.BedrockGeometry;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 把一个 TLM 自定义模型包（目录结构 {@code assets/<ns>/maid_model.json} 等）批量转换为 BetterModel 可用的
 * {@code .bbmodel}，同时输出 {@code tlm_models.json} 清单，记录运行时需要但 bbmodel 承载不了的信息：
 * 显示名、缩放、是否 Gecko 模型，以及非 Gecko 模型使用的内置程序化动画列表（原模组的 {@code *.js}）。
 *
 * <p>只读写文件，不碰游戏状态，既可以在构建期运行（见 build.gradle.kts 的 convertModels），
 * 也可以在服务器上异步运行（用于导入外来模型包）。
 */
public final class ModelPackConverter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** 与原模组 MaidModelInfo#decorate 一致的默认程序化动画（未声明 animation 的普通模型使用）。 */
    private static final List<String> DEFAULT_MAID_ANIMATIONS = List.of(
            "touhou_little_maid:animation/maid/default/head/default.js",
            "touhou_little_maid:animation/maid/default/head/blink.js",
            "touhou_little_maid:animation/maid/default/head/beg.js",
            "touhou_little_maid:animation/maid/default/head/music_shake.js",
            "touhou_little_maid:animation/maid/default/leg/default.js",
            "touhou_little_maid:animation/maid/default/arm/default.js",
            "touhou_little_maid:animation/maid/default/arm/swing.js",
            "touhou_little_maid:animation/maid/default/arm/vertical.js",
            "touhou_little_maid:animation/maid/default/sit/default.js",
            "touhou_little_maid:animation/maid/default/armor/default.js",
            "touhou_little_maid:animation/maid/default/armor/reverse.js",
            "touhou_little_maid:animation/maid/default/wing/default.js",
            "touhou_little_maid:animation/maid/default/tail/default.js",
            "touhou_little_maid:animation/maid/default/sit/skirt_rotation.js",
            "touhou_little_maid:animation/base/float/default.js");

    public enum Kind {
        MAID("maid_model.json", "maid"),
        CHAIR("maid_chair.json", "chair");

        final String indexFile;
        final String prefix;

        Kind(String indexFile, String prefix) {
            this.indexFile = indexFile;
            this.prefix = prefix;
        }
    }

    public record Result(int converted, int failed, List<String> errors) {
    }

    private final Path packRoot;
    private final Path outputDir;
    private final Consumer<String> log;

    private final JsonArray manifest = new JsonArray();
    private final List<String> errors = new ArrayList<>();
    private int converted;
    private int replacedTotal;
    private int geckoUnparsed;

    /**
     * @param packRoot  模型包根目录（含 {@code assets/}）
     * @param outputDir bbmodel 输出目录
     */
    public ModelPackConverter(Path packRoot, Path outputDir, Consumer<String> log) {
        this.packRoot = packRoot;
        this.outputDir = outputDir;
        this.log = log;
    }

    public Result run() throws IOException {
        Files.createDirectories(outputDir);
        Path assets = packRoot.resolve("assets");
        if (!Files.isDirectory(assets)) {
            throw new IOException("不是 TLM 模型包（缺少 assets/）: " + packRoot);
        }
        try (Stream<Path> namespaces = Files.list(assets)) {
            for (Path namespaceDir : namespaces.sorted().toList()) {
                for (Kind kind : Kind.values()) {
                    Path index = namespaceDir.resolve(kind.indexFile);
                    if (Files.isRegularFile(index)) {
                        convertIndex(namespaceDir.getFileName().toString(), index, kind);
                    }
                }
            }
        }
        Files.writeString(outputDir.resolve("tlm_models.json"), PRETTY.toJson(manifest), StandardCharsets.UTF_8);
        log.accept("[转换] Gecko 动画表达式解析失败 " + geckoUnparsed + " 处（运行时按 0）");
        if (replacedTotal > 0) {
            log.accept("[转换] 共 " + replacedTotal + " 处不支持的 Molang 引用已折算为常量（明细见 tlm_models.json 的 unsupported_molang）");
        }
        return new Result(converted, errors.size(), List.copyOf(errors));
    }

    private void convertIndex(String namespace, Path index, Kind kind) throws IOException {
        JsonObject pack = readJson(index);
        JsonArray models = pack.getAsJsonArray("model_list");
        if (models == null) {
            return;
        }
        for (JsonElement element : models) {
            JsonObject info = element.getAsJsonObject();
            String modelId = info.has("model_id") ? info.get("model_id").getAsString() : null;
            if (modelId == null) {
                errors.add(index + ": 条目缺少 model_id");
                continue;
            }
            try {
                convertModel(kind, modelId, info);
                converted++;
            } catch (Exception e) {
                errors.add(modelId + ": " + e.getMessage());
            }
        }
        log.accept("[转换] " + namespace + "/" + kind.indexFile + " 完成");
    }

    private void convertModel(Kind kind, String modelId, JsonObject info) throws IOException {
        String[] id = splitId(modelId);
        String modelRes = info.has("model") ? info.get("model").getAsString()
                : id[0] + ":models/entity/" + id[1] + ".json";
        String textureRes = info.has("texture") ? info.get("texture").getAsString()
                : id[0] + ":textures/entity/" + id[1] + ".png";
        boolean gecko = info.has("is_gecko") && info.get("is_gecko").getAsBoolean();

        BedrockGeometry geometry = BedrockGeometry.parse(readJson(resolve(modelRes)));
        byte[] texture = Files.readAllBytes(resolve(textureRes));

        // .json 是关键帧动画，烘进 bbmodel；.js 是原模组的内置程序化动画，交给运行时
        List<BedrockAnimation> keyframed = new ArrayList<>();
        List<String> procedural = new ArrayList<>();
        // Gecko 模型的关键帧动画由插件运行时状态机播放并实时求值 Molang，原始 JSON 合并后整份带走，
        // 不再烘进 bbmodel（否则 ysm.* / variable.* 只能折算成常量）
        JsonObject geckoAnimations = new JsonObject();
        JsonArray animationList = info.getAsJsonArray("animation");
        if (animationList != null) {
            for (JsonElement animation : animationList) {
                String res = animation.getAsString();
                if (res.endsWith(".json") && gecko) {
                    JsonObject file = readJson(resolve(res)).getAsJsonObject("animations");
                    if (file != null) {
                        // 后列出的文件覆盖先列出的同名动画（GeckoLib 加载语义）
                        file.entrySet().forEach(e -> geckoAnimations.add(e.getKey(), e.getValue()));
                    }
                } else if (res.endsWith(".json")) {
                    keyframed.addAll(BedrockAnimation.parseFile(readJson(resolve(res))));
                } else if (!gecko) {
                    procedural.add(res);
                }
            }
        } else if (!gecko && kind == Kind.MAID) {
            procedural.addAll(DEFAULT_MAID_ANIMATIONS);
        }

        // 多个动画文件可能定义同名动画（如 winefox_blue 覆盖基础包的 sleep）。
        // 按 GeckoLib 的加载语义，后列出的文件覆盖先列出的；BetterModel 遇到重名会整体拒绝加载。
        java.util.LinkedHashMap<String, BedrockAnimation> byName = new java.util.LinkedHashMap<>();
        for (BedrockAnimation animation : keyframed) {
            byName.remove(animation.name());
            byName.put(animation.name(), animation);
        }
        keyframed = new ArrayList<>(byName.values());

        String key = modelKey(kind, modelId);
        BbModelWriter writer = new BbModelWriter(key, geometry, texture, keyframed, procedural);
        if (kind == Kind.MAID) {
            writer.withHandItems();
        }
        JsonObject bbmodel = writer.write();
        Files.writeString(outputDir.resolve(key + ".bbmodel"), GSON.toJson(bbmodel), StandardCharsets.UTF_8);
        if (gecko) {
            Path geckoDir = outputDir.resolve("gecko");
            Files.createDirectories(geckoDir);
            JsonObject root = new JsonObject();
            root.add("animations", geckoAnimations);
            Files.writeString(geckoDir.resolve(key + ".json"), GSON.toJson(root), StandardCharsets.UTF_8);
            int failed = GeckoExpressionCheck.check(geckoAnimations, errors, key);
            geckoUnparsed += failed;
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
        entry.addProperty("model_id", modelId);
        entry.addProperty("key", key);
        entry.addProperty("gecko", gecko);
        copy(info, entry, "name");
        entry.addProperty("display_name", displayName(id, info));
        copy(info, entry, "description");
        copy(info, entry, "render_entity_scale");
        copy(info, entry, "render_item_scale");
        copy(info, entry, "use_sound_pack_id");
        copy(info, entry, "show_backpack");
        copy(info, entry, "show_custom_head");
        copy(info, entry, "extra_textures");
        // 椅子专有字段
        copy(info, entry, "mounted_height");
        copy(info, entry, "tameable_can_ride");
        copy(info, entry, "no_gravity");
        // 源模型路径：部分条目（餐桌四季、狐妖雕像）用 model 字段复用别的模型文件，
        // 清单里带上它，插件侧才能定位到源文件算碰撞箱
        if (info.has("model")) {
            entry.addProperty("source_model", info.get("model").getAsString());
        }
        JsonArray proceduralJson = new JsonArray();
        procedural.forEach(proceduralJson::add);
        entry.add("procedural_animations", proceduralJson);
        JsonArray keyframedNames = new JsonArray();
        keyframed.forEach(a -> keyframedNames.add(a.name()));
        geckoAnimations.keySet().forEach(keyframedNames::add);
        entry.add("keyframe_animations", keyframedNames);
        entry.addProperty("baked_sleep", bakedSleep(geckoAnimations));
        JsonArray hiddenJson = new JsonArray();
        writer.hiddenBones().forEach(hiddenJson::add);
        entry.add("hidden_bones", hiddenJson);
        // hidden_bones 是「有效隐藏」（含祖先继承）；self_hidden_bones 只含自身被脚本隐藏的骨骼，
        // 运行时才能在祖先恢复显示时把只被继承隐藏的子部件重新显示。
        JsonArray selfHiddenJson = new JsonArray();
        writer.selfHiddenBones().forEach(selfHiddenJson::add);
        entry.add("self_hidden_bones", selfHiddenJson);
        // 同名骨骼去重改名表：运行时程序化动画按原名取骨骼，需要映射到实际 id。
        JsonObject aliasesJson = new JsonObject();
        writer.boneAliases().forEach(aliasesJson::addProperty);
        entry.add("bone_aliases", aliasesJson);
        // 关键帧里被折算成常量的 Molang 引用，运行时需要用程序化动画补回
        JsonArray replacedJson = new JsonArray();
        writer.replacedMolang().forEach(replacedJson::add);
        if (!replacedJson.isEmpty()) {
            entry.add("unsupported_molang", replacedJson);
            replacedTotal += replacedJson.size();
        }
        manifest.add(entry);
    }

    /** sleep 动画是否已经把躺姿烘进 Root/AllBody/Body 的 X 旋转（YSM 模型）。 */
    private static boolean bakedSleep(JsonObject animations) {
        if (animations == null || !animations.has("sleep")) {
            return false;
        }
        JsonObject sleep = animations.getAsJsonObject("sleep");
        JsonObject bones = sleep.getAsJsonObject("bones");
        if (bones == null) {
            return false;
        }
        for (String name : new String[]{"Root", "AllBody", "Body"}) {
            JsonObject bone = bones.getAsJsonObject(name);
            if (bone == null || !bone.has("rotation")) {
                continue;
            }
            com.google.gson.JsonElement rotation = bone.get("rotation");
            if (rotation.isJsonArray() && rotation.getAsJsonArray().size() > 0) {
                if (Math.abs(pitch(rotation.getAsJsonArray().get(0))) > 45.0) {
                    return true;
                }
            } else if (rotation.isJsonObject()) {
                for (var frame : rotation.getAsJsonObject().entrySet()) {
                    com.google.gson.JsonElement value = frame.getValue();
                    if (value.isJsonObject()) {
                        value = value.getAsJsonObject().get("post");
                    }
                    if (value != null && value.isJsonArray() && value.getAsJsonArray().size() > 0
                            && Math.abs(pitch(value.getAsJsonArray().get(0))) > 45.0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double pitch(com.google.gson.JsonElement element) {
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsDouble();
            }
            var matcher = java.util.regex.Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)")
                    .matcher(element.getAsString());
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        return 0.0;
    }

    /** BetterModel 模型键：只允许小写字母数字下划线，如 {@code tlm_maid_touhou_little_maid__cirno}。 */
    public static String modelKey(Kind kind, String modelId) {
        String[] id = splitId(modelId);
        String raw = "tlm_" + kind.prefix + "_" + id[0] + "__" + id[1];
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /** {@code ns:path/to/file} → 包内 {@code assets/ns/path/to/file}。 */
    private Path resolve(String resource) throws IOException {
        String[] id = splitId(resource);
        Path path = packRoot.resolve("assets").resolve(id[0]).resolve(id[1]);
        if (!Files.isRegularFile(path)) {
            throw new IOException("缺少文件 " + resource);
        }
        return path;
    }

    /** 各命名空间的 zh_cn 语言表（模型包自带的 lang/zh_cn.json）。 */
    private final java.util.Map<String, JsonObject> langCache = new java.util.HashMap<>();

    /**
     * 模型显示名，对照原模组 MaidModelInfo#decorate：未声明 name 时用 {@code {model.<ns>.<path>.name}}，
     * 花括号内是语言键，按模型包的 zh_cn 翻译；翻译不到时退回模型 id 的路径部分。
     */
    private String displayName(String[] id, JsonObject info) {
        String raw = info.has("name") ? info.get("name").getAsString()
                : "{model." + id[0] + "." + id[1] + ".name}";
        if (raw.startsWith("{") && raw.endsWith("}")) {
            String key = raw.substring(1, raw.length() - 1);
            JsonObject lang = langCache.computeIfAbsent(id[0], this::loadLang);
            JsonElement value = lang.get(key);
            return value != null && value.isJsonPrimitive() ? value.getAsString() : id[1];
        }
        return raw;
    }

    private JsonObject loadLang(String namespace) {
        Path file = packRoot.resolve("assets").resolve(namespace).resolve("lang").resolve("zh_cn.json");
        if (!Files.isRegularFile(file)) {
            return new JsonObject();
        }
        try {
            return readJson(file);
        } catch (IOException e) {
            return new JsonObject();
        }
    }

    private static String[] splitId(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? new String[]{"minecraft", id} : new String[]{id.substring(0, colon), id.substring(colon + 1)};
    }

    private static JsonObject readJson(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("JSON 解析失败 " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) {
            to.add(key, from.get(key));
        }
    }

    /** 构建期入口：{@code <packRoot> <outputDir>}。有失败条目时以退出码 1 结束，但仍会写出成功的部分。 */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("用法: ModelPackConverter <模型包目录> <输出目录>");
            System.exit(2);
        }
        Result result = new ModelPackConverter(Path.of(args[0]), Path.of(args[1]), System.out::println).run();
        System.out.println("[转换] 成功 " + result.converted() + "，失败 " + result.failed());
        result.errors().forEach(e -> System.out.println("  - " + e));
        if (result.failed() > 0) {
            System.exit(1);
        }
    }
}
