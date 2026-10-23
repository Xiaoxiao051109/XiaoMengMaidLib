package io.github.tlm.converter.content;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 普通物品表：原模组 {@code items/*.json} 中不属于家具的全部物品。
 *
 * <p>堆叠、耐久、稀有度取自原模组各 Item 类的 {@code Properties}（stacksTo / durability / rarity），
 * 以原版数据组件写出，交互逻辑由插件后续实现。
 */
final class ItemTable {

    /** 耐久：ItemCamera、ItemChisel、ItemExtinguisher、ItemGohei 与 InitItems 里的 ItemDamageableBauble。 */
    private static final Map<String, Integer> DURABILITY = Map.ofEntries(
            Map.entry("camera", 50),
            Map.entry("chisel", 64),
            Map.entry("extinguisher", 128),
            Map.entry("hakurei_gohei", 1200),
            Map.entry("sanae_gohei", 1200),
            Map.entry("explosion_protect_bauble", 32),
            Map.entry("fire_protect_bauble", 128),
            Map.entry("projectile_protect_bauble", 64),
            Map.entry("magic_protect_bauble", 128),
            Map.entry("fall_protect_bauble", 32),
            Map.entry("drown_protect_bauble", 64),
            Map.entry("ultramarine_orb_elixir", 6),
            Map.entry("nimble_fabric", 64));

    /** stacksTo(1) 的物品（不含已带耐久的，耐久物品原版强制不可堆叠）。 */
    private static final Set<String> SINGLE = Set.of(
            "broom", "chair", "chair_show", "entity_id_copy", "favorability_tool_add", "favorability_tool_reduce",
            "favorability_tool_full", "film", "red_fox_scroll", "white_fox_scroll", "garage_kit", "kappa_compass",
            "maid_backpack_small", "maid_backpack_middle", "maid_backpack_big", "maid_beacon",
            "crafting_table_backpack", "ender_chest_backpack", "furnace_backpack", "tank_backpack", "maid_bed",
            "pink_maid_bed", "white_maid_bed", "black_maid_bed", "yellow_maid_bed", "blue_maid_bed",
            "green_maid_bed", "purple_maid_bed", "model_switcher", "item_magnet_bauble", "mute_bauble", "photo",
            "owner_conversion_tool", "picnic_basket", "servant_bell", "smart_slab_init", "smart_slab_empty",
            "smart_slab_has_maid", "substitute_jizo", "trumpet", "wireless_io",
            // ItemAdvancementIcon
            "change_chair_model", "change_maid_model", "maid_100_healthy", "kill_100", "kill_slime_300",
            "all_netherite_equipment", "kill_wither", "kill_dragon", "tacz_gun_icon");

    /** 原模组主创造标签与附加家具标签中的公开物品；其它资源物品只供配方/内部显示使用。 */
    private static final Set<String> CATEGORY_ITEMS = Set.of(
            "maid_spawn_egg", "fairy_spawn_egg", "hakurei_gohei", "sanae_gohei", "power_point",
            "smart_slab_empty", "smart_slab_init", "maid_backpack_small", "maid_backpack_middle",
            "maid_backpack_big", "crafting_table_backpack", "ender_chest_backpack", "furnace_backpack",
            "tank_backpack", "maid_bed", "substitute_jizo", "ultramarine_orb_elixir", "explosion_protect_bauble",
            "fire_protect_bauble", "projectile_protect_bauble", "magic_protect_bauble", "fall_protect_bauble",
            "drown_protect_bauble", "nimble_fabric", "item_magnet_bauble", "mute_bauble", "wireless_io",
            "trumpet", "red_fox_scroll", "white_fox_scroll", "servant_bell", "kappa_compass", "extinguisher",
            "gomoku", "cchess", "wchess", "keyboard", "bookshelf", "computer", "favorability_tool_add",
            "favorability_tool_reduce", "favorability_tool_full", "camera", "photo", "film", "chisel",
            "pink_maid_bed", "white_maid_bed", "black_maid_bed", "yellow_maid_bed", "blue_maid_bed",
            "green_maid_bed", "purple_maid_bed", "picnic_basket", "maid_beacon", "snack_cabinet", "shrine",
            "model_switcher", "chair_show", "broom", "scarecrow", "entity_id_copy", "owner_conversion_tool",
            "chair", "garage_kit");

    /** 原模组有贴图/物品模型、但没有 items/<id>.json 定义的特殊物品。 */
    private static final Set<String> EXTRA_ITEMS = Set.of(
            "crafting_table_backpack", "ender_chest_backpack", "furnace_backpack", "tank_backpack");

    private static final Map<String, String> RARITY = Map.of(
            "favorability_tool_add", "epic",
            "favorability_tool_reduce", "epic",
            "favorability_tool_full", "epic",
            "owner_conversion_tool", "epic",
            "substitute_jizo", "epic",
            "smart_slab_init", "rare",
            "smart_slab_empty", "rare",
            "smart_slab_has_maid", "rare");

    private ItemTable() {
    }

    static void generateItems(ContentPackGenerator gen, JsonObject out) throws IOException {
        Path dir = gen.assets().resolve("items");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String id = file.getFileName().toString().replace(".json", "");
                if (FurnitureTable.isFurniture(id)) {
                    continue;
                }
                out.add(ContentPackGenerator.id(id), item(gen, id));
                if (CATEGORY_ITEMS.contains(id)) {
                    gen.addCategoryItem(id);
                }
            }
        }
        for (String id : EXTRA_ITEMS) {
            if (!out.has(ContentPackGenerator.id(id))) {
                out.add(ContentPackGenerator.id(id), item(gen, id));
                gen.addCategoryItem(id);
            }
        }
    }

    /** 单个物品定义；家具表也复用它来写物品本体部分。 */
    static JsonObject item(ContentPackGenerator gen, String id) throws IOException {
        JsonObject components = new JsonObject();
        Integer durability = DURABILITY.get(id);
        if (durability != null) {
            components.addProperty("minecraft:max_damage", durability);
            components.addProperty("minecraft:damage", 0);
            components.addProperty("minecraft:max_stack_size", 1);
        } else if (SINGLE.contains(id)) {
            components.addProperty("minecraft:max_stack_size", 1);
        }
        String rarity = RARITY.get(id);
        if (rarity != null) {
            components.addProperty("minecraft:rarity", rarity);
        }

        JsonObject data = new JsonObject();
        data.addProperty("item_name", gen.itemName(id));
        if (!components.isEmpty()) {
            data.add("components", components);
        }

        JsonObject item = new JsonObject();
        item.add("data", data);
        item.add("model", gen.itemModel(id));
        if (id.endsWith("_maid_bed")) {
            // 床染色配方以 #touhou_little_maid:maid_bed 作原料
            JsonObject settings = new JsonObject();
            com.google.gson.JsonArray tags = new com.google.gson.JsonArray();
            tags.add(ContentPackGenerator.id("maid_bed"));
            settings.add("tags", tags);
            item.add("settings", settings);
        }
        return item;
    }
}
