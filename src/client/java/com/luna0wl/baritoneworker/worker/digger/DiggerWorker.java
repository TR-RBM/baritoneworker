package com.luna0wl.baritoneworker.worker.digger;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.RotationUtils;
import com.luna0wl.baritoneworker.worker.common.Baritones;
import com.luna0wl.baritoneworker.worker.common.ChestRoute;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.MenuActions;
import com.luna0wl.baritoneworker.worker.common.Teleporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DiggerWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritonedigger/worker");

    private static final int MAX_LAYER_RESCANS = 3;
    private static final long MAX_LAYER_CELLS = 500_000L;

    private final DiggerConfig config;
    private final Teleporter teleporter = new Teleporter();
    private final ChestRoute chestRoute = new ChestRoute();

    private DiggerState state = DiggerState.IDLE;
    private int ticksInState;
    private IBaritone baritone;

    private Boolean savedAllowBreak;

    // After a dump trip, decide whether a supply trip is still needed before resuming.
    private boolean needSupplyAfterDump;

    // --- dig engine ---
    private enum Phase { SCAN, MOVE, BREAK, BUCKET }

    private final List<BlockPos> layer = new ArrayList<>();
    private int cursor;
    private int currentY;
    private boolean digInitialised;
    private int layerRescans;

    private Phase phase = Phase.SCAN;
    private BlockPos actPos;
    private int phaseTicks;
    private int clickCooldown;

    private final Set<Long> skipped = new HashSet<>();
    private int reportedSkips;
    private boolean fluidPending;

    public DiggerWorker(DiggerConfig config) {
        this.config = config;
    }

    public boolean isRunning() {
        return state != DiggerState.IDLE;
    }

    public DiggerState getState() {
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
        if (!config.hasRegion()) {
            chat(mc, "§cNo dig region set. Make a Baritone selection (#sel 1 / #sel 2) then run §e#digger area§c.");
            return;
        }
        if (!config.hasSupply()) {
            chat(mc, "§cNo supply chests set. Select your tool/food chests and run §e#digger supply§c.");
            return;
        }
        baritone = null;
        chestRoute.abort();
        skipped.clear();
        reportedSkips = 0;
        fluidPending = false;
        digInitialised = false;
        chat(mc, "§aStarted. region=§e" + config.digBoxes.size() + "§a box(es) supply=§e" + config.supplyHome
                + "§a dump=§e" + (config.twoArea() ? config.effectiveDumpHome() : "(supply chests)")
                + "§a fluids=§e" + (config.handleFluids ? "on" : "off"));

        if (digNeedsSupply(mc) || belowTargets(mc)) {
            chat(mc, "Stocking tools first.");
            setState(mc, DiggerState.GO_TO_SUPPLY);
        } else {
            setState(mc, DiggerState.GO_TO_WORK);
        }
    }

    public void stop(Minecraft mc) {
        endBreaking(mc);
        cancelBaritone();
        restoreBreak();
        chestRoute.abort();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        state = DiggerState.IDLE;
        ticksInState = 0;
        chat(mc, "§cStopped.");
    }

    public void tick(Minecraft mc) {
        if (state == DiggerState.IDLE) return;

        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            state = DiggerState.IDLE;
            baritone = null;
            return;
        }
        if (!ensureBaritone(mc)) {
            chat(mc, "§cLost Baritone — stopping.");
            state = DiggerState.IDLE;
            return;
        }

        ticksInState++;

        switch (state) {
            case GO_TO_SUPPLY -> tickTeleport(mc, DiggerState.SERVICE_SUPPLY);
            case SERVICE_SUPPLY -> tickServiceSupply(mc);
            case GO_TO_WORK -> tickGoToWork(mc);
            case DIGGING -> tickDigging(mc);
            case RESET_WORK_HOME -> tickResetWorkHome(mc);
            case GO_TO_DUMP -> tickTeleport(mc, DiggerState.SERVICE_DUMP);
            case SERVICE_DUMP -> tickServiceDump(mc);
            default -> { }
        }
    }

    private void setState(Minecraft mc, DiggerState s) {
        state = s;
        ticksInState = 0;
        onEnter(mc, s);
    }

    private void onEnter(Minecraft mc, DiggerState s) {
        switch (s) {
            case GO_TO_SUPPLY -> beginTeleport(mc, config.supplyHome);
            case GO_TO_DUMP -> beginTeleport(mc, config.effectiveDumpHome());
            case GO_TO_WORK -> {
                if (near(mc, config.workPos, config.skipTeleportRange)) {
                    chat(mc, "Already at the dig site — skipping teleport.");
                    setState(mc, DiggerState.DIGGING);
                } else {
                    teleporter.begin(mc);
                    sendCommand(mc, "home " + config.workHome);
                }
            }
            case DIGGING -> beginDigging(mc);
            case RESET_WORK_HOME -> cancelBaritone();
            case SERVICE_SUPPLY -> beginServiceSupply(mc);
            case SERVICE_DUMP -> beginServiceDump(mc);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ teleport helpers

    private void beginTeleport(Minecraft mc, String home) {
        teleporter.begin(mc);
        sendCommand(mc, "home " + home);
    }

    private boolean teleportArrived(Minecraft mc) {
        return teleporter.arrived(mc, config.teleportTimeoutTicks,
                config.teleportSettleTicks, config.teleportMoveThreshold);
    }

    private void tickTeleport(Minecraft mc, DiggerState onArrive) {
        if (teleportArrived(mc)) {
            setState(mc, onArrive);
        }
    }

    private void tickGoToWork(Minecraft mc) {
        if (teleportArrived(mc)) {
            config.workPos = posOf(mc);
            config.save();
            chat(mc, "At the dig site — excavating.");
            setState(mc, DiggerState.DIGGING);
        }
    }

    private void tickResetWorkHome(Minecraft mc) {
        if (!config.advanceWorkHome) {
            goDumpOrSupply(mc);
            return;
        }
        int g = Math.max(1, config.commandGapTicks);
        if (ticksInState == g) {
            sendCommand(mc, "delhome " + config.workHome);
        } else if (ticksInState == 2 * g) {
            sendCommand(mc, "sethome " + config.workHome);
            config.workPos = posOf(mc);
            config.save();
        } else if (ticksInState >= 3 * g) {
            goDumpOrSupply(mc);
        }
    }

    /** True if we hold anything that isn't a keep item (i.e. spoil worth dumping). */
    private boolean haveDepositableSpoil(Minecraft mc) {
        return ContainerService.countMatching(mc.player.getInventory(), it -> !config.keepItems.contains(it)) > 0;
    }

    private void goDumpOrSupply(Minecraft mc) {
        boolean haveSpoil = haveDepositableSpoil(mc);
        needSupplyAfterDump = belowTargets(mc);
        if (config.twoArea() && haveSpoil) {
            setState(mc, DiggerState.GO_TO_DUMP);
        } else {
            // One-home mode (or nothing to dump): the supply service both deposits and withdraws.
            needSupplyAfterDump = false;
            setState(mc, DiggerState.GO_TO_SUPPLY);
        }
    }

    // ------------------------------------------------------------------ chest servicing

    private void beginServiceSupply(Minecraft mc) {
        // Two-home: withdraw only. One-home: deposit spoil AND withdraw supplies in one pass.
        boolean deposit = !config.twoArea();
        ChestRoute.Handler handler = new ServiceHandler(true, deposit);
        ChestRoute.Mode mode = deposit ? ChestRoute.Mode.DEPOSIT_WITHDRAW : ChestRoute.Mode.WITHDRAW_ONLY;
        int found = chestRoute.begin(mc, config.supplyBoxes, config.includeEnderChests,
                config.clickDelayTicks, config.chestPathTimeoutTicks, false, mode, handler);
        if (found == 0) {
            chat(mc, "§cNo chests found in the supply area — stopping.");
            stop(mc);
            return;
        }
        chat(mc, "Found §e" + found + "§r supply chest(s).");
    }

    private void tickServiceSupply(Minecraft mc) {
        switch (chestRoute.tick(mc, baritone)) {
            case FINISHED -> {
                Inventory inv = mc.player.getInventory();
                chat(mc, "Restocked (picks=" + count(inv, config.pickaxeItem)
                        + ", shovels=" + count(inv, config.shovelItem)
                        + ", food=" + count(inv, config.foodItem)
                        + ", buckets=" + count(inv, Items.BUCKET) + "). Back to digging.");
                fluidPending = false;
                setState(mc, DiggerState.GO_TO_WORK);
            }
            case EMPTY -> {
                chat(mc, "§cSupply area has no reachable chests — stopping.");
                stop(mc);
            }
            case BLOCKED -> reportBlocked(mc);
            case RUNNING -> { }
        }
    }

    private void beginServiceDump(Minecraft mc) {
        ChestRoute.Handler handler = new ServiceHandler(false, true);
        int found = chestRoute.begin(mc, config.dumpBoxes, config.includeEnderChests,
                config.clickDelayTicks, config.chestPathTimeoutTicks, false, ChestRoute.Mode.DEPOSIT_ONLY, handler);
        if (found == 0) {
            chat(mc, "§cNo chests found in the dump area — stopping.");
            stop(mc);
            return;
        }
        chat(mc, "Found §e" + found + "§r dump chest(s).");
    }

    private void tickServiceDump(Minecraft mc) {
        switch (chestRoute.tick(mc, baritone)) {
            case FINISHED -> {
                if (haveDepositableSpoil(mc)) {
                    chat(mc, "§eDump chests are full — spoil left in inventory. Add more chests to the dump area.");
                }
                if (needSupplyAfterDump) {
                    setState(mc, DiggerState.GO_TO_SUPPLY);
                } else {
                    setState(mc, DiggerState.GO_TO_WORK);
                }
            }
            case EMPTY -> {
                chat(mc, "§cDump area has no reachable chests — stopping.");
                stop(mc);
            }
            case BLOCKED -> reportBlocked(mc);
            case RUNNING -> { }
        }
    }

    private void reportBlocked(Minecraft mc) {
        BlockPos blocked = chestRoute.blockedChest();
        chat(mc, "§cCan't reach the chest at §e"
                + (blocked != null ? blocked.toShortString() : "?")
                + "§c without breaking blocks — stopping so no chest is skipped. "
                + "Clear a path to it (or move it), then restart.");
        stop(mc);
    }

    private final class ServiceHandler implements ChestRoute.Handler {
        private final boolean withdraw;
        private final boolean deposit;

        ServiceHandler(boolean withdraw, boolean deposit) {
            this.withdraw = withdraw;
            this.deposit = deposit;
        }

        @Override
        public int nextDepositSlot(Minecraft mc, AbstractContainerMenu menu) {
            return deposit ? ContainerService.nextDepositSlotMatching(menu, it -> !config.keepItems.contains(it)) : -1;
        }

        @Override
        public int nextWithdrawSlot(Minecraft mc, AbstractContainerMenu menu) {
            return withdraw ? nextSupplyWithdrawSlot(mc, menu) : -1;
        }

        @Override
        public boolean moreWorkToDo(Minecraft mc) {
            if (withdraw && belowTargets(mc)) return true;
            return deposit && haveDepositableSpoil(mc);
        }

        @Override
        public void chat(String msg) {
            DiggerWorker.this.chat(Minecraft.getInstance(), msg);
        }
    }

    private int nextSupplyWithdrawSlot(Minecraft mc, AbstractContainerMenu menu) {
        Inventory inv = mc.player.getInventory();
        if (config.targetPickaxes - count(inv, config.pickaxeItem) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, config.pickaxeItem);
            if (s != -1) return s;
        }
        if (config.targetShovels - count(inv, config.shovelItem) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, config.shovelItem);
            if (s != -1) return s;
        }
        if (config.targetFood - count(inv, config.foodItem) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, config.foodItem);
            if (s != -1) return s;
        }
        if (config.targetBuckets - count(inv, Items.BUCKET) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, Items.BUCKET);
            if (s != -1) return s;
        }
        return -1;
    }

    private boolean belowTargets(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        return count(inv, config.pickaxeItem) < config.targetPickaxes
                || count(inv, config.shovelItem) < config.targetShovels
                || count(inv, config.foodItem) < config.targetFood
                || count(inv, Items.BUCKET) < config.targetBuckets;
    }

    /** True when digging must pause to restock right now (a tool ran out, or a fluid needs a bucket we lack). */
    private boolean digNeedsSupply(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (count(inv, config.pickaxeItem) <= 0) return true;
        if (count(inv, config.shovelItem) <= 0) return true;
        if (count(inv, config.foodItem) <= 0) return true;
        if (fluidPending && count(inv, Items.BUCKET) <= 0) return true;
        return false;
    }

    // ------------------------------------------------------------------ dig engine

    private void beginDigging(Minecraft mc) {
        if (!digInitialised) {
            int max = Integer.MIN_VALUE;
            for (int[] b : config.digBoxes) max = Math.max(max, b[4]);
            currentY = max;
            digInitialised = true;
        }
        buildLayer(mc, currentY);
        layerRescans = 0;
        phase = Phase.SCAN;
        actPos = null;
        phaseTicks = 0;
        clickCooldown = 0;
        forceNoBreak();
    }

    private void tickDigging(Minecraft mc) {
        // Leave to dump when nearly full, or to restock when out of a tool / bucket.
        Inventory inv = mc.player.getInventory();
        if (ContainerService.freeSlots(inv) <= config.stopAtFreeSlots) {
            chat(mc, "Inventory full — hauling spoil out.");
            leaveForChests(mc);
            return;
        }
        if (digNeedsSupply(mc)) {
            chat(mc, "Out of a tool/bucket — restocking.");
            leaveForChests(mc);
            return;
        }

        phaseTicks++;
        if (clickCooldown > 0) clickCooldown--;

        switch (phase) {
            case SCAN -> doScan(mc);
            case MOVE -> doMove(mc);
            case BREAK -> doBreak(mc);
            case BUCKET -> doBucket(mc);
        }
    }

    private void leaveForChests(Minecraft mc) {
        endBreaking(mc);
        cancelBaritone();
        restoreBreak();
        setState(mc, DiggerState.RESET_WORK_HOME);
    }

    private void setPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
        clickCooldown = 0;
    }

    private void doScan(Minecraft mc) {
        BlockPos t = firstValid(mc);
        if (t == null) {
            // Layer looks done — rescan once for blocks that fell or flowed in, then descend.
            int before = buildLayer(mc, currentY);
            if (before > 0 && layerRescans < MAX_LAYER_RESCANS) {
                layerRescans++;
                return;
            }
            descend(mc);
            return;
        }
        if (!reachable(mc, t)) {
            actPos = t;
            BaritoneAPI.getSettings().allowBreak.value = config.breakWhileMoving;
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(t));
            setPhase(Phase.MOVE);
            return;
        }
        classifyAndAct(mc, t);
    }

    /** Decide what to do with the reachable target at the cursor: break it, drain a fluid, or skip it. */
    private void classifyAndAct(Minecraft mc, BlockPos t) {
        if (isLiquid(mc, t)) {
            if (!config.handleFluids || skipped.contains(t.asLong())) {
                skip(mc, t, "fluid left in place");
                return;
            }
            if (isSource(mc, t)) {
                beginBucket(mc, t);
            } else {
                // Flowing fluid — recedes on its own once sources are gone; pass over it.
                cursor++;
            }
            return;
        }
        // Solid block: drain any adjacent fluid source first so the dug space doesn't flood.
        BlockPos src = adjacentFluidSource(mc, t);
        if (src != null) {
            if (!config.handleFluids || skipped.contains(src.asLong())) {
                skip(mc, t, "can't drain neighbouring fluid");
                return;
            }
            beginBucket(mc, src);
            return;
        }
        actPos = t;
        setPhase(Phase.BREAK);
    }

    private void beginBucket(Minecraft mc, BlockPos src) {
        if (count(mc.player.getInventory(), Items.BUCKET) <= 0) {
            fluidPending = true; // tickDigging will divert to a supply run next tick.
            return;
        }
        actPos = src;
        setPhase(Phase.BUCKET);
    }

    private void doMove(Minecraft mc) {
        if (actPos == null || !isTarget(mc, actPos)) {
            cancelBaritone();
            BaritoneAPI.getSettings().allowBreak.value = false;
            setPhase(Phase.SCAN);
            return;
        }
        if (reachable(mc, actPos)) {
            cancelBaritone();
            BaritoneAPI.getSettings().allowBreak.value = false;
            resortLayer(mc);
            setPhase(Phase.SCAN);
            return;
        }
        if (phaseTicks > config.moveTimeoutTicks) {
            cancelBaritone();
            BaritoneAPI.getSettings().allowBreak.value = false;
            skip(mc, actPos, "couldn't be reached");
            setPhase(Phase.SCAN);
        }
    }

    private void doBreak(Minecraft mc) {
        if (!isTarget(mc, actPos)) { // already gone (broken / fell / flowed away)
            cursor++;
            setPhase(Phase.SCAN);
            return;
        }
        BlockState st = mc.level.getBlockState(actPos);
        if (st.getDestroySpeed(mc.level, actPos) < 0) { // bedrock / unbreakable
            mc.gameMode.stopDestroyBlock();
            skip(mc, actPos, "unbreakable");
            setPhase(Phase.SCAN);
            return;
        }
        selectBestTool(mc, st);
        MenuActions.aimAt(mc, baritone, actPos);
        Direction face = exposedFace(mc, actPos);
        if (!mc.gameMode.isDestroying()) {
            mc.gameMode.startDestroyBlock(actPos, face);
        } else {
            mc.gameMode.continueDestroyBlock(actPos, face);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (phaseTicks > config.breakTimeoutTicks) {
            mc.gameMode.stopDestroyBlock();
            skip(mc, actPos, "wouldn't break (protected?)");
            setPhase(Phase.SCAN);
        }
    }

    private void doBucket(Minecraft mc) {
        if (!isSource(mc, actPos)) { // scooped (or it drained away)
            if (sameCell(actPos, layerCursorPos())) cursor++;
            setPhase(Phase.SCAN);
            return;
        }
        if (count(mc.player.getInventory(), Items.BUCKET) <= 0) {
            fluidPending = true;
            setPhase(Phase.SCAN);
            return;
        }
        if (!reachable(mc, actPos)) {
            BaritoneAPI.getSettings().allowBreak.value = config.breakWhileMoving;
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(actPos));
            setPhase(Phase.MOVE);
            return;
        }
        if (clickCooldown <= 0) {
            if (selectItem(mc, Items.BUCKET)) {
                MenuActions.aimAt(mc, baritone, actPos);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                mc.player.swing(InteractionHand.MAIN_HAND);
                clickCooldown = Math.max(2, config.clickDelayTicks * 2);
            } else {
                fluidPending = true;
                setPhase(Phase.SCAN);
                return;
            }
        }
        if (phaseTicks > config.bucketTimeoutTicks) {
            skip(mc, actPos, "couldn't scoop fluid");
            setPhase(Phase.SCAN);
        }
    }

    private void descend(Minecraft mc) {
        int min = Integer.MAX_VALUE;
        for (int[] b : config.digBoxes) min = Math.min(min, b[1]);
        currentY--;
        if (currentY < min) {
            finishDig(mc);
            return;
        }
        buildLayer(mc, currentY);
        layerRescans = 0;
        setPhase(Phase.SCAN);
    }

    private void finishDig(Minecraft mc) {
        String tail = reportedSkips > 0
                ? " §e(" + reportedSkips + " block(s) left behind — see chat above)"
                : "";
        chat(mc, "§aExcavation complete." + tail);
        endBreaking(mc);
        cancelBaritone();
        restoreBreak();
        state = DiggerState.IDLE;
        ticksInState = 0;
    }

    // ------------------------------------------------------------------ layer bookkeeping

    private int buildLayer(Minecraft mc, int y) {
        layer.clear();
        cursor = 0;
        long count = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Set<Long> seen = new HashSet<>();
        for (int[] b : config.digBoxes) {
            if (y < b[1] || y > b[4]) continue;
            for (int x = b[0]; x <= b[3]; x++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    if (++count > MAX_LAYER_CELLS) {
                        chat(mc, "§eLayer y=" + y + " is enormous (>" + MAX_LAYER_CELLS
                                + " cells) — tighten the region selection.");
                        sortLayer(mc);
                        return layer.size();
                    }
                    m.set(x, y, z);
                    if (!mc.level.isLoaded(m)) continue;
                    long key = m.asLong();
                    if (skipped.contains(key) || !seen.add(key)) continue;
                    if (isLayerCell(mc, m)) layer.add(m.immutable());
                }
            }
        }
        sortLayer(mc);
        return layer.size();
    }

    /**
     * A cell worth queueing this layer: any solid block, plus fluid <em>sources</em> when we
     * handle fluids (to scoop them). Flowing fluid is skipped — it recedes once its source is
     * gone — and fluids are left entirely when fluid handling is off.
     */
    private boolean isLayerCell(Minecraft mc, BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        if (s.isAir()) return false;
        if (s.is(Blocks.WATER) || s.is(Blocks.LAVA)) {
            return config.handleFluids && mc.level.getFluidState(pos).isSource();
        }
        return true;
    }

    private void sortLayer(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        layer.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
        cursor = 0;
    }

    private void resortLayer(Minecraft mc) {
        if (cursor >= layer.size()) return;
        List<BlockPos> remaining = new ArrayList<>(layer.subList(cursor, layer.size()));
        BlockPos feet = mc.player.blockPosition();
        remaining.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
        layer.clear();
        layer.addAll(remaining);
        cursor = 0;
    }

    /** Advance past cleared / skipped cells and return the current cursor target, or null if the layer is done. */
    private BlockPos firstValid(Minecraft mc) {
        while (cursor < layer.size()) {
            BlockPos p = layer.get(cursor);
            if (skipped.contains(p.asLong()) || !isTarget(mc, p)) {
                cursor++;
                continue;
            }
            return p;
        }
        return null;
    }

    private BlockPos layerCursorPos() {
        return cursor < layer.size() ? layer.get(cursor) : null;
    }

    private void skip(Minecraft mc, BlockPos pos, String why) {
        if (skipped.add(pos.asLong())) {
            reportedSkips++;
            if (reportedSkips <= 12) {
                chat(mc, "§eLeaving " + pos.toShortString() + " (" + why + ").");
            } else if (reportedSkips == 13) {
                chat(mc, "§e…further skipped blocks will be summarised at the end.");
            }
        }
        if (sameCell(pos, layerCursorPos())) cursor++;
    }

    // ------------------------------------------------------------------ block / fluid queries

    private boolean isTarget(Minecraft mc, BlockPos pos) {
        if (!mc.level.isLoaded(pos)) return false;
        BlockState s = mc.level.getBlockState(pos);
        if (s.isAir()) return false;
        return true;
    }

    private boolean isLiquid(Minecraft mc, BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        return s.is(Blocks.WATER) || s.is(Blocks.LAVA);
    }

    private boolean isSource(Minecraft mc, BlockPos pos) {
        FluidState fs = mc.level.getFluidState(pos);
        return !fs.isEmpty() && fs.isSource();
    }

    private BlockPos adjacentFluidSource(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (mc.level.isLoaded(n) && isSource(mc, n)) return n;
        }
        return null;
    }

    private boolean passable(Minecraft mc, BlockPos pos) {
        if (!mc.level.isLoaded(pos)) return false;
        return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private Direction exposedFace(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (passable(mc, pos.relative(d))) return d;
        }
        return Direction.UP;
    }

    private boolean reachable(Minecraft mc, BlockPos pos) {
        return RotationUtils.reachable(baritone.getPlayerContext(), pos).isPresent();
    }

    // ------------------------------------------------------------------ inventory helpers

    private static int count(Inventory inv, Item item) {
        return ContainerService.countItem(inv, item);
    }

    /** Move the fastest-mining tool for this block into the hotbar and select it. */
    private void selectBestTool(Minecraft mc, BlockState state) {
        Inventory inv = mc.player.getInventory();
        int bestSlot = -1;
        float best = inv.getItem(inv.getSelectedSlot()).getDestroySpeed(state);
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            float sp = s.getDestroySpeed(state);
            if (sp > best) {
                best = sp;
                bestSlot = i;
            }
        }
        if (bestSlot >= 0) selectSlot(mc, bestSlot);
    }

    private boolean selectItem(Minecraft mc, Item item) {
        Inventory inv = mc.player.getInventory();
        if (inv.getItem(inv.getSelectedSlot()).is(item)) return true;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                selectSlot(mc, i);
                return true;
            }
        }
        return false;
    }

    private void selectSlot(Minecraft mc, int slot) {
        Inventory inv = mc.player.getInventory();
        if (slot < 9) {
            inv.setSelectedSlot(slot);
            return;
        }
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slot, 8,
                ContainerInput.SWAP, mc.player);
        inv.setSelectedSlot(8);
    }

    private void endBreaking(Minecraft mc) {
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    // ------------------------------------------------------------------ misc

    private static boolean sameCell(BlockPos a, BlockPos b) {
        return a != null && b != null && a.asLong() == b.asLong();
    }

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

    private boolean near(Minecraft mc, int[] pos, double range) {
        if (pos == null) return false;
        double dx = mc.player.getX() - (pos[0] + 0.5);
        double dy = mc.player.getY() - pos[1];
        double dz = mc.player.getZ() - (pos[2] + 0.5);
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    private void cancelBaritone() {
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

    private boolean ensureBaritone(Minecraft mc) {
        if (baritone != null) return true;
        baritone = Baritones.resolve(mc);
        return baritone != null;
    }

    private void chat(Minecraft mc, String msg) {
        LOG.info(msg.replaceAll("§.", ""));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§6[Digger]§r " + msg));
        }
    }
}
