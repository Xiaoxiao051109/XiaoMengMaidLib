package io.github.tlm.converter.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * 原版物品模型定义（{@code assets/<ns>/items/*.json} 的 model 字段）→ CraftEngine 物品模型写法。
 *
 * <p>两者结构一致，差别只在 {@code minecraft:model} 的模型路径字段：原版叫 {@code model}，CE 叫 {@code path}。
 * {@code minecraft:special} 是原模组客户端代码渲染的（椅子、手办），服务端无法还原，
 * 改为一张静态图标，3D 外观由放置后的实体/家具负责。
 */
final class ItemModels {

    private ItemModels() {
    }

    static JsonElement convert(ContentPackGenerator gen, String id, JsonObject model) throws IOException {
        String type = model.has("type") ? model.get("type").getAsString() : "minecraft:model";
        return switch (type) {
            case "minecraft:model" -> {
                JsonObject out = new JsonObject();
                out.addProperty("type", "minecraft:model");
                out.addProperty("path", model.get("model").getAsString());
                if (model.has("tints")) {
                    out.add("tints", model.get("tints"));
                }
                yield out;
            }
            case "minecraft:select" -> {
                JsonObject out = new JsonObject();
                out.addProperty("type", "minecraft:select");
                out.addProperty("property", model.get("property").getAsString());
                JsonArray cases = new JsonArray();
                for (JsonElement element : model.getAsJsonArray("cases")) {
                    JsonObject source = element.getAsJsonObject();
                    JsonObject converted = new JsonObject();
                    converted.add("when", source.get("when"));
                    converted.add("model", convert(gen, id, source.getAsJsonObject("model")));
                    cases.add(converted);
                }
                out.add("cases", cases);
                if (model.has("fallback")) {
                    out.add("fallback", convert(gen, id, model.getAsJsonObject("fallback")));
                }
                yield out;
            }
            case "minecraft:special" -> staticIcon(gen, id);
            default -> {
                gen.warn(id + ": 不支持的物品模型类型 " + type + "，回退为静态图标");
                yield staticIcon(gen, id);
            }
        };
    }

    /** 为客户端专属渲染的物品生成一张平面图标模型。 */
    private static JsonElement staticIcon(ContentPackGenerator gen, String id) throws IOException {
        String texture = switch (id) {
            case "chair" -> "item/change_chair_model";
            case "garage_kit" -> "item/entity_placeholder";
            default -> "item/" + id;
        };
        if (!gen.hasTexture(texture)) {
            gen.warn(id + ": 没有可用的图标贴图 " + texture);
        }
        String name = id + "_icon";
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", ContentPackGenerator.id(texture));
        JsonObject itemModel = new JsonObject();
        itemModel.addProperty("parent", "minecraft:item/generated");
        itemModel.add("textures", textures);
        gen.writeItemModel(name, itemModel);

        JsonObject out = new JsonObject();
        out.addProperty("type", "minecraft:model");
        out.addProperty("path", ContentPackGenerator.id("item/" + name));
        return out;
    }
}
