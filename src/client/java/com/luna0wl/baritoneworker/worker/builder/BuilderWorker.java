package com.luna0wl.baritoneworker.worker.builder;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.RotationUtils;
import com.luna0wl.baritoneworker.worker.common.Baritones;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.MenuActions;
import com.luna0wl.baritoneworker.worker.common.Teleporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The autonomous builder loop, driven once per client tick. Baritone builds the
 * schematic currently open in Litematica; the worker watches the build and, when
 * it runs out of materials, resets the build home to the current spot, teleports
 * to the supply room, refills on blocks (and food), teleports back, and resumes —
 * the same loop the miner uses, triggered by low materials instead of a full bag.
 *
 * <p>Stops when the optional coordinate target is reached, or when a refill can't
 * find more blocks and the build can't progress.
 */
public final class BuilderWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/builder");

    /** "Materials" = any placeable block item. */
    private static final Predicate<Item> MATERIAL = item -> item instanceof BlockItem;

    private final BuilderConfig config;
    private final Teleporter teleporter = new Teleporter();

    private BuilderState state = BuilderState.IDLE;
    private int ticksInState;
    private IBaritone baritone;

    // --- BUILD progress tracking ---
    private boolean becameActive;   // did Baritone actually start building this session?
    private int idleTicks;          // ticks the builder has sat inactive
    private boolean nudged;         // re-issued the build once while waiting to start

    /** Anchor for a file build, captured once per session so re-issues stay put (null = none yet). */
    private BlockPos fileOrigin;

    // --- chest servicing sub-state (withdraw only — the builder hoards, never dumps) ---
    private enum ChestStep { PATH, OPEN, WITHDRAW, CLOSE }
    private final List<BlockPos> chestQueue = new ArrayList<>();
    private int chestIndex;
    private ChestStep chestStep = ChestStep.PATH;
    private int ticksInStep;
    private int clickCooldown;
    private int actionClicks;
    private int blocksBeforeService;

    public BuilderWorker(BuilderConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------- public API

    public boolean isRunning() {
        return state != BuilderState.IDLE;
    }

    public BuilderState getState() {
        return state;
    }

    public void toggle(Minecraft mc) {
        if (isRunning()) stop(mc);
        else start(mc);
    }

    public void start(Minecraft mc) {
        if (isRunning()) {
            chat(mc, "Already running (" + state + ").");
            return;
        }
        if (!Baritones.isLoaded()) {
            chat(mc, "§cBaritone is not installed — cannot start.");
            return;
        }
        if (mc.player == null || mc.level == null) {
            chat(mc, "§cJoin a world first.");
            return;
        }
        baritone = null;
        fileOrigin = null;
        String source = config.hasSchematicFile()
                ? "§a file=§e" + config.schematicFile
                : "§a litematic=§e#" + config.litematicIndex;
        chat(mc, "§aStarted. buildHome=§e" + config.workHome + "§a baseHome=§e" + config.baseHome
                + source
                + (config.hasArea() ? "" : "§a (no supply area — build-only, won't restock)")
                + (config.hasStop() ? "§a stopAt=§e" + posStr(config.stopPos) : ""));

        int blocks = ContainerService.countMatching(mc.player.getInventory(), MATERIAL);
        if (blocks == 0) {
            if (!config.hasArea()) {
                chat(mc, "§cNo blocks on hand and no supply area set. Put blocks in your inventory, "
                        + "or set a supply area with §e#sel 1§c/§e#sel 2§c then §e#builder area§c.");
                state = BuilderState.IDLE;
                return;
            }
            chat(mc, "No blocks on hand — fetching materials first.");
            setState(mc, BuilderState.GO_TO_HOME);
        } else {
            setState(mc, BuilderState.GO_TO_WORK);
        }
    }

    public void stop(Minecraft mc) {
        cancelBaritone();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        state = BuilderState.IDLE;
        ticksInState = 0;
        chat(mc, "§cStopped.");
    }

    // ----------------------------------------------------------------- tick

    public void tick(Minecraft mc) {
        if (state == BuilderState.IDLE) return;

        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            state = BuilderState.IDLE;
            baritone = null;
            return;
        }
        if (!ensureBaritone(mc)) {
            chat(mc, "§cLost Baritone — stopping.");
            state = BuilderState.IDLE;
            return;
        }

        ticksInState++;
        if (clickCooldown > 0) clickCooldown--;

        switch (state) {
            case GO_TO_WORK -> tickGoToWork(mc);
            case BUILD -> tickBuild(mc);
            case RESET_HOME -> tickResetHome(mc);
            case GO_TO_HOME -> tickGoToHome(mc);
            case SERVICE_CHESTS -> tickServiceChests(mc);
            default -> { }
        }
    }

    // ----------------------------------------------------------- state logic

    private void setState(Minecraft mc, BuilderState s) {
        state = s;
        ticksInState = 0;
        onEnter(mc, s);
    }

    private void onEnter(Minecraft mc, BuilderState s) {
        switch (s) {
            case GO_TO_WORK -> {
                if (near(mc, config.workPos, config.skipTeleportRange)) {
                    chat(mc, "Already at the build site — building.");
                    setState(mc, BuilderState.BUILD);
                } else {
                    teleporter.begin(mc);
                    sendCommand(mc, "home " + config.workHome);
                }
            }
            case BUILD -> {
                becameActive = false;
                idleTicks = 0;
                nudged = false;
                issueBuild(mc);
            }
            case RESET_HOME -> cancelBaritone();
            case GO_TO_HOME -> {
                if (alreadyAtBase(mc)) {
                    chat(mc, "Already at base — servicing chests without teleport.");
                    setState(mc, BuilderState.SERVICE_CHESTS);
                } else {
                    teleporter.begin(mc);
                    sendCommand(mc, "home " + config.baseHome);
                }
            }
            case SERVICE_CHESTS -> enterServiceChests(mc);
            default -> { }
        }
    }

    private boolean teleportArrived(Minecraft mc) {
        return teleporter.arrived(mc, config.teleportTimeoutTicks,
                config.teleportSettleTicks, config.teleportMoveThreshold);
    }

    private void tickGoToWork(Minecraft mc) {
        if (teleportArrived(mc)) {
            rememberWorkPos(mc);
            chat(mc, "At the build site — building.");
            setState(mc, BuilderState.BUILD);
        }
    }

    private void tickBuild(Minecraft mc) {
        // Optional coordinate stop (works for one-shot and repeating builds).
        if (config.hasStop() && near(mc, config.stopPos, config.stopRadius)) {
            chat(mc, "Reached the stop coordinates — done.");
            stop(mc);
            return;
        }

        boolean active = baritone.getBuilderProcess().isActive() || baritone.getPathingBehavior().isPathing();
        if (active) {
            becameActive = true;
            idleTicks = 0;
            return;
        }

        idleTicks++;
        if (!becameActive) {
            // Builder hasn't started since we (re)issued it.
            if (idleTicks == Math.max(1, config.idleReissueTicks) && !nudged) {
                nudged = true;
                issueBuild(mc); // nudge once in case the first issue didn't take
            } else if (idleTicks > config.resumeGraceTicks) {
                chat(mc, "§eNothing to build — schematic complete, no open Litematica placement, or out of supplies. Stopping.");
                stop(mc);
            }
        } else {
            // It was building and has now stopped → out of materials (or finished).
            if (idleTicks > Math.max(1, config.idleReissueTicks)) {
                int blocks = ContainerService.countMatching(mc.player.getInventory(), MATERIAL);
                if (!config.hasArea()) {
                    chat(mc, "§eStopped (blocks=" + blocks + "). No supply area set, so nothing to restock from — "
                            + "schematic may be complete, or refill your inventory and start again.");
                    stop(mc);
                    return;
                }
                chat(mc, "Out of materials (blocks=" + blocks + ") — restocking.");
                cancelBaritone();
                setState(mc, BuilderState.RESET_HOME);
            }
        }
    }

    private void tickResetHome(Minecraft mc) {
        int g = Math.max(1, config.commandGapTicks);
        if (ticksInState == g) {
            sendCommand(mc, "delhome " + config.workHome);
        } else if (ticksInState == 2 * g) {
            sendCommand(mc, "sethome " + config.workHome);
            rememberWorkPos(mc); // the new build home is right here, where we left off
        } else if (ticksInState >= 3 * g) {
            chat(mc, "Build home reset to current spot — heading to base.");
            setState(mc, BuilderState.GO_TO_HOME);
        }
    }

    private void tickGoToHome(Minecraft mc) {
        if (teleportArrived(mc)) {
            config.homePos = posOf(mc);
            config.save();
            chat(mc, "At base — fetching materials.");
            setState(mc, BuilderState.SERVICE_CHESTS);
        }
    }

    // ------------------------------------------------------------- building

    private void issueBuild(Minecraft mc) {
        if (baritone == null) return;
        if (config.hasSchematicFile()) {
            issueFileBuild(mc);
        } else {
            baritone.getBuilderProcess().buildOpenLitematic(config.litematicIndex);
        }
    }

    /** Build a schematic file from the {@code schematics/} folder, like {@code #build <file>}. */
    private void issueFileBuild(Minecraft mc) {
        File file = resolveSchematic(mc);
        if (!file.exists()) {
            chat(mc, "§cSchematic not found: §e" + file.getName()
                    + "§c. Put it in §eschematics/§c (next to your Baritone schematics). Stopping.");
            stop(mc);
            return;
        }
        BlockPos origin = resolveOrigin(mc);
        boolean ok = baritone.getBuilderProcess().build(file.getName(), file, origin);
        if (!ok) {
            chat(mc, "§cCouldn't load §e" + file.getName()
                    + "§c — unsupported format or corrupt file. Stopping.");
            stop(mc);
        }
    }

    /** Resolve the configured schematic name against {@code schematics/}, adding the fallback extension if missing. */
    private File resolveSchematic(Minecraft mc) {
        File f = new File(config.schematicFile);
        if (!f.isAbsolute()) {
            f = new File(new File(mc.gameDirectory, "schematics"), config.schematicFile);
        }
        if (!f.getName().contains(".")) {
            f = new File(f.getAbsolutePath() + "." + BaritoneAPI.getSettings().schematicFallbackExtension.value);
        }
        return f;
    }

    /** Origin (corner) for a file build: the configured coords, else the spot where we first start placing. */
    private BlockPos resolveOrigin(Minecraft mc) {
        if (config.buildOriginPos != null) {
            int[] o = config.buildOriginPos;
            return new BlockPos(o[0], o[1], o[2]);
        }
        if (fileOrigin == null) {
            fileOrigin = mc.player.blockPosition();
        }
        return fileOrigin;
    }

    // ------------------------------------------------------- chest servicing

    private void enterServiceChests(Minecraft mc) {
        chestQueue.clear();
        chestIndex = 0;
        chestStep = ChestStep.PATH;
        ticksInStep = 0;
        actionClicks = 0;
        blocksBeforeService = ContainerService.countMatching(mc.player.getInventory(), MATERIAL);
        chestQueue.addAll(ContainerService.scanChests(mc.level, config.chestBoxes, mc.player.blockPosition()));
        if (chestQueue.isEmpty()) {
            chat(mc, "§cNo chests found in the supply area — stopping.");
            stop(mc);
            return;
        }
        chat(mc, "Found §e" + chestQueue.size() + "§r supply chest(s).");
    }

    private void tickServiceChests(Minecraft mc) {
        ticksInStep++;

        if (chestIndex >= chestQueue.size()) {
            finishService(mc);
            return;
        }
        BlockPos chest = chestQueue.get(chestIndex);

        switch (chestStep) {
            case PATH -> {
                if (ticksInStep == 1) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(chest));
                }
                if (RotationUtils.reachable(baritone.getPlayerContext(), chest).isPresent()) {
                    cancelBaritone();
                    setStep(ChestStep.OPEN);
                } else if (ticksInStep > config.chestPathTimeoutTicks) {
                    chat(mc, "§eCouldn't reach chest at " + chest.toShortString() + " — skipping.");
                    cancelBaritone();
                    nextChest();
                }
            }
            case OPEN -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (menu != mc.player.inventoryMenu && menu instanceof ChestMenu) {
                    setStep(ChestStep.WITHDRAW);
                } else if (clickCooldown <= 0) {
                    MenuActions.openChest(mc, baritone, chest);
                    clickCooldown = config.clickDelayTicks * 2;
                    if (ticksInStep > 100) {
                        chat(mc, "§eChest at " + chest.toShortString() + " wouldn't open — skipping.");
                        nextChest();
                    }
                }
            }
            case WITHDRAW -> {
                if (clickCooldown > 0) return;
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (!(menu instanceof ChestMenu)) { setStep(ChestStep.CLOSE); return; }
                int bound = ContainerService.containerSlotCount(menu) + 40;
                int slot = nextSupplyWithdrawSlot(mc, menu);
                if (slot == -1 || actionClicks > bound) {
                    actionClicks = 0;
                    setStep(ChestStep.CLOSE);
                    return;
                }
                MenuActions.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
                actionClicks++;
                clickCooldown = config.clickDelayTicks;
            }
            case CLOSE -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
                nextChest();
                if (!moreWorkToDo(mc)) {
                    finishService(mc);
                }
            }
        }
    }

    /** Next supply slot to pull: food up to target, then fill remaining slots with blocks. */
    private int nextSupplyWithdrawSlot(Minecraft mc, AbstractContainerMenu menu) {
        Inventory inv = mc.player.getInventory();
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, config.foodItem);
            if (s != -1) return s;
        }
        if (ContainerService.freeSlots(inv) > 0) {
            return ContainerService.nextWithdrawSlotMatching(menu, MATERIAL);
        }
        return -1;
    }

    private boolean moreWorkToDo(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) return true;
        return ContainerService.freeSlots(inv) > 0; // still room for more materials
    }

    private void finishService(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        int blocks = ContainerService.countMatching(inv, MATERIAL);
        int food = ContainerService.countItem(inv, config.foodItem);
        if (blocks == 0) {
            chat(mc, "§cSupply chests are out of blocks — stopping.");
            stop(mc);
            return;
        }
        if (blocks <= blocksBeforeService) {
            chat(mc, "§eCouldn't add more blocks (chests low) — building with what's on hand (blocks=" + blocks + ").");
        } else {
            chat(mc, "Restocked (blocks=" + blocks + ", food=" + food + "). Back to building.");
        }
        setState(mc, BuilderState.GO_TO_WORK);
    }

    private void nextChest() {
        chestIndex++;
        setStep(ChestStep.PATH);
    }

    private void setStep(ChestStep s) {
        chestStep = s;
        ticksInStep = 0;
        clickCooldown = 0;
        actionClicks = 0;
    }

    // ------------------------------------------------------------- low level

    private void sendCommand(Minecraft mc, String command) {
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            conn.sendCommand(command);
            LOG.info("/{}", command);
        }
    }

    private int[] posOf(Minecraft mc) {
        BlockPos p = mc.player.blockPosition();
        return new int[]{p.getX(), p.getY(), p.getZ()};
    }

    private void rememberWorkPos(Minecraft mc) {
        config.workPos = posOf(mc);
        config.save();
    }

    private boolean near(Minecraft mc, int[] pos, double range) {
        if (pos == null) return false;
        double dx = mc.player.getX() - (pos[0] + 0.5);
        double dy = mc.player.getY() - pos[1];
        double dz = mc.player.getZ() - (pos[2] + 0.5);
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private boolean alreadyAtBase(Minecraft mc) {
        if (!config.hasArea()) return false;
        int m = config.chestAreaMargin;
        BlockPos pp = mc.player.blockPosition();
        int x = pp.getX(), y = pp.getY(), z = pp.getZ();
        for (int[] b : config.chestBoxes) {
            if (x >= b[0] - m && x <= b[3] + m
                    && y >= b[1] - m && y <= b[4] + m
                    && z >= b[2] - m && z <= b[5] + m) {
                return true;
            }
        }
        return false;
    }

    private void cancelBaritone() {
        if (baritone != null) {
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    private boolean ensureBaritone(Minecraft mc) {
        if (baritone != null) return true;
        baritone = Baritones.resolve(mc);
        return baritone != null;
    }

    private static String posStr(int[] p) {
        return p == null ? "?" : p[0] + " " + p[1] + " " + p[2];
    }

    private void chat(Minecraft mc, String msg) {
        LOG.info(msg.replaceAll("§.", ""));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§d[Builder]§r " + msg));
        }
    }
}
