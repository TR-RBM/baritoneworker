package com.luna0wl.baritoneworker.worker.common;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ChestCache {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/chestcache");

    private final Path file;

    public ChestCache(String name) {
        this.file = FabricLoader.getInstance().getConfigDir()
                .resolve("baritoneworker").resolve(name + "-chestcache.txt");
    }

    public List<BlockPos> load() {
        List<BlockPos> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try {
            for (String line : Files.readAllLines(file)) {
                String[] parts = line.trim().split(",");
                if (parts.length != 3) continue;
                try {
                    out.add(new BlockPos(
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not load chest cache: {}", e.toString());
        }
        return out;
    }

    public void save(List<BlockPos> coords) {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            for (BlockPos p : coords) {
                lines.add(p.getX() + "," + p.getY() + "," + p.getZ());
            }
            Files.write(file, lines);
        } catch (IOException e) {
            LOG.warn("Could not save chest cache: {}", e.toString());
        }
    }
}
