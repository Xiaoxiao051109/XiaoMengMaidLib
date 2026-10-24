package io.github.tlm.converter.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可放置物品 → CraftEngine 家具。
 *
 * <p>原模组这些方块全靠 BlockEntityRenderer 画 Bedrock 模型，服务端没有对应的方块模型可用，
 * 所以统一做成家具：
 * <ul>
 *   <li>Bedrock 模型：转成 bbmodel，用 CE 内置的 {@code craftengine:better_model} 元素显示；</li>
 *   <li>Java 方块模型（女仆信标、稻草人、模型切换器）：用 item_display 元素显示该方块模型。</li>
 * </ul>
 * 碰撞箱由几何体包围盒推导（interaction 实体只能是正方形底面，取 x/z 较大者）。
 *
 * <p>方块的功能（床可睡、神社放物品、棋盘对弈……）在后续阶段由插件挂到家具交互事件上。
 */
final class FurnitureTable {

    /** Bedrock 模型家具：物品 id → [模型路径, 贴图路径]（均相对 models/bedrock、textures/bedrock）。 */
    private static final Map<String, String[]> BEDROCK = new LinkedHashMap<>();
    /** Java 方块模型家具：物品 id → 方块模型名列表（多段方块按 y 递增堆叠，如稻草人上下两半）。 */
    private static final Map<String, List<String>> JAVA = new LinkedHashMap<>();
    /** 祭坛显示家具的 id，与插件 AltarManager 一致。 */
    private static final String ALTAR = "altar";
    /** 原模组里不是物品、因而不存在 items/<id>.json 的 id（避免无谓的缺模型警告）。 */
    static final java.util.Set<String> NO_ITEM_DEFINITION = java.util.Set.of(ALTAR);

    static {
        for (String color : new String[]{"pink", "white", "black", "yellow", "blue", "green", "purple"}) {
            BEDROCK.put(color + "_maid_bed", new String[]{"block/maid_bed/" + color, "block/maid_bed/" + color});
        }
        // 旧版通用 maid_bed（颜色由 NBT 记录）现在以粉色床家具作为默认外观。
        BEDROCK.put("maid_bed", new String[]{"block/maid_bed/pink", "block/maid_bed/pink"});
        for (String block : new String[]{"shrine", "snack_cabinet", "keyboard", "bookshelf", "computer",
                "gomoku", "cchess", "wchess"}) {
            BEDROCK.put(block, new String[]{"block/" + block, "block/" + block});
        }
        // 野餐篮放下后是野餐垫
        BEDROCK.put("picnic_basket", new String[]{"block/picnic_mat", "block/picnic_mat"});
        // 祭坛：插件激活多方块结构时以家具形式放在结构中央，只负责显示（无碰撞箱、不掉落）
        BEDROCK.put(ALTAR, new String[]{"block/altar", "block/altar"});
        // 手办的底座；底座上的女仆模型由插件按存储的模型 id 叠加
        BEDROCK.put("garage_kit", new String[]{"block/statue_base", "block/statue_base"});

        JAVA.put("maid_beacon", List.of("maid_beacon_down"));
        JAVA.put("scarecrow", List.of("scarecrow_lower", "scarecrow_upper"));
        JAVA.put("model_switcher", List.of("model_switcher"));
    }

    private FurnitureTable() {
    }

    static boolean isFurniture(String id) {
        return BEDROCK.containsKey(id) || JAVA.containsKey(id);
    }

