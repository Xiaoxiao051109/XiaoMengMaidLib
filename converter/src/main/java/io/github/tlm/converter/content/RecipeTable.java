package io.github.tlm.converter.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 配方：原模组 datagen 输出的 {@code recipe/*.json}。
 *
 * <ul>
 *   <li>{@code minecraft:crafting_shaped/shapeless} → CE 的 shaped/shapeless 配方；</li>
 *   <li>{@code touhou_little_maid:altar}（祭坛合成，原模组自定义配方类型）CE 无法表达，
 *       原样汇总到 {@code altar_recipes.json}，由插件实现祭坛时读取。</li>
 * </ul>
 */
final class RecipeTable {

    /**
     * NeoForge 的 {@code c:} 通用标签在原版服务端不存在，换成等价的原版物品/标签。
     */
    private static final Map<String, String> COMMON_TAGS = Map.ofEntries(
            Map.entry("#c:ingots/iron", "minecraft:iron_ingot"),
            Map.entry("#c:dusts/redstone", "minecraft:redstone"),
            Map.entry("#c:gems/diamond", "minecraft:diamond"),
            Map.entry("#c:leathers", "minecraft:leather"),
            Map.entry("#c:rods/wooden", "minecraft:stick"),
            Map.entry("#c:dyes/black", "minecraft:black_dye"),
            Map.entry("#c:dyes/blue", "minecraft:blue_dye"),
            Map.entry("#c:dyes/green", "minecraft:green_dye"),
            Map.entry("#c:dyes/pink", "minecraft:pink_dye"),
            Map.entry("#c:dyes/purple", "minecraft:purple_dye"),
            Map.entry("#c:dyes/white", "minecraft:white_dye"),
            Map.entry("#c:dyes/yellow", "minecraft:yellow_dye"));

    private RecipeTable() {
    }

    static JsonObject generate(ContentPackGenerator gen) throws IOException {
        JsonObject recipes = new JsonObject();
        JsonArray altar = new JsonArray();
        Path dir = gen.data().resolve("recipe");
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                String name = dir.relativize(file).toString().replace('\\', '/').replace(".json", "");
                JsonObject json = ContentPackGenerator.readJson(file);
                String type = json.get("type").getAsString();
                switch (type) {
                    case "minecraft:crafting_shaped" -> recipes.add(ContentPackGenerator.id(name), shaped(json));
                    case "minecraft:crafting_shapeless" -> recipes.add(ContentPackGenerator.id(name), shapeless(json));
                    case "touhou_little_maid:altar" -> {
                        json.addProperty("id", ContentPackGenerator.id(name));
                        altar.add(json);
                    }
                    default -> gen.warn("配方 " + name + ": 未处理的类型 " + type);
                }
            }
        }
        writeAltar(gen, altar);
        return recipes;
    }

    private static void writeAltar(ContentPackGenerator gen, JsonArray altar) throws IOException {
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        Files.writeString(gen.outputRoot().resolve("altar_recipes.json"), gson.toJson(altar), StandardCharsets.UTF_8);
    }

    private static JsonObject shaped(JsonObject json) {
        // 去掉全空行：datagen 会留下 "   " 这样的占位行，CE 的图案校验不接受
        JsonArray pattern = new JsonArray();
        for (JsonElement row : json.getAsJsonArray("pattern")) {
            if (!row.getAsString().isBlank()) {
                pattern.add(row.getAsString());
            }
        }
        JsonObject key = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("key").entrySet()) {
            key.addProperty(entry.getKey(), ingredient(entry.getValue()));
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "shaped");
        out.add("pattern", pattern);
        out.add("ingredients", key);
        out.add("result", result(json));
        return out;
    }

    private static JsonObject shapeless(JsonObject json) {
        JsonArray ingredients = new JsonArray();
        for (JsonElement element : json.getAsJsonArray("ingredients")) {
            ingredients.add(ingredient(element));
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "shapeless");
        out.add("ingredients", ingredients);
        out.add("result", result(json));
        return out;
    }

    /** 原料：字符串（物品 id 或 #标签）；数组形式只取第一个（本模组的原版配方没有用到多选）。 */
    private static String ingredient(JsonElement element) {
        String value = element.isJsonArray() ? element.getAsJsonArray().get(0).getAsString() : element.getAsString();
        return COMMON_TAGS.getOrDefault(value, value);
    }

    private static JsonObject result(JsonObject json) {
        JsonObject source = json.getAsJsonObject("result");
        JsonObject out = new JsonObject();
        out.addProperty("id", source.get("id").getAsString());
        out.addProperty("count", source.has("count") ? source.get("count").getAsInt() : 1);
        return out;
    }
}
