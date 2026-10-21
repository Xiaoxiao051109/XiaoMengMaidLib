package io.github.tlm.folia.api;

/**
 * 棋盘坐标（列、行，从 0 开始）。
 *
 * <p>原为 A 侧 {@code BoardModels.Cell}，因成为 A/B 的接口类型而提升到 api 模块。
 * 棋盘渲染、点击换算、棋子摆放都用它做中间坐标。
 */
public record Cell(int column, int row) {
}
