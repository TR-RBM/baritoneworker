package com.luna0wl.baritoneworker.worker.common;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class Baritones {

    private Baritones() {}

    public static final String[] IDS = {"baritone-meteor", "baritone"};

    public static boolean isLoaded() {
        for (String id : IDS) {
            if (FabricLoader.getInstance().isModLoaded(id)) return true;
        }
        return false;
    }

    public static IBaritone resolve(Minecraft mc) {
        IBaritoneProvider provider = BaritoneAPI.getProvider();
        if (provider == null) return null;
        IBaritone b = provider.getBaritoneForMinecraft(mc);
        if (b == null) b = provider.getPrimaryBaritone();
        return b;
    }

    public static final String PAUSE_PROCESS = "Pause/Resume Commands";

    public static boolean isUserPaused(IBaritone b) {
        if (b == null) return false;
        return b.getPathingControlManager().mostRecentInControl()
                .map(p -> PAUSE_PROCESS.equals(p.displayName0()))
                .orElse(false);
    }
}
