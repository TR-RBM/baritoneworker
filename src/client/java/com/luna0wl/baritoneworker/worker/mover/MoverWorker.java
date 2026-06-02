package com.luna0wl.baritoneworker.worker.mover;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.RotationUtils;
import com.luna0wl.baritoneworker.worker.common.Baritones;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.MenuActions;
import com.luna0wl.baritoneworker.worker.common.Teleporter;
import com.luna0wl.baritoneworker.worker.sorter.SortScheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class MoverWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritonemover/worker");

    private final MoverConfig config;
    private final SortScheme scheme;
    private final Teleporter teleporter = new Teleporter();

    private MoverState state = MoverState.IDLE;
    private int ticksInState;
    private IBaritone baritone;

    private record ChestInfo(BlockPos pos, List<String> tags) {}

    private final List<BlockPos> sourceChests = new ArrayList<>();
    private final List<ChestInfo> destChests = new ArrayList<>();
    private boolean sourceScanned;
    private boolean destScanned;

    private int srcIdx;
    private boolean sourceEmptied;
    private int putIdx;
    private int placedThisPut;

    private enum Step { PATH, OPEN, ACT, CLOSE }
    private Step step = Step.PATH;
    private int ticksInStep;
    private int clickCooldown;
    private int actionClicks;

    public MoverWorker(MoverConfig config, SortScheme scheme) {
        this.config = config;
        this.scheme = scheme;
    }

    public boolean isRunning() {
        return state != MoverState.IDLE;
    }

    public MoverState getState() {
        return state;
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
        if (!config.hasSource() || !config.hasDest()) {
            chat(mc, "§cSet both areas first: §e#mover source§c and §e#mover dest§c (after #sel 1/#sel 2).");
            return;
        }
        baritone = null;
        if (config.mode == MoverConfig.Mode.SORT) scheme.load();
        sourceScanned = false;
        destScanned = false;
        srcIdx = 0;
        chat(mc, "§aStarted. " + config.sourceHome + " → " + config.destHome + " (mode=§e" + config.mode + "§a)");
        setState(mc, MoverState.GO_TO_SOURCE);
    }

    public void stop(Minecraft mc) {
        cancelBaritone();
        closeMenu(mc);
        state = MoverState.IDLE;
        ticksInState = 0;
        chat(mc, "§cStopped.");
    }

    public void tick(Minecraft mc) {
        if (state == MoverState.IDLE) return;

        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            state = MoverState.IDLE;
            baritone = null;
            return;
        }
        if (!ensureBaritone(mc)) {
            chat(mc, "§cLost Baritone — stopping.");
            state = MoverState.IDLE;
            return;
        }

        ticksInState++;
        if (clickCooldown > 0) clickCooldown--;

        switch (state) {
            case GO_TO_SOURCE -> tickGoToSource(mc);
            case TAKE -> tickTake(mc);
            case GO_TO_DEST -> tickGoToDest(mc);
            case PUT -> tickPut(mc);
            default -> { }
        }
    }

    private void setState(Minecraft mc, MoverState s) {
        state = s;
        ticksInState = 0;
        onEnter(mc, s);
    }

    private void onEnter(Minecraft mc, MoverState s) {
        switch (s) {
            case GO_TO_SOURCE -> {
                if (inArea(mc, config.sourceBoxes)) {
                    arriveSource(mc);
                } else {
                    teleporter.begin(mc);
                    sendCommand(mc, "home " + config.sourceHome);
                }
            }
            case GO_TO_DEST -> {
                if (inArea(mc, config.destBoxes)) {
                    arriveDest(mc);
                } else {
                    teleporter.begin(mc);
                    sendCommand(mc, "home " + config.destHome);
                }
            }
            default -> { }
        }
    }

    private boolean teleportArrived(Minecraft mc) {
        return teleporter.arrived(mc, config.teleportTimeoutTicks,
                config.teleportSettleTicks, config.teleportMoveThreshold);
    }

    private void tickGoToSource(Minecraft mc) {
        if (teleportArrived(mc)) arriveSource(mc);
    }

    private void arriveSource(Minecraft mc) {
        if (!sourceScanned) {
            sourceChests.clear();
            sourceChests.addAll(ContainerService.scanChests(mc.level, config.sourceBoxes, mc.player.blockPosition()));
            sourceScanned = true;
            chat(mc, "Source: §e" + sourceChests.size() + "§r chest(s).");
            if (sourceChests.isEmpty()) {
                chat(mc, "§cNo chests in the source area — stopping.");
                stop(mc);
                return;
            }
        }
        if (srcIdx >= sourceChests.size()) {
            finish(mc, "Move complete.");
            return;
        }
        state = MoverState.TAKE;
        ticksInState = 0;
        sourceEmptied = false;
        setStep(Step.PATH);
    }

    private void tickGoToDest(Minecraft mc) {
        if (teleportArrived(mc)) arriveDest(mc);
    }

    private void arriveDest(Minecraft mc) {
        if (!destScanned) {
            destChests.clear();
            for (BlockPos pos : ContainerService.scanChests(mc.level, config.destBoxes, mc.player.blockPosition())) {
                List<String> tags = new ArrayList<>(scheme.pinnedTags(pos));
                for (String sign : ContainerService.readChestTags(mc.level, pos)) {
                    if (!tags.contains(sign)) tags.add(sign);
                }
                destChests.add(new ChestInfo(pos, tags));
            }
            destScanned = true;
            chat(mc, "Destination: §e" + destChests.size() + "§r chest(s).");
            if (destChests.isEmpty()) {
                chat(mc, "§cNo chests in the destination area — stopping.");
                stop(mc);
                return;
            }
        }
        state = MoverState.PUT;
        ticksInState = 0;
        placedThisPut = 0;

        putIdx = (config.mode == MoverConfig.Mode.COPY)
                ? Math.min(srcIdx, destChests.size() - 1)
                : 0;
        setStep(Step.PATH);
    }

    private void tickTake(Minecraft mc) {
        ticksInStep++;
        BlockPos chest = sourceChests.get(srcIdx);

        switch (step) {
            case PATH -> pathTo(mc, chest);
            case OPEN -> openOr(mc, chest);
            case ACT -> {
                if (clickCooldown > 0) return;
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (!(menu instanceof ChestMenu)) { setStep(Step.CLOSE); return; }
                Inventory inv = mc.player.getInventory();
                if (!ContainerService.hasFreePlayerSlot(menu) || ContainerService.freeSlots(inv) <= 0) {
                    sourceEmptied = false;
                    setStep(Step.CLOSE);
                    return;
                }
                int slot = ContainerService.firstNonEmptyContainerSlot(menu);
                int bound = ContainerService.containerSlotCount(menu) + 40;
                if (slot == -1 || actionClicks > bound) {
                    sourceEmptied = (slot == -1);
                    setStep(Step.CLOSE);
                    return;
                }
                MenuActions.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
                actionClicks++;
                clickCooldown = config.clickDelayTicks;
            }
            case CLOSE -> {
                closeMenu(mc);
                boolean carrying = ContainerService.freeSlots(mc.player.getInventory()) < 36;
                if (sourceEmptied && !carrying) {

                    srcIdx++;
                    if (srcIdx >= sourceChests.size()) {
                        finish(mc, "Move complete.");
                    } else {
                        ticksInState = 0;
                        setStep(Step.PATH);
                    }
                } else {
                    setState(mc, MoverState.GO_TO_DEST);
                }
            }
        }
    }

    private void tickPut(Minecraft mc) {
        ticksInStep++;

        if (putIdx >= destChests.size()) {
            handlePutEnd(mc);
            return;
        }
        BlockPos chest = destChests.get(putIdx).pos();

        switch (step) {
            case PATH -> pathTo(mc, chest);
            case OPEN -> openOr(mc, chest);
            case ACT -> {
                if (clickCooldown > 0) return;
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (!(menu instanceof ChestMenu)) { setStep(Step.CLOSE); return; }
                int bound = ContainerService.containerSlotCount(menu) + 40;
                int slot;
                if (config.mode == MoverConfig.Mode.COPY) {

                    if (!ContainerService.hasEmptyContainerSlot(menu)) { setStep(Step.CLOSE); return; }
                    slot = ContainerService.nextDepositSlotMatching(menu, item -> true);
                } else {
                    final int here = putIdx;
                    slot = ContainerService.nextDepositSlotMatching(menu, item -> destTargetIndex(item) == here);
                }
                if (slot == -1 || actionClicks > bound) {
                    setStep(Step.CLOSE);
                    return;
                }
                MenuActions.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
                actionClicks++;
                placedThisPut++;
                clickCooldown = config.clickDelayTicks;
            }
            case CLOSE -> {
                closeMenu(mc);
                putIdx++;
                ticksInStep = 0;

                if (ContainerService.freeSlots(mc.player.getInventory()) >= 36) {
                    handlePutEnd(mc);
                } else {
                    setStep(Step.PATH);
                }
            }
        }
    }

    private void handlePutEnd(Minecraft mc) {
        boolean carrying = ContainerService.freeSlots(mc.player.getInventory()) < 36;
        if (carrying && placedThisPut == 0) {
            finish(mc, "§eStopped — destination chests are full (still carrying items).");
            return;
        }
        if (sourceEmptied) {
            srcIdx++;
            if (srcIdx >= sourceChests.size() && !carrying) {
                finish(mc, "Move complete.");
                return;
            }
        }
        setState(mc, MoverState.GO_TO_SOURCE);
    }

    private int destTargetIndex(Item item) {
        for (int i = 0; i < destChests.size(); i++) {
            if (scheme.accepts(destChests.get(i).tags(), item)) return i;
        }
        return -1;
    }

    private void pathTo(Minecraft mc, BlockPos chest) {
        if (ticksInStep == 1) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(chest));
        }
        if (RotationUtils.reachable(baritone.getPlayerContext(), chest).isPresent()) {
            cancelBaritone();
            setStep(Step.OPEN);
        } else if (ticksInStep > config.chestPathTimeoutTicks) {
            chat(mc, "§eCouldn't reach chest at " + chest.toShortString() + " — skipping.");
            cancelBaritone();
            skipChest(mc);
        }
    }

    private void openOr(Minecraft mc, BlockPos chest) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != mc.player.inventoryMenu && menu instanceof ChestMenu) {
            setStep(Step.ACT);
        } else if (clickCooldown <= 0) {
            MenuActions.openChest(mc, baritone, chest);
            clickCooldown = config.clickDelayTicks * 2;
            if (ticksInStep > 100) {
                chat(mc, "§eChest at " + chest.toShortString() + " wouldn't open — skipping.");
                skipChest(mc);
            }
        }
    }

    private void skipChest(Minecraft mc) {
        if (state == MoverState.TAKE) {
            sourceEmptied = true;
            setStep(Step.CLOSE);
        } else {
            putIdx++;
            ticksInStep = 0;
            setStep(Step.PATH);
        }
    }

    private void setStep(Step s) {
        step = s;
        ticksInStep = 0;
        clickCooldown = 0;
        actionClicks = 0;
    }

    private void closeMenu(Minecraft mc) {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
    }

    private void sendCommand(Minecraft mc, String command) {
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            conn.sendCommand(command);
            LOG.info("/{}", command);
        }
    }

    private boolean inArea(Minecraft mc, List<int[]> boxes) {
        int m = config.chestAreaMargin;
        BlockPos pp = mc.player.blockPosition();
        int x = pp.getX(), y = pp.getY(), z = pp.getZ();
        for (int[] b : boxes) {
            if (x >= b[0] - m && x <= b[3] + m
                    && y >= b[1] - m && y <= b[4] + m
                    && z >= b[2] - m && z <= b[5] + m) {
                return true;
            }
        }
        return false;
    }

    private void finish(Minecraft mc, String msg) {
        chat(mc, msg);
        stop(mc);
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

    private void chat(Minecraft mc, String msg) {
        LOG.info(msg.replaceAll("§.", ""));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§b[Mover]§r " + msg));
        }
    }
}
