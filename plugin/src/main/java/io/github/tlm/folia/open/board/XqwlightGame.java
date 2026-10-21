package io.github.tlm.folia.open.board;

import io.github.tlm.folia.api.BoardGame;
import io.github.tlm.folia.api.Cell;
import io.github.tlm.folia.game.xqwlight.Position;
import io.github.tlm.folia.game.xqwlight.Search;

import java.util.ArrayList;
import java.util.List;

/**
 * 中国象棋对局（开源包侧实现），对应原模组 {@code BlockCChess} + {@code CChessUtil}。
 *
 * <p>引擎是原模组自带的 XiangQi Wizard Light（{@code game/xqwlight}，GPL-2.0）。
 * 本类由 A 侧实现迁移而来，只依赖 {@link BoardGame} 契约，不依赖 A 的任何类型。
 *
 * <p>坐标：0x88 布局，{@code sq = x + (y << 4)}，file 取 3..11（九列）、rank 取 3..12（十行）。
 * 点击换算是**取交叉点**而非切格子（{@code CChessUtil#getClickPosition}）：
 * 把命中点按 0.304 格换算成格号后四舍五入，偏离超过 0.3 格就算没点中（棋盘上棋子摆在交叉点）。
 */
public final class XqwlightGame implements BoardGame {

    /** 唯一的对局实例。 */
    public static final XqwlightGame INSTANCE = new XqwlightGame();

    /** 引擎的起始局面（{@code CChessUtil#INIT}）。 */
    public static final String INIT = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";

    /** 女仆的思考预算（毫秒），与原模组客户端代理一致。 */
    private static final long THINKING_MILLIS = 1000L;

    /** 哈希表级别。 */
    private static final int HASH_LEVEL = 12;

    /** 九列十行。 */
    private static final int COLUMNS = 9;
    private static final int ROWS = 10;

    /** 命中容差：偏离交叉点超过这么多格就算没点中（{@code CChessUtil} 用 0.3）。 */
    private static final double CLICK_TOLERANCE = 0.3;

    public XqwlightGame() {
    }

    @Override
    public String id() {
        return "cchess";
    }

    @Override
    public String initialFen() {
        return INIT;
    }

    @Override
    public int clickToSquare(double localX, double localZ) {
        // 原模组：命中点 +1.365 / +1.370 再除 0.304，四舍五入到最近交叉点
        double gridX = (localX + 1.365) / 0.304;
        double gridZ = (localZ + 1.370) / 0.304;
        int column = (int) Math.round(gridX);
        int localRow = (int) Math.round(gridZ);
        if (Math.abs(column - gridX) >= CLICK_TOLERANCE
                || Math.abs(localRow - gridZ) >= CLICK_TOLERANCE) {
            return -1;
        }
        // 必须与摆放用同一个镜像（见 mirroredRows）：摆放是 row → z，这里是 z → row，
        // 两者互为逆运算，只翻一边会让点击落在隔壁格
        int row = mirroredRows() ? ROWS - 1 - localRow : localRow;
        int sqX = column + Position.FILE_LEFT;
        int sqY = row + Position.RANK_TOP;
        if (sqX > Position.FILE_RIGHT || sqY > Position.RANK_BOTTOM) {
            return -1;
        }
        return Position.COORD_XY(sqX, sqY);
    }

    @Override
    public Cell squareToCell(int square) {
        return new Cell(Position.FILE_X(square) - Position.FILE_LEFT,
                Position.RANK_Y(square) - Position.RANK_TOP);
    }

    /**
     * 网格原点必须**等同于点击换算里的边界值**（{@code getClickPosition} 的 {@code -1.365} /
     * {@code -1.370}），棋子才会正好落在自己算出来的那个格子上。
     */
    @Override
    public double originX() {
        return -1.365;
    }

    @Override
    public double originZ() {
        return -1.370;
    }

    /**
     * 行号要倒置：{@link #originZ()} 是玩家那一侧的边界（−Z），而点击换算里 row 0 排在 +Z；
     * FEN 的 row 0 又是黑方（女仆）。不倒置的话女仆的子会摆到玩家面前。
     */
    @Override
    public boolean mirroredRows() {
        return true;
    }

    @Override
    public int cellToSquare(int column, int row) {
        if (column < 0 || row < 0 || column >= COLUMNS || row >= ROWS) {
            return -1;
        }
        return Position.COORD_XY(column + Position.FILE_LEFT, row + Position.RANK_TOP);
    }

    @Override
    public List<Placed> pieces(String fen) {
        Position position = position(fen);
        List<Placed> out = new ArrayList<>();
        for (int column = 0; column < COLUMNS; column++) {
            for (int row = 0; row < ROWS; row++) {
                String item = itemOf(position.squares[cellToSquare(column, row)]);
                if (item != null) {
                    out.add(new Placed(column, row, item));
                }
            }
        }
        return out;
    }

