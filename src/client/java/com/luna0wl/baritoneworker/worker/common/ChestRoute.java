package com.luna0wl.baritoneworker.worker.common;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ChestRoute {

    public interface Handler {

        int nextDepositSlot(Minecraft mc, AbstractContainerMenu menu);

        int nextWithdrawSlot(Minecraft mc, AbstractContainerMenu menu);

        boolean moreWorkToDo(Minecraft mc);

        void chat(String msg);

        default String cacheKey() { return null; }

        default void debug(String msg) {}
    }

    public enum Result { RUNNING, FINISHED, BLOCKED, EMPTY }

    private enum Step { PATH, OPEN, DEPOSIT, WITHDRAW, CLOSE }

    private static final int REACH_RETRIES = 2;
    private static final int OPEN_TIMEOUT_TICKS = 100;
    private static final int RESCAN_LIMIT = 6;
    private static final int RESCAN_SETTLE_TICKS = 20;
    private static final int CHUNK_LOAD_TIMEOUT_TICKS = 400;
    private static final int PATH_TO_AREA_TIMEOUT_TICKS = 1200;
    private static final double ARRIVAL_DIST = 5.0;
    private static final int NEAR_AREA_GIVEUP_TICKS = 100;
    private static final double GHOST_CHECK_DIST = 4.0;
    private static final int STALL_LIMIT = 2;

    private final List<BlockPos> queue = new ArrayList<>();
    private final Set<BlockPos> visited = new HashSet<>();

    private Handler handler;
    private List<int[]> boxes;
    private boolean includeEnderChests;
    private boolean rescanForMore;
    private int clickDelayTicks;
    private int chestPathTimeoutTicks;

    private int index;
    private Step step = Step.PATH;
    private int ticksInStep;
    private int clickCooldown;
    private int actionClicks;
    private int lastActionSlot = -1;
    private int lastActionCount = -1;
    private int actionStalls;
    private int rescans;
    private int reachRetries;
    private int initialWaitTicks;
    private int nearTicks;
    private BlockPos blockedChest;

    private ChestCache cache;
    private boolean seededFromCache;
    private boolean reconciled;

    private Boolean savedAllowBreak;

    public int begin(Minecraft mc, List<int[]> boxes, boolean includeEnderChests,
                     int clickDelayTicks, int chestPathTimeoutTicks, boolean rescanForMore, Handler handler) {
        this.handler = handler;
        this.boxes = boxes;
        this.includeEnderChests = includeEnderChests;
        this.rescanForMore = rescanForMore;
        this.clickDelayTicks = clickDelayTicks;
        this.chestPathTimeoutTicks = chestPathTimeoutTicks;
        queue.clear();
        visited.clear();
        index = 0;
        step = Step.PATH;
        ticksInStep = 0;
        clickCooldown = 0;
        actionClicks = 0;
        rescans = 0;
        reachRetries = 0;
        initialWaitTicks = 0;
        nearTicks = 0;
        blockedChest = null;
        seededFromCache = false;
        reconciled = false;
        cache = handler.cacheKey() == null ? null : new ChestCache(handler.cacheKey());
        forceNoBreak();
        List<BlockPos> live = ContainerService.scanChests(mc.level, boxes, mc.player.blockPosition(), includeEnderChests);
        if (!live.isEmpty()) {
            queue.addAll(live);
            saveCache(live);
        } else if (cache != null) {
            for (BlockPos p : cache.load()) {
                if (inBoxes(p) && !queue.contains(p)) queue.add(p);
            }
            seededFromCache = !queue.isEmpty();
        }
        handler.debug("begin: player=" + mc.player.blockPosition().toShortString()
                + " boxes=" + boxes.size() + " liveScan=" + live.size()
                + " seededFromCache=" + seededFromCache + " queue=" + queue.size()
                + " loaded=" + ContainerService.boxesLoaded(mc.level, boxes));
        return queue.size();
    }

    public Result tick(Minecraft mc, IBaritone baritone) {
        ticksInStep++;
        if (clickCooldown > 0) clickCooldown--;

        if (index >= queue.size()) {
            return atQueueEnd(mc, baritone);
        }
        BlockPos chest = queue.get(index);
        if (visited.contains(chest)) {
            nextChest();
            return Result.RUNNING;
        }

        switch (step) {
            case PATH -> { return tickPath(mc, baritone, chest); }
            case OPEN -> { return tickOpen(mc, baritone, chest); }
            case DEPOSIT -> { return tickDeposit(mc); }
            case WITHDRAW -> { return tickWithdraw(mc); }
            case CLOSE -> { return tickClose(mc); }
        }
        return Result.RUNNING;
    }

    public BlockPos blockedChest() {
        return blockedChest;
    }

    public int visitedCount() {
        return visited.size();
    }

    public void abort() {
        restoreBreak();
    }

    private Result tickPath(Minecraft mc, IBaritone baritone, BlockPos chest) {
        reconcile(mc);
        if (seededFromCache && nearBlock(mc, chest, GHOST_CHECK_DIST)
                && !ContainerService.isStorageAt(mc.level, chest, includeEnderChests)) {
            cancelBaritone(baritone);
            nextChest();
            return Result.RUNNING;
        }
        if (ticksInStep == 1) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(chest));
        }
        if (RotationUtils.reachable(baritone.getPlayerContext(), chest).isPresent()) {
            cancelBaritone(baritone);
            setStep(Step.OPEN);
            return Result.RUNNING;
        }
        if (ticksInStep > chestPathTimeoutTicks) {
            cancelBaritone(baritone);
            return retryOrBlock(chest, "couldn't be reached");
        }
        return Result.RUNNING;
    }

    private Result tickOpen(Minecraft mc, IBaritone baritone, BlockPos chest) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != mc.player.inventoryMenu && menu instanceof ChestMenu) {
            setStep(Step.DEPOSIT);
            return Result.RUNNING;
        }
        if (clickCooldown <= 0) {
            MenuActions.openChest(mc, baritone, chest);
            clickCooldown = clickDelayTicks * 2;
        }
        if (ticksInStep > OPEN_TIMEOUT_TICKS) {
            return retryOrBlock(chest, "wouldn't open");
        }
        return Result.RUNNING;
    }

    private Result tickDeposit(Minecraft mc) {
        if (clickCooldown > 0) return Result.RUNNING;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof ChestMenu)) { setStep(Step.CLOSE); return Result.RUNNING; }
        int bound = ContainerService.containerSlotCount(menu) + 40;
        int slot = handler.nextDepositSlot(mc, menu);
        if (slot == -1 || actionClicks > bound || stalled(menu, slot)) {
            actionClicks = 0;
            setStep(Step.WITHDRAW);
            return Result.RUNNING;
        }
        MenuActions.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
        actionClicks++;
        clickCooldown = clickDelayTicks;
        return Result.RUNNING;
    }

    private Result tickWithdraw(Minecraft mc) {
        if (clickCooldown > 0) return Result.RUNNING;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof ChestMenu)) { setStep(Step.CLOSE); return Result.RUNNING; }
        int bound = ContainerService.containerSlotCount(menu) + 40;
        int slot = handler.nextWithdrawSlot(mc, menu);
        if (slot == -1 || actionClicks > bound || stalled(menu, slot)) {
            actionClicks = 0;
            setStep(Step.CLOSE);
            return Result.RUNNING;
        }
        MenuActions.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
        actionClicks++;
        clickCooldown = clickDelayTicks;
        return Result.RUNNING;
    }

    private Result tickClose(Minecraft mc) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        nextChest();
        if (!handler.moreWorkToDo(mc)) {
            return finish();
        }
        return Result.RUNNING;
    }

    private Result atQueueEnd(Minecraft mc, IBaritone baritone) {
        if (visited.isEmpty()) {
            return awaitInitialChests(mc, baritone);
        }
        if (rescanForMore && handler.moreWorkToDo(mc) && rescans < RESCAN_LIMIT) {
            if (ticksInStep < RESCAN_SETTLE_TICKS) return Result.RUNNING;
            int before = queue.size();
            rescan(mc);
            if (queue.size() > before) {
                rescans = 0;
                handler.chat("Found more chests in the area — checking those (" + queue.size() + " total).");
            } else {
                rescans++;
            }
            setStep(Step.PATH);
            return Result.RUNNING;
        }
        return finish();
    }

    private Result awaitInitialChests(Minecraft mc, IBaritone baritone) {
        boolean nearArea = !boxes.isEmpty() && nearCenter(mc, ARRIVAL_DIST);
        boolean pathing = baritone != null && baritone.getPathingBehavior().isPathing();
        if (!nearArea && baritone != null && !boxes.isEmpty() && !pathing) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(boxCenter()));
            pathing = true;
        }
        if (ticksInStep < RESCAN_SETTLE_TICKS) return Result.RUNNING;
        initialWaitTicks += ticksInStep;
        if (nearArea) nearTicks += ticksInStep;
        rescan(mc);
        handler.debug("await: nearArea=" + nearArea + " pathing=" + pathing
                + " waited=" + initialWaitTicks + "t nearTicks=" + nearTicks
                + " player=" + mc.player.blockPosition().toShortString()
                + " center=" + (boxes.isEmpty() ? "-" : boxCenter().toShortString())
                + " found=" + queue.size());
        if (!queue.isEmpty()) {
            rescans = 0;
            cancelBaritone(baritone);
            saveCache(new ArrayList<>(queue));
            handler.chat("Found " + queue.size() + " chest(s) to service.");
            setStep(Step.PATH);
            return Result.RUNNING;
        }
        boolean giveUp = nearArea
                ? nearTicks >= NEAR_AREA_GIVEUP_TICKS
                : initialWaitTicks >= (pathing ? PATH_TO_AREA_TIMEOUT_TICKS : CHUNK_LOAD_TIMEOUT_TICKS);
        if (!giveUp) {
            ticksInStep = 0;
            return Result.RUNNING;
        }
        cancelBaritone(baritone);
        restoreBreak();
        return Result.EMPTY;
    }

    private boolean nearCenter(Minecraft mc, double dist) {
        return nearBlock(mc, boxCenter(), dist);
    }

    private boolean nearBlock(Minecraft mc, BlockPos c, double dist) {
        double dx = mc.player.getX() - (c.getX() + 0.5);
        double dy = mc.player.getY() - (c.getY() + 0.5);
        double dz = mc.player.getZ() - (c.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= dist * dist;
    }

    private BlockPos boxCenter() {
        int[] b = boxes.get(0);
        return new BlockPos((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
    }

    private Result retryOrBlock(BlockPos chest, String why) {
        reachRetries++;
        if (reachRetries > REACH_RETRIES) {
            blockedChest = chest;
            restoreBreak();
            return Result.BLOCKED;
        }
        handler.chat("§eChest at " + chest.toShortString() + " " + why
                + " without breaking blocks — retrying (" + reachRetries + "/" + REACH_RETRIES + ").");
        setStep(Step.PATH);
        return Result.RUNNING;
    }

    private Result finish() {
        restoreBreak();
        return Result.FINISHED;
    }

    private void nextChest() {
        if (index < queue.size()) visited.add(queue.get(index));
        index++;
        reachRetries = 0;
        setStep(Step.PATH);
    }

    private void reconcile(Minecraft mc) {
        if (reconciled || cache == null || boxes.isEmpty()) return;
        if (!nearCenter(mc, ARRIVAL_DIST)) return;
        reconciled = true;
        List<BlockPos> live = ContainerService.scanChests(mc.level, boxes, mc.player.blockPosition(), includeEnderChests);
        if (seededFromCache) {
            for (BlockPos p : live) {
                if (!queue.contains(p) && !visited.contains(p)) queue.add(p);
            }
        }
        saveCache(live);
    }

    private void saveCache(List<BlockPos> coords) {
        if (cache != null && !coords.isEmpty()) cache.save(coords);
    }

    private boolean inBoxes(BlockPos p) {
        int x = p.getX(), y = p.getY(), z = p.getZ();
        for (int[] b : boxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return true;
        }
        return false;
    }

    private void rescan(Minecraft mc) {
        for (BlockPos p : ContainerService.scanChests(mc.level, boxes, mc.player.blockPosition(), includeEnderChests)) {
            if (!queue.contains(p) && !visited.contains(p)) {
                queue.add(p);
            }
        }
    }

    private void setStep(Step s) {
        step = s;
        ticksInStep = 0;
        clickCooldown = 0;
        actionClicks = 0;
        lastActionSlot = -1;
        lastActionCount = -1;
        actionStalls = 0;
    }

    private boolean stalled(AbstractContainerMenu menu, int slot) {
        int count = menu.getSlot(slot).getItem().getCount();
        if (slot == lastActionSlot && lastActionCount != -1 && count >= lastActionCount) {
            actionStalls++;
        } else {
            actionStalls = 0;
        }
        lastActionSlot = slot;
        lastActionCount = count;
        return actionStalls >= STALL_LIMIT;
    }

    private void cancelBaritone(IBaritone baritone) {
        if (baritone != null) {
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    private void forceNoBreak() {
        if (savedAllowBreak == null) {
            savedAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
        }
        BaritoneAPI.getSettings().allowBreak.value = false;
    }

    private void restoreBreak() {
        if (savedAllowBreak != null) {
            BaritoneAPI.getSettings().allowBreak.value = savedAllowBreak;
            savedAllowBreak = null;
        }
    }
}
