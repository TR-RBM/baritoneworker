package com.luna0wl.baritoneworker.worker.sorter;

import net.fabricmc.loader.api.FabricLoader;
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
 * Settings for the stash sorter: the chest-room {@code /home}, the captured
 * area, and shared tunables. Persisted to {@code config/baritonesorter.properties}.
 * The actual item→chest scheme lives in {@link SortScheme} (a separate JSON).
 */
public final class SorterConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritonesorter/config");

    /** Essentials-style home by the chest room. */
    public String home = "Home";

    public int teleportTimeoutTicks = 200;
    public int teleportSettleTicks = 25;
    public double teleportMoveThreshold = 2.0;
    public double skipTeleportRange = 6.0;
    public int chestAreaMargin = 8;

    public int clickDelayTicks = 3;
    public int chestPathTimeoutTicks = 1200;
    /** Leave at least this many free inventory slots while collecting. */
    public int collectBufferSlots = 4;
    /** Safety cap on collect/distribute rounds. */
    public int maxRounds = 64;

    /** Captured chest-area boxes; each is {minX,minY,minZ,maxX,maxY,maxZ}. */
    public final List<int[]> chestBoxes = new ArrayList<>();

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
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("sorter.properties");
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("home", home);
        p.setProperty("teleportTimeoutTicks", Integer.toString(teleportTimeoutTicks));
        p.setProperty("teleportSettleTicks", Integer.toString(teleportSettleTicks));
        p.setProperty("teleportMoveThreshold", Double.toString(teleportMoveThreshold));
        p.setProperty("skipTeleportRange", Double.toString(skipTeleportRange));
        p.setProperty("chestAreaMargin", Integer.toString(chestAreaMargin));
        p.setProperty("clickDelayTicks", Integer.toString(clickDelayTicks));
        p.setProperty("chestPathTimeoutTicks", Integer.toString(chestPathTimeoutTicks));
        p.setProperty("collectBufferSlots", Integer.toString(collectBufferSlots));
        p.setProperty("maxRounds", Integer.toString(maxRounds));
        p.setProperty("chestBoxes", serializeBoxes());
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "BaritoneWorker sorter settings");
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
        home = p.getProperty("home", home);
        teleportTimeoutTicks = parseInt(p, "teleportTimeoutTicks", teleportTimeoutTicks);
        teleportSettleTicks = parseInt(p, "teleportSettleTicks", teleportSettleTicks);
        teleportMoveThreshold = parseDouble(p, "teleportMoveThreshold", teleportMoveThreshold);
        skipTeleportRange = parseDouble(p, "skipTeleportRange", skipTeleportRange);
        chestAreaMargin = parseInt(p, "chestAreaMargin", chestAreaMargin);
        clickDelayTicks = parseInt(p, "clickDelayTicks", clickDelayTicks);
        chestPathTimeoutTicks = parseInt(p, "chestPathTimeoutTicks", chestPathTimeoutTicks);
        collectBufferSlots = parseInt(p, "collectBufferSlots", collectBufferSlots);
        maxRounds = parseInt(p, "maxRounds", maxRounds);
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
