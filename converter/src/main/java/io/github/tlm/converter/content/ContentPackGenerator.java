package io.github.tlm.converter.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.tlm.converter.bedrock.BedrockGeometry;
import io.github.tlm.converter.convert.BbModelWriter;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 从原模组资源生成 CraftEngine 内容包（阶段 2：物品、可放置方块、语言、配方）。
 *
 * <p>输出两部分：
 * <ul>
 *   <li>{@code <out>/content/}：CE 内容包，插件释放到 {@code CraftEngine/resources/touhou_little_maid/}；</li>
 *   <li>{@code <out>/models/}：方块/实体的 Bedrock 模型转出的 .bbmodel，释放到 BetterModel。</li>
 * </ul>
 *
 * <p>配置文件以 JSON 语法写入 .yml：CE 使用 YAML 1.2 解析器，JSON 是其合法子集，
 * 省去自己实现 YAML 序列化。
 *
 * <p>本阶段只解决「能拿到、能看到、能放下、能合成」；物品与方块的交互逻辑在后续阶段由插件实现。
 */
public final class ContentPackGenerator {

    /** tlm_models.json（由 ModelPackConverter 产出）；椅子家具需要读里面的坐高。 */
    static Path MANIFEST;

    /**
     * 当前正在生成的内容包的 assets 根目录（原模组的 {@code assets/touhou_little_maid}）。
     *
     * <p>棋子的立体烘焙要回头读原模型（{@code models/bedrock/block/cchess_pieces.json}），
     * 而那条路径只有这里知道，所以留一个静态出口。
     */
    private static Path CURRENT_ASSETS;

    static Path assetsRoot() {
        return CURRENT_ASSETS;
    }

    static final String NS = "touhou_little_maid";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    /** 原模组 26.x 资源里缺失的部分物品翻译：转换器补到资源包 en_us / zh_cn。 */
    /** 1.20 遗留、26.1 已移除的物品：连定义一起移除，避免 /give 和 CE 菜单混入旧物。 */
    private static final java.util.Set<String> REMOVED_IDS = java.util.Set.of(
            "crafting_table_backpack", "ender_chest_backpack", "furnace_backpack", "tank_backpack",
            "maid_bed", "monster_list");

    private static final Map<String, String[]> FALLBACK_NAMES = Map.of(
            "ender_chest_backpack", new String[]{"Ender Chest Backpack", "末影箱背包"},
            "crafting_table_backpack", new String[]{"Crafting Table Backpack", "工作台背包"},
            "tank_backpack", new String[]{"Tank Backpack", "储罐背包"},
            "furnace_backpack", new String[]{"Furnace Backpack", "熔炉背包"},
            "maid_bed", new String[]{"Maid Bed", "女仆床"});
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private final Path assets;
    private final Path data;
    private final Path content;
    private final Path resourcepack;
    private final Path modelsOut;
    /** 默认模型包根目录（touhou_little_maid-1.0.0），用于把内置女仆音效并入资源包；可为 null。 */
    private final Path soundPack;
    private final JsonObject lang;
    private final List<String> warnings = new ArrayList<>();
    /**
     * CE 物品定义还包含家具内部的 display、棋子和椅子模型变体；这些定义必须保留供家具渲染，
     * 但不能自动出现在玩家可获取的物品菜单里。分类只收集各生成表明确标记的公开物品。
     */
    private final Map<String, Set<String>> categoryItems = new LinkedHashMap<>();

    /**
     * @param assets 原模组 {@code assets/touhou_little_maid}
     * @param data   原模组 datagen 输出 {@code data/touhou_little_maid}
     * @param out    输出根目录
     */
    public ContentPackGenerator(Path assets, Path data, Path out) throws IOException {
        this(assets, data, out, null);
    }

    public ContentPackGenerator(Path assets, Path data, Path out, Path soundPack) throws IOException {
        this.assets = assets;
        this.data = data;
        this.soundPack = soundPack;
        CURRENT_ASSETS = assets;
        this.content = out.resolve("content");
        this.resourcepack = content.resolve("resourcepack").resolve("assets").resolve(NS);
        this.modelsOut = out.resolve("models");
        this.lang = readJson(assets.resolve("lang").resolve("en_us.json"));
    }

