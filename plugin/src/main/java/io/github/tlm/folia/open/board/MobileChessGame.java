package io.github.tlm.folia.open.board;

import io.github.tlm.folia.api.BoardGame;
import io.github.tlm.folia.api.Cell;
import io.github.tlm.folia.game.chess.Position;
import io.github.tlm.folia.game.chess.Search;

import java.util.ArrayList;
import java.util.List;

/**
 * 国际象棋对局（开源包侧实现），对应原模组 {@code BlockWChess} + {@code WChessUtil}。
 *
 * <p>引擎是原模组自带的 Mobile Chess（{@code game/chess}，GPL-2.0）。
 * 本类由 A 侧实现迁移而来，只依赖 {@link BoardGame} 契约，不依赖 A 的任何类型。
 *
 * <p>坐标：引擎用 0x88 布局，{@code sq = x + (y << 4)}，其中 file 取 4..11、rank 取 0..7。
 * 点击换算照搬 {@code WChessUtil#getClickPosition}：把命中点按 0.25 格切 8×8（取整，不是四舍五入）。
 */
public final class MobileChessGame implements BoardGame {

    /** 唯一的对局实例（引擎无共享可变状态，局面都存在 FEN 里）。 */
    public static final MobileChessGame INSTANCE = new MobileChessGame();

    /** 引擎的起始局面（{@code WChessUtil#INIT}）。 */
    public static final String INIT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /** 女仆的思考预算（毫秒），与原模组客户端代理一致。 */
    private static final long THINKING_MILLIS = 1000L;

    /** 哈希表级别，与原模组 {@code new Search(position, 12)} 一致。 */
    private static final int HASH_LEVEL = 12;

    /** 棋盘边长（格）：8×8，格距 0.25 格铺满 2 格。 */
    private static final int SIZE = 8;

    public MobileChessGame() {
    }

    @Override
    public String id() {
        return "wchess";
    }

    @Override
    public String initialFen() {
        return INIT;
    }

    @Override
    public int clickToSquare(double localX, double localZ) {
        // 原模组：以棋盘中心为原点、0.25 格一格，左边界 -1 格
        double gridX = (localX + 1.0) / 0.25;
        double gridZ = (localZ + 1.0) / 0.25;
        // 先挡掉负数：下面要 (int) 截断，负数会朝零截断成 0/1，越界反而算成合法格
        if (gridX < 0 || gridZ < 0) {
            return -1;
        }
        int localRow = (int) Math.floor(gridZ);
        if (localRow > SIZE - 1) {
            return -1;
        }
        // 与摆放同镜像，理由见 XqwlightGame#clickToSquare
        int row = mirroredRows() ? SIZE - 1 - localRow : localRow;
        int column = (int) Math.floor(gridX) + Position.FILE_LEFT;
        if (column > Position.FILE_RIGHT) {
            return -1;
        }
        return Position.COORD_XY(column, row + Position.RANK_TOP);
    }

    @Override
    public Cell squareToCell(int square) {
        return new Cell(Position.FILE_X(square) - Position.FILE_LEFT,
                Position.RANK_Y(square) - Position.RANK_TOP);
    }

    /**
     * 国际象棋的格子在点击换算里是**整格**（{@code floor}），所以行列表格的中心要偏半格，
     * 棋子才会落在格子正中。原点取 {@code -1}（原模组 {@code getClickPosition} 的左边界）。
     */
    @Override
    public double originX() {
        return -1.0 + 0.125;
    }

    @Override
    public double originZ() {
        return -1.0 + 0.125;
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
        if (column < 0 || row < 0 || column >= SIZE || row >= SIZE) {
            return -1;
        }
        return Position.COORD_XY(column + Position.FILE_LEFT, row + Position.RANK_TOP);
    }

