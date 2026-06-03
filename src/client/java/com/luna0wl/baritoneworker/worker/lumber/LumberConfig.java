package com.luna0wl.baritoneworker.worker.lumber;

import com.luna0wl.baritoneworker.worker.common.WorkerEquip;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
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

public final class LumberConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritonelumber/config");

    public String workHome = "wood";

    public String baseHome = "Home";

    public WorkerEquip equip = defaultEquip();

    public String restockHome = "";

    public int stopAtFreeSlots = 1;

    public int teleportTimeoutTicks = 200;
    public int teleportSettleTicks = 25;
    public double teleportMoveThreshold = 2.0;

    public double skipTeleportRange = 6.0;
    public int chestAreaMargin = 8;

    public int[] workPos;
    public int[] homePos;

    public int clickDelayTicks = 3;
    public int commandGapTicks = 15;
    public int chestPathTimeoutTicks = 1200;

    public int harvestIdleReissueTicks = 60;

    public boolean replant = false;

    public int targetSaplings = 16;

    public int replantRadius = 8;

    public int replantScanInterval = 20;

    public int treeScanRadius = 24;

    public int treeFollowRadius = 10;

    public int treeStuckTimeoutTicks = 400;

    public int maxTreeBlocks = 512;

    public final Set<String> woodFlavours = new LinkedHashSet<>();

    public final List<int[]> chestBoxes = new ArrayList<>();

    public final List<int[]> restockBoxes = new ArrayList<>();

    public boolean includeEnderChests = false;

    public boolean useSorter = false;

    private static WorkerEquip defaultEquip() {
        WorkerEquip eq = new WorkerEquip();
        eq.add("minecraft:diamond_axe", 1);
        eq.add("minecraft:baked_potato", 64);
        return eq;
    }

    public void setReplant(boolean on) {
        replant = on;
    }

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

    public void setRestock(List<int[]> boxes) {
        restockBoxes.clear();
        restockBoxes.addAll(boxes);
    }

    public void clearRestock() {
        restockBoxes.clear();
    }

    public boolean hasRestock() {
        return !restockBoxes.isEmpty();
    }

    public List<int[]> depositArea() {
        return chestBoxes;
    }

    public List<int[]> restockArea() {
        return hasRestock() ? restockBoxes : chestBoxes;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("lumber.properties");
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("workHome", workHome);
        p.setProperty("baseHome", baseHome);
        p.setProperty("restockHome", restockHome);
        p.setProperty("keep", equip.serialize());
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
        p.setProperty("includeEnderChests", Boolean.toString(includeEnderChests));
        p.setProperty("useSorter", Boolean.toString(useSorter));
        p.setProperty("workPos", serializePos(workPos));
        p.setProperty("homePos", serializePos(homePos));
        p.setProperty("chestBoxes", serializeBoxes(chestBoxes));
        p.setProperty("restockBoxes", serializeBoxes(restockBoxes));
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
        restockHome = p.getProperty("restockHome", restockHome);
        String keepStr = p.getProperty("keep");
        if (keepStr != null) {
            equip = WorkerEquip.deserialize(keepStr);
        } else {
            equip = migrateLegacyEquip(p);
        }
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
        includeEnderChests = Boolean.parseBoolean(p.getProperty("includeEnderChests", Boolean.toString(includeEnderChests)));
        useSorter = Boolean.parseBoolean(p.getProperty("useSorter", Boolean.toString(useSorter)));
        workPos = deserializePos(p.getProperty("workPos", ""));
        homePos = deserializePos(p.getProperty("homePos", ""));
        deserializeBoxes(chestBoxes, p.getProperty("chestBoxes", ""));
        deserializeBoxes(restockBoxes, p.getProperty("restockBoxes", ""));
    }

    private static WorkerEquip migrateLegacyEquip(Properties p) {
        WorkerEquip eq = new WorkerEquip();
        eq.add(p.getProperty("axeItem", "minecraft:diamond_axe"), parseInt(p, "targetAxes", 1));
        eq.add(p.getProperty("foodItem", "minecraft:baked_potato"), parseInt(p, "targetFood", 64));
        return eq;
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

    private static String serializeBoxes(List<int[]> src) {
        StringBuilder sb = new StringBuilder();
        for (int[] b : src) {
            if (sb.length() > 0) sb.append(';');
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(',');
                sb.append(b[i]);
            }
        }
        return sb.toString();
    }

    private static void deserializeBoxes(List<int[]> dst, String s) {
        dst.clear();
        if (s == null || s.isBlank()) return;
        for (String boxStr : s.split(";")) {
            String[] parts = boxStr.split(",");
            if (parts.length != 6) continue;
            try {
                int[] b = new int[6];
                for (int i = 0; i < 6; i++) b[i] = Integer.parseInt(parts[i].trim());
                dst.add(b);
            } catch (NumberFormatException ignored) {

            }
        }
    }
}