    static void generate(ContentPackGenerator gen, JsonObject items) throws IOException {
        // 祭坛在原模组里不是物品，没有 items/altar.json；这里补一个空的物品模型（父模型是原模组的空方块模型），
        // 只为了让 CE 不报「缺少模型文件」，手上拿着它看不到东西
        JsonObject altarItemModel = new JsonObject();
        altarItemModel.addProperty("parent", ContentPackGenerator.NS + ":block/altar");
        gen.writeItemModel(ALTAR, altarItemModel);

        for (Map.Entry<String, String[]> entry : BEDROCK.entrySet()) {
            String id = entry.getKey();
            String key = "tlm_block_" + id;
            // 棋盘要缩放几何：better_model 元素没有缩放参数，而原模型按 1/16 渲染时过大
            BoardTable.Board board = BoardTable.board(id);
            // 模型保持原样转换，不做任何几何变换
            if (gen.bedrockModel(key, entry.getValue()[0], entry.getValue()[1]) == null) {
                continue;
            }
            JsonObject element = new JsonObject();
            element.addProperty("type", "craftengine:better_model");
            element.addProperty("model", key);
            element.addProperty("position", "0,0,0");
            JsonArray elements = new JsonArray();
            elements.add(element);
            float[] bounds = gen.bedrockBounds(entry.getValue()[0]);
            // 棋盘：3×3 碰撞还原原模组多方块占位，外加覆盖棋面的点击箱
            // 其余娱乐家具的碰撞箱用原模组手工 VoxelShape，不用模型包围盒
            JsonArray boxes = board != null ? BoardTable.hitboxes(board)
                    : JoyTable.isJoy(id) ? new JsonArray()
                    : id.equals(ALTAR) ? new JsonArray()
                    : id.endsWith("_maid_bed") || "maid_bed".equals(id) ? bedHitboxes(bounds) : hitboxes(gen, id, bounds);
            // 棋盘的座位仍由 JoyTable 提供（女仆要坐在棋盘旁），但碰撞/点击箱已自带，
            // 所以不再走 JoyTable 的碰撞箱生成
            items.add(ContentPackGenerator.id(id), furniture(gen, id, elements, boxes,
                    JoyTable.joy(id), board));
            if (!id.equals(ALTAR)) {
                gen.addCategoryItem(categoryOf(id), id);
            }
        }

        for (Map.Entry<String, List<String>> entry : JAVA.entrySet()) {
            String id = entry.getKey();
            JsonArray elements = new JsonArray();
            int level = 0;
            for (String model : entry.getValue()) {
                String displayItem = ContentPackGenerator.id(model + "_display");
                items.add(displayItem, displayItem(model));
                JsonObject element = new JsonObject();
                element.addProperty("item", displayItem);
                // item_display 以模型中心为原点，方块模型要上移半格才落在地面上
                element.addProperty("translation", "0," + (0.5 + level) + ",0");
                elements.add(element);
                level++;
            }
            // Java 方块模型家具都是整格方块：每格一只边长 1 的潜影贝
            JsonArray boxes = cubeGrid(gen, id, 0, 0, 0, 1, entry.getValue().size(), 1);
            items.add(ContentPackGenerator.id(id), furniture(gen, id, elements, boxes, null, null));
            gen.addCategoryItem("furniture", id);
        }
    }

    /** 单个家具最多用多少个碰撞单元；超过就放大单元格。 */
    private static final int MAX_CELLS = 12;

    /**
     * 由 Bedrock 包围盒（像素）推导实心碰撞箱。
     *
     * <p>interaction 实体只有点击判定没有碰撞，玩家能直接穿过。CE 的 shulker 碰撞箱是纯发包的假实体
     * （ClientboundAddEntityPacket，不进服务端世界、没有 AI、不会攻击），服务端碰撞由 CE 另建的
     * collider 负责；玩家攻击它等同于敲击家具。缺点是只能是立方体（scale 缩放边长），
     * 这里用一组等大的单元铺满包围盒：
     * 单元边长按高度取（高度方向整除），x/z 方向各自取最接近的格数，误差在一个单元以内。
     * 中心坐标换算见 {@link #worldX}。
     */
    private static JsonArray hitboxes(ContentPackGenerator gen, String id, float[] b) {
        float minY = Math.max(0, b[1]);
        float sizeX = (b[3] - b[0]) / 16f;
        float sizeY = Math.max(0.125f, (b[4] - minY) / 16f);
        float sizeZ = (b[5] - b[2]) / 16f;
        return cubeGrid(gen, id, worldX(b), minY / 16f, worldZ(b), sizeX, sizeY, sizeZ);
    }

    /**
     * 女仆床：按原模组 BlockMaidBed 的形状（两格长、一格宽、9 像素高，BASE = box(0,0,0,16,9,16)），
     * 不按模型包围盒推导——部分床模型带装饰（蓝色床的蝴蝶结等）会把包围盒撑到 3 格以上。
     * 用边长 0.5 的单元铺 2x4x1，底面正好 1x2，高度 0.5 接近原版 9 像素。
     * 位置以模型包围盒中心为准，与模型对齐。
     */
    private static JsonArray bedHitboxes(float[] b) {
        return emitGrid(worldX(b), 0, worldZ(b), 0.5f, 2, 1, 4);
    }

    /**
     * Bedrock 包围盒中心 → 家具坐标（格）。
     *
     * <p>bbmodel 相对 Bedrock 做了 x 镜像，BetterModel 渲染时再绕 Y 轴转 180°（x、z 同时取反），
     * 合起来是：x 保持 Bedrock 原值，z 取反。实机核对：女仆床原先按 z 不取反放置时，
     * 碰撞箱整体落在床尾方向一格外。
     */
    private static float worldX(float[] b) {
        return (b[0] + b[3]) / 2f / 16f;
    }