    @Override
    public List<Placed> pieces(String fen) {
        Position position = position(fen);
        List<Placed> out = new ArrayList<>();
        for (int column = 0; column < SIZE; column++) {
            for (int row = 0; row < SIZE; row++) {
                int square = cellToSquare(column, row);
                String item = itemOf(position.squares[square]);
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
        if (!Position.IN_BOARD(square)) {
            return new Result(Action.IGNORE, selected, null);
        }
        byte[] squares = position.squares;
        byte previous = selected >= 0 && selected < squares.length ? squares[selected] : 0;
        byte now = squares[square];

        // 没有选中白子（玩家执白）：点白子就选中，否则忽略。
        // 原模组这里不判「轮到谁」——因为轮到女仆时玩家点了也不会有白子可选
        if (previous <= 0 || !isWhite(previous)) {
            if (isWhite(now)) {
                return new Result(Action.SELECT, square, null);
            }
            return new Result(Action.IGNORE, selected, null);
        }

        // 选中的也是白子 → 换选
        if (isWhite(now)) {
            return new Result(Action.SELECT, square, null);
        }

        // 试着走子
        int move = Position.MOVE(selected, square);
        if (!position.legalMove(move)) {
            return new Result(Action.IGNORE, selected, null);
        }
        String before = fen;
        Cell from = squareToCell(Position.SRC(move));
        Cell to = squareToCell(Position.DST(move));
        boolean captured = squares[square] != 0;
        // makeMove 返回 false 表示走完自己被将军 —— 原模组同样拒绝这步
        if (!position.makeMove(move)) {
            return new Result(Action.IGNORE, selected, null);
        }
        // 吃子或动兵要重置自然限着计数器（原模组 setIrrev）。
        // 读的是走子**之后**的目标格——源格这时已经空了
        if (captured || Position.PIECE_TYPE(position.squares[Position.DST(move)]) == Position.PIECE_PAWN) {
            position.setIrrev();
        }
        String after = position.toFen();
        // 走完轮到自己（sdPlayer 翻转后是白方）且被将死 → 女仆输了
        boolean mate = position.isMate() && position.sdPlayer == 0;
        // 落点即新的选中格：跟原模组一致（setSelectChessPoint(nowClick)），
        // 玩家下一步点自己的子就能直接换选
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
        if (captured || Position.PIECE_TYPE(position.squares[Position.DST(move)]) == Position.PIECE_PAWN) {
            position.setIrrev();
        }
        String after = position.toFen();
        // 走完轮到黑方（女仆）→ 玩家被将死
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
        return position(fen).sdPlayer == 0;
    }

    @Override
    public boolean isDraw(String fen) {
        Position position = position(fen);
        // 原模组：五十回合自然限着（引擎用 distance 记录半回合数）、长将判和。
        // 注意 chess 的 isRep 返回 boolean（xqwlight 的 repStatus 才返回次数）
        return position.isRep(3) || position.distance > 100;
    }

    /** 白方是玩家（原模组 {@code WChessUtil#isWhite}）。 */
    private static boolean isWhite(byte piece) {
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
     * <p>引擎的棋子字节 = 类型 + 8（白）/ +16（黑），见 {@code Position#FEN_PIECE}。
     * 物品 id 对应转换器 {@code BoardTable} 里注册的 12 种棋子。
     */
    private static String itemOf(byte piece) {
        if (piece == 0) {
            return null;
        }
        String side = isWhite(piece) ? "_white" : "_black";
        // PIECE_TYPE 取低 3 位（去掉阵营标记）
        String type = switch (piece & 7) {
            case Position.PIECE_KING -> "king";
            case Position.PIECE_QUEEN -> "queen";
            case Position.PIECE_ROOK -> "rook";
            case Position.PIECE_BISHOP -> "bishop";
            case Position.PIECE_KNIGHT -> "knight";
            case Position.PIECE_PAWN -> "pawn";
            default -> null;
        };
        return type == null ? null : "wchess_piece_" + type + side;
    }
}
