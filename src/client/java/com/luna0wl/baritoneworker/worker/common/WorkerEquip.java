package com.luna0wl.baritoneworker.worker.common;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

public final class WorkerEquip {

    public static final class Entry {
        private final boolean category;
        private final String key;
        private final Item item;
        private int count;

        private Entry(boolean category, String key, Item item, int count) {
            this.category = category;
            this.key = key;
            this.item = item;
            this.count = count;
        }

        public boolean isCategory() {
            return category;
        }

        public Item item() {
            return item;
        }

        public int count() {
            return count;
        }

        public boolean matches(Item it) {
            return category ? ItemCategories.matches(key, it) : it == item;
        }

        public String token() {
            return category ? key : ItemNames.idOf(item);
        }

        @Override
        public String toString() {
            return token() + "×" + count;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean add(String token, int count) {
        if (token == null) return false;
        String key = token.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) return false;
        if (count < 0) count = 0;
        if (ItemCategories.isCategory(key)) {
            Entry existing = find(true, key, null);
            if (existing != null) existing.count = count;
            else entries.add(new Entry(true, key, null, count));
            return true;
        }
        Item it = ItemNames.byId(key);
        if (it == null) return false;
        Entry existing = find(false, null, it);
        if (existing != null) existing.count = count;
        else entries.add(new Entry(false, ItemNames.idOf(it), it, count));
        return true;
    }

    public void set(String token, int count) {
        add(token, count);
    }

    public boolean remove(String token) {
        if (token == null) return false;
        String key = token.trim().toLowerCase(java.util.Locale.ROOT);
        Item it = ItemCategories.isCategory(key) ? null : ItemNames.byId(key);
        return entries.removeIf(e -> e.category
                ? (e.key.equals(key))
                : (it != null && e.item == it));
    }

    public void clear() {
        entries.clear();
    }

    private Entry find(boolean category, String key, Item item) {
        for (Entry e : entries) {
            if (e.category == category && (category ? e.key.equals(key) : e.item == item)) return e;
        }
        return null;
    }

    public boolean isKept(Item it) {
        for (Entry e : entries) {
            if (e.count > 0 && e.matches(it)) return true;
        }
        return false;
    }

    public int deficit(Inventory inv, Entry e) {
        int have = e.category
                ? ContainerService.countMatching(inv, e::matches)
                : ContainerService.countItem(inv, e.item);
        int need = e.count - have;
        return Math.max(0, need);
    }

    public boolean fullyStocked(Inventory inv) {
        for (Entry e : entries) {
            if (deficit(inv, e) > 0) return false;
        }
        return true;
    }

    public int nextWithdrawSlot(AbstractContainerMenu menu, Inventory inv) {
        for (Entry e : entries) {
            if (deficit(inv, e) <= 0) continue;
            int slot = e.category
                    ? ContainerService.nextWithdrawSlotMatching(menu, e::matches)
                    : ContainerService.nextWithdrawSlot(menu, e.item);
            if (slot != -1) return slot;
        }
        return -1;
    }

    public int nextDepositSlot(AbstractContainerMenu menu) {
        return ContainerService.nextDepositSlotMatching(menu, it -> !isKept(it));
    }

    public boolean hasDepositable(AbstractContainerMenu menu) {
        return nextDepositSlot(menu) != -1;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.token()).append(':').append(e.count);
        }
        return sb.toString();
    }

    public static WorkerEquip deserialize(String s) {
        WorkerEquip eq = new WorkerEquip();
        if (s == null || s.isBlank()) return eq;
        for (String part : s.split(",")) {
            String chunk = part.trim();
            if (chunk.isEmpty()) continue;
            int colon = chunk.lastIndexOf(':');
            if (colon <= 0 || colon == chunk.length() - 1) continue;
            String token = chunk.substring(0, colon);
            int count;
            try {
                count = Integer.parseInt(chunk.substring(colon + 1).trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            eq.add(token, count);
        }
        return eq;
    }
}
