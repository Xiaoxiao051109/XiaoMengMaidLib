package io.github.tlm.converter.convert;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.tlm.molang.MolangParser;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 转换期校验 Gecko 动画里的每个 Molang 表达式都能被插件运行时的解析器（{@code :molang}）解析。
 * 解析失败的表达式运行时按 0 处理；这里把它们列进错误清单，避免到服务器上才发现。
 */
final class GeckoExpressionCheck {

    /** 不是表达式的字段。 */
    private static final Set<String> SKIP = Set.of("loop", "sound_effects", "lerp_mode", "easing", "animation_length");

    private GeckoExpressionCheck() {
    }

    /** @return 解析失败的表达式数量 */
    static int check(JsonObject animations, List<String> errors, String modelKey) {
        int[] failed = {0};
        for (Map.Entry<String, JsonElement> animation : animations.entrySet()) {
            walk(animation.getValue(), text -> {
                try {
                    MolangParser.parse(text);
                } catch (RuntimeException e) {
                    failed[0]++;
                    errors.add(modelKey + "/" + animation.getKey() + ": Molang 无法解析 " + text);
                }
            });
        }
        return failed[0];
    }

    private static void walk(JsonElement element, java.util.function.Consumer<String> visitor) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                String text = element.getAsString().trim();
                if (!text.isEmpty() && !isNumber(text)) {
                    visitor.accept(text);
                }
            }
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(e -> walk(e, visitor));
            return;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!SKIP.contains(entry.getKey())) {
                walk(entry.getValue(), visitor);
            }
        }
    }

    private static boolean isNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
