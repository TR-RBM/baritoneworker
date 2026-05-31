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

/**
 * All tunable settings for the miner, plus the captured chest area. Persisted to
 * {@code config/baritoneworker/miner.properties} so they survive a restart.
 *
 * <p>The chest area is stored as a list of axis-aligned boxes (one per Baritone
 * selection that was captured), each as inclusive min/max corners.
 */
public final class MinerConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/config");

    /** Essentials-style home the worker teleports to for mining (default {@code mine}). */
    public String mineHome = "mine";
    /** Essentials-style home where the chests live (default {@code Home}). */
    public String baseHome = "Home";

    /** Which pickaxe to keep stocked / mine ore with. */
    public Item pickaxeItem = Items.DIAMOND_PICKAXE;
    /** Which food to keep stocked (for a separate auto-eat mod). */
    public Item foodItem = Items.BAKED_POTATO;

    /** Pickaxes to keep on hand; restock tops up to this. */
    public int targetPickaxes = 2;
    /** Food items to keep on hand; restock tops up to this. */
    public int targetFood = 64;

    /**
     * Return to base once this many or fewer free slots remain in the main
     * inventory (slots 0..35). 1 leaves a slot of headroom so the last mined
     * stack still fits.
     */
    public int stopAtFreeSlots = 1;

    /**
     * Teleport handling. {@code /home} on this server has a ~4s warm-up, so we
     * don't act on a fixed timer — we wait until the player's position actually
     * jumps by {@link #teleportMoveThreshold} blocks, then settle.
     */
    public int teleportTimeoutTicks = 200;      // give up waiting for the jump (already-there fallback)
    public int teleportSettleTicks = 25;        // after the jump: let chunks/ground load
    public double teleportMoveThreshold = 2.0;  // blocks of movement that count as "teleported"

    /** Skip a {@code /home} if already within this many blocks of the destination. */
    public double skipTeleportRange = 6.0;
    /** Treat the player as "at base" if within this many blocks of the chest area. */
    public int chestAreaMargin = 8;
    /** How far to probe each direction when picking the tunnel heading. */
    public int tunnelScanDepth = 256;

    /** Cached landing spots so we can skip redundant teleports (null = unknown). */
    public int[] minePos;
    public int[] homePos;
    /** Ticks between successive container clicks (server-friendly throttle). */
    public int clickDelayTicks = 3;
    /** Ticks between the three RESET_HOME commands. */
    public int commandGapTicks = 15;
    /** Max ticks to path to a single chest before giving up on it. */
    public int chestPathTimeoutTicks = 1200;

    /** Extra item types to never deposit, on top of the pickaxe and food. */
    public final Set<Item> extraKeepItems = new LinkedHashSet<>(List.of(Items.TORCH));

    /**
     * Effective never-deposit set = pickaxe + food + extras. Rebuilt by
     * {@link #rebuildKeep()} whenever those change; read directly by the worker.
     */
    public final Set<Item> keepItems = new LinkedHashSet<>();

    public MinerConfig() {
        rebuildKeep();
    }

    public void rebuildKeep() {
        keepItems.clear();
        keepItems.add(pickaxeItem);
        keepItems.add(foodItem);
        keepItems.addAll(extraKeepItems);
    }

    public void setPickaxeItem(Item item) {
        pickaxeItem = item;
        rebuildKeep();
    }

    public void setFoodItem(Item item) {
        foodItem = item;
        rebuildKeep();
    }

    // --- exposed-ore mining (while tunnelling) ---
    /**
     * Master toggle: detour to grab ore exposed in the tunnel walls. Off by
     * default, so out of the box the worker tunnels exactly as before. Turn on
     * with {@code #miner ore on}.
     */
    public boolean mineExposedOres = false;
    /** Skip an ore if any neighbouring block is lava or water. */
    public boolean avoidFluidBehindOre = true;
    /** How far around the player to look for exposed ore. */
    public int oreScanRadius = 6;
    /** Ticks between ore scans while tunnelling. */
    public int oreScanInterval = 10;
    /** Ore groups to ignore (see {@link Ores}); empty = mine every ore. */
    public final Set<String> excludedOreGroups = new LinkedHashSet<>();

    /** Captured chest-area boxes; each is {minX,minY,minZ,maxX,maxY,maxZ}. */
    public final List<int[]> chestBoxes = new ArrayList<>();

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

    /** True if {@code pos} lies inside any captured box. */
    public boolean areaContains(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int[] b : chestBoxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------- persistence

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("miner.properties");
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("mineHome", mineHome);
        p.setProperty("baseHome", baseHome);
        p.setProperty("pickaxeItem", ItemNames.idOf(pickaxeItem));
        p.setProperty("foodItem", ItemNames.idOf(foodItem));
        p.setProperty("targetPickaxes", Integer.toString(targetPickaxes));
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
        Item fi = ItemNames.byId(p.getProperty("foodItem", ""));
        if (fi != null) foodItem = fi;
        targetPickaxes = parseInt(p, "targetPickaxes", targetPickaxes);
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
