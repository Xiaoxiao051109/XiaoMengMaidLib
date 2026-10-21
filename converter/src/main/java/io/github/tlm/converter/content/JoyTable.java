package io.github.tlm.converter.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 娱乐方块家具的座位配置，对应原模组 {@code BlockJoy} 的 {@code sitPosition / sitYRot / getTypeName}。
 *
 * <p>这些方块都是「右键坐上去」的娱乐设施（五子棋、书架、电脑、键盘、野餐垫……），女仆空闲时会
 * 自己走过去坐（原模组靠 JOY_BLOCK POI + {@code MaidBoardGameTask}，这里由插件扫附近的家具）。
 *
 * <p>CraftEngine 的座位只服务玩家（按需生成盔甲架，只接受 {@code CraftEngine Player}），
 * 所以这里的座位是给玩家的；女仆的座位由插件另外生成（见 {@code MaidSeat}）。
 */
final class JoyTable {

    /**
     * 一件娱乐家具的座位配置。
     *
     * @param sit   坐面位置（相对家具原点的方块内偏移）；对棋盘而言 y 即坐面高度
     * @param yRot  相对家具朝向的额外偏转（度）
     * @param seatOut 坐面沿家具「朝向的反方向」向外推的距离（格）。
     *                棋盘要坐在棋盘外侧（原模组 {@code BlockGomoku#startMaidSit} 是沿面朝方向 1.5 格），
     *                否则女仆会试图坐到棋盘正中央、被棋盘自身的碰撞挡住
     */
    record Joy(String id, double[] sit, float yRot, String joyType, double[] shape, double seatOut) {
    }

    /** 家具 id → 座位。坐姿数据取自原模组各 BlockJoy 子类。 */
    private static final Map<String, Joy> JOYS = new LinkedHashMap<>();

    static {
        // 碰撞箱照搬原模组各 BlockJoy 子类的 VoxelShape（像素，1/16 格），不用模型包围盒——
        // 这些家具的模型远大于实际碰撞体积，按包围盒会挡路
        // BlockGomoku / BlockCChess / BlockWChess：整格
        // 三种棋盘：坐面沿朝向外推 2.2 格、坐高 0（坐在棋盘外侧的地面上，坐高给正数会悬空）。
        // 外推距离必须大于棋盘碰撞体的半径：9 个边长 1 的单元铺满 ±1.5 格，
        // 女仆自身身位约 0.6 格宽，留出余量才站得下、寻路不会被卡住。
        JOYS.put("gomoku", new Joy("gomoku", new double[]{0.5, 0.0, 0.5}, 0, "Gomoku",
                new double[]{0, 0, 0, 16, 9, 16}, 2.2));
        JOYS.put("cchess", new Joy("cchess", new double[]{0.5, 0.0, 0.5}, 0, "Gomoku",
                new double[]{0, 0, 0, 16, 9, 16}, 2.2));
        JOYS.put("wchess", new Joy("wchess", new double[]{0.5, 0.0, 0.5}, 0, "Gomoku",
                new double[]{0, 0, 0, 16, 9, 16}, 2.2));
        // BlockBookshelf：SHAPE = box(1, 0, 1, 15, 5, 15)
        JOYS.put("bookshelf", new Joy("bookshelf", new double[]{0.5, 0.375, 0.5}, -90, "Bookshelf",
                new double[]{1, 0, 1, 15, 5, 15}, 0));
        // BlockComputer：SHAPE = 桌面 (0,0,0,16,3,16) + 支架 + 屏幕 (1,9,1,15,12,15) + (0,12,0,16,14,16)
        JOYS.put("computer", new Joy("computer", new double[]{0.5, 1.0, 0.5}, 180, "Computer",
                new double[]{0, 0, 0, 16, 14, 16}, 0));
        // BlockKeyboard：SHAPE = box(4, 0, 4, 12, 10, 12)
        JOYS.put("keyboard", new Joy("keyboard", new double[]{0.5, 0.625, 0.5}, 0, "Keyboard",
                new double[]{4, 0, 4, 12, 10, 12}, 0));
    }

