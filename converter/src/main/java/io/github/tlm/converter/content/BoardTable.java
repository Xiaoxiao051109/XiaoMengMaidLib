package io.github.tlm.converter.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 棋类棋盘与棋子 → CraftEngine 家具 / 物品，对应原模组 {@code BlockGomoku}、{@code BlockCChess}、
 * {@code BlockWChess} 与它们的 {@code BlockEntityRenderer}。
 *
 * <p>原模组的棋盘是 3×3 多方块，棋子由客户端渲染器在 15×15（或 9×10、8×8）网格上循环绘制。
 * 插件没有客户端渲染，所以：
 * <ul>
 *   <li><b>棋盘</b>做成一件 CE 家具：9 个潜影贝碰撞单元铺满 3×3 还原多方块占位，
 *       外加一个覆盖整个棋面的 {@code interaction} 碰撞箱负责接收点击；</li>
 *   <li><b>棋子</b>做成隐藏的 CE 物品，由插件用 {@code ItemDisplay} 摆在网格上。
 *       物品模型直接按原 Bedrock 立方体几何生成，沿用原贴图，所以外观与原模组一致。</li>
 * </ul>
 *
 * <p>棋盘模型要缩放：{@code better_model} 元素没有缩放参数（只有 position/yaw/pitch），
 * 而原模型按 1/16 渲染时五子棋板有 9.4 格宽，必须在几何体上缩小到 3×3 占位能容纳的尺寸。
 * 缩放系数由「原模组渲染器的落子间距」反推，这样网格与贴图上画的线自然对齐。
 */
final class BoardTable {

    /**
     * 一种棋盘。
     *
     * @param id          家具 / 物品 id
     * @param model       Bedrock 模型名（models/bedrock/block 下）
     * @param scale       几何缩放系数
     * @param columns     网格列数
     * @param rows        网格行数
     * @param spacing     网格间距（格，已含缩放）
     * @param clickWindow 命中窗口边长（格），原模组是间距的 53%
     * @param surfaceY    棋面（模型顶面）相对家具原点的高度（格）——棋子与点击面都落在这里
     * @param pieceSize   棋子显示边长（格），按格距的八成左右取值
     */
    record Board(String id, String model, float scale, int columns, int rows,
                 double spacing, double clickWindow, double surfaceY, double pieceSize,
                 double originX, double originZ) {

        /** 网格整体跨度（格）。 */
        double spanX() {
            return (columns - 1) * spacing;
        }

        double spanZ() {
            return (rows - 1) * spacing;
        }
    }

    /** 网格起点相对棋盘中心的偏移：让 (columns-1)/2 格的中点是棋盘中心。 */
    private static double centeredOrigin(int count, double spacing) {
        return -(count - 1) * spacing / 2;
    }

    /**
     * 一种棋子：原 Bedrock 模型里的一个立方体（棋子的视觉主体）。
     *
     * @param id      物品 id
     * @param texture 贴图（相对 textures/bedrock/block，不含 .png）
     * @param texSize 贴图边长（像素）
     * @param uv      Box UV 偏移
     * @param size    可见尺寸（Bedrock 单位，已算进 inflate）——棋子摆在世界里的实际大小
     * @param uvSize  原始立方体尺寸（未算 inflate）——Box UV 必须按它展开，
     *                因为贴图是按毛坯尺寸切的；用可见尺寸展开会取到错误的贴图区域
     * @param render  目标显示宽度（格）。16 单位 = 1 格，所以取 {@code size[0]/16} 就是原尺寸原样呈现
     * @param model   原模型路径（相对 models/bedrock）；只有走立体烘焙的棋子才需要
     * @param bone    原模型里的根骨骼名；非 null 时整棵子树都会被烘焙成 3D 棋子
     */
    record Piece(String id, String texture, int texSize, int[] uv, double[] size, double[] uvSize,
                 double render, String model, String bone) {

        /** 简化构造：单立方体棋子（五子棋、国际象棋）。 */
        Piece(String id, String texture, int texSize, int[] uv, double[] size, double[] uvSize,
              double render) {
            this(id, texture, texSize, uv, size, uvSize, render, null, null);
        }
    }