    private static float worldZ(float[] b) {
        return -(b[2] + b[5]) / 2f / 16f;
    }

    static JsonArray cubeGrid(ContentPackGenerator gen, String id, float centerX, float baseY, float centerZ,
                              float sizeX, float sizeY, float sizeZ) {
        int ny = Math.max(1, (int) Math.ceil(sizeY - 1e-3));
        float cell = sizeY / ny;
        // 单元过小会生成一大堆碎块（坐垫 0.19 格高会被切成 9 块），统一抬到半格
        if (cell < MIN_CELL) {
            cell = Math.min(MIN_CELL, Math.max(sizeX, sizeZ));
            ny = Math.max(1, (int) Math.round(sizeY / cell));
        }
        int nx = Math.max(1, Math.round(sizeX / cell));
        int nz = Math.max(1, Math.round(sizeZ / cell));
        // 大件（书架、电脑）格数过多时整体放大单元，保证实体数量可控
        while (nx * ny * nz > MAX_CELLS) {
            ny = Math.max(1, ny - 1);
            cell = sizeY / ny;
            nx = Math.max(1, Math.round(sizeX / cell));
            nz = Math.max(1, Math.round(sizeZ / cell));
            if (ny == 1 && nx * nz > MAX_CELLS) {
                cell *= (float) Math.sqrt(nx * nz / (double) MAX_CELLS);
                nx = Math.max(1, Math.round(sizeX / cell));
                nz = Math.max(1, Math.round(sizeZ / cell));
                gen.warn(id + ": 碰撞箱较大，使用 " + nx + "x" + nz + " 个边长 " + round(cell) + " 的单元近似");
                break;
            }
        }

        return emitGrid(centerX, baseY, centerZ, cell, nx, ny, nz);
    }

    /**
     * 按模型包围盒（像素）给家具生成实心碰撞箱。
     *
     * <p>坐垫、凳子这类小件给一个整格的小方盒（直接坐上去，不需要绕行）；
     * 麻将桌、八仙桌、被炉这类大件按包围盒铺碰撞单元，但高度封顶，
     * 否则模型的装饰件会把包盒撑得很高、变成一堵看不见的墙。
     */
    static JsonArray modelCollision(ContentPackGenerator gen, String id, float[] b) {
        float sizeX = Math.max(0.125f, (b[3] - b[0]) / 16f);
        float sizeZ = Math.max(0.125f, (b[5] - b[2]) / 16f);
        float minY = Math.max(0, b[1]);
        float sizeY = Math.max(0.125f, (b[4] - minY) / 16f);
        // 薄坐垫按包围盒会得到一个 0.1 格高的碰撞箱，玩家会陷进去；给个最小高度
        if (sizeX <= 0.75f && sizeZ <= 0.75f && sizeY <= 0.6f) {
            return emitGrid(worldX(b), 0, worldZ(b), 0.5f, 1, 1, 1);
        }
        // 多格大结构（戏台、鱼塘、矿洞、小屋……）按包围盒会铺出横跨十几格的隐形墙，
        // 这里给水平与竖直尺寸都加上限：只保证「站在里面不会穿模」，不整片挡住行走。
        float width = Math.min(sizeX, COLLISION_MAX_WIDTH);
        float depth = Math.min(sizeZ, COLLISION_MAX_WIDTH);
        float height = Math.min(sizeY, COLLISION_MAX_HEIGHT);
        // 大结构的包围盒中心可能落在相邻好几格上，碰撞箱必须留在家具所在的这一格内，
        // 否则会出现「家具在这边、脚感在那边」
        float centerX = clamp(worldX(b), -0.5f, 0.5f);
        float centerZ = clamp(worldZ(b), -0.5f, 0.5f);
        return cubeGrid(gen, id, centerX, minY / 16f, centerZ, width, height, depth);
    }

    /** 碰撞单元的最小边长（格），避免小家具被切成碎块。 */
    private static final float MIN_CELL = 0.5f;

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 家具碰撞箱的水平尺寸上限（格）。 */
    private static final float COLLISION_MAX_WIDTH = 1.5f;

    /** 家具碰撞箱的高度上限（格）。 */
    private static final float COLLISION_MAX_HEIGHT = 1.0f;

