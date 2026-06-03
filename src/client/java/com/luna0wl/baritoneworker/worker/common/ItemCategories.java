package com.luna0wl.baritoneworker.worker.common;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class ItemCategories {

    private ItemCategories() {}

    private static final Map<String, Predicate<Item>> CATS = new LinkedHashMap<>();

    static {
        CATS.put("logs", tag(ItemTags.LOGS));
        CATS.put("wood", or(tag(ItemTags.LOGS), pathContains("_wood")));
        CATS.put("planks", tag(ItemTags.PLANKS));
        CATS.put("saplings", tag(ItemTags.SAPLINGS));
        CATS.put("wool", tag(ItemTags.WOOL));
        CATS.put("food", ItemCategories::isFood);
        CATS.put("ores", ItemCategories::isOreLike);
        CATS.put("pickaxe", pathEndsWith("_pickaxe"));
        CATS.put("axe", pathEndsWith("_axe"));
        CATS.put("shovel", pathEndsWith("_shovel"));
        CATS.put("hoe", pathEndsWith("_hoe"));
        CATS.put("sword", pathEndsWith("_sword"));
        CATS.put("tools", ItemCategories::isTool);
        CATS.put("weapons", ItemCategories::isWeapon);
        CATS.put("armor", ItemCategories::isArmor);
        CATS.put("redstone", ItemCategories::isRedstone);
        CATS.put("dyes", pathEndsWith("_dye"));
        CATS.put("building", ItemCategories::isBuilding);
        CATS.put("misc", item -> true);
    }

    public static boolean isCategory(String name) {
        return CATS.containsKey(name);
    }

    public static Set<String> names() {
        return CATS.keySet();
    }

    public static boolean matches(String name, Item item) {
        Predicate<Item> p = CATS.get(name);
        return p != null && p.test(item);
    }

    private static Predicate<Item> tag(TagKey<Item> key) {
        return item -> item.builtInRegistryHolder().is(key);
    }

    @SafeVarargs
    private static Predicate<Item> or(Predicate<Item>... ps) {
        return item -> {
            for (Predicate<Item> p : ps) if (p.test(item)) return true;
            return false;
        };
    }

    private static Predicate<Item> pathContains(String needle) {
        return item -> ItemNames.pathOf(item).contains(needle);
    }

    private static Predicate<Item> pathEndsWith(String suffix) {
        return item -> ItemNames.pathOf(item).endsWith(suffix);
    }

    private static boolean isFood(Item item) {
        return item.getDefaultInstance().has(DataComponents.FOOD);
    }

    private static boolean isOreLike(Item item) {
        String p = ItemNames.pathOf(item);
        if (p.endsWith("_ore") || p.startsWith("raw_")) return true;
        return switch (p) {
            case "coal", "diamond", "emerald", "lapis_lazuli", "quartz", "redstone",
                 "amethyst_shard", "iron_ingot", "gold_ingot", "copper_ingot",
                 "iron_nugget", "gold_nugget", "netherite_scrap", "ancient_debris" -> true;
            default -> false;
        };
    }

    private static boolean isTool(Item item) {
        String p = ItemNames.pathOf(item);
        return p.endsWith("_pickaxe") || p.endsWith("_axe") || p.endsWith("_shovel")
                || p.endsWith("_hoe") || p.equals("shears") || p.equals("flint_and_steel")
                || p.equals("fishing_rod") || p.equals("brush");
    }

    private static boolean isWeapon(Item item) {
        String p = ItemNames.pathOf(item);
        return p.endsWith("_sword") || p.equals("bow") || p.equals("crossbow")
                || p.equals("trident") || p.equals("mace") || p.equals("arrow")
                || p.equals("spectral_arrow") || p.equals("tipped_arrow");
    }

    private static boolean isArmor(Item item) {
        String p = ItemNames.pathOf(item);
        return p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings")
                || p.endsWith("_boots") || p.equals("shield") || p.equals("elytra")
                || p.endsWith("_horse_armor") || p.equals("turtle_helmet");
    }

    private static boolean isRedstone(Item item) {
        String p = ItemNames.pathOf(item);
        if (p.contains("redstone")) return true;
        return switch (p) {
            case "repeater", "comparator", "piston", "sticky_piston", "observer",
                 "dropper", "dispenser", "hopper", "lever", "tripwire_hook",
                 "daylight_detector", "target", "redstone_lamp", "note_block" -> true;
            default -> false;
        };
    }

    private static boolean isBuilding(Item item) {
        String p = ItemNames.pathOf(item);
        if (p.endsWith("_stairs") || p.endsWith("_slab") || p.endsWith("_wall")
                || p.endsWith("_planks") || p.endsWith("_bricks") || p.endsWith("_brick")
                || p.endsWith("_concrete") || p.endsWith("_terracotta")) return true;
        return switch (p) {
            case "stone", "cobblestone", "deepslate", "cobbled_deepslate", "dirt",
                 "grass_block", "sand", "gravel", "glass", "stone_bricks",
                 "smooth_stone", "andesite", "diorite", "granite", "netherrack",
                 "blackstone", "tuff", "calcite", "dripstone_block" -> true;
            default -> false;
        };
    }
}
