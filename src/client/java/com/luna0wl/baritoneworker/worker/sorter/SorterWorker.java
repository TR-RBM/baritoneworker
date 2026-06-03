package com.luna0wl.baritoneworker.worker.sorter;

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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class SorterWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritonesorter/worker");

    private final SorterConfig config;
    private final SortScheme scheme;
    private final Teleporter teleporter = new Teleporter();

    private SortState state = SortState.IDLE;
    private int ticksInState;
    private IBaritone baritone;

    private record ChestInfo(BlockPos pos, List<String> tags) {}

    private final List<ChestInfo> chests = new ArrayList<>();
    private int idx;
    private int resumeCollectIdx;
    private int collectedThisRound;
    private int roundCount;

    private enum Step { PATH, OPEN, ACT, CLOSE }
    private Step step = Step.PATH;
    private int ticksInStep;
    private int clickCooldown;
    private int actionClicks;

    public SorterWorker(SorterConfig config, SortScheme scheme) {
        this.config = config;
        this.scheme = scheme;
    }

    public boolean isRunning() {
        return state != SortState.IDLE;
    }

    public SortState getState() {
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
        if (!config.hasArea()) {
            chat(mc, "§cNo chest area set. Make a Baritone selection (#sel 1 / #sel 2) then run §e#sorter area§c.");
            return;
        }
        baritone = null;
        scheme.load();
        chat(mc, "§aStarted. home=§e" + config.home + "§a chests=§e" + config.chestBoxes.size() + " box(es)");
        setState(mc, SortState.GO_TO_HOME);
    }

    public void stop(Minecraft mc) {
        cancelBaritone();
        closeMenu(mc);
        state = SortState.IDLE;
        ticksInState = 0;
        chat(mc, "§cStopped.");
    }

    public void tick(Minecraft mc) {
        if (state == SortState.IDLE) return;

        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            state = SortState.IDLE;
            baritone = null;
            return;
        }
        if (!ensureBaritone(mc)) {
            chat(mc, "§cLost Baritone — stopping.");
            state = SortState.IDLE;
            return;
        }

        if (Baritones.isUserPaused(baritone)) return;

        ticksInState++;
        if (clickCooldown > 0) clickCooldown--;

        switch (state) {
            case GO_TO_HOME -> tickGoToHome(mc);
            case SCAN -> tickScan(mc);
            case COLLECT, DISTRIBUTE -> tickVisit(mc);
            default -> { }
        }
    }

    private void setState(Minecraft mc, SortState s) {
        state = s;
        ticksInState = 0;
        onEnter(mc, s);
    }

    private void onEnter(Minecraft mc, SortState s) {
        if (s == SortState.GO_TO_HOME) {
            if (alreadyAtArea(mc)) {
                setState(mc, SortState.SCAN);
            } else {
                teleporter.begin(mc);
                sendCommand(mc, "home " + config.home);
            }
        }

    }

    private void tickGoToHome(Minecraft mc) {
        if (teleportArrived(mc)) {
            chat(mc, "At the chest room — scanning.");
            setState(mc, SortState.SCAN);
        }
    }

    private boolean teleportArrived(Minecraft mc) {
        return teleporter.arrived(mc, config.teleportTimeoutTicks,
                config.teleportSettleTicks, config.teleportMoveThreshold);
    }

    private void tickScan(Minecraft mc) {
        chests.clear();
        List<BlockPos> positions = ContainerService.scanChests(mc.level, config.chestBoxes, mc.player.blockPosition());
        for (BlockPos pos : positions) {
            List<String> tags = new ArrayList<>(scheme.pinnedTags(pos));
            for (String sign : ContainerService.readChestTags(mc.level, pos)) {
                if (!tags.contains(sign)) tags.add(sign);
            }
            chests.add(new ChestInfo(pos, tags));
        }
        if (chests.isEmpty()) {
            chat(mc, "§cNo chests found in the selected area — stopping.");
            stop(mc);
            return;
        }
        long tagged = chests.stream().filter(c -> !c.tags().isEmpty()).count();
        chat(mc, "Found §e" + chests.size() + "§r chest(s), §e" + tagged + "§r tagged. Sorting…");
        if (tagged == 0) {
            chat(mc, "§eNo tagged chests — nothing to sort. Add signs or a sortscheme.json.");
            stop(mc);
            return;
        }
        roundCount = 0;
        collectedThisRound = 0;
        resumeCollectIdx = chests.size();
        startSweep(mc, SortState.COLLECT, 0);
    }

    private void startSweep(Minecraft mc, SortState mode, int from) {
        setState(mc, mode);
        idx = from;
        setStep(Step.PATH);
    }

    private void tickVisit(Minecraft mc) {
        ticksInStep++;

        if (idx >= chests.size()) {
            if (state == SortState.COLLECT) handleCollectEnd(mc);
            else handleDistributeEnd(mc);
            return;
        }
        BlockPos chest = chests.get(idx).pos();

        switch (step) {
            case PATH -> {
                if (ticksInStep == 1) {
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(chest));
                }
                if (RotationUtils.reachable(baritone.getPlayerContext(), chest).isPresent()) {
                    cancelBaritone();
                    setStep(Step.OPEN);
                } else if (ticksInStep > config.chestPathTimeoutTicks) {
                    chat(mc, "§eCouldn't reach chest at " + chest.toShortString() + " — skipping.");
                    cancelBaritone();
                    nextChest();
                }
            }
            case OPEN -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (menu != mc.player.inventoryMenu && menu instanceof ChestMenu) {
                    setStep(Step.ACT);
                } else if (clickCooldown <= 0) {
                    MenuActions.openChest(mc, baritone, chest);
                    clickCooldown = config.clickDelayTicks * 2;
                    if (ticksInStep > 100) {
                        chat(mc, "§eChest at " + chest.toShortString() + " wouldn't open — skipping.");
                        nextChest();
                    }
                }
            }
            case ACT -> {
                if (clickCooldown > 0) return;
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (!(menu instanceof ChestMenu)) { setStep(Step.CLOSE); return; }
                if (state == SortState.COLLECT) actCollect(mc, menu);
                else actDistribute(mc, menu);
            }
            case CLOSE -> {
                closeMenu(mc);
                nextChest();
            }
        }
    }

    private void actCollect(Minecraft mc, AbstractContainerMenu menu) {
        Inventory inv = mc.player.getInventory();

        if (ContainerService.freeSlots(inv) <= config.collectBufferSlots) {
            resumeCollectIdx = idx;
            closeMenu(mc);
            startSweep(mc, SortState.DISTRIBUTE, 0);
            return;
        }
        int bound = ContainerService.containerSlotCount(menu) + 40;
        int slot = ContainerService.nextWithdrawSlotMatching(menu, this::misplacedHere);
        if (slot == -1 || actionClicks > bound) {
            setStep(Step.CLOSE);
            return;
        }
        MenuActions.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
        actionClicks++;
        collectedThisRound++;
        clickCooldown = config.clickDelayTicks;
    }

    private void actDistribute(Minecraft mc, AbstractContainerMenu menu) {
        int bound = ContainerService.containerSlotCount(menu) + 40;
        final int here = idx;
        int slot = ContainerService.nextDepositSlotMatching(menu, item -> targetIndex(item) == here);
        if (slot == -1 || actionClicks > bound) {
            setStep(Step.CLOSE);
            return;
        }
        MenuActions.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
        actionClicks++;
        clickCooldown = config.clickDelayTicks;
    }

    private boolean misplacedHere(Item item) {
        int t = targetIndex(item);
        return t != -1 && t != idx;
    }

    private int targetIndex(Item item) {
        for (int i = 0; i < chests.size(); i++) {
            if (scheme.accepts(chests.get(i).tags(), item)) return i;
        }
        return -1;
    }

    private void handleCollectEnd(Minecraft mc) {
        if (hasPlaceable(mc)) {
            resumeCollectIdx = chests.size();
            startSweep(mc, SortState.DISTRIBUTE, 0);
        } else if (collectedThisRound > 0 && roundCount < config.maxRounds) {
            roundCount++;
            collectedThisRound = 0;
            startSweep(mc, SortState.COLLECT, 0);
        } else {
            finish(mc);
        }
    }

    private void handleDistributeEnd(Minecraft mc) {
        if (resumeCollectIdx < chests.size()) {
            startSweep(mc, SortState.COLLECT, resumeCollectIdx);
        } else if (collectedThisRound > 0 && roundCount < config.maxRounds) {
            roundCount++;
            collectedThisRound = 0;
            startSweep(mc, SortState.COLLECT, 0);
        } else {
            finish(mc);
        }
    }

    private boolean hasPlaceable(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && targetIndex(s.getItem()) != -1) return true;
        }
        return false;
    }

    private void finish(Minecraft mc) {
        int leftover = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && targetIndex(s.getItem()) != -1) leftover += s.getCount();
        }
        if (leftover > 0) {
            chat(mc, "§eDone, but §c" + leftover + "§e item(s) couldn't be placed (target chest full/unreachable).");
        } else {
            chat(mc, "Sorting complete after §e" + (roundCount + 1) + "§r round(s).");
        }
        stop(mc);
    }

    private void nextChest() {
        idx++;
        setStep(Step.PATH);
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

    private boolean alreadyAtArea(Minecraft mc) {
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

    private void chat(Minecraft mc, String msg) {
        LOG.info(msg.replaceAll("§.", ""));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§6[Sorter]§r " + msg));
        }
    }
}
