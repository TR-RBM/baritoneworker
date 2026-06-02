package com.luna0wl.baritoneworker.worker.miner;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Ores {

    private Ores() {}

    public static final Map<String, List<Block>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("coal", List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));
        GROUPS.put("iron", List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE));
        GROUPS.put("copper", List.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
        GROUPS.put("gold", List.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE));
        GROUPS.put("redstone", List.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE));
        GROUPS.put("lapis", List.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE));
        GROUPS.put("diamond", List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE));
        GROUPS.put("emerald", List.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE));

        GROUPS.put("nether_gold", List.of(Blocks.NETHER_GOLD_ORE));
        GROUPS.put("quartz", List.of(Blocks.NETHER_QUARTZ_ORE));
        GROUPS.put("debris", List.of(Blocks.ANCIENT_DEBRIS));
    }

    public static boolean isGroup(String name) {
        return GROUPS.containsKey(name);
    }

    public static Set<String> groupNames() {
        return GROUPS.keySet();
    }

    public static Set<Block> targetSet(Set<String> excludedGroups) {
        Set<Block> out = new LinkedHashSet<>();
        for (Map.Entry<String, List<Block>> e : GROUPS.entrySet()) {
            if (!excludedGroups.contains(e.getKey())) {
                out.addAll(e.getValue());
            }
        }
        return out;
    }
}
