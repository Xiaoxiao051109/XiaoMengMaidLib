package io.github.tlm.converter.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 椅子 / 坐垫 → CraftEngine 带座位的家具，对应原模组 {@code ItemChair} + {@code EntityChair}。
 *
 * <p>原模组椅子是实体：放置后玩家和女仆都能坐上去，潜行右键打开换模型界面；模型来自椅子资源包
 * （63 个，默认 {@code touhou_little_maid:cushion}）。这里做成 CE 家具：
 * <ul>
 *   <li>每个椅子模型生成一个隐藏的家具物品 {@code chair_<模型键后缀>}，{@code chair} 物品放置时按数据挑一个；</li>
 *   <li>家具带 {@code interaction} 碰撞箱与一个座位（坐高取模型的 {@code mounted_height}）。</li>
 * </ul>
 * 椅子按任意角度、居中对齐放置（原模组按 45° 取整旋转，这里放宽）。
 */
final class ChairTable {

    /** 椅子模型：清单里的 kind = "chair" 条目。 */
    record Chair(String key, String modelId, String sourceModel, double mountedHeight, String displayName) {
    }

    private ChairTable() {
    }

    /** 读取 {@code tlm_models.json} 里的椅子条目；清单不存在时返回空列表。 */
    static List<Chair> read() throws IOException {
        Path manifest = ContentPackGenerator.MANIFEST;
        if (manifest == null) {
            System.err.println("[椅子] 未提供 tlm_models.json 路径，跳过椅子家具生成");
            return List.of();
        }
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        JsonArray array = com.google.gson.JsonParser.parseReader(
                Files.newBufferedReader(manifest, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        List<Chair> chairs = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject();
            if (!"chair".equals(entry.get("kind").getAsString())) {
                continue;
            }
            double height = entry.has("mounted_height") ? entry.get("mounted_height").getAsDouble() : 0;
            String name = entry.has("display_name") ? entry.get("display_name").getAsString() : entry.get("model_id").getAsString();
            // 原模组坐高单位是像素，EntityChair 里再除以 16
            // source_model：条目用 model 字段复用别的模型文件时，碰撞箱要按被复用的那个算
            String sourceModel = entry.has("source_model") ? entry.get("source_model").getAsString() : null;
            chairs.add(new Chair(entry.get("key").getAsString(), entry.get("model_id").getAsString(),
                    sourceModel, height / 16.0, name));
        }
        return chairs;
    }

    static void generate(ContentPackGenerator gen, JsonObject items, List<Chair> chairs) throws IOException {
        if (chairs.isEmpty()) {
            return;
        }
        JsonArray list = new JsonArray();
        for (Chair chair : chairs) {
            String furnitureId = shortId(chair.key());
            JsonObject entry = new JsonObject();
            entry.addProperty("id", ContentPackGenerator.id(furnitureId));
            entry.addProperty("name", chair.displayName());
            entry.addProperty("height", round(chair.mountedHeight()));
            list.add(entry);
            items.add(ContentPackGenerator.id(furnitureId), furniture(gen, furnitureId, chair));
        }
        // 椅子清单给插件用：chair 物品放置时按物品数据挑家具，没数据就用默认坐垫
        JsonObject manifest = new JsonObject();
        manifest.addProperty("default", ContentPackGenerator.id(shortId(defaultKey(chairs))));
        manifest.add("chairs", list);
        writeChairManifest(gen, manifest);
    }

    private static JsonObject furniture(ContentPackGenerator gen, String furnitureId, Chair chair) throws IOException {
        JsonObject item = ItemTable.item(gen, furnitureId);

        JsonObject element = new JsonObject();
        element.addProperty("type", "craftengine:better_model");
        element.addProperty("model", chair.key());
        element.addProperty("position", "0,0,0");

        JsonObject hitbox = new JsonObject();
        hitbox.addProperty("type", "interaction");
        hitbox.addProperty("position", "0,0,0");
        hitbox.addProperty("width", 0.8f);
        hitbox.addProperty("height", 1f);
        hitbox.addProperty("blocks_building", false);
        hitbox.addProperty("interactive", true);
        hitbox.addProperty("invisible", true);
        // 座位不交给 CraftEngine：CE 的座位只服务玩家，女仆用的是插件自己生成的座位实体，
        // 两套并存会出现「女仆坐着玩家还能坐上去」。就座统一由插件处理（见 SeatManager），
        // 坐高与朝向由插件按 chair_models.json 计算。

        JsonArray elements = new JsonArray();
        elements.add(element);
        JsonArray hitboxes = new JsonArray();
        hitboxes.add(hitbox);

        // 实心碰撞：原来只有 interaction（纯点击、零碰撞），玩家和女仆能直接穿过椅子/桌子。
        // 碰撞箱按模型包围盒推导，坐面高度以上的部分封顶，避免装饰件把包盒撑成隐形墙。
        try {
            float[] bounds = gen.entityModelBounds(chair.sourceModel() != null ? chair.sourceModel() : chair.modelId());
            if (bounds != null && bounds[0] <= bounds[3]) {
                hitboxes.addAll(FurnitureTable.modelCollision(gen, furnitureId, bounds));
            }
        } catch (IOException e) {
            gen.warn(furnitureId + ": 无法读取模型包围盒，跳过碰撞箱（" + e.getMessage() + "）");
        }

        JsonObject ground = new JsonObject();
        ground.add("elements", elements);
        ground.add("hitboxes", hitboxes);
        JsonObject variants = new JsonObject();
        variants.add("ground", ground);

        JsonObject settings = new JsonObject();
        // 各款椅子都是 chair 物品放置 / 换模型得到的，拆除时返还可再次放置的椅子物品。
        settings.addProperty("item", ContentPackGenerator.id("chair"));

        JsonObject lootArgs = new JsonObject();
        lootArgs.addProperty("item", ContentPackGenerator.id("chair"));
        JsonObject loot = new JsonObject();
        loot.addProperty("template", "default:loot_table/furniture");
        loot.add("arguments", lootArgs);

        JsonObject furniture = new JsonObject();
        furniture.add("settings", settings);
        furniture.add("variants", variants);
        furniture.add("loot", loot);
        furniture.add("behaviors", FurnitureBehaviorConfig.list(
                FurnitureBehaviorConfig.seat("", chair.displayName(), chair.mountedHeight(), 0, 0, true)));

        JsonObject groundRule = new JsonObject();
        groundRule.addProperty("rotation", "any");
        groundRule.addProperty("alignment", "center");
        JsonObject rules = new JsonObject();
        rules.add("ground", groundRule);

        JsonObject behavior = new JsonObject();
        behavior.addProperty("type", "furniture_item");
        behavior.add("rules", rules);
        behavior.add("furniture", furniture);
        item.add("behavior", behavior);
        return item;
    }

    /**
     * 模型键 → 家具 id：去掉 {@code tlm_chair_} 前缀，再把命名空间里的 {@code __} 还原成 {@code _}，
     * 这样 {@code tlm_chair_touhou_little_maid__cushion} 就是 {@code cushion}、{@code tlm_chair_geckolib__x} 是 {@code geckolib_x}。
     */
    /** 默认椅子模型：原模组 EntityChair 的 DEFAULT_MODEL_ID 是 {@code touhou_little_maid:cushion}。 */
    private static String defaultKey(List<Chair> chairs) {
        for (Chair chair : chairs) {
            if ("touhou_little_maid:cushion".equals(chair.modelId())) {
                return chair.key();
            }
        }
        return chairs.getFirst().key();
    }

    /** 写出椅子清单（{@code chair_models.json}，插件运行时读取）。 */
    private static void writeChairManifest(ContentPackGenerator gen, JsonObject manifest) throws IOException {
        gen.writeRootFile("chair_models.json", manifest);
    }

    /**
     * 模型键 → 家具 id：去掉 {@code tlm_chair_} 前缀与本模组命名空间，再把 {@code __} 还原成 {@code _}。
     * 例：{@code tlm_chair_touhou_little_maid__cushion} → {@code cushion}，
     * {@code tlm_chair_geckolib__qingluka} → {@code geckolib_qingluka}。
     */
    private static String shortId(String key) {
        String rest = key.startsWith("tlm_chair_") ? key.substring("tlm_chair_".length()) : key;
        String prefix = ContentPackGenerator.NS + "__";
        if (rest.startsWith(prefix)) {
            rest = rest.substring(prefix.length());
        }
        return rest.replace("__", "_");
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
