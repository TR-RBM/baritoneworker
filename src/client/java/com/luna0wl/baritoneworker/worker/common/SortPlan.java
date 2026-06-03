package com.luna0wl.baritoneworker.worker.common;

import com.luna0wl.baritoneworker.worker.sorter.SortScheme;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class SortPlan {

    private final SortScheme scheme;
    private final Map<BlockPos, List<String>> tagsByChest = new HashMap<>();
    private boolean enabled;

    public SortPlan(SortScheme scheme) {
        this.scheme = scheme;
    }

    public void build(Minecraft mc, List<int[]> boxes, boolean includeEnderChests, boolean enabled) {
        tagsByChest.clear();
        this.enabled = enabled;
        if (!enabled) return;
        scheme.load();
        for (BlockPos pos : ContainerService.scanChests(mc.level, boxes, mc.player.blockPosition(), includeEnderChests)) {
            List<String> tags = new ArrayList<>(scheme.pinnedTags(pos));
            for (String sign : ContainerService.readChestTags(mc.level, pos)) {
                if (!tags.contains(sign)) tags.add(sign);
            }
            tagsByChest.put(pos, tags);
        }
    }

    public void clear() {
        tagsByChest.clear();
        enabled = false;
    }

    public boolean active() {
        return enabled && !tagsByChest.isEmpty();
    }

    public int taggedChests() {
        int n = 0;
        for (List<String> tags : tagsByChest.values()) {
            if (!tags.isEmpty()) n++;
        }
        return n;
    }

    public int depositSlot(AbstractContainerMenu menu, BlockPos current, Predicate<Item> keep) {
        if (!active()) {
            return ContainerService.nextDepositSlotMatching(menu, it -> !keep.test(it));
        }
        List<String> here = current == null ? List.of() : tagsByChest.getOrDefault(current, List.of());
        return ContainerService.nextDepositSlotMatching(menu, it ->
                !keep.test(it) && (scheme.accepts(here, it) || !acceptedAnywhere(it)));
    }

    private boolean acceptedAnywhere(Item item) {
        for (List<String> tags : tagsByChest.values()) {
            if (scheme.accepts(tags, item)) return true;
        }
        return false;
    }
}