    /** 原模组渲染器的落子间距（格）：GomokuRenderer 0.1316、CChessRenderer 0.304、WChessRenderer 0.25。 */
    private static final Map<String, Board> BOARDS = new LinkedHashMap<>();

    /** 棋子物品。 */
    private static final Map<String, Piece> PIECES = new LinkedHashMap<>();

    /** 命中窗口占间距的比例：原模组 getData 里窗口 0.07 / 间距 0.1316。 */
    private static final double WINDOW_RATIO = 0.07 / 0.1316;

    static {
        // ------------------------------------------------------------------
        // 刻度：Bedrock 几何 16 单位 = 1 格（与 BetterModel 一致，实机核对过床的模型）。
        //
        // 关键陷阱：模型文件里的立方体要连同 inflate 一起看，光看 size 会大得离谱。
        //   五子棋板面立方体 size=151，但 inflate=-59.5，实际可见体只有 151-2*59.5 = 32 单位 = 2.0 格；
        //   中国象棋主立方体 size=64、inflate=-8，另外四个立方体 size=32、inflate=-14
        //   （-14 会让 32 单位缩到 4 单位、位置偏出去），合起来可见体 48 单位 = 3.0 格。
        // 所以下面一律按「可见体」给值，而不是按文件里的 size。
        //
        // 网格间距反过来由原模组的渲染器定：GomokuRenderer 每次 translate(0,0,0.1316)、
        // CChessRenderer 0.304、WChessRenderer 0.25。这些数字本身就是「格」，
        // 且 0.1316*16 = 2.1056 单位，与五子棋贴图 10 单位一格的网格线吻合
        // （板面 32 单位宽装下 15 列共 29.48 单位，两侧各留 1.26 单位边框），
        // 说明原模组就是按 16 单位/格渲染的，可以直接搬。
        // ------------------------------------------------------------------
        // 板面高度 = 模型可见体顶面 / 16。
        // 不能用原模组渲染器里的 translate(0.5, 1.5, 0.5) —— 那是原模组自己的渲染管线
        // （它把模型整体抬 1.5 格再做 ZN180 翻转）；BetterModel 是直接按模型原点渲染的，
        // 模型 y=0 就落在方块底面。旁证：同目录的女仆床可见体 0..12.70 单位、
        // 电脑 0..48 单位，都是贴地起算；原模组给棋盘定的碰撞形状
        // Block.box(0, 0, 0, 16, 2, 16) 也说明板厚只有 2 像素。
        BOARDS.put("gomoku", new Board("gomoku", "block/gomoku", 1f,
                15, 15, 0.1316, 0.07, 1.76 / 16, 0.11,
                centeredOrigin(15, 0.1316), centeredOrigin(15, 0.1316)));
        BOARDS.put("cchess", new Board("cchess", "block/cchess", 1f,
                9, 10, 0.304, 0.304 * WINDOW_RATIO, 2.05 / 16, 0.26,
                centeredOrigin(9, 0.304), centeredOrigin(10, 0.304)));
        BOARDS.put("wchess", new Board("wchess", "block/wchess", 1f,
                8, 8, 0.25, 0.25 * WINDOW_RATIO, 2.0 / 16, 0.19,
                centeredOrigin(8, 0.25), centeredOrigin(8, 0.25)));

        // 五子棋棋子：gomoku_piece.json 是 size 2x1x2、inflate -0.25 的单立方体，
        // 可见体 1.5x0.5x1.5 单位 —— 原尺寸就是 0.094 格宽、0.031 格厚，正好落在 0.1316 的格距里。
        // 这里按原尺寸 1:1 还原，不做额外缩放。
        PIECES.put("gomoku_piece_black", new Piece("gomoku_piece_black",
                "block/gomoku_black_piece", 16, new int[]{0, 0},
                new double[]{1.5, 0.5, 1.5}, new double[]{2, 1, 2},
                BOARDS.get("gomoku").pieceSize()));
        PIECES.put("gomoku_piece_white", new Piece("gomoku_piece_white",
                "block/gomoku_white_piece", 16, new int[]{0, 0},
                new double[]{1.5, 0.5, 1.5}, new double[]{2, 1, 2},
                BOARDS.get("gomoku").pieceSize()));

        // 中国象棋棋子：每种棋子的视觉主体是 size 64x0x64、inflate -30.75 的平面
        // （实际 2.5x0x2.5 单位），棋子面与汉字都画在 512x512 贴图上，各棋子只差 Box UV 偏移
        cchess("ShuaiRed");
        cchess("ShiRed");
        cchess("XiangRed");
        cchess("MaRed");
        cchess("JuRed");
        cchess("PaoRed");
        cchess("BingRed");
        cchess("JiangBlack");
        cchess("ShiBlack");
        cchess("XiangBlack");
        cchess("MaBlack");
        cchess("JuBlack");
        cchess("PaoBlack");
        cchess("ZuBlack");

        // 国际象棋棋子：wchess_pieces.json 里每种是 size 4x1x4、inflate -0.25 的盒
        // （实际 3.5x0.5x3.5 单位），32x32 贴图上各差 UV 偏移
        // UV 取自 wchess_pieces.json 里各骨骼立方体的 uv 字段（不是按 4x4 排布推算的：
        // 贴图是两列 x 六行，白子在第一列前三行 + 第二列首行、黑子在第二列后几行 + 第一列末三行）
        wchess("king_white", 0, 0);
        wchess("queen_white", 0, 5);
        wchess("rook_white", 0, 10);
        wchess("bishop_white", 0, 15);
        wchess("knight_white", 16, 0);
        wchess("pawn_white", 16, 5);
        wchess("king_black", 16, 10);
        wchess("queen_black", 16, 15);
        wchess("rook_black", 0, 20);
        wchess("bishop_black", 16, 20);
        wchess("knight_black", 0, 25);
        wchess("pawn_black", 16, 25);
        // 选中标记：原模组 SELECT 骨骼是一个 4x0x4 的平面
        PIECES.put("wchess_select", new Piece("wchess_select",
                "block/wchess_pieces", 32, new int[]{8, 0}, new double[]{4, 0.1, 4},
                new double[]{4, 0, 4}, 3.5 * 0.9 / 16.0));
    }