    private JoyTable() {
    }

    /** 写出娱乐家具清单（{@code joy_models.json}，插件运行时读取：女仆坐下与动画类型）。 */
    static void writeManifest(ContentPackGenerator gen) throws java.io.IOException {
        JsonArray array = new JsonArray();
        for (Joy joy : JOYS.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", ContentPackGenerator.id(joy.id()));
            entry.addProperty("joy_type", joy.joyType());
            entry.addProperty("height", joy.sit()[1]);
            entry.addProperty("y_rot", joy.yRot());
            entry.addProperty("seat_out", joy.seatOut());
            array.add(entry);
        }
        JsonObject root = new JsonObject();
        root.add("joys", array);
        gen.writeRootFile("joy_models.json", root);
    }

    static boolean isJoy(String id) {
        return JOYS.containsKey(id);
    }

    static Joy joy(String id) {
        return JOYS.get(id);
    }

    /**
     * 娱乐家具的碰撞箱与座位：碰撞箱用原模组 VoxelShape（取整格方位），座位是坐面处的交互箱。
     *
     * <p>原来按模型包围盒推导碰撞箱，娱乐家具的模型普遍很大（电脑模型跨 3 格），会挡住一大片路，
     * 所以这里照搬原模组手工定义的形状。
     */
    static void addSeat(JsonObject ground, Joy joy) {
        JsonArray hitboxes = ground.getAsJsonArray("hitboxes");
        // 原 VoxelShape 转成居中的潜影贝碰撞箱（CE 只支持立方体，按形状包围盒取一个单元）
        double[] shape = joy.shape();
        double minX = shape[0] / 16.0, minY = shape[1] / 16.0, minZ = shape[2] / 16.0;
        double maxX = shape[3] / 16.0, maxY = shape[4] / 16.0, maxZ = shape[5] / 16.0;
        double sizeX = maxX - minX, sizeY = Math.max(0.125, maxY - minY), sizeZ = maxZ - minZ;
        // 方块坐标以方块中心为原点（0.5 格偏移），CE 的碰撞箱位置以方块原点为基准
        double centerX = (minX + maxX) / 2 - 0.5;
        double centerZ = (minZ + maxZ) / 2 - 0.5;
        JsonObject solid = new JsonObject();
        solid.addProperty("type", "shulker");
        solid.addProperty("position", round(centerX) + "," + round(minY) + "," + round(centerZ));
        // CE 的潜影贝碰撞箱是立方体，边长取水平与竖直尺寸的较大者
        solid.addProperty("scale", round(Math.max(Math.max(sizeX, sizeZ), sizeY)));
        solid.addProperty("blocks_building", true);
        solid.addProperty("interactive", true);
        solid.addProperty("invisible", true);
        solid.addProperty("can_be_hit_by_projectile", false);
        hitboxes.add(solid);

        // 只留一个纯交互箱（负责接收右键），座位本身不交给 CraftEngine：
        // CE 的座位只服务玩家，女仆坐的是插件生成的座位实体，两套并存会互相看不见对方
        // 的占用状态。就座由插件统一处理（见 SeatManager），坐高按 joy_models.json 计算。
        JsonObject seat = new JsonObject();
        seat.addProperty("type", "interaction");
        // CE 的碰撞箱位置以方块原点为基准，水平居中就是 0,0；高度覆盖整个坐面区域
        seat.addProperty("position", "0," + round(joy.sit()[1]) + ",0");
        seat.addProperty("width", 0.8f);
        seat.addProperty("height", 1f);
        seat.addProperty("blocks_building", false);
        seat.addProperty("interactive", true);
        seat.addProperty("invisible", true);
        seat.addProperty("can_use_item_on", true);
        hitboxes.add(seat);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
