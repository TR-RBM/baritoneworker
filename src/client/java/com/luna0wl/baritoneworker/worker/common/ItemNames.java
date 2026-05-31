package com.luna0wl.baritoneworker.worker.common;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** Resolve items to/from their registry id (e.g. {@code minecraft:diamond_pickaxe}). */
public final class ItemNames {

    private ItemNames() {}

    public static String idOf(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "minecraft:air" : id.toString();
    }

    /** The registry path only (e.g. {@code diamond_pickaxe}), namespace stripped. */
    public static String pathOf(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "air" : id.getPath();
    }

    /** Item for a registry name (namespace optional), or null if unknown/invalid. */
    public static Item byId(String name) {
        if (name == null || name.isBlank()) return null;
        Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        // The item registry is defaulted to AIR, so AIR means "not found"
        // unless the caller literally asked for air.
        if (item == Items.AIR && !id.getPath().equals("air")) return null;
        return item;
    }
}