    /**
     * 中国象棋棋子。
     *
     * <p>它在原模型里不是一块立方体，而是一棵骨骼子树：一圈圈小立方体拼出棋子的圆柱轮廓，
     * 顶上再压一块写着汉字、朝向天空的平面。只导出那块平面就会变成「一张卡片」，
     * 所以这里整棵子树烘焙（见 {@link PieceGeometryBuilder}）。
     *
     * @param bone 原模型里的根骨骼名（如 {@code ShuaiRed}）
     */
    private static void cchess(String bone) {
        String id = "cchess_piece_" + snake(bone);
        // render 传 0：保持原模型尺寸（棋子直径 3.8 单位 ≈ 0.24 格，正好放在 0.304 的交叉点间距里）
        PIECES.put(id, new Piece(id, "block/cchess_pieces", 512,
                new int[]{0, 0}, new double[]{3.8, 1.3, 3.8}, new double[]{64, 0, 64},
                0, "block/cchess_pieces", bone));
    }

    /** {@code ShuaiRed} → {@code shuai_red}（沿用转换器里已有的物品命名）。 */
    private static String snake(String bone) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bone.length(); i++) {
            char c = bone.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void wchess(String name, int u, int v) {
        String id = "wchess_piece_" + name;
        // 可见尺寸 4 + 2*(-0.25) = 3.5 单位；原渲染器另外缩了 0.9
        PIECES.put(id, new Piece(id, "block/wchess_pieces", 32,
                new int[]{u, v}, new double[]{3.5, 0.5, 3.5}, new double[]{4, 1, 4},
                3.5 * 0.9 / 16.0));
    }

    private BoardTable() {
    }

    static boolean isBoard(String id) {
        return BOARDS.containsKey(id);
    }

    static Board board(String id) {
        return BOARDS.get(id);
    }

    /** 棋盘与棋子都不是原模组的物品（棋盘有 items/<id>.json，棋子纯属新增），棋子要跳过缺模型警告。 */
    static java.util.Set<String> pieceIds() {
        return PIECES.keySet();
    }

    /** 生成棋子物品（隐藏物品，只给 ItemDisplay 用）与棋盘清单。 */
    static void generate(ContentPackGenerator gen, JsonObject items) throws IOException {
        for (Piece piece : PIECES.values()) {
            gen.writeItemModel(piece.id(), pieceModel(piece));
            items.add(ContentPackGenerator.id(piece.id()), pieceItem(gen, piece));
        }
        writeManifest(gen);
    }

    /**
     * 棋盘家具：3×3 碰撞 + 覆盖棋面的点击箱。
     *
     * @param elements 已经建好的 better_model 元素（由 {@link FurnitureTable} 生成）
     */
    static JsonArray hitboxes(Board board) {
        JsonArray out = new JsonArray();
        // 板面厚度：模型可见体顶面就是板的厚度（三个棋盘都是 2 像素上下），
        // 碰撞箱必须跟着这个厚度走，否则一块 3x3x1 的实心立方体会把棋子顶到 1 格高，
        // 看起来就是「棋子浮在棋盘碰撞箱上」。
        double thickness = board.surfaceY();
        // 3×3 占位：9 块 1x1 的薄板，还原原模组多方块的碰撞与「破坏任一格整体消失」
        // （同一件家具的碰撞箱共享生命周期，打碎任意一个就是打碎这件家具）。
        // interaction 可以单独给 width/height，所以能做扁板；shulker 是立方体做不到。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                JsonObject box = new JsonObject();
                box.addProperty("type", "interaction");
                // AABB 语义：minX = x - width/2、maxX = x + width/2、minY = y、maxY = y + height。
                // 家具原点在方块中心，所以正中间的格子就是 (0,0)，斜对角是 (1,1)
                box.addProperty("position", dx + ",0," + dz);
                box.addProperty("width", 1.0);
                box.addProperty("height", round(thickness));
                box.addProperty("blocks_building", true);
                box.addProperty("interactive", true);
                box.addProperty("invisible", true);
                box.addProperty("can_be_hit_by_projectile", false);
                out.add(box);
            }
        }
        // 点击面：覆盖整个棋面的交互箱，右键它才能拿到精确命中点。
        // 落子的格子换算只用命中点的 X/Z（见 BoardModels#toCell），高度不影响判定。
        //
        // 高度要盖过棋子：棋子是摆在棋盘上的 display 实体，客户端射线会先撞到它们，
        // 点击面只到板面厚度的话，点在棋子上就什么也点不到（象棋棋子高 0.26 格，
        // 点击面原先只有 0.178，正好被压住 → 全盘无法走棋）。
        // 五子棋的棋子只有 0.03 格厚，原来的高度就够，且实机确认正常，所以只有象棋加高。
        double width = Math.max(board.spanX(), board.spanZ()) + board.spacing();
        JsonObject surface = new JsonObject();
        surface.addProperty("type", "interaction");
        surface.addProperty("position", "0,0,0");
        surface.addProperty("width", round(width));
        surface.addProperty("height", round(isChess(board.id()) ? clickHeight(board) : thickness + 0.05));
        surface.addProperty("blocks_building", false);
        surface.addProperty("interactive", true);
        surface.addProperty("invisible", true);
        surface.addProperty("can_use_item_on", true);
        out.add(surface);
        return out;
    }

    /** 棋子物品：不可堆叠、不进创造菜单，只作为 ItemDisplay 的内容。 */
    private static JsonObject pieceItem(ContentPackGenerator gen, Piece piece) {
        JsonObject data = new JsonObject();
        data.addProperty("item_name", "<!i>" + piece.id());

        JsonObject item = new JsonObject();
        item.add("data", data);
        item.addProperty("model", ContentPackGenerator.NS + ":item/" + piece.id());
        return item;
    }

    /**
     * 棋子的 Java 物品模型：把原 Bedrock 立方体按 Box UV 展开成一个元素。
     *
     * <p>ItemDisplay 以模型空间的 (8,8,8) 为中心渲染，且 0~16 映射成 1 格，
     * 所以这里把立方体按目标显示大小缩放后摆在模型中心。
     */
    private static JsonObject pieceModel(Piece piece) {
        // 有骨骼名的棋子走立体烘焙：整棵子树（棋子轮廓 + 文字面）都导出
        if (piece.bone() != null) {
            JsonObject baked = bakedPieceModel(piece);
            if (baked != null) {
                return baked;
            }
            // 烘焙失败（模型缺失/骨骼改名）时退回单立方体，至少不会没有棋子
        }
        // 平面棋子（原始立方体高度为 0，如中国象棋的贴图面）走另一条路
        if (Math.abs(piece.uvSize()[1]) < FLAT_THRESHOLD) {
            return flatPieceModel(piece);
        }
        double[] size = piece.size();
        // render 是棋子在世界里的目标宽（格）；ItemDisplay 的 NONE 变换下 0~16 模型单位 = 1 格。
        // 高度基准：display 实体以模型中心对齐，而棋子要「落在棋面上」，
        // 所以整块几何的底面压在 y=0，而不是以 8 为中心 —— 否则棋子会有一半陷进棋盘。
        double unit = piece.render() * 16 / size[0];
        double w = size[0] * unit / 2;
        // 平面棋子（中国象棋是 64x0x64 的贴图面）厚度为 0，给一点厚度避免零体积元素
        double h = Math.max(0.05, size[1]) * unit;
        double d = size[2] * unit / 2;

        JsonObject element = new JsonObject();
        element.add("from", vector(8 - w, 0, 8 - d));
        element.add("to", vector(8 + w, h, 8 + d));
        element.add("faces", boxUv(piece));

        JsonArray elements = new JsonArray();
        elements.add(element);

        // 贴图实际位于 assets/<ns>/textures/bedrock/block/，Java 模型引用要带 bedrock/ 前缀，
        // 否则 CE 会去 textures/block/ 找并报「缺少纹理」
        String texture = ContentPackGenerator.NS + ":bedrock/" + piece.texture();
        JsonObject textures = new JsonObject();
        textures.addProperty("0", texture);
        textures.addProperty("particle", texture);

        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        return model;
    }

    /** 平面棋子（贴图面）的厚度阈值：原始立方体的 y 尺寸小于它就算平面。 */
    private static final double FLAT_THRESHOLD = 0.5;

    /**
     * 读原模型并把指定骨骼子树烘焙成 Java 物品模型。
     *
     * <p>原模型的贴图声明尺寸和贴图文件实际尺寸并不一致（{@code cchess_pieces.json} 声明
     * 512×512，而 PNG 是 256×256），所以这里以**声明值**为准做 UV 换算 —— 原模组自己也
     * 是按声明值渲染的。
     *
     * @return 烘焙好的模型；模型文件读不到或骨骼缺失时返回 null
     */
    private static JsonObject bakedPieceModel(Piece piece) {
        try {
            java.nio.file.Path geo = ContentPackGenerator.assetsRoot()
                    .resolve("models").resolve("bedrock").resolve(piece.model() + ".json");
            if (!java.nio.file.Files.isRegularFile(geo)) {
                return null;
            }
            io.github.tlm.converter.bedrock.BedrockGeometry geometry =
                    io.github.tlm.converter.bedrock.BedrockGeometry.parse(
                            ContentPackGenerator.readJson(geo));
            PieceGeometryBuilder.textureSize = piece.texSize();
            String texture = ContentPackGenerator.NS + ":bedrock/" + piece.texture();
            JsonObject baked = PieceGeometryBuilder.build(geometry, piece.bone(), texture, piece.render());
            if (baked == null) {
                System.out.println("[棋子] " + piece.id() + " 的骨骼 " + piece.bone()
                        + " 烘焙失败（骨骼树为空或骨骼名不存在）");
            } else {
                System.out.println("[棋子] " + piece.id() + " 烘焙出 "
                        + baked.getAsJsonArray("elements").size() + " 个元素");
            }
            return baked;
        } catch (Exception e) {
            System.out.println("[棋子] " + piece.id() + " 烘焙异常: " + e);
            return null;
        }
    }

    /**
     * 平面棋子（中国象棋）的模型：整块贴图面。
     *
     * <p>它的原始立方体是 {@code 64×0×64}，Box UV 在这种零厚度盒子上会退化 ——
     * up/down 面算出来高度为 0，取到的是贴图上相邻棋子的像素，看起来就是「只有文字没有棋子」。
     * 所以平面棋子直接铺 {@code uv .. uv+64} 这个矩形（原模型骨骼的 UV 偏移就是各棋子的位置）。
     */
    private static JsonObject flatPieceModel(Piece piece) {
        double[] size = piece.size();
        double unit = piece.render() * 16 / size[0];
        double w = size[0] * unit / 2;
        double d = size[2] * unit / 2;
        // 平面棋子给一点厚度：零体积元素在部分渲染路径下不显示
        double h = 0.1;

        // 贴图像素 → Java 模型 UV（整张贴图是 0~16）
        double k = 16.0 / piece.texSize();
        double u0 = piece.uv()[0] * k;
        double v0 = piece.uv()[1] * k;
        double u1 = (piece.uv()[0] + piece.uvSize()[0]) * k;
        double v1 = (piece.uv()[1] + piece.uvSize()[2]) * k;

        JsonObject element = new JsonObject();
        element.add("from", vector(8 - w, 0, 8 - d));
        element.add("to", vector(8 + w, h, 8 + d));

        JsonObject faces = new JsonObject();
        // 只有上下面是棋子的图案面；四个侧面退化成薄边，用同一块 UV 即可（看不见）
        for (String face : new String[]{"up", "down"}) {
            JsonObject f = new JsonObject();
            JsonArray uv = new JsonArray();
            uv.add(round(u0));
            uv.add(round(v0));
            uv.add(round(u1));
            uv.add(round(v1));
            f.add("uv", uv);
            f.addProperty("texture", "#0");
            faces.add(face, f);
        }
        for (String face : new String[]{"north", "south", "east", "west"}) {
            JsonObject f = new JsonObject();
            JsonArray uv = new JsonArray();
            // 侧面只有 0.1 单位高，取图案的一条边即可
            uv.add(round(u0));
            uv.add(round(v0));
            uv.add(round(u1));
            uv.add(round(v0 + (v1 - v0) * 0.05));
            f.add("uv", uv);
            f.addProperty("texture", "#0");
            faces.add(face, f);
        }
        element.add("faces", faces);

        JsonArray elements = new JsonArray();
        elements.add(element);
        String texture = ContentPackGenerator.NS + ":bedrock/" + piece.texture();
        JsonObject textures = new JsonObject();
        textures.addProperty("0", texture);
        textures.addProperty("particle", texture);
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        return model;
    }

    /**
     * Box UV → 逐面 UV，规则与 Bedrock 一致（{@code BbModelWriter#boxUv} 的同款展开），
     * 再换算到 Java 模型的 0~16 UV 空间。
     */
    private static JsonObject boxUv(Piece piece) {
        double u = piece.uv()[0];
        double v = piece.uv()[1];
        // Box UV 按**原始毛坯尺寸**展开（Bedrock 的规矩：inflate 只改几何，不改 UV 布局）
        double w = piece.uvSize()[0];
        double h = piece.uvSize()[1];
        double d = piece.uvSize()[2];

        Map<String, double[]> rects = new LinkedHashMap<>();
        rects.put("north", new double[]{u + d, v + d, u + d + w, v + d + h});
        rects.put("east", new double[]{u, v + d, u + d, v + d + h});
        rects.put("south", new double[]{u + d + w + d, v + d, u + d + w + d + w, v + d + h});
        rects.put("west", new double[]{u + d + w, v + d, u + d + w + d, v + d + h});
        rects.put("up", new double[]{u + d + w, v + d, u + d, v});
        rects.put("down", new double[]{u + d + w + w, v, u + d + w, v + d});

        // 贴图像素 → Java 模型 UV（整张贴图是 0~16）
        double k = 16.0 / piece.texSize();
        JsonObject faces = new JsonObject();
        for (Map.Entry<String, double[]> entry : rects.entrySet()) {
            double[] r = entry.getValue();
            JsonArray uv = new JsonArray();
            // 夹到贴图范围内：平面棋子的 Box UV 偏移是负数，侧面会落到贴图外
            for (double value : new double[]{r[0], r[1], r[2], r[3]}) {
                uv.add(round(Math.max(0, Math.min(16, value * k))));
            }
            JsonObject face = new JsonObject();
            face.add("uv", uv);
            face.addProperty("texture", "#0");
            faces.add(entry.getKey(), face);
        }
        return faces;
    }

    /** 只有国际象棋与中国象棋需要把点击面抬高（它们的棋子明显高出棋面）。 */
    private static boolean isChess(String id) {
        return "cchess".equals(id) || "wchess".equals(id);
    }

    /**
     * 象棋点击面的高度：盖住最高的棋子，再留一点余量。
     *
     * <p>棋盘上摆的是 display 实体，玩家点到的往往是棋子而不是棋面，所以点击面必须比棋子高，
     * 否则点在棋子上会被 display 吃掉。
     */
    private static double clickHeight(Board board) {
        // 象棋棋子摆好后最高约 0.25 格（立体棋子高 1.27 单位），点击面罩到 0.5 格足够，
        // 又不会高到挡住玩家看棋盘
        return Math.max(board.surfaceY() + 0.25, 0.5);
    }

    /** 棋盘清单（{@code board_models.json}）：插件按它换算点击位置与棋子摆放。 */
    private static void writeManifest(ContentPackGenerator gen) throws IOException {
        JsonArray boards = new JsonArray();
        for (Board board : BOARDS.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", board.id());
            entry.addProperty("columns", board.columns());
            entry.addProperty("rows", board.rows());
            // 间距与窗口要高精度：14 格累积下来，千分位的误差会吃掉命中窗口的一成
            entry.addProperty("spacing", board.spacing());
            entry.addProperty("click_window", board.clickWindow());
            entry.addProperty("surface_y", board.surfaceY());
            entry.addProperty("scale", board.scale());
            entry.addProperty("piece_size", board.pieceSize());
            entry.addProperty("origin_x", board.originX());
            entry.addProperty("origin_z", board.originZ());
            boards.add(entry);
        }
        JsonObject root = new JsonObject();
        root.add("boards", boards);
        JsonArray pieces = new JsonArray();
        PIECES.keySet().forEach(pieces::add);
        root.add("pieces", pieces);
        gen.writeRootFile("board_models.json", root);
    }

    private static JsonArray vector(double x, double y, double z) {
        JsonArray out = new JsonArray();
        out.add(round(x));
        out.add(round(y));
        out.add(round(z));
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 未使用但保留签名对齐（棋盘的 elements 由 FurnitureTable 生成）。 */
    static List<String> boardIds() {
        return List.copyOf(BOARDS.keySet());
    }
}
