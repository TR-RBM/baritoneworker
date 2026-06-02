package com.luna0wl.baritoneworker.worker.lumber;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Woods {

    private Woods() {}

    public record Flavour(String name, List<Block> blocks, Item sapling) {}

    public static final Map<String, Flavour> FLAVOURS = new LinkedHashMap<>();

    static {
        add("oak", List.of(Blocks.OAK_LOG, Blocks.OAK_WOOD), Items.OAK_SAPLING);
        add("birch", List.of(Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD), Items.BIRCH_SAPLING);
        add("spruce", List.of(Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD), Items.SPRUCE_SAPLING);
        add("jungle", List.of(Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD), Items.JUNGLE_SAPLING);
        add("acacia", List.of(Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD), Items.ACACIA_SAPLING);
        add("dark_oak", List.of(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD), Items.DARK_OAK_SAPLING);
        add("mangrove", List.of(Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD), Items.MANGROVE_PROPAGULE);
        add("cherry", List.of(Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD), Items.CHERRY_SAPLING);
        add("pale_oak", List.of(Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_WOOD), Items.PALE_OAK_SAPLING);
    }

    private static void add(String name, List<Block> blocks, Item sapling) {
        FLAVOURS.put(name, new Flavour(name, blocks, sapling));
    }

    public static boolean isFlavour(String name) {
        return FLAVOURS.containsKey(name);
    }

    public static Set<String> names() {
        return FLAVOURS.keySet();
    }

    public static Block[] targetBlocks(Set<String> selected) {
        List<Block> out = new ArrayList<>();
        for (Flavour f : FLAVOURS.values()) {
            if (selected.isEmpty() || selected.contains(f.name())) {
                out.addAll(f.blocks());
            }
        }
        return out.toArray(new Block[0]);
    }

    public static Set<Item> saplings(Set<String> selected) {
        Set<Item> out = new LinkedHashSet<>();
        for (Flavour f : FLAVOURS.values()) {
            if (selected.isEmpty() || selected.contains(f.name())) {
                out.add(f.sapling());
            }
        }
        return out;
    }

    public static Set<Item> allSaplings() {
        Set<Item> out = new LinkedHashSet<>();
        for (Flavour f : FLAVOURS.values()) out.add(f.sapling());
        return out;
    }
}
