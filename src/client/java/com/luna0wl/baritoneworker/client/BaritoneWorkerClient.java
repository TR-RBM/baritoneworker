package com.luna0wl.baritoneworker.client;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import com.luna0wl.baritoneworker.worker.builder.BuilderCommand;
import com.luna0wl.baritoneworker.worker.builder.BuilderConfig;
import com.luna0wl.baritoneworker.worker.builder.BuilderWorker;
import com.luna0wl.baritoneworker.worker.common.Baritones;
import com.luna0wl.baritoneworker.worker.lumber.LumberCommand;
import com.luna0wl.baritoneworker.worker.lumber.LumberConfig;
import com.luna0wl.baritoneworker.worker.lumber.LumberWorker;
import com.luna0wl.baritoneworker.worker.miner.MinerCommand;
import com.luna0wl.baritoneworker.worker.miner.MinerConfig;
import com.luna0wl.baritoneworker.worker.miner.MinerWorker;
import com.luna0wl.baritoneworker.worker.mover.MoverCommand;
import com.luna0wl.baritoneworker.worker.mover.MoverConfig;
import com.luna0wl.baritoneworker.worker.mover.MoverWorker;
import com.luna0wl.baritoneworker.worker.sorter.SortScheme;
import com.luna0wl.baritoneworker.worker.sorter.SorterCommand;
import com.luna0wl.baritoneworker.worker.sorter.SorterConfig;
import com.luna0wl.baritoneworker.worker.sorter.SorterWorker;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point: loads each worker's config, registers the miner toggle
 * keybind and the per-tick driver, and installs the {@code #miner}, {@code #lumber},
 * {@code #sorter}, {@code #mover} and {@code #builder} Baritone commands once
 * Baritone and a world are available.
 *
 * <p>The workers are independent tick-driven state machines; idle ones are
 * no-ops. Run one at a time.
 */
public class BaritoneWorkerClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/client");

    // --- miner ---
    private final MinerConfig minerConfig = new MinerConfig();
    private final MinerWorker miner = new MinerWorker(minerConfig);

    // --- lumber ---
    private final LumberConfig lumberConfig = new LumberConfig();
    private final LumberWorker lumber = new LumberWorker(lumberConfig);

    // --- sorter + mover share one sort scheme ---
    private final SortScheme scheme = new SortScheme();
    private final SorterConfig sorterConfig = new SorterConfig();
    private final SorterWorker sorter = new SorterWorker(sorterConfig, scheme);
    private final MoverConfig moverConfig = new MoverConfig();
    private final MoverWorker mover = new MoverWorker(moverConfig, scheme);

    // --- builder ---
    private final BuilderConfig builderConfig = new BuilderConfig();
    private final BuilderWorker builder = new BuilderWorker(builderConfig);

    private KeyMapping toggleKey;
    private boolean commandRegistered;

    @Override
    public void onInitializeClient() {
        minerConfig.load();
        lumberConfig.load();
        sorterConfig.load();
        moverConfig.load();
        builderConfig.load();
        scheme.load();

        toggleKey = new KeyMapping(
                "key.baritoneworker.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH,
                KeyMapping.Category.MISC);
        KeyMappingHelper.registerKeyMapping(toggleKey);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.consumeClick()) {
                miner.toggle(mc);
            }
            miner.tick(mc);
            lumber.tick(mc);
            sorter.tick(mc);
            mover.tick(mc);
            builder.tick(mc);
        });

        // Register the commands once Baritone is up and we've joined a world.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> tryRegisterCommands());

        if (!Baritones.isLoaded()) {
            LOG.warn("Baritone (baritone-meteor) is not installed — the workers will refuse to start.");
        }
        LOG.info("BaritoneWorker client initialized (miner, lumber, sorter, mover, builder).");
    }

    private void tryRegisterCommands() {
        if (commandRegistered || !Baritones.isLoaded()) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null) return;
            var registry = baritone.getCommandManager().getRegistry();
            registry.register(new MinerCommand(baritone, miner, minerConfig));
            registry.register(new LumberCommand(baritone, lumber, lumberConfig));
            registry.register(new SorterCommand(baritone, sorter, sorterConfig, scheme));
            registry.register(new MoverCommand(baritone, mover, moverConfig));
            registry.register(new BuilderCommand(baritone, builder, builderConfig));
            commandRegistered = true;
            LOG.info("Registered #miner, #lumber, #sorter, #mover, #builder commands.");
        } catch (Throwable t) {
            LOG.warn("Could not register worker commands: {}", t.toString());
        }
    }
}