    @Override
    public String pieceAt(String fen, int column, int row) {
        int square = cellToSquare(column, row);
        if (square < 0) {
            return null;
        }
        return itemOf(position(fen).squares[square]);
    }

    @Override
    public Result click(String fen, int selected, int square) {
        Position position = position(fen);
        if (square < 0) {
            return new Result(Action.IGNORE, selected, null);
        }
        byte[] squares = position.squares;
        byte previous = selected >= 0 && selected < squares.length ? squares[selected] : 0;
        byte now = squares[square];

        // 没选中红子（玩家执红）：点红子就选中
        if (previous <= 0 || !isRed(previous)) {
            if (isRed(now)) {
                return new Result(Action.SELECT, square, null);
            }
            return new Result(Action.IGNORE, selected, null);
        }

        // 选中的也是红子 → 换选
        if (isRed(now)) {
            return new Result(Action.SELECT, square, null);
        }

        int move = Position.MOVE(selected, square);
        if (!position.legalMove(move)) {
            return new Result(Action.IGNORE, selected, null);
        }
        String before = fen;
        Cell from = squareToCell(Position.SRC(move));
        Cell to = squareToCell(Position.DST(move));
        boolean captured = now != 0;
        if (!position.makeMove(move)) {
            return new Result(Action.IGNORE, selected, null);
        }
        // xqwlight 没有 PIECE_TYPE 方法，棋子类型就是低 3 位。
        // 读走子**之后**的目标格——源格这时已经空了
        if (captured || (position.squares[Position.DST(move)] & 7) == Position.PIECE_PAWN) {
            position.setIrrev();
        }
        String after = position.toFen();
        boolean mate = position.isMate() && position.sdPlayer == 0;
        return new Result(Action.MOVE, square,
                new Move(before, after, from.column(), from.row(), to.column(), to.row(),
                        captured, position.inCheck(), mate));
    }

    @Override
    public Move think(String fen, long thinkingMillis) {
        Position position = position(fen);
        String before = fen;
        int move = new Search(position, HASH_LEVEL).searchMain((int) Math.max(1, thinkingMillis));
        if (move == 0) {
            return null;
        }
        Cell from = squareToCell(Position.SRC(move));
        Cell to = squareToCell(Position.DST(move));
        boolean captured = position.squares[Position.DST(move)] != 0;
        if (!position.makeMove(move)) {
            return null;
        }
        if (captured || (position.squares[Position.DST(move)] & 7) == Position.PIECE_PAWN) {
            position.setIrrev();
        }
        String after = position.toFen();
        boolean mate = position.isMate() && position.sdPlayer == 1;
        return new Move(before, after, from.column(), from.row(), to.column(), to.row(),
                captured, position.inCheck(), mate);
    }

    @Override
    public boolean isCheck(String fen) {
        return position(fen).inCheck();
    }

    @Override
    public boolean isMate(String fen) {
        return position(fen).isMate();
    }

    @Override
    public boolean isPlayerTurn(String fen) {
        // xqwlight 的 FEN 不含手番，只能从局面读
        return position(fen).sdPlayer == 0;
    }

    @Override
    public boolean isDraw(String fen) {
        Position position = position(fen);
        // 原模组：六十回合自然限着（moveNum）、三回合长打（repStatus(3)）
        return position.moveNum > 60 || position.repStatus(3) > 0;
    }

    private static boolean isRed(byte piece) {
        return (piece & 8) == 8;
    }

    private static Position position(String fen) {
        Position position = new Position();
        position.fromFen(fen == null || fen.isBlank() ? INIT : fen);
        return position;
    }

    /**
     * 棋子字节 → 棋子物品 id。
     *
     * <p>xqwlight 的棋子字节 = 类型 + 8（红）/ +16（黑），类型见 {@code Position#PIECE_*}：
     * K 帅/将、A 仕/士、B 相/象、N 马、R 车、C 炮、P 兵/卒。
     * 物品 id 对应转换器 {@code BoardTable} 里注册的 14 种棋子（各自的 Box UV 取自原模型骨骼）。
     */
    private static String itemOf(byte piece) {
        if (piece == 0) {
            return null;
        }
        boolean red = isRed(piece);
        String name = switch (piece & 7) {
            case Position.PIECE_KING -> red ? "shuai_red" : "jiang_black";
            case Position.PIECE_ADVISOR -> red ? "shi_red" : "shi_black";
            case Position.PIECE_BISHOP -> red ? "xiang_red" : "xiang_black";
            case Position.PIECE_KNIGHT -> red ? "ma_red" : "ma_black";
            case Position.PIECE_ROOK -> red ? "ju_red" : "ju_black";
            case Position.PIECE_CANNON -> red ? "pao_red" : "pao_black";
            case Position.PIECE_PAWN -> red ? "bing_red" : "zu_black";
            default -> null;
        };
        return name == null ? null : "cchess_piece_" + name;
    }
}
