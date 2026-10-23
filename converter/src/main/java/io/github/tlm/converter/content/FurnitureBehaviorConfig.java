package io.github.tlm.converter.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 生成家具配置里的自定义 behavior 段，供插件在运行时读取坐姿与棋盘几何。 */
final class FurnitureBehaviorConfig {

    private FurnitureBehaviorConfig() {
    }

    static JsonObject seat(String joyType, String displayName, double height,
                           double yRot, double seatOut, boolean chair) {
        JsonObject behavior = new JsonObject();
        behavior.addProperty("type", ContentPackGenerator.NS + ":seat");
        if (displayName != null && !displayName.isBlank()) {
            behavior.addProperty("display_name", displayName);
        }
        if (joyType != null && !joyType.isBlank()) {
            behavior.addProperty("joy_type", joyType);
        }
        behavior.addProperty("chair", chair);
        behavior.addProperty("height", round(height));
        behavior.addProperty("y_rot", round(yRot));
        behavior.addProperty("seat_out", round(seatOut));
        return behavior;
    }

    static JsonObject board(BoardTable.Board board) {
        JsonObject behavior = new JsonObject();
        behavior.addProperty("type", ContentPackGenerator.NS + ":board");
        behavior.addProperty("game", board.id());
        behavior.addProperty("columns", board.columns());
        behavior.addProperty("rows", board.rows());
        behavior.addProperty("spacing", board.spacing());
        behavior.addProperty("click_window", board.clickWindow());
        behavior.addProperty("surface_y", board.surfaceY());
        behavior.addProperty("piece_size", board.pieceSize());
        behavior.addProperty("origin_x", board.originX());
        behavior.addProperty("origin_z", board.originZ());
        return behavior;
    }

    static JsonArray list(JsonObject... behaviors) {
        JsonArray array = new JsonArray();
        for (JsonObject behavior : behaviors) {
            if (behavior != null) {
                array.add(behavior);
            }
        }
        return array;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
