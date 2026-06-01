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

public final class BuilderConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/builder");

    public String workHome = "build";

    public String baseHome = "Home";

    public Item foodItem = Items.BAKED_POTATO;
    public int targetFood = 64;

    public int litematicIndex = 0;

    public String schematicFile = "";

    public int[] buildOriginPos;

    public int[] stopPos;
    public double stopRadius = 3.0;

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

    public int teleportRetries = 3;

    public boolean resetWorkHome = false;

    public int materialBuilds = 1;

    public int maxStrayBlocks = 20;

    public int idleReissueTicks = 60;

    public int resumeGraceTicks = 120;

    public final List<int[]> chestBoxes = new ArrayList<>();

    public void setFoodItem(Item item) {
        foodItem = item;
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

    public boolean hasStop() {
        return stopPos != null;
    }

    public boolean hasSchematicFile() {
        return schematicFile != null && !schematicFile.isBlank();
    }

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
        p.setProperty("maxStrayBlocks", Integer.toString(maxStrayBlocks));
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
        maxStrayBlocks = Math.max(0, parseInt(p, "maxStrayBlocks", maxStrayBlocks));
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

            }
        }
    }
}
