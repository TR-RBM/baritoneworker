package com.luna0wl.baritoneworker.worker.digger;

import com.luna0wl.baritoneworker.worker.common.ItemNames;
import net.fabricmc.loader.api.FabricLoader;
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
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class DiggerConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritonedigger/config");

    // Homes the worker teleports between.
    public String workHome = "dig";
    public String supplyHome = "Home";
    // Empty = dump into the supply chests at supplyHome (one-home mode).
    public String dumpHome = "";

    // Tools / supplies the worker keeps stocked.
    public Item pickaxeItem = Items.DIAMOND_PICKAXE;
    public Item shovelItem = Items.DIAMOND_SHOVEL;
    public Item foodItem = Items.BAKED_POTATO;

    public int targetPickaxes = 2;
    public int targetShovels = 2;
    public int targetFood = 64;
    public int targetBuckets = 4;

    public int stopAtFreeSlots = 1;

    // Bucket lava (and collect it) / drain water on contact. Off = leave fluids and report.
    public boolean handleFluids = true;
    // Let Baritone break blocks while repositioning between dig spots (it never breaks while
    // we clear a spot ourselves). Off = pure no-break movement; unreachable blocks are skipped.
    public boolean breakWhileMoving = true;
    // Re-set the work home where it stands before each supply/dump trip, so it returns to the
    // exact dig face rather than the original (possibly now hollow) start spot.
    public boolean advanceWorkHome = true;

    public boolean includeEnderChests = false;

    // Teleport / interaction timing (shared defaults with the other workers).
    public int teleportTimeoutTicks = 200;
    public int teleportSettleTicks = 25;
    public double teleportMoveThreshold = 2.0;
    public double skipTeleportRange = 6.0;
    public int chestAreaMargin = 8;

    public int clickDelayTicks = 3;
    public int commandGapTicks = 15;
    public int chestPathTimeoutTicks = 1200;

    // Dig-engine tuning.
    public int moveTimeoutTicks = 400;
    public int breakTimeoutTicks = 300;
    public int bucketTimeoutTicks = 60;

    public int[] workPos;

    // The region to excavate, the chests to restock from, and the chests to dump spoil into.
    public final List<int[]> digBoxes = new ArrayList<>();
    public final List<int[]> supplyBoxes = new ArrayList<>();
    public final List<int[]> dumpBoxes = new ArrayList<>();

    // Items never deposited (rebuilt from the tools/food above; empty buckets are reused).
    public final Set<Item> keepItems = new LinkedHashSet<>();

    public DiggerConfig() {
        rebuildKeep();
    }

    public void rebuildKeep() {
        keepItems.clear();
        keepItems.add(pickaxeItem);
        keepItems.add(shovelItem);
        keepItems.add(foodItem);
        keepItems.add(Items.BUCKET);
    }

    public void setPickaxeItem(Item item) { pickaxeItem = item; rebuildKeep(); }
    public void setShovelItem(Item item) { shovelItem = item; rebuildKeep(); }
    public void setFoodItem(Item item) { foodItem = item; rebuildKeep(); }

    public boolean hasRegion() { return !digBoxes.isEmpty(); }
    public boolean hasSupply() { return !supplyBoxes.isEmpty(); }
    public boolean hasDump() { return !dumpBoxes.isEmpty(); }

    /** True when a separate dump area is configured; otherwise spoil goes to the supply chests. */
    public boolean twoArea() { return hasDump(); }

    public String effectiveDumpHome() {
        return dumpHome.isBlank() ? supplyHome : dumpHome;
    }

    public void setRegion(List<int[]> boxes) { digBoxes.clear(); digBoxes.addAll(boxes); }
    public void setSupply(List<int[]> boxes) { supplyBoxes.clear(); supplyBoxes.addAll(boxes); }
    public void setDump(List<int[]> boxes) { dumpBoxes.clear(); dumpBoxes.addAll(boxes); }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("digger.properties");
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("workHome", workHome);
        p.setProperty("supplyHome", supplyHome);
        p.setProperty("dumpHome", dumpHome);
        p.setProperty("pickaxeItem", ItemNames.idOf(pickaxeItem));
        p.setProperty("shovelItem", ItemNames.idOf(shovelItem));
        p.setProperty("foodItem", ItemNames.idOf(foodItem));
        p.setProperty("targetPickaxes", Integer.toString(targetPickaxes));
        p.setProperty("targetShovels", Integer.toString(targetShovels));
        p.setProperty("targetFood", Integer.toString(targetFood));
        p.setProperty("targetBuckets", Integer.toString(targetBuckets));
        p.setProperty("stopAtFreeSlots", Integer.toString(stopAtFreeSlots));
        p.setProperty("handleFluids", Boolean.toString(handleFluids));
        p.setProperty("breakWhileMoving", Boolean.toString(breakWhileMoving));
        p.setProperty("advanceWorkHome", Boolean.toString(advanceWorkHome));
        p.setProperty("includeEnderChests", Boolean.toString(includeEnderChests));
        p.setProperty("teleportTimeoutTicks", Integer.toString(teleportTimeoutTicks));
        p.setProperty("teleportSettleTicks", Integer.toString(teleportSettleTicks));
        p.setProperty("teleportMoveThreshold", Double.toString(teleportMoveThreshold));
        p.setProperty("skipTeleportRange", Double.toString(skipTeleportRange));
        p.setProperty("chestAreaMargin", Integer.toString(chestAreaMargin));
        p.setProperty("clickDelayTicks", Integer.toString(clickDelayTicks));
        p.setProperty("commandGapTicks", Integer.toString(commandGapTicks));
        p.setProperty("chestPathTimeoutTicks", Integer.toString(chestPathTimeoutTicks));
        p.setProperty("moveTimeoutTicks", Integer.toString(moveTimeoutTicks));
        p.setProperty("breakTimeoutTicks", Integer.toString(breakTimeoutTicks));
        p.setProperty("bucketTimeoutTicks", Integer.toString(bucketTimeoutTicks));
        p.setProperty("workPos", serializePos(workPos));
        p.setProperty("digBoxes", serializeBoxes(digBoxes));
        p.setProperty("supplyBoxes", serializeBoxes(supplyBoxes));
        p.setProperty("dumpBoxes", serializeBoxes(dumpBoxes));
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "BaritoneWorker digger settings");
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
        supplyHome = p.getProperty("supplyHome", supplyHome);
        dumpHome = p.getProperty("dumpHome", dumpHome);
        Item pi = ItemNames.byId(p.getProperty("pickaxeItem", ""));
        if (pi != null) pickaxeItem = pi;
        Item si = ItemNames.byId(p.getProperty("shovelItem", ""));
        if (si != null) shovelItem = si;
        Item fi = ItemNames.byId(p.getProperty("foodItem", ""));
        if (fi != null) foodItem = fi;
        targetPickaxes = parseInt(p, "targetPickaxes", targetPickaxes);
        targetShovels = parseInt(p, "targetShovels", targetShovels);
        targetFood = parseInt(p, "targetFood", targetFood);
        targetBuckets = parseInt(p, "targetBuckets", targetBuckets);
        stopAtFreeSlots = parseInt(p, "stopAtFreeSlots", stopAtFreeSlots);
        handleFluids = Boolean.parseBoolean(p.getProperty("handleFluids", Boolean.toString(handleFluids)));
        breakWhileMoving = Boolean.parseBoolean(p.getProperty("breakWhileMoving", Boolean.toString(breakWhileMoving)));
        advanceWorkHome = Boolean.parseBoolean(p.getProperty("advanceWorkHome", Boolean.toString(advanceWorkHome)));
        includeEnderChests = Boolean.parseBoolean(p.getProperty("includeEnderChests", Boolean.toString(includeEnderChests)));
        teleportTimeoutTicks = parseInt(p, "teleportTimeoutTicks", teleportTimeoutTicks);
        teleportSettleTicks = parseInt(p, "teleportSettleTicks", teleportSettleTicks);
        teleportMoveThreshold = parseDouble(p, "teleportMoveThreshold", teleportMoveThreshold);
        skipTeleportRange = parseDouble(p, "skipTeleportRange", skipTeleportRange);
        chestAreaMargin = parseInt(p, "chestAreaMargin", chestAreaMargin);
        clickDelayTicks = parseInt(p, "clickDelayTicks", clickDelayTicks);
        commandGapTicks = parseInt(p, "commandGapTicks", commandGapTicks);
        chestPathTimeoutTicks = parseInt(p, "chestPathTimeoutTicks", chestPathTimeoutTicks);
        moveTimeoutTicks = parseInt(p, "moveTimeoutTicks", moveTimeoutTicks);
        breakTimeoutTicks = parseInt(p, "breakTimeoutTicks", breakTimeoutTicks);
        bucketTimeoutTicks = parseInt(p, "bucketTimeoutTicks", bucketTimeoutTicks);
        workPos = deserializePos(p.getProperty("workPos", ""));
        deserializeBoxes(digBoxes, p.getProperty("digBoxes", ""));
        deserializeBoxes(supplyBoxes, p.getProperty("supplyBoxes", ""));
        deserializeBoxes(dumpBoxes, p.getProperty("dumpBoxes", ""));
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

    private static String serializeBoxes(List<int[]> boxes) {
        StringBuilder sb = new StringBuilder();
        for (int[] b : boxes) {
            if (sb.length() > 0) sb.append(';');
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(',');
                sb.append(b[i]);
            }
        }
        return sb.toString();
    }

    private static void deserializeBoxes(List<int[]> out, String s) {
        out.clear();
        if (s == null || s.isBlank()) return;
        for (String boxStr : s.split(";")) {
            String[] parts = boxStr.split(",");
            if (parts.length != 6) continue;
            try {
                int[] b = new int[6];
                for (int i = 0; i < 6; i++) b[i] = Integer.parseInt(parts[i].trim());
                out.add(b);
            } catch (NumberFormatException ignored) {

            }
        }
    }
}
