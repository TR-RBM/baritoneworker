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

/**
 * The sort scheme: which items belong in which chest. A chest's "tags" come from
 * two places — signs placed on/by it (read live), and this JSON file
 * ({@code config/baritoneworker/sortscheme.json}) which can pre-define many chests
 * by position and define custom groups.
 *
 * <p>A tag matches an item if it is: an {@link ItemCategories} keyword (e.g.
 * {@code ores}, {@code logs}, {@code food}); a custom group named in this file;
 * or a literal item id (namespace optional, e.g. {@code diamond} or
 * {@code minecraft:diamond}).
 *
 * <pre>
 * {
 *   "groups":  { "smeltables": ["raw_iron", "raw_gold", "raw_copper"] },
 *   "chests":  [ { "pos": [10, 64, 20], "tags": ["ores", "smeltables"] } ]
 * }
 * </pre>
 */
public final class SortScheme {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/sortscheme");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_TAG_DEPTH = 8;

    /** group name -> member tokens (item ids, category keywords, or other groups). */
    private final Map<String, List<String>> groups = new LinkedHashMap<>();
    /** "x,y,z" -> tags assigned to that chest position. */
    private final Map<String, List<String>> chestTags = new LinkedHashMap<>();

    // ----------------------------------------------------------- file access

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("sortscheme.json");
    }

    /** (Re)load the scheme from disk; missing file just means "signs only". */
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

    // ------------------------------------------------------------- queries

    /** Tags pinned to this position in the JSON (empty if none). */
    public List<String> pinnedTags(BlockPos pos) {
        return chestTags.getOrDefault(key(pos.getX(), pos.getY(), pos.getZ()), List.of());
    }

    /** Assign (overwrite) the tags for a chest position and persist. */
    public void assign(BlockPos pos, List<String> tags) {
        chestTags.put(key(pos.getX(), pos.getY(), pos.getZ()), lower(tags));
        save();
    }

    public boolean hasAnyChestPins() {
        return !chestTags.isEmpty();
    }

    /** True if a chest carrying {@code tags} should hold {@code item}. */
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
        // Literal item id (namespace optional).
        return ItemNames.idOf(item).equals(tag) || ItemNames.pathOf(item).equals(tag)
                || ("minecraft:" + ItemNames.pathOf(item)).equals(tag);
    }

    // ------------------------------------------------------------- helpers

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    // ----------------------------------------------------------- json shape

    private static final class SchemeJson {
        Map<String, List<String>> groups;
        List<ChestJson> chests;
    }

    private static final class ChestJson {
        List<Integer> pos;
        List<String> tags;
    }
}
