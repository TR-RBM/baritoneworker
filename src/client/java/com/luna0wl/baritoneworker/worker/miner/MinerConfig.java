package com.luna0wl.baritoneworker.worker.miner;

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

public final class MinerConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/config");

    public String mineHome = "mine";

    public String baseHome = "Home";

    public Item pickaxeItem = Items.DIAMOND_PICKAXE;

    public Item shovelItem = Items.DIAMOND_SHOVEL;

    public Item foodItem = Items.BAKED_POTATO;

    public int targetPickaxes = 2;

    public int targetShovels = 1;

    public int targetFood = 64;

    public int stopAtFreeSlots = 1;

    public int teleportTimeoutTicks = 200;
    public int teleportSettleTicks = 25;
    public double teleportMoveThreshold = 2.0;

    public double skipTeleportRange = 6.0;

    public int chestAreaMargin = 8;

    public int tunnelScanDepth = 256;

    public int[] minePos;
    public int[] homePos;

    public int clickDelayTicks = 3;

    public int commandGapTicks = 15;

    public int chestPathTimeoutTicks = 1200;

    public final Set<Item> extraKeepItems = new LinkedHashSet<>(List.of(Items.TORCH));

    public final Set<Item> keepItems = new LinkedHashSet<>();

    public MinerConfig() {
        rebuildKeep();
    }

    public void rebuildKeep() {
        keepItems.clear();
        keepItems.add(pickaxeItem);
        keepItems.add(shovelItem);
        keepItems.add(foodItem);
        keepItems.addAll(extraKeepItems);
    }

    public void setPickaxeItem(Item item) {
        pickaxeItem = item;
        rebuildKeep();
    }

    public void setShovelItem(Item item) {
        shovelItem = item;
        rebuildKeep();
    }

    public void setFoodItem(Item item) {
        foodItem = item;
        rebuildKeep();
    }

    public boolean mineExposedOres = false;

    public boolean avoidFluidBehindOre = true;

    public int oreScanRadius = 6;

    public int oreScanInterval = 10;

    public final Set<String> excludedOreGroups = new LinkedHashSet<>();

    public final List<int[]> chestBoxes = new ArrayList<>();

    public boolean includeEnderChests = false;

    public boolean debug = false;

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

    public boolean areaContains(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int[] b : chestBoxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return false;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("miner.properties");
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("mineHome", mineHome);
        p.setProperty("baseHome", baseHome);
        p.setProperty("pickaxeItem", ItemNames.idOf(pickaxeItem));
        p.setProperty("shovelItem", ItemNames.idOf(shovelItem));
        p.setProperty("foodItem", ItemNames.idOf(foodItem));
        p.setProperty("targetPickaxes", Integer.toString(targetPickaxes));
        p.setProperty("targetShovels", Integer.toString(targetShovels));
        p.setProperty("targetFood", Integer.toString(targetFood));
        p.setProperty("stopAtFreeSlots", Integer.toString(stopAtFreeSlots));
        p.setProperty("teleportTimeoutTicks", Integer.toString(teleportTimeoutTicks));
        p.setProperty("teleportSettleTicks", Integer.toString(teleportSettleTicks));
        p.setProperty("teleportMoveThreshold", Double.toString(teleportMoveThreshold));
        p.setProperty("clickDelayTicks", Integer.toString(clickDelayTicks));
        p.setProperty("commandGapTicks", Integer.toString(commandGapTicks));
        p.setProperty("chestPathTimeoutTicks", Integer.toString(chestPathTimeoutTicks));
        p.setProperty("skipTeleportRange", Double.toString(skipTeleportRange));
        p.setProperty("chestAreaMargin", Integer.toString(chestAreaMargin));
        p.setProperty("tunnelScanDepth", Integer.toString(tunnelScanDepth));
        p.setProperty("minePos", serializePos(minePos));
        p.setProperty("homePos", serializePos(homePos));
        p.setProperty("mineExposedOres", Boolean.toString(mineExposedOres));
        p.setProperty("avoidFluidBehindOre", Boolean.toString(avoidFluidBehindOre));
        p.setProperty("oreScanRadius", Integer.toString(oreScanRadius));
        p.setProperty("oreScanInterval", Integer.toString(oreScanInterval));
        p.setProperty("excludedOreGroups", String.join(",", excludedOreGroups));
        p.setProperty("includeEnderChests", Boolean.toString(includeEnderChests));
        p.setProperty("debug", Boolean.toString(debug));
        p.setProperty("chestBoxes", serializeBoxes());
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "BaritoneWorker miner settings");
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
        mineHome = p.getProperty("mineHome", mineHome);
        baseHome = p.getProperty("baseHome", baseHome);
        Item pi = ItemNames.byId(p.getProperty("pickaxeItem", ""));
        if (pi != null) pickaxeItem = pi;
        Item si = ItemNames.byId(p.getProperty("shovelItem", ""));
        if (si != null) shovelItem = si;
        Item fi = ItemNames.byId(p.getProperty("foodItem", ""));
        if (fi != null) foodItem = fi;
        targetPickaxes = parseInt(p, "targetPickaxes", targetPickaxes);
        targetShovels = parseInt(p, "targetShovels", targetShovels);
        targetFood = parseInt(p, "targetFood", targetFood);
        stopAtFreeSlots = parseInt(p, "stopAtFreeSlots", stopAtFreeSlots);
        teleportTimeoutTicks = parseInt(p, "teleportTimeoutTicks", teleportTimeoutTicks);
        teleportSettleTicks = parseInt(p, "teleportSettleTicks", teleportSettleTicks);
        teleportMoveThreshold = parseDouble(p, "teleportMoveThreshold", teleportMoveThreshold);
        clickDelayTicks = parseInt(p, "clickDelayTicks", clickDelayTicks);
        commandGapTicks = parseInt(p, "commandGapTicks", commandGapTicks);
        chestPathTimeoutTicks = parseInt(p, "chestPathTimeoutTicks", chestPathTimeoutTicks);
        skipTeleportRange = parseDouble(p, "skipTeleportRange", skipTeleportRange);
        chestAreaMargin = parseInt(p, "chestAreaMargin", chestAreaMargin);
        tunnelScanDepth = parseInt(p, "tunnelScanDepth", tunnelScanDepth);
        minePos = deserializePos(p.getProperty("minePos", ""));
        homePos = deserializePos(p.getProperty("homePos", ""));
        mineExposedOres = Boolean.parseBoolean(p.getProperty("mineExposedOres", Boolean.toString(mineExposedOres)));
        avoidFluidBehindOre = Boolean.parseBoolean(p.getProperty("avoidFluidBehindOre", Boolean.toString(avoidFluidBehindOre)));
        oreScanRadius = parseInt(p, "oreScanRadius", oreScanRadius);
        oreScanInterval = parseInt(p, "oreScanInterval", oreScanInterval);
        excludedOreGroups.clear();
        for (String g : p.getProperty("excludedOreGroups", "").split(",")) {
            if (!g.isBlank() && Ores.isGroup(g.trim())) excludedOreGroups.add(g.trim());
        }
        includeEnderChests = Boolean.parseBoolean(p.getProperty("includeEnderChests", Boolean.toString(includeEnderChests)));
        debug = Boolean.parseBoolean(p.getProperty("debug", Boolean.toString(debug)));
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

            }
        }
    }
}
