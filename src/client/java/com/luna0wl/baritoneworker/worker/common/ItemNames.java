package com.luna0wl.baritoneworker.worker.common;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;

public final class ItemNames {

    private ItemNames() {}

    public static String idOf(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "minecraft:air" : id.toString();
    }

    public static String pathOf(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "air" : id.getPath();
    }

    public static Item byId(String name) {
        if (name == null || name.isBlank()) return null;
        Identifier id = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.getValue(id);

        if (item == Items.AIR && !id.getPath().equals("air")) return null;
        return item;
    }
}
