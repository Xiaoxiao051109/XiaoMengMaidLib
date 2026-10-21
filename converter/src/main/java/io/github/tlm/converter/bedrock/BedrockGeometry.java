package io.github.tlm.converter.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bedrock 几何体的最小解析模型，兼容两种格式：
 * <ul>
 *   <li>旧版 1.10.0：{@code {"geometry.model": {"texturewidth":..., "bones": [...]}}}；</li>
 *   <li>新版 1.12.0+：{@code {"minecraft:geometry": [{"description": {...}, "bones": [...]}]}}。</li>
 * </ul>
 * 字段语义与原模组 {@code simplebedrockmodel} 的 POJO 保持一致（镜像继承规则见 {@link #parse}）。
 */
public record BedrockGeometry(int textureWidth, int textureHeight, List<Bone> bones) {

    public record Bone(String name, String parent, float[] pivot, float[] rotation, boolean mirror, List<Cube> cubes) {
    }

    /**
     * @param uv      box UV 偏移（u, v）；与 {@code faceUv} 二选一
     * @param faceUv  逐面 UV：面名 → [u, v, uSize, vSize]
     * @param pivot   可为 null，表示沿用所属骨骼的旋转点
     * @param rotation 可为 null
     */
    public record Cube(float[] origin, float[] size, float inflate, boolean mirror,
                       float[] uv, Map<String, float[]> faceUv, float[] pivot, float[] rotation) {
    }

    public static BedrockGeometry parse(JsonObject root) {
        JsonObject geometry;
        int texWidth;
        int texHeight;

        if (root.has("minecraft:geometry")) {
            geometry = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            JsonObject description = geometry.getAsJsonObject("description");
            texWidth = intOr(description, "texture_width", 64);
            texHeight = intOr(description, "texture_height", 64);
        } else {
            geometry = null;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getKey().startsWith("geometry.") && entry.getValue().isJsonObject()) {
                    geometry = entry.getValue().getAsJsonObject();
                    break;
                }
            }
            if (geometry == null) {
                throw new IllegalArgumentException("不是 Bedrock 几何体文件（缺少 minecraft:geometry / geometry.*）");
            }
            texWidth = intOr(geometry, "texturewidth", 64);
            texHeight = intOr(geometry, "textureheight", 64);
        }

        List<Bone> bones = new ArrayList<>();
        JsonArray boneArray = geometry.getAsJsonArray("bones");
        if (boneArray != null) {
            for (JsonElement element : boneArray) {
                bones.add(parseBone(element.getAsJsonObject()));
            }
        }
        return new BedrockGeometry(texWidth, texHeight, bones);
    }

    private static Bone parseBone(JsonObject json) {
        boolean boneMirror = json.has("mirror") && json.get("mirror").getAsBoolean();
        List<Cube> cubes = new ArrayList<>();
        JsonArray cubeArray = json.getAsJsonArray("cubes");
        if (cubeArray != null) {
            for (JsonElement element : cubeArray) {
                cubes.add(parseCube(element.getAsJsonObject(), boneMirror));
            }
        }
        return new Bone(
                json.get("name").getAsString(),
                json.has("parent") ? json.get("parent").getAsString() : null,
                vec(json, "pivot", new float[3]),
                vec(json, "rotation", null),
                boneMirror,
                cubes);
    }

    private static Cube parseCube(JsonObject json, boolean boneMirror) {
        // 与原模组一致：立方体未显式声明 mirror 时继承骨骼的 mirror
        boolean mirror = json.has("mirror") && json.get("mirror").isJsonPrimitive()
                ? json.get("mirror").getAsBoolean()
                : boneMirror;

        float[] boxUv = null;
        Map<String, float[]> faceUv = null;
        JsonElement uvElement = json.get("uv");
        if (uvElement != null && uvElement.isJsonArray()) {
            boxUv = toFloats(uvElement.getAsJsonArray());
        } else if (uvElement != null && uvElement.isJsonObject()) {
            faceUv = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> face : uvElement.getAsJsonObject().entrySet()) {
                JsonObject f = face.getValue().getAsJsonObject();
                float[] uv = toFloats(f.getAsJsonArray("uv"));
                float[] size = f.has("uv_size") ? toFloats(f.getAsJsonArray("uv_size")) : new float[2];
                faceUv.put(face.getKey(), new float[]{uv[0], uv[1], size[0], size[1]});
            }
        } else {
            boxUv = new float[2];
        }

        return new Cube(
                vec(json, "origin", new float[3]),
                vec(json, "size", new float[3]),
                json.has("inflate") ? json.get("inflate").getAsFloat() : 0f,
                mirror,
                boxUv,
                faceUv,
                vec(json, "pivot", null),
                vec(json, "rotation", null));
    }

    private static float[] vec(JsonObject json, String key, float[] fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonArray() ? toFloats(element.getAsJsonArray()) : fallback;
    }

    private static float[] toFloats(JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    private static int intOr(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }
}
