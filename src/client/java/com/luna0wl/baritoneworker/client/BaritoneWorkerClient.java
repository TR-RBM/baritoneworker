package com.luna0wl.baritoneworker.client;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import com.luna0wl.baritoneworker.worker.builder.BuilderCommand;
import com.luna0wl.baritoneworker.worker.builder.BuilderConfig;
import com.luna0wl.baritoneworker.worker.builder.BuilderWorker;
import com.luna0wl.baritoneworker.worker.common.Baritones;
import com.luna0wl.baritoneworker.worker.digger.DiggerCommand;
import com.luna0wl.baritoneworker.worker.digger.DiggerConfig;
import com.luna0wl.baritoneworker.worker.digger.DiggerWorker;
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

public class BaritoneWorkerClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/client");

    private final SortScheme scheme = new SortScheme();

    private final MinerConfig minerConfig = new MinerConfig();
    private final MinerWorker miner = new MinerWorker(minerConfig, scheme);

    private final LumberConfig lumberConfig = new LumberConfig();
    private final LumberWorker lumber = new LumberWorker(lumberConfig, scheme);

    private final SorterConfig sorterConfig = new SorterConfig();
    private final SorterWorker sorter = new SorterWorker(sorterConfig, scheme);
    private final MoverConfig moverConfig = new MoverConfig();
    private final MoverWorker mover = new MoverWorker(moverConfig, scheme);

    private final BuilderConfig builderConfig = new BuilderConfig();
    private final BuilderWorker builder = new BuilderWorker(builderConfig);

    private final DiggerConfig diggerConfig = new DiggerConfig();
    private final DiggerWorker digger = new DiggerWorker(diggerConfig);

    private KeyMapping toggleKey;
    private boolean commandRegistered;

    @Override
    public void onInitializeClient() {
        minerConfig.load();
        lumberConfig.load();
        sorterConfig.load();
        moverConfig.load();
        builderConfig.load();
        diggerConfig.load();
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
            digger.tick(mc);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> tryRegisterCommands());

        if (!Baritones.isLoaded()) {
            LOG.warn("Baritone (baritone-meteor) is not installed — the workers will refuse to start.");
        }
        LOG.info("BaritoneWorker client initialized (miner, lumber, sorter, mover, builder, digger).");
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
            registry.register(new DiggerCommand(baritone, digger, diggerConfig));
            commandRegistered = true;
            LOG.info("Registered #miner, #lumber, #sorter, #mover, #builder, #digger commands.");
        } catch (Throwable t) {
            LOG.warn("Could not register worker commands: {}", t.toString());
        }
    }
}