    public List<String> warnings() {
        return warnings;
    }

    public void run() throws IOException {
        Files.createDirectories(content.resolve("configuration"));
        Files.createDirectories(modelsOut);
        writePackMeta();
        copyResources();

        JsonObject items = new JsonObject();
        ItemTable.generateItems(this, items);
        FurnitureTable.generate(this, items);
        ChairTable.generate(this, items, ChairTable.read());
        BoardTable.generate(this, items);
        JoyTable.writeManifest(this);
        for (String removed : REMOVED_IDS) {
            items.remove(id(removed));
        }
        applyCategoryTags(items);

        JsonObject itemsFile = new JsonObject();
        itemsFile.add("items", items);
        writeConfig("items.yml", itemsFile);

        JsonObject recipesFile = new JsonObject();
        recipesFile.add("recipes", RecipeTable.generate(this));
        writeConfig("recipes.yml", recipesFile);

        writeCategory();
    }


    /**
     * 把默认模型包里的女仆音效并入资源包，并把 sounds.json 里原本指向 maid/empty 的事件
     * 换成实际 ogg 变体。原模组靠客户端音效包替换，服务端重写没有客户端，只能静态打包。
     */
    private void applySoundPack() throws IOException {
        Path source = soundPack.resolve("assets").resolve(NS).resolve("sounds").resolve("maid");
        if (!Files.isDirectory(source)) {
            warnings.add("缺少默认模型包女仆音效：" + source);
            return;
        }
        Path target = resourcepack.resolve("sounds").resolve("maid");
        copyDirectory(source, target);
        Path jsonPath = resourcepack.resolve("sounds.json");
        if (!Files.isRegularFile(jsonPath)) {
            return;
        }
        JsonObject root = readJson(jsonPath);
        Map<String, List<String>> variants = new LinkedHashMap<>();
        List<String> idle = soundsUnder(source.resolve("mode"), "idle");
        List<String> attack = soundsUnder(source.resolve("mode"), "attack");
        List<String> feed = soundsUnder(source.resolve("mode"), "feed");
        variants.put("maid.mode.idle", idle);
        variants.put("maid.mode.attack", attack);
        variants.put("maid.mode.range_attack", attack);
        variants.put("maid.mode.danmaku_attack", attack);
        variants.put("maid.mode.feed", feed);
        variants.put("maid.mode.feed_animal", feed);
        for (String reuse : List.of("farm", "shears", "milk", "torch", "extinguishing", "snow", "break", "furnace", "brewing")) {
            variants.put("maid.mode." + reuse, idle);
        }
        variants.put("maid.ai.find_target", soundsUnder(source.resolve("ai"), "find_target"));
        List<String> hurt = soundsUnder(source.resolve("ai"), "hurt");
        variants.put("maid.ai.hurt", hurt);
        variants.put("maid.ai.hurt_fire", hurt);
        variants.put("maid.ai.hurt_player", soundsUnder(source.resolve("ai"), "hurt_player"));
        variants.put("maid.ai.tamed", soundsUnder(source.resolve("ai"), "tamed"));
        variants.put("maid.ai.item_get", soundsUnder(source.resolve("ai"), "item_get"));
        variants.put("maid.ai.death", soundsUnder(source.resolve("ai"), "death"));
        variants.put("maid.environment.cold", soundsUnder(source.resolve("environment"), "cold"));
        variants.put("maid.environment.hot", soundsUnder(source.resolve("environment"), "hot"));
        variants.put("maid.environment.rain", soundsUnder(source.resolve("environment"), "rain"));
        variants.put("maid.environment.snow", soundsUnder(source.resolve("environment"), "snow"));
        variants.put("maid.environment.morning", soundsUnder(source.resolve("environment"), "morning"));
        variants.put("maid.environment.night", soundsUnder(source.resolve("environment"), "night"));
        variants.put("maid.credit", soundsUnder(source.resolve("other"), "credit"));
        variants.forEach((event, files) -> {
            if (files.isEmpty()) {
                return;
            }
            JsonObject entry = root.getAsJsonObject(event);
            if (entry == null) {
                entry = new JsonObject();
                entry.addProperty("category", "neutral");
                root.add(event, entry);
            }
            JsonArray sounds = new JsonArray();
            files.forEach(sounds::add);
            entry.add("sounds", sounds);
        });
        JsonObject sorted = new JsonObject();
        root.keySet().stream().sorted().forEach(key -> sorted.add(key, root.get(key)));
        Files.writeString(jsonPath, GSON.toJson(sorted), StandardCharsets.UTF_8);
    }

