package com.luna0wl.baritoneworker.worker.mover;

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

public final class MoverConfig {

    private static final Logger LOG = LoggerFactory.getLogger("baritonemover/config");

    public enum Mode {

        COPY,

        SORT
    }

    public String sourceHome = "source";
    public String destHome = "Home";
    public Mode mode = Mode.COPY;

    public int teleportTimeoutTicks = 200;
    public int teleportSettleTicks = 25;
    public double teleportMoveThreshold = 2.0;
    public double skipTeleportRange = 6.0;
    public int chestAreaMargin = 8;

    public int clickDelayTicks = 3;
    public int commandGapTicks = 15;
    public int chestPathTimeoutTicks = 1200;

    public final List<int[]> sourceBoxes = new ArrayList<>();
    public final List<int[]> destBoxes = new ArrayList<>();

    public void setSource(List<int[]> boxes) {
        sourceBoxes.clear();
        sourceBoxes.addAll(boxes);
    }

    public void setDest(List<int[]> boxes) {
        destBoxes.clear();
        destBoxes.addAll(boxes);
    }

    public boolean hasSource() {
        return !sourceBoxes.isEmpty();
    }

    public boolean hasDest() {
        return !destBoxes.isEmpty();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("baritoneworker").resolve("mover.properties");
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("sourceHome", sourceHome);
        p.setProperty("destHome", destHome);
        p.setProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        p.setProperty("teleportTimeoutTicks", Integer.toString(teleportTimeoutTicks));
        p.setProperty("teleportSettleTicks", Integer.toString(teleportSettleTicks));
        p.setProperty("teleportMoveThreshold", Double.toString(teleportMoveThreshold));
        p.setProperty("skipTeleportRange", Double.toString(skipTeleportRange));
        p.setProperty("chestAreaMargin", Integer.toString(chestAreaMargin));
        p.setProperty("clickDelayTicks", Integer.toString(clickDelayTicks));
        p.setProperty("commandGapTicks", Integer.toString(commandGapTicks));
        p.setProperty("chestPathTimeoutTicks", Integer.toString(chestPathTimeoutTicks));
        p.setProperty("sourceBoxes", serializeBoxes(sourceBoxes));
        p.setProperty("destBoxes", serializeBoxes(destBoxes));
        try {
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(out, "BaritoneWorker mover settings");
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
        sourceHome = p.getProperty("sourceHome", sourceHome);
        destHome = p.getProperty("destHome", destHome);
        try {
            mode = Mode.valueOf(p.getProperty("mode", mode.name()).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {

        }
        teleportTimeoutTicks = parseInt(p, "teleportTimeoutTicks", teleportTimeoutTicks);
        teleportSettleTicks = parseInt(p, "teleportSettleTicks", teleportSettleTicks);
        teleportMoveThreshold = parseDouble(p, "teleportMoveThreshold", teleportMoveThreshold);
        skipTeleportRange = parseDouble(p, "skipTeleportRange", skipTeleportRange);
        chestAreaMargin = parseInt(p, "chestAreaMargin", chestAreaMargin);
        clickDelayTicks = parseInt(p, "clickDelayTicks", clickDelayTicks);
        commandGapTicks = parseInt(p, "commandGapTicks", commandGapTicks);
        chestPathTimeoutTicks = parseInt(p, "chestPathTimeoutTicks", chestPathTimeoutTicks);
        deserializeBoxes(sourceBoxes, p.getProperty("sourceBoxes", ""));
        deserializeBoxes(destBoxes, p.getProperty("destBoxes", ""));
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
