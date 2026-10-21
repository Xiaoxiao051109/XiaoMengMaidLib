package io.github.tlm.converter.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock / GeckoLib 动画文件（{@code *.animation.json}）的最小解析模型。
 *
 * <p>通道值可以是数字或 Molang 字符串，这里统一保留为字符串，由下游决定如何求值。
 */
public record BedrockAnimation(String name, Loop loop, float length, Map<String, Map<String, List<Keyframe>>> bones) {

    public enum Loop {
        ONCE, LOOP, HOLD
    }

    /**
     * @param time          秒
     * @param value         x, y, z（数字或 Molang 表达式）
     * @param interpolation linear / catmullrom / step
     */
    public record Keyframe(float time, String[] value, String interpolation) {
    }

    public static List<BedrockAnimation> parseFile(JsonObject root) {
        List<BedrockAnimation> out = new ArrayList<>();
        JsonObject animations = root.getAsJsonObject("animations");
        if (animations == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                out.add(parse(entry.getKey(), entry.getValue().getAsJsonObject()));
            }
        }
        return out;
    }

    private static BedrockAnimation parse(String name, JsonObject json) {
        Loop loop = Loop.ONCE;
        JsonElement loopElement = json.get("loop");
        if (loopElement != null && loopElement.isJsonPrimitive()) {
            JsonPrimitive primitive = loopElement.getAsJsonPrimitive();
            if (primitive.isBoolean() && primitive.getAsBoolean()) {
                loop = Loop.LOOP;
            } else if (primitive.isString() && "hold_on_last_frame".equals(primitive.getAsString())) {
                loop = Loop.HOLD;
            }
        }

        Map<String, Map<String, List<Keyframe>>> bones = new LinkedHashMap<>();
        float maxTime = 0;
        JsonObject boneJson = json.getAsJsonObject("bones");
        if (boneJson != null) {
            for (Map.Entry<String, JsonElement> bone : boneJson.entrySet()) {
                if (!bone.getValue().isJsonObject()) {
                    continue;
                }
                Map<String, List<Keyframe>> channels = new LinkedHashMap<>();
                for (String channel : new String[]{"rotation", "position", "scale"}) {
                    JsonElement channelJson = bone.getValue().getAsJsonObject().get(channel);
                    if (channelJson == null) {
                        continue;
                    }
                    List<Keyframe> keyframes = parseChannel(channelJson, channel);
                    for (Keyframe keyframe : keyframes) {
                        maxTime = Math.max(maxTime, keyframe.time());
                    }
                    if (!keyframes.isEmpty()) {
                        channels.put(channel, keyframes);
                    }
                }
                if (!channels.isEmpty()) {
                    bones.put(bone.getKey(), channels);
                }
            }
        }

        float length = json.has("animation_length") ? json.get("animation_length").getAsFloat() : maxTime;
        return new BedrockAnimation(name, loop, length, bones);
    }

    /** 通道有三种写法：常量值、{时间: 值}、{时间: {pre, post, lerp_mode}}。 */
    private static List<Keyframe> parseChannel(JsonElement json, String channel) {
        List<Keyframe> out = new ArrayList<>();
        if (json.isJsonObject() && !isVectorObject(json.getAsJsonObject())) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                float time;
                try {
                    time = Float.parseFloat(entry.getKey());
                } catch (NumberFormatException e) {
                    continue;
                }
                JsonElement value = entry.getValue();
                String interpolation = "linear";
                if (value.isJsonObject()) {
                    JsonObject frame = value.getAsJsonObject();
                    if (frame.has("lerp_mode")) {
                        interpolation = frame.get("lerp_mode").getAsString();
                    }
                    // post 是关键帧之后的取值，pre 用于制造突变；单轨关键帧系统只能取一个，优先 post
                    value = frame.has("post") ? frame.get("post") : frame.get("pre");
                }
                String[] vector = vector(value, channel);
                if (vector != null) {
                    out.add(new Keyframe(time, vector, interpolation));
                }
            }
            out.sort((a, b) -> Float.compare(a.time(), b.time()));
        } else {
            String[] vector = vector(json, channel);
            if (vector != null) {
                out.add(new Keyframe(0, vector, "linear"));
            }
        }
        return out;
    }

    private static boolean isVectorObject(JsonObject json) {
        return json.has("vector");
    }

    private static String[] vector(JsonElement value, String channel) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject() && value.getAsJsonObject().has("vector")) {
            value = value.getAsJsonObject().get("vector");
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.size() == 0) {
                return null;
            }
            String[] out = new String[3];
            for (int i = 0; i < 3; i++) {
                out[i] = array.get(Math.min(i, array.size() - 1)).getAsString();
            }
            return out;
        }
        if (value.isJsonPrimitive()) {
            // 标量写法：缩放表示等比，其余表示三个轴同值
            String scalar = value.getAsString();
            return new String[]{scalar, scalar, scalar};
        }
        return null;
    }
}
