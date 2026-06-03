package com.luna0wl.baritoneworker.worker.common;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;

public final class DebugLog {

    private DebugLog() {}

    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("baritoneworker").resolve("debug.log");

    private static volatile boolean enabled = false;

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static Path file() {
        return FILE;
    }

    public static void reset() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static void log(String tag, String msg) {
        if (enabled) write(tag, msg);
    }

    public static synchronized void write(String tag, String msg) {
        try {
            Files.createDirectories(FILE.getParent());
            String clean = msg.replaceAll("§.", "");
            String line = "[" + LocalTime.now() + "] [" + tag + "] " + clean + System.lineSeparator();
            Files.writeString(FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