    private static List<String> soundsUnder(Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".ogg") && name.matches(prefix + "\\d*\\.ogg");
                    })
                    .sorted()
                    .map(path -> NS + ":maid/" + dir.getFileName() + "/" + path.getFileName().toString().replace(".ogg", ""))
                    .toList();
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ---------------------------------------------------------------- 资源复制

    private void writePackMeta() throws IOException {
        String yml = "author: TartaricAcid\nversion: " + packVersion + "\n"
                + "description: XiaoMengMaid content (generated by XiaoMengMaidLib converter)\n"
                + "namespace: " + NS + "\n";
        Files.writeString(content.resolve("pack.yml"), yml, StandardCharsets.UTF_8);
    }

    /** 物品/方块贴图、Java 模型、语言、声音原样复制进资源包；Bedrock 贴图已内嵌在 bbmodel 里，不复制。 */
    private void copyResources() throws IOException {
        // textures/bedrock/block 也要复制：部分手持模型（如 snack_cabinet_in_hand）直接引用 Bedrock 方块贴图
        for (String dir : new String[]{"textures/item", "textures/block", "textures/bedrock/block",
                "models/item", "models/block", "lang", "sounds"}) {
            copyDir(assets.resolve(dir), resourcepack.resolve(dir));
        }
        Path sounds = assets.resolve("sounds.json");
        if (Files.isRegularFile(sounds)) {
            Files.copy(sounds, resourcepack.resolve("sounds.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (soundPack != null) {
            applySoundPack();
        }
        applyFallbackLang();
    }

    private void applyFallbackLang() throws IOException {
        for (String code : List.of("en_us", "zh_cn")) {
            Path file = resourcepack.resolve("lang").resolve(code + ".json");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            JsonObject json = readJson(file);
            FALLBACK_NAMES.forEach((id, names) -> {
                String key = "item." + NS + "." + id;
                if (!json.has(key)) {
                    json.addProperty(key, "zh_cn".equals(code) ? names[1] : names[0]);
                }
            });
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        }
    }

    private static void copyDir(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 供各张表使用的工具

    Path assets() {
        return assets;
    }

    Path data() {
        return data;
    }

    /** 输出根目录（content/ 与 models/ 的上级），祭坛配方清单写在这里。 */
    Path outputRoot() {
        return content.getParent();
    }

    void warn(String message) {
        warnings.add(message);
    }

    /** 显示名：原模组的物品优先用 item.* 键，方块物品用 block.* 键，交给客户端资源包里的语言文件翻译。 */
    String itemName(String id) {
        String key = lang.has("item." + NS + "." + id) ? "item." + NS + "." + id
                : lang.has("block." + NS + "." + id) ? "block." + NS + "." + id
                : null;
        if (key == null && FALLBACK_NAMES.containsKey(id)) {
            key = "item." + NS + "." + id;
        }
        if (key == null) {
            // 内部/家具展示物品也可能没有翻译键：给一个可读名称，不把裸 id 直接暴露给玩家。
            StringBuilder pretty = new StringBuilder();
            for (String part : id.split("_")) {
                if (part.isEmpty()) continue;
                if (!pretty.isEmpty()) pretty.append(' ');
                pretty.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return "<!i>" + pretty;
        }
        return "<!i><lang:" + key + ">";
    }

    /** 读取原模组 items/<id>.json 的 model 字段并转换成 CE 的物品模型写法。 */
    JsonElement itemModel(String id) throws IOException {
        Path def = assets.resolve("items").resolve(id + ".json");
        if (!Files.isRegularFile(def)) {
            // 祭坛有专门写的空模型（手里看不到东西），不要用占位图标覆盖
            if ("altar".equals(id)) {
                return new com.google.gson.JsonPrimitive(NS + ":item/altar");
            }
            // 椅子/家具物品在原模组里是代码注册的，没有 items/<id>.json；
            // 生成一张统一的占位图标，避免 CE 报「缺少模型文件」
            String texture = hasTexture("item/change_chair_model")
                    ? "item/change_chair_model" : "item/entity_placeholder";
            if (FALLBACK_NAMES.containsKey(id) && !hasTexture("item/" + id)
                    && hasTexture("item/maid_backpack_small")) {
                texture = "item/maid_backpack_small";
            }
            return ItemModels.staticIcon(this, id, texture);
        }
        return ItemModels.convert(this, id, readJson(def).getAsJsonObject("model"));
    }

    /** 往资源包写一个 Java 物品模型（用于替换原模组 builtin/entity 一类客户端专属模型）。 */
    void writeItemModel(String name, JsonObject model) throws IOException {
        Path file = resourcepack.resolve("models").resolve("item").resolve(name + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(model), StandardCharsets.UTF_8);
    }

    boolean hasTexture(String path) {
        return Files.isRegularFile(assets.resolve("textures").resolve(path + ".png"));
    }

    /**
     * 把原模组 models/bedrock 下的方块/实体 Bedrock 模型转换为 bbmodel 写入 models/。
     *
     * @param modelPath 相对 models/bedrock 的路径（不含 .json），如 {@code block/shrine}
     * @param texture   相对 textures/bedrock 的路径（不含 .png）
     * @return BetterModel 模型键；源文件缺失时返回 null
     */
    String bedrockModel(String key, String modelPath, String texture) throws IOException {
        Path geo = assets.resolve("models").resolve("bedrock").resolve(modelPath + ".json");
        Path png = assets.resolve("textures").resolve("bedrock").resolve(texture + ".png");
        if (!Files.isRegularFile(geo) || !Files.isRegularFile(png)) {
            warn(key + ": 缺少 Bedrock 模型或贴图（" + modelPath + " / " + texture + "）");
            return null;
        }
        BedrockGeometry geometry = BedrockGeometry.parse(readJson(geo));
        JsonObject bbmodel = new BbModelWriter(key, geometry, Files.readAllBytes(png), List.of(), List.of()).write();
        Files.writeString(modelsOut.resolve(key + ".bbmodel"), COMPACT.toJson(bbmodel), StandardCharsets.UTF_8);
        return key;
    }

    /**
     * 按模型 id（如 {@code touhou_little_maid:mahjong_table}）取实体模型包围盒（像素，Bedrock 坐标）。
     *
     * <p>椅子 / 娱乐家具的模型是实体模型，位于 {@code assets/<ns>/models/entity/<path>.json}，
     * 与方块家具的 {@code models/bedrock/} 不同，所以单独走一条路径。
     *
     * @return 包围盒 {minX,minY,minZ,maxX,maxY,maxZ}；模型缺失时返回 null
     */
    float[] entityModelBounds(String modelId) throws IOException {
        int colon = modelId.indexOf(':');
        String ns = colon < 0 ? "minecraft" : modelId.substring(0, colon);
        String path = colon < 0 ? modelId : modelId.substring(colon + 1);
        // 清单里的 source_model 是完整资源路径（models/entity/x.json），归一化成模型名
        if (path.startsWith("models/entity/")) {
            path = path.substring("models/entity/".length());
        }
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - ".json".length());
        }
        for (Path root : modelPackRoots()) {
            Path geo = root.resolve(ns).resolve("models").resolve("entity").resolve(path + ".json");
            if (Files.isRegularFile(geo)) {
                return bounds(BedrockGeometry.parse(readJson(geo)));
            }
        }
        // 模型包内命名空间与模型 id 前缀不一定一致，最后全包兜底
        for (Path root : modelPackRoots()) {
            Path geo = findBySuffix(root, "models/entity/" + path + ".json");
            if (geo != null) {
                return bounds(BedrockGeometry.parse(readJson(geo)));
            }
        }
        return null;
    }

    /**
     * 实体模型所在的模型包根目录（含各命名空间子目录的那一层）。
     *
     * <p>传入的 assets 目录是原模组本体（{@code .../assets/touhou_little_maid}），
     * 而椅子 / 娱乐家具的模型在 {@code .../touhou_little_maid/tlm_custom_pack/<包名>/assets/<ns>/models/entity/}，
     * 层级固定但包名可变，所以按 {@code tlm_custom_pack} 定位。
     */
    private java.util.List<Path> modelPackRoots() {
        java.util.List<Path> roots = new ArrayList<>();
        Path cursor = assets;
        for (int depth = 0; depth < 6 && cursor != null; depth++) {
            Path packDir = cursor.resolve("tlm_custom_pack");
            if (Files.isDirectory(packDir)) {
                try (java.util.stream.Stream<Path> packs = Files.list(packDir)) {
                    packs.filter(Files::isDirectory)
                            .map(p -> p.resolve("assets"))
                            .filter(Files::isDirectory)
                            .forEach(roots::add);
                } catch (IOException ignored) {
                    // 目录不可读就跳过
                }
            }
            cursor = cursor.getParent();
        }
        return roots;
    }

    /** 在目录下按路径后缀查找第一个匹配文件（命名空间不一致时兜底）。 */
    private static Path findBySuffix(Path root, String suffix) throws IOException {
        String normalized = slash(suffix);
        try (java.util.stream.Stream<Path> walk = Files.walk(root, 6)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> slash(p.toString()).endsWith(normalized))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** 把平台路径分隔符统一成正斜杠，便于按后缀比较。 */
    private static String slash(String path) {
        return path.indexOf(java.io.File.separatorChar) >= 0
                ? path.replace(java.io.File.separatorChar, '/') : path;
    }

    /** 几何体的包围盒（像素，Bedrock 坐标），用于推导家具碰撞箱。 */
    float[] bedrockBounds(String modelPath) throws IOException {
        Path geo = assets.resolve("models").resolve("bedrock").resolve(modelPath + ".json");
        return bounds(BedrockGeometry.parse(readJson(geo)));
    }

    /** 几何体所有立方体的包围盒（像素），会滤掉建模辅助用的大立方体。 */
    static float[] bounds(BedrockGeometry geometry) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (BedrockGeometry.Bone bone : geometry.bones()) {
            for (BedrockGeometry.Cube cube : bone.cubes()) {
                // 超过 4 格的立方体是建模辅助（如 gomoku 里 151px 的外壳），不计入碰撞箱
                if (cube.size()[0] > 64 || cube.size()[1] > 64 || cube.size()[2] > 64) {
                    continue;
                }
                for (int i = 0; i < 3; i++) {
                    b[i] = Math.min(b[i], cube.origin()[i]);
                    b[i + 3] = Math.max(b[i + 3], cube.origin()[i] + cube.size()[i]);
                }
            }
        }
        return b;
    }

    // ---------------------------------------------------------------- 输出

    private void writeConfig(String name, JsonObject root) throws IOException {
        Path file = content.resolve("configuration").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /** 写到输出根目录，供插件随 jar 发布（与 altar_recipes.json 同级）。 */
    void writeRootFile(String name, JsonObject root) throws IOException {
        Files.createDirectories(outputRoot());
        Path file = outputRoot().resolve(name);
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /** 给分类物品补 settings.tags，根分类用 #tag 聚合，行为与 FD 的分类配置一致。 */
    private void applyCategoryTags(JsonObject items) {
        for (Map.Entry<String, Set<String>> entry : this.categoryItems.entrySet()) {
            String tag = NS + ":" + entry.getKey();
            for (String itemId : entry.getValue()) {
                JsonElement element = items.get(itemId);
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                JsonObject settings = item.has("settings") && item.get("settings").isJsonObject()
                        ? item.getAsJsonObject("settings") : new JsonObject();
                JsonArray tags = settings.has("tags") && settings.get("tags").isJsonArray()
                        ? settings.getAsJsonArray("tags") : new JsonArray();
                if (!contains(tags, tag)) {
                    tags.add(tag);
                }
                settings.add("tags", tags);
                item.add("settings", settings);
            }
        }
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement e : array) {
            if (e.isJsonPrimitive() && value.equals(e.getAsString())) return true;
        }
        return false;
    }

    /** 把一个确实面向玩家的物品加入 CE 菜单；内部 display/model 定义不要调用此方法。 */
    void addCategoryItem(String category, String path) {
        this.categoryItems.computeIfAbsent(category, key -> new java.util.LinkedHashSet<>()).add(id(path));
    }

    /** 创造模式式的物品分类：FD 风格根分类 + 隐藏子分类；内部渲染定义仍保留在 items.yml。 */
    private void writeCategory() throws IOException {
        JsonObject categories = new JsonObject();
        // 椅子是 26.1 的独立分类图标，但服务端也把它放进家具页，避免玩家找不到。
        addCategoryItem("furniture", "chair");

        JsonObject root = new JsonObject();
        root.addProperty("priority", 0);
        root.addProperty("name", "<!i><lang:item_group.touhou_little_maid.main>");
        root.addProperty("icon", id("hakurei_gohei"));
        JsonArray rootList = new JsonArray();
        String[] keys = {"maid", "bauble", "game", "furniture", "model", "admin", "misc"};
        for (String key : keys) {
            rootList.add("#" + NS + ":" + key);
        }
        root.add("list", rootList);
        categories.add(NS + ":categories", root);

        category(categories, "maid", "女仆", "maid_spawn_egg", 10);
        category(categories, "bauble", "饰品", "ultramarine_orb_elixir", 20);
        category(categories, "game", "娱乐与棋盘", "gomoku", 30);
        category(categories, "furniture", "家具与装饰", "chair", 40);
        category(categories, "model", "模型与展示", "model_switcher", 50);
        category(categories, "admin", "管理与工具", "favorability_tool_full", 60);
        category(categories, "misc", "其他", "film", 70);
        JsonObject out = new JsonObject();
        out.add("categories", categories);
        writeConfig("categories.yml", out);
    }

    private void category(JsonObject categories, String key, String display, String icon, int priority) {
        JsonArray list = new JsonArray();
        this.categoryItems.getOrDefault(key, Set.of()).forEach(list::add);
        JsonObject category = new JsonObject();
        category.addProperty("priority", priority);
        category.addProperty("hidden", true);
        category.addProperty("name", "<!i><#F7A8C4>" + display);
        category.addProperty("icon", id(icon));
        category.add("list", list);
        categories.add(NS + ":" + key, category);
    }

    static JsonObject readJson(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    static String id(String path) {
        return NS + ":" + path;
    }

    static Set<String> set(String... values) {
        return Set.of(values);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static <K, V> Map<K, V> ordered() {
        return new LinkedHashMap<>();
    }

    /** 内容包版本（写入 pack.yml，由构建传入；缺省 1.0）。 */
    private static String packVersion = "1.0";

    /** 本地入口：{@code <原模组 assets 目录> <原模组 data 目录> <输出目录>}。 */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法: ContentPackGenerator <assets> <data> <输出目录> [tlm_models.json] [version]");
            System.exit(2);
        }
        if (args.length >= 5) {
            packVersion = args[4];
        }
        if (args.length >= 4) {
            MANIFEST = Path.of(args[3]);
        }
        Path soundPack = args.length >= 6 ? Path.of(args[5]) : null;
        ContentPackGenerator generator = new ContentPackGenerator(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), soundPack);
        generator.run();
        System.out.println("[内容包] 生成完成，警告 " + generator.warnings().size() + " 条");
        generator.warnings().forEach(w -> System.out.println("  - " + w));
    }
}
