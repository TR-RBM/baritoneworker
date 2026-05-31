package com.luna0wl.baritoneworker.worker.common;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Small shared helpers for locating the installed Baritone, across forks. The
 * Meteor fork ships under the id {@code baritone-meteor}; the upstream build is
 * just {@code baritone}.
 */
public final class Baritones {

    private Baritones() {}

    /** Candidate Baritone mod ids, most-specific first. */
    public static final String[] IDS = {"baritone-meteor", "baritone"};

    public static boolean isLoaded() {
        for (String id : IDS) {
            if (FabricLoader.getInstance().isModLoaded(id)) return true;
        }
        return false;
    }

    /**
     * Resolve the {@link IBaritone} bound to the running client, falling back to
     * the primary instance. Returns null if Baritone isn't available yet.
     */
    public static IBaritone resolve(Minecraft mc) {
        IBaritoneProvider provider = BaritoneAPI.getProvider();
        if (provider == null) return null;
        IBaritone b = provider.getBaritoneForMinecraft(mc);
        if (b == null) b = provider.getPrimaryBaritone();
        return b;
    }
}
