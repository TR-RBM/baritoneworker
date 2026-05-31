package com.luna0wl.baritoneworker.worker.lumber;

import com.luna0wl.baritoneworker.worker.common.ItemNames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Settings for the lumber worker, plus the captured chest area. Persisted to
 * {@code config/baritonelumber.properties}. Mirrors the miner's config but for
 * wood: an axe instead of a pickaxe, selected wood flavours instead of ore
 * groups, and an optional sapling-replant toggle.
 */
public final class LumberConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritonelumber/config");

    /** Essentials-style home the worker teleports to for chopping (default {@code wood}). */
    public String workHome = "wood";
    /** Essentials-style home where the chests live (default {@code Home}). */
    public String baseHome = "Home";

    /** Which axe to keep stocked / chop with. */
    public Item axeItem = Items.DIAMOND_AXE;
    /** Which food to keep stocked (for a separate auto-eat mod). */
    public Item foodItem = Items.BAKED_POTATO;

    public int targetAxes = 1;
    public int targetFood = 64;

    /** Return to base once this many or fewer free slots remain (0..35). */
    public int stopAtFreeSlots = 1;

    // --- teleport handling (same warm-up dance as the miner) ---
    public int teleportTimeoutTicks = 200;
    public int teleportSettleTicks = 25;
    public double teleportMoveThreshold = 2.0;

    public double skipTeleportRange = 6.0;
    public int chestAreaMargin = 8;

    /** Cached landing spots so we can skip redundant teleports (null = unknown). */
    public int[] workPos;
    public int[] homePos;

    public int clickDelayTicks = 3;
    public int commandGapTicks = 15;
    public int chestPathTimeoutTicks = 1200;

    /** Ticks Baritone's mine may sit idle before we re-issue it. */
    public int harvestIdleReissueTicks = 60;

    // --- optional sapling replant ---
    /** Master toggle: replant a sapling on cleared ground between trees. Off by default. */
    public boolean replant = false;
    /** Saplings to keep on hand when replanting; restock tops up to this. */
    public int targetSaplings = 16;
    /** How far around the player to look for a spot to replant. */
    public int replantRadius = 8;
    /** Ticks between replant scans while harvesting. */
    public int replantScanInterval = 20;

    // --- tree-at-a-time harvesting ---
    /** How far to look for the next tree to fell. */
    public int treeScanRadius = 24;
    /** Stay within this many blocks of the current tree until every log is gone. */
    public int treeFollowRadius = 10;
    /** Give up on a tree's remaining (unreachable) logs after this many idle ticks. */
    public int treeStuckTimeoutTicks = 400;
    /** Flood-fill cap when collecting one tree's connected log/wood blocks. */
    public int maxTreeBlocks = 512;

    /** Selected wood flavours (see {@link Woods}); empty = every flavour. */
    public final Set<String> woodFlavours = new LinkedHashSet<>();

    /** Captured chest-area boxes; each is {minX,minY,minZ,maxX,maxY,maxZ}. */
    public final List<int[]> chestBoxes = new ArrayList<>();

    /** Effective never-deposit set = axe + food + (saplings, if replanting). */
    public final Set<Item> keepItems = new LinkedHashSet<>();

    public LumberConfig() {
        rebuildKeep();
    }

    public void rebuildKeep() {
        keepItems.clear();
        keepItems.add(axeItem);
        keepItems.add(foodItem);
        if (replant) keepItems.addAll(Woods.saplings(woodFlavours));
    }

    public void setAxeItem(Item item) {
        axeItem = item;
        rebuildKeep();
    }

    public void setFoodItem(Item item) {
        foodItem = item;
        rebuildKeep();
    }

    public void setReplant(boolean on) {
        replant = on;
        rebuildKeep();
    }

    // ------------------------------------------------------------------ area

    public void setArea(List<int[]> boxes) {
        chestBoxes.clear();
        chestBoxes.addAll(boxes);
    }

    public void clearArea() {
        chestBoxes.clear();
    }

    public boolean hasArea() {
        return !chestBoxes.isEmpty();
    }

    // ----------------------------------------------------------- persistence

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("lumber.properties");
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("workHome", workHome);
        p.setProperty("baseHome", baseHome);
        p.setProperty("axeItem", ItemNames.idOf(axeItem));
        p.setProperty("foodItem", ItemNames.idOf(foodItem));
        p.setProperty("targetAxes", Integer.toString(targetAxes));
        p.setProperty("targetFood", Integer.toString(targetFood));
        p.setProperty("stopAtFreeSlots", Integer.toString(stopAtFreeSlots));
        p.setProperty("teleportTimeoutTicks", Integer.toString(teleportTimeoutTicks));
        p.setProperty("teleportSettleTicks", Integer.toString(teleportSettleTicks));
        p.setProperty("teleportMoveThreshold", Double.toString(teleportMoveThreshold));
        p.setProperty("skipTeleportRange", Double.toString(skipTeleportRange));
        p.setProperty("chestAreaMargin", Integer.toString(chestAreaMargin));
        p.setProperty("clickDelayTicks", Integer.toString(clickDelayTicks));
        p.setProperty("commandGapTicks", Integer.toString(commandGapTicks));
        p.setProperty("chestPathTimeoutTicks", Integer.toString(chestPathTimeoutTicks));
        p.setProperty("harvestIdleReissueTicks", Integer.toString(harvestIdleReissueTicks));
        p.setProperty("replant", Boolean.toString(replant));
        p.setProperty("targetSaplings", Integer.toString(targetSaplings));
        p.setProperty("replantRadius", Integer.toString(replantRadius));
        p.setProperty("replantScanInterval", Integer.toString(replantScanInterval));
        p.setProperty("treeScanRadius", Integer.toString(treeScanRadius));
        p.setProperty("treeFollowRadius", Integer.toString(treeFollowRadius));
        p.setProperty("treeStuckTimeoutTicks", Integer.toString(treeStuckTimeoutTicks));
        p.setProperty("maxTreeBlocks", Integer.toString(maxTreeBlocks));
        p.setProperty("woodFlavours", String.join(",", woodFlavours));
        p.setProperty("workPos", serializePos(workPos));
        p.setProperty("homePos", serializePos(homePos));
        p.setProperty("chestBoxes", serializeBoxes());
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "BaritoneWorker lumber settings");
            }
        } catch (IOException e) {
            LOG.warn("Could not save config: {}", e.toString());
        }
    }

    public void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            LOG.warn("Could not load config: {}", e.toString());
            return;
        }
        workHome = p.getProperty("workHome", workHome);
        baseHome = p.getProperty("baseHome", baseHome);
        Item ai = ItemNames.byId(p.getProperty("axeItem", ""));
        if (ai != null) axeItem = ai;
        Item fi = ItemNames.byId(p.getProperty("foodItem", ""));
        if (fi != null) foodItem = fi;
        targetAxes = parseInt(p, "targetAxes", targetAxes);
        targetFood = parseInt(p, "targetFood", targetFood);
        stopAtFreeSlots = parseInt(p, "stopAtFreeSlots", stopAtFreeSlots);
        teleportTimeoutTicks = parseInt(p, "teleportTimeoutTicks", teleportTimeoutTicks);
        teleportSettleTicks = parseInt(p, "teleportSettleTicks", teleportSettleTicks);
        teleportMoveThreshold = parseDouble(p, "teleportMoveThreshold", teleportMoveThreshold);
        skipTeleportRange = parseDouble(p, "skipTeleportRange", skipTeleportRange);
        chestAreaMargin = parseInt(p, "chestAreaMargin", chestAreaMargin);
        clickDelayTicks = parseInt(p, "clickDelayTicks", clickDelayTicks);
        commandGapTicks = parseInt(p, "commandGapTicks", commandGapTicks);
        chestPathTimeoutTicks = parseInt(p, "chestPathTimeoutTicks", chestPathTimeoutTicks);
        harvestIdleReissueTicks = parseInt(p, "harvestIdleReissueTicks", harvestIdleReissueTicks);
        replant = Boolean.parseBoolean(p.getProperty("replant", Boolean.toString(replant)));
        targetSaplings = parseInt(p, "targetSaplings", targetSaplings);
        replantRadius = parseInt(p, "replantRadius", replantRadius);
        replantScanInterval = parseInt(p, "replantScanInterval", replantScanInterval);
        treeScanRadius = parseInt(p, "treeScanRadius", treeScanRadius);
        treeFollowRadius = parseInt(p, "treeFollowRadius", treeFollowRadius);
        treeStuckTimeoutTicks = parseInt(p, "treeStuckTimeoutTicks", treeStuckTimeoutTicks);
        maxTreeBlocks = parseInt(p, "maxTreeBlocks", maxTreeBlocks);
        woodFlavours.clear();
        for (String g : p.getProperty("woodFlavours", "").split(",")) {
            if (!g.isBlank() && Woods.isFlavour(g.trim())) woodFlavours.add(g.trim());
        }
        workPos = deserializePos(p.getProperty("workPos", ""));
        homePos = deserializePos(p.getProperty("homePos", ""));
        deserializeBoxes(p.getProperty("chestBoxes", ""));
        rebuildKeep();
    }

    private static int parseInt(Properties p, String key, int fallback) {
        try {
            String v = p.getProperty(key);
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(Properties p, String key, double fallback) {
        try {
            String v = p.getProperty(key);
            return v == null ? fallback : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String serializePos(int[] pos) {
        return pos == null ? "" : pos[0] + "," + pos[1] + "," + pos[2];
    }

    private static int[] deserializePos(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        if (parts.length != 3) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String serializeBoxes() {
        StringBuilder sb = new StringBuilder();
        for (int[] b : chestBoxes) {
            if (sb.length() > 0) sb.append(';');
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(',');
                sb.append(b[i]);
            }
        }
        return sb.toString();
    }

    private void deserializeBoxes(String s) {
        chestBoxes.clear();
        if (s == null || s.isBlank()) return;
        for (String boxStr : s.split(";")) {
            String[] parts = boxStr.split(",");
            if (parts.length != 6) continue;
            try {
                int[] b = new int[6];
                for (int i = 0; i < 6; i++) b[i] = Integer.parseInt(parts[i].trim());
                chestBoxes.add(b);
            } catch (NumberFormatException ignored) {
                // skip malformed box
            }
        }
    }
}