    /** 以 (centerX, baseY, centerZ) 为底面中心，铺 nx × ny × nz 个边长 cell 的潜影贝单元。 */
    static JsonArray emitGrid(float centerX, float baseY, float centerZ, float cell, int nx, int ny, int nz) {
        JsonArray out = new JsonArray();
        for (int ix = 0; ix < nx; ix++) {
            for (int iz = 0; iz < nz; iz++) {
                for (int iy = 0; iy < ny; iy++) {
                    float x = centerX + (ix - (nx - 1) / 2f) * cell;
                    float z = centerZ + (iz - (nz - 1) / 2f) * cell;
                    float y = baseY + iy * cell;
                    JsonObject box = new JsonObject();
                    box.addProperty("type", "shulker");
                    // 潜影贝以脚底中心定位
                    box.addProperty("position", round(x) + "," + round(y) + "," + round(z));
                    box.addProperty("scale", round(cell));
                    box.addProperty("blocks_building", true);
                    box.addProperty("interactive", true);
                    box.addProperty("invisible", true);
                    // 箭矢等投射物直接穿过，不会打到碰撞箱
                    box.addProperty("can_be_hit_by_projectile", false);
                    out.add(box);
                }
            }
        }
        return out;
    }

    private static JsonObject furniture(ContentPackGenerator gen, String id, JsonArray elements, JsonArray hitboxes)
            throws IOException {
        return furniture(gen, id, elements, hitboxes, null, null);
    }

    private static JsonObject furniture(ContentPackGenerator gen, String id, JsonArray elements, JsonArray hitboxes,
                                        JoyTable.Joy joy, BoardTable.Board board) throws IOException {
        JsonObject item = ItemTable.item(gen, id);

        JsonObject ground = new JsonObject();
        ground.add("elements", elements);
        ground.add("hitboxes", hitboxes);
        if (joy != null && board == null) {
            // 娱乐家具（五子棋、书架、电脑……）：再加一个带座位的交互碰撞箱，玩家右键坐上去
            JoyTable.addSeat(ground, joy);
        }
        JsonObject variants = new JsonObject();
        variants.add("ground", ground);

        JsonObject settings = new JsonObject();
        settings.addProperty("item", ContentPackGenerator.id(id));

        JsonObject lootArgs = new JsonObject();
        lootArgs.addProperty("item", ContentPackGenerator.id(id));
        JsonObject loot = new JsonObject();
        loot.addProperty("template", "default:loot_table/furniture");
        loot.add("arguments", lootArgs);

        JsonObject furniture = new JsonObject();
        furniture.add("settings", settings);
        furniture.add("variants", variants);
        if (!id.equals(ALTAR)) {
            furniture.add("loot", loot);
        }
        JsonObject seatBehavior = joy == null ? null : FurnitureBehaviorConfig.seat(
                joy.joyType(), null, joy.sit()[1], joy.yRot(), joy.seatOut(), false);
        JsonObject boardBehavior = board == null ? null : FurnitureBehaviorConfig.board(board);
        if (seatBehavior != null || boardBehavior != null) {
            furniture.add("behaviors", FurnitureBehaviorConfig.list(seatBehavior, boardBehavior));
        }

        // 原模组方块按玩家朝向四向放置
        JsonObject groundRule = new JsonObject();
        groundRule.addProperty("rotation", "four");
        groundRule.addProperty("alignment", "center");
        JsonObject rules = new JsonObject();
        rules.add("ground", groundRule);

        JsonObject behavior = new JsonObject();
        behavior.addProperty("type", "furniture_item");
        behavior.add("rules", rules);
        behavior.add("furniture", furniture);
        item.add("behavior", behavior);
        return item;
    }

    /** 只用于家具显示的隐藏物品：模型就是原模组的 Java 方块模型。 */
    private static JsonObject displayItem(String blockModel) {
        JsonObject data = new JsonObject();
        data.addProperty("item_name", "<!i>" + blockModel);
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("path", ContentPackGenerator.id("block/" + blockModel));
        JsonObject item = new JsonObject();
        item.add("data", data);
        item.add("model", model);
        return item;
    }

    private static float round(float value) {
        return Math.round(value * 1000f) / 1000f;
    }

    /** 家具分类：床归女仆，棋盘/娱乐家具归娱乐，其余归家具。 */
    private static String categoryOf(String id) {
        if ("maid_bed".equals(id) || id.endsWith("_maid_bed")) {
            return "maid";
        }
        if (JOY_CATEGORY.contains(id)) {
            return "game";
        }
        return "furniture";
    }

    private static final Set<String> JOY_CATEGORY = Set.of(
            "gomoku", "cchess", "wchess", "bookshelf", "computer", "keyboard", "picnic_basket", "picnic_mat");
}
