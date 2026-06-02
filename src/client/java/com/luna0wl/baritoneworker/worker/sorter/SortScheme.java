package com.luna0wl.baritoneworker.worker.sorter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.luna0wl.baritoneworker.worker.common.ItemCategories;
import com.luna0wl.baritoneworker.worker.common.ItemNames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SortScheme {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/sortscheme");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_TAG_DEPTH = 8;

    private final Map<String, List<String>> groups = new LinkedHashMap<>();

    private final Map<String, List<String>> chestTags = new LinkedHashMap<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("sortscheme.json");
    }

    public void load() {
        groups.clear();
        chestTags.clear();
        Path f = file();
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            SchemeJson json = GSON.fromJson(r, SchemeJson.class);
            if (json == null) return;
            if (json.groups != null) {
                json.groups.forEach((k, v) -> groups.put(k.toLowerCase(Locale.ROOT), lower(v)));
            }
            if (json.chests != null) {
                for (ChestJson c : json.chests) {
                    if (c == null || c.pos == null || c.pos.size() != 3 || c.tags == null) continue;
                    chestTags.put(key(c.pos.get(0), c.pos.get(1), c.pos.get(2)), lower(c.tags));
                }
            }
            LOG.info("Loaded sort scheme: {} group(s), {} pinned chest(s).", groups.size(), chestTags.size());
        } catch (Exception e) {
            LOG.warn("Could not load sort scheme: {}", e.toString());
        }
    }

    public void save() {
        SchemeJson json = new SchemeJson();
        json.groups = new LinkedHashMap<>(groups);
        json.chests = new ArrayList<>();
        chestTags.forEach((k, tags) -> {
            String[] p = k.split(",");
            ChestJson c = new ChestJson();
            c.pos = List.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            c.tags = tags;
            json.chests.add(c);
        });
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file())) {
                GSON.toJson(json, w);
            }
        } catch (IOException e) {
            LOG.warn("Could not save sort scheme: {}", e.toString());
        }
    }

    public List<String> pinnedTags(BlockPos pos) {
        return chestTags.getOrDefault(key(pos.getX(), pos.getY(), pos.getZ()), List.of());
    }

    public void assign(BlockPos pos, List<String> tags) {
        chestTags.put(key(pos.getX(), pos.getY(), pos.getZ()), lower(tags));
        save();
    }

    public boolean hasAnyChestPins() {
        return !chestTags.isEmpty();
    }

    public boolean accepts(List<String> tags, Item item) {
        for (String tag : tags) {
            if (tagMatches(tag, item, 0)) return true;
        }
        return false;
    }

    private boolean tagMatches(String tag, Item item, int depth) {
        if (depth > MAX_TAG_DEPTH) return false;
        tag = tag.trim().toLowerCase(Locale.ROOT);
        if (tag.isEmpty()) return false;
        List<String> group = groups.get(tag);
        if (group != null) {
            for (String member : group) {
                if (tagMatches(member, item, depth + 1)) return true;
            }
            return false;
        }
        if (ItemCategories.isCategory(tag)) {
            return ItemCategories.matches(tag, item);
        }

        return ItemNames.idOf(item).equals(tag) || ItemNames.pathOf(item).equals(tag)
                || ("minecraft:" + ItemNames.pathOf(item)).equals(tag);
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static final class SchemeJson {
        Map<String, List<String>> groups;
        List<ChestJson> chests;
    }

    private static final class ChestJson {
        List<Integer> pos;
        List<String> tags;
    }
}
