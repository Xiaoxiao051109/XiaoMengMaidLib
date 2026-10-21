package io.github.tlm.folia.api;

import java.util.List;

/**
 * 一种棋盘对局的共同接口（中国象棋 / 国际象棋）。
 *
 * <p>由 A 侧 {@code board.ChessGame} 提升而来，作为 A/B 之间唯一的棋类契约：
 * A（付费闭源）只依赖本接口，B（开源义务包）提供实现并通过 Bukkit ServicesManager 注册。
 *
 * <p>所有状态都通过引擎自己的 FEN 字符串传递，接口不暴露任何引擎内部类型。
 *
 * <p>流程（两种象棋一致）：
 * <ol>
 *   <li>点击 → 格子（各自的点击换算比例不同）；</li>
 *   <li>没选中任何子 → 点自己的子就选中，否则忽略；</li>
 *   <li>已选中 → 再点自己的子就换选，点别的格就试着走子；</li>
 *   <li>走完换手，轮到女仆时异步搜索应手。</li>
 * </ol>
 * 棋子由 {@link #pieces(String)} 按引擎局面（FEN）实时列出，渲染层只管摆 display。
 */
public interface BoardGame {

    /** 家具 id（{@code wchess} / {@code cchess}）。 */
    String id();

    /** 初始局面（FEN）。 */
    String initialFen();

    /**
     * 把点击点换算成引擎的格子编号。
     *
     * @param localX 命中点相对棋盘中心的 X（已按朝向反旋转）
     * @param localZ 命中点相对棋盘中心的 Z
     * @return 引擎的格子编号；点在棋盘外或没点中空档时返回 -1
     */
    int clickToSquare(double localX, double localZ);

    /** 格子编号 → 棋盘行列（用于摆放棋子与算世界坐标）。 */
    Cell squareToCell(int square);

    /** 行列 (0,0) 相对棋盘中心的 X 偏移（格）。 */
    double originX();

    /** 行列 (0,0) 相对棋盘中心的 Z 偏移（格）。 */
    double originZ();

    /** 棋盘行列 → 引擎格子编号；不合法返回 -1。 */
    int cellToSquare(int column, int row);

    /**
     * 摆放时是否需要把行号倒过来（{@code row' = rows() - 1 - row}）。
     *
     * <p>与 {@link #clickToSquare} 互为逆运算，两边必须翻同一侧。
     */
    boolean mirroredRows();

    /**
     * 读出当前局面上所有棋子。
     *
     * @param fen 引擎局面（各引擎自己的 {@code toFen} 格式）
     * @return 每个棋子的行列与棋子物品 id
     */
    List<Placed> pieces(String fen);

    /** 该格子上的棋子物品 id；空格返回 null。 */
    String pieceAt(String fen, int column, int row);

    /**
     * 玩家点了一个格子。
     *
     * @param fen      当前局面
     * @param selected 上次选中的格子编号；没选中时为 -1
     * @return 处理结果：换选、走了这步、或是非法点击
     */
    Result click(String fen, int selected, int square);

    /**
     * 女仆的应手（在异步线程里算）。
     *
     * @param fen      当前局面
     * @param thinking 思考预算（毫秒）
     * @return 走子结果；没棋可走时返回 null
     */
    Move think(String fen, long thinking);

    /** 走完之后是否将军（用于提示音与文字）。 */
    boolean isCheck(String fen);

    /** 是否已经将死（局面里轮到的那方被将死）。 */
    boolean isMate(String fen);

    /** 是否轮到玩家走。 */
    boolean isPlayerTurn(String fen);

    /** 是否该判和：自然限着或长将。 */
    boolean isDraw(String fen);

    /** 一颗棋子：棋盘行列 + 棋子物品 id。 */
    record Placed(int column, int row, String item) {
    }

    /** 一次点击的处理结果。 */
    record Result(Action action, int selected, Move move) {

        public boolean moved() {
            return this.move != null;
        }
    }

    /** 点击的三种下场。 */
    enum Action {
        /** 选中了（或换选了）一枚子。 */
        SELECT,
        /** 走了一步棋（见 {@link Result#move()}）。 */
        MOVE,
        /** 非法点击，什么都不做。 */
        IGNORE
    }

    /** 一步棋：走子前后的局面 + 起点终点（渲染层用来画选中框与高亮）。 */
    record Move(String fenBefore, String fenAfter, int fromColumn, int fromRow,
                int toColumn, int toRow, boolean captured, boolean check, boolean mate) {
    }
}
