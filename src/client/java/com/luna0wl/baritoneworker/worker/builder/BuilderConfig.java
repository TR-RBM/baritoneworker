package com.luna0wl.baritoneworker.worker.builder;

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
import java.util.List;
import java.util.Properties;

/**
 * Settings for the builder worker, plus the captured supply-chest area.
 * Persisted to {@code config/baritoneworker/builder.properties}.
 *
 * <p>The builder can build either the schematic currently open in Litematica
 * (default) or a schematic file from the {@code schematics/} folder — set
 * {@link #schematicFile} for the latter, just like Baritone's own {@code #build}.
 * "Materials" are simply placeable blocks: when it runs out, it restocks every
 * block it can find in the supply chests.
 */
public final class BuilderConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/builder");

    /** Essentials-style home at the build site (default {@code build}). */
    public String workHome = "build";
    /** Essentials-style home where the supply chests live (default {@code Home}). */
    public String baseHome = "Home";

    /** Which food to keep stocked (for a separate auto-eat mod). */
    public Item foodItem = Items.BAKED_POTATO;
    public int targetFood = 64;

    /** Which open Litematica placement to build (index, default 0 = the primary one). */
    public int litematicIndex = 0;

    /**
     * Schematic file to build from the {@code schematics/} folder (e.g.
     * {@code house.litematic}). When set, the worker builds this file like the
     * normal {@code #build} command; when blank it builds the open Litematica
     * placement instead.
     */
    public String schematicFile = "";
    /**
     * Fixed origin (corner) for a file build, like the optional coords of
     * {@code #build <file> <x> <y> <z>}. When null the worker anchors the
     * schematic at the build site the first time it starts placing.
     */
    public int[] buildOriginPos;

    // --- optional coordinate stop ---
    /** When set, the worker stops once the player reaches within {@link #stopRadius} of this. */
    public int[] stopPos;
    public double stopRadius = 3.0;

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

    /** How many times to re-send {@code /home <workHome>} if the teleport back didn't land at the build. */
    public int teleportRetries = 3;

    /**
     * Whether to delhome/sethome the work home at the current spot when materials run out.
     * Off by default: the build site is world-anchored, so the bot keeps the user's existing
     * {@code build} home instead of overwriting it with wherever it happened to pause (often
     * mid-air on the structure). Turn on for builds that crawl far from the start point.
     */
    public boolean resetWorkHome = false;

    /**
     * How many builds' worth of materials to carry per supply trip. {@code 1} (default) = one
     * full bill of materials for the schematic; a higher number stocks that many copies (handy
     * for buildRepeat tiling, so the bot makes fewer trips); {@code 0} = infinite, i.e. fill the
     * bag with as many of the needed blocks as fit. Only ever pulls block types the schematic
     * actually uses — never quantities of a type beyond {@code count × materialBuilds}.
     */
    public int materialBuilds = 1;

    /** Ticks the builder may sit idle (after having built) before we treat it as out of materials. */
    public int idleReissueTicks = 60;
    /** Ticks to wait for the builder to start after a (re)issue before concluding the build is done. */
    public int resumeGraceTicks = 120;

    /** Captured supply-chest boxes; each is {minX,minY,minZ,maxX,maxY,maxZ}. */
    public final List<int[]> chestBoxes = new ArrayList<>();

    public void setFoodItem(Item item) {
        foodItem = item;
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

    public boolean hasStop() {
        return stopPos != null;
    }

    /** True when configured to build a schematic file rather than the open Litematica placement. */
    public boolean hasSchematicFile() {
        return schematicFile != null && !schematicFile.isBlank();
    }

    // ----------------------------------------------------------- persistence

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("builder.properties");
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("workHome", workHome);
        p.setProperty("baseHome", baseHome);
        p.setProperty("foodItem", ItemNames.idOf(foodItem));
        p.setProperty("targetFood", Integer.toString(targetFood));
        p.setProperty("litematicIndex", Integer.toString(litematicIndex));
        p.setProperty("schematicFile", schematicFile == null ? "" : schematicFile);
        p.setProperty("buildOriginPos", serializePos(buildOriginPos));
        p.setProperty("stopPos", serializePos(stopPos));
        p.setProperty("stopRadius", Double.toString(stopRadius));
        p.setProperty("teleportTimeoutTicks", Integer.toString(teleportTimeoutTicks));
        p.setProperty("teleportSettleTicks", Integer.toString(teleportSettleTicks));
        p.setProperty("teleportMoveThreshold", Double.toString(teleportMoveThreshold));
        p.setProperty("skipTeleportRange", Double.toString(skipTeleportRange));
        p.setProperty("chestAreaMargin", Integer.toString(chestAreaMargin));
        p.setProperty("clickDelayTicks", Integer.toString(clickDelayTicks));
        p.setProperty("commandGapTicks", Integer.toString(commandGapTicks));
        p.setProperty("chestPathTimeoutTicks", Integer.toString(chestPathTimeoutTicks));
        p.setProperty("teleportRetries", Integer.toString(teleportRetries));
        p.setProperty("resetWorkHome", Boolean.toString(resetWorkHome));
        p.setProperty("materialBuilds", Integer.toString(materialBuilds));
        p.setProperty("idleReissueTicks", Integer.toString(idleReissueTicks));
        p.setProperty("resumeGraceTicks", Integer.toString(resumeGraceTicks));
        p.setProperty("workPos", serializePos(workPos));
        p.setProperty("homePos", serializePos(homePos));
        p.setProperty("chestBoxes", serializeBoxes());
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "BaritoneWorker builder settings");
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
        Item fi = ItemNames.byId(p.getProperty("foodItem", ""));
        if (fi != null) foodItem = fi;
        targetFood = parseInt(p, "targetFood", targetFood);
        litematicIndex = parseInt(p, "litematicIndex", litematicIndex);
        schematicFile = p.getProperty("schematicFile", schematicFile);
        buildOriginPos = deserializePos(p.getProperty("buildOriginPos", ""));
        stopPos = deserializePos(p.getProperty("stopPos", ""));
        stopRadius = parseDouble(p, "stopRadius", stopRadius);
        teleportTimeoutTicks = parseInt(p, "teleportTimeoutTicks", teleportTimeoutTicks);
        teleportSettleTicks = parseInt(p, "teleportSettleTicks", teleportSettleTicks);
        teleportMoveThreshold = parseDouble(p, "teleportMoveThreshold", teleportMoveThreshold);
        skipTeleportRange = parseDouble(p, "skipTeleportRange", skipTeleportRange);
        chestAreaMargin = parseInt(p, "chestAreaMargin", chestAreaMargin);
        clickDelayTicks = parseInt(p, "clickDelayTicks", clickDelayTicks);
        commandGapTicks = parseInt(p, "commandGapTicks", commandGapTicks);
        chestPathTimeoutTicks = parseInt(p, "chestPathTimeoutTicks", chestPathTimeoutTicks);
        teleportRetries = Math.max(0, parseInt(p, "teleportRetries", teleportRetries));
        resetWorkHome = Boolean.parseBoolean(p.getProperty("resetWorkHome", Boolean.toString(resetWorkHome)));
        materialBuilds = Math.max(0, parseInt(p, "materialBuilds", materialBuilds));
        idleReissueTicks = parseInt(p, "idleReissueTicks", idleReissueTicks);
        resumeGraceTicks = parseInt(p, "resumeGraceTicks", resumeGraceTicks);
        workPos = deserializePos(p.getProperty("workPos", ""));
        homePos = deserializePos(p.getProperty("homePos", ""));
        deserializeBoxes(p.getProperty("chestBoxes", ""));
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
