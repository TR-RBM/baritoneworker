package com.luna0wl.baritoneworker.worker.lumber;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

public final class LumberWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritonelumber/worker");

    private static final Set<Block> GROWABLE = Set.of(
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.PODZOL,
            Blocks.ROOTED_DIRT, Blocks.MYCELIUM, Blocks.MUD, Blocks.MOSS_BLOCK,
            Blocks.PALE_MOSS_BLOCK);

    private final LumberConfig config;
    private final Teleporter teleporter = new Teleporter();
    private final ChestRoute chestRoute = new ChestRoute();
    private final ChestRoute.Handler serviceHandler = new ServiceHandler();

    private LumberState state = LumberState.IDLE;
    private int ticksInState;
    private IBaritone baritone;

    private int harvestIdleTicks;

    private final Set<Block> woodBlocks = new HashSet<>();
    private final Set<Long> currentTree = new HashSet<>();
    private boolean returningToTree;
    private int treeSize;
    private int treeStuckTicks;
    private final Set<Long> logBlacklist = new HashSet<>();

    private enum ReplantStep { GOTO, PLACE }
    private BlockPos replantSoil;
    private ReplantStep replantStep;
    private int replantStepTicks;
    private int replantClickCooldown;
    private final Set<Long> replantBlacklist = new HashSet<>();

    public LumberWorker(LumberConfig config) {
        this.config = config;
    }

    public boolean isRunning() {
        return state != LumberState.IDLE;
    }

    public LumberState getState() {
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
        if (!config.hasArea()) {
            chat(mc, "§cNo chest area set. Make a Baritone selection (#sel 1 / #sel 2) then run §e#lumber area§c.");
            return;
        }
        baritone = null;
        chestRoute.abort();
        chat(mc, "§aStarted. workHome=§e" + config.workHome + "§a baseHome=§e" + config.baseHome
                + "§a woods=§e" + (config.woodFlavours.isEmpty() ? "all" : String.join(",", config.woodFlavours))
                + "§a replant=§e" + (config.replant ? "on" : "off"));

        Inventory inv = mc.player.getInventory();
        int axes = ContainerService.countItem(inv, config.axeItem);
        int food = ContainerService.countItem(inv, config.foodItem);
        boolean lowSaplings = config.replant
                && ContainerService.countMatching(inv, Woods.saplings(config.woodFlavours)::contains) < config.targetSaplings;
        if (axes < config.targetAxes || food < config.targetFood || lowSaplings) {
            chat(mc, "Low on supplies — restocking first.");
            setState(mc, LumberState.GO_TO_HOME);
        } else {
            setState(mc, LumberState.GO_TO_WORK);
        }
    }

    public void stop(Minecraft mc) {
        endReplant();
        cancelBaritone();
        chestRoute.abort();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        state = LumberState.IDLE;
        ticksInState = 0;
        chat(mc, "§cStopped.");
    }

    public void tick(Minecraft mc) {
        if (state == LumberState.IDLE) return;

        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            state = LumberState.IDLE;
            baritone = null;
            return;
        }
        if (!ensureBaritone(mc)) {
            chat(mc, "§cLost Baritone — stopping.");
            state = LumberState.IDLE;
            return;
        }

        ticksInState++;
        if (replantClickCooldown > 0) replantClickCooldown--;

        switch (state) {
            case GO_TO_WORK -> tickGoToWork(mc);
            case HARVEST -> tickHarvest(mc);
            case RESET_HOME -> tickResetHome(mc);
            case GO_TO_HOME -> tickGoToHome(mc);
            case SERVICE_CHESTS -> tickServiceChests(mc);
            default -> { }
        }
    }

    private void setState(Minecraft mc, LumberState s) {
        state = s;
        ticksInState = 0;
        onEnter(mc, s);
    }

    private void onEnter(Minecraft mc, LumberState s) {
        switch (s) {
            case GO_TO_WORK -> {
                if (near(mc, config.workPos, config.skipTeleportRange)) {
                    chat(mc, "Already at the forest — harvesting.");
                    setState(mc, LumberState.HARVEST);
                } else {
                    teleporter.begin(mc);
                    sendCommand(mc, "home " + config.workHome);
                }
            }
            case HARVEST -> {
                harvestIdleTicks = 0;
                replantSoil = null;
                replantBlacklist.clear();
                woodBlocks.clear();
                for (Block b : Woods.targetBlocks(config.woodFlavours)) woodBlocks.add(b);
                currentTree.clear();
                logBlacklist.clear();
                returningToTree = false;
                treeSize = 0;
                treeStuckTicks = 0;
                startMine();
            }
            case RESET_HOME -> cancelBaritone();
            case GO_TO_HOME -> {
                if (alreadyAtBase(mc)) {
                    chat(mc, "Already at base — servicing chests without teleport.");
                    setState(mc, LumberState.SERVICE_CHESTS);
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
            chat(mc, "At the forest — harvesting.");
            setState(mc, LumberState.HARVEST);
        }
    }

    private void tickHarvest(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (ContainerService.freeSlots(inv) <= config.stopAtFreeSlots) {
            chat(mc, "Inventory full — returning to base.");
            endReplant();
            currentTree.clear();
            cancelBaritone();
            setState(mc, LumberState.RESET_HOME);
            return;
        }

        if (config.replant && replantSoil != null) {
            handleReplant(mc);
            return;
        }

        pruneTree(mc);

        if (currentTree.isEmpty()) {
            if (config.replant && tryStartReplant(mc)) return;
            if (ticksInState % 10 == 0) {
                BlockPos start = findNearestTree(mc);
                if (start != null) {
                    lockTree(mc, start);
                    return;
                }
            }
            ensureMining(mc);
            return;
        }

        BlockPos target = nearestRemaining(mc);
        if (target == null) { currentTree.clear(); return; }

        double dx = mc.player.getX() - (target.getX() + 0.5);
        double dz = mc.player.getZ() - (target.getZ() + 0.5);
        boolean nearTree = dx * dx + dz * dz <= (double) config.treeFollowRadius * config.treeFollowRadius;

        if (nearTree) {
            if (returningToTree) {
                returningToTree = false;
                cancelBaritone();
            }
            ensureMining(mc);
        } else if (!returningToTree) {
            cancelBaritone();
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(target));
            returningToTree = true;
        }

        if (currentTree.size() == treeSize) {
            if (++treeStuckTicks > Math.max(1, config.treeStuckTimeoutTicks)) {
                for (long l : currentTree) blacklistLog(l);
                currentTree.clear();
                treeStuckTicks = 0;
                returningToTree = false;
                cancelBaritone();
                chat(mc, "§eSkipping unreachable logs on this tree.");
            }
        } else {
            treeSize = currentTree.size();
            treeStuckTicks = 0;
        }
    }

    private void ensureMining(Minecraft mc) {
        boolean active = baritone.getMineProcess().isActive() || baritone.getPathingBehavior().isPathing();
        if (active) {
            harvestIdleTicks = 0;
        } else if (++harvestIdleTicks > Math.max(1, config.harvestIdleReissueTicks)) {
            harvestIdleTicks = 0;
            startMine();
        }
    }

    private boolean isWood(Minecraft mc, BlockPos p) {
        return mc.level.isLoaded(p) && woodBlocks.contains(mc.level.getBlockState(p).getBlock());
    }

    private void pruneTree(Minecraft mc) {
        if (currentTree.isEmpty()) return;
        currentTree.removeIf(l -> !isWood(mc, BlockPos.of(l)));
    }

    private BlockPos findNearestTree(Minecraft mc) {
        if (woodBlocks.isEmpty()) return null;
        BlockPos feet = mc.player.blockPosition();
        int r = config.treeScanRadius;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!mc.level.isLoaded(m)) continue;
                    if (!woodBlocks.contains(mc.level.getBlockState(m).getBlock())) continue;
                    if (logBlacklist.contains(m.asLong())) continue;
                    double d = feet.distSqr(m);
                    if (d < bestSq) {
                        bestSq = d;
                        best = m.immutable();
                    }
                }
            }
        }
        return best;
    }

    private void lockTree(Minecraft mc, BlockPos start) {
        currentTree.clear();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        currentTree.add(start.asLong());
        queue.add(start);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && currentTree.size() < config.maxTreeBlocks) {
            BlockPos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                        long key = m.asLong();
                        if (currentTree.contains(key) || !isWood(mc, m)) continue;
                        currentTree.add(key);
                        queue.add(m.immutable());
                    }
                }
            }
        }
        treeSize = currentTree.size();
        treeStuckTicks = 0;
        returningToTree = false;
        cancelBaritone();
        startMine();
        LOG.info("Locked a tree of {} log block(s).", currentTree.size());
    }

    private BlockPos nearestRemaining(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (long l : currentTree) {
            BlockPos p = BlockPos.of(l);
            double d = feet.distSqr(p);
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    private void blacklistLog(long packed) {
        if (logBlacklist.size() > 1024) logBlacklist.clear();
        logBlacklist.add(packed);
    }

    private boolean tryStartReplant(Minecraft mc) {
        if (replantSoil != null) return false;
        if (!haveSapling(mc)) return false;
        if (ticksInState % Math.max(1, config.replantScanInterval) != 0) return false;
        BlockPos soil = findReplantSpot(mc);
        if (soil == null) return false;
        beginReplant(mc, soil);
        return true;
    }

    private void tickResetHome(Minecraft mc) {
        int g = Math.max(1, config.commandGapTicks);
        if (ticksInState == g) {
            sendCommand(mc, "delhome " + config.workHome);
        } else if (ticksInState == 2 * g) {
            sendCommand(mc, "sethome " + config.workHome);
            rememberWorkPos(mc);
        } else if (ticksInState >= 3 * g) {
            chat(mc, "Work home reset to current spot — heading to base.");
            setState(mc, LumberState.GO_TO_HOME);
        }
    }

    private void tickGoToHome(Minecraft mc) {
        if (teleportArrived(mc)) {
            config.homePos = posOf(mc);
            config.save();
            chat(mc, "At base — servicing chests.");
            setState(mc, LumberState.SERVICE_CHESTS);
        }
    }

    private void startMine() {
        cancelBaritone();
        Block[] targets = Woods.targetBlocks(config.woodFlavours);
        if (targets.length > 0 && baritone != null) {
            baritone.getMineProcess().mine(targets);
        }
    }

    private boolean haveSapling(Minecraft mc) {
        Set<Item> wanted = config.woodFlavours.isEmpty() ? Woods.allSaplings() : Woods.saplings(config.woodFlavours);
        return ContainerService.countMatching(mc.player.getInventory(), wanted::contains) > 0;
    }

    private BlockPos findReplantSpot(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        int r = config.replantRadius;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!mc.level.isLoaded(m)) continue;
                    if (!GROWABLE.contains(mc.level.getBlockState(m).getBlock())) continue;
                    if (replantBlacklist.contains(m.asLong())) continue;
                    BlockPos above = m.above();
                    if (!passable(mc, above)) continue;
                    if (hasLogNeighbour(mc, above)) continue;
                    double d = feet.distSqr(m);
                    if (d < bestSq) {
                        bestSq = d;
                        best = m.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean hasLogNeighbour(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Block b = mc.level.getBlockState(pos.relative(d)).getBlock();
            if (b.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS)) return true;
        }
        return false;
    }

    private void beginReplant(Minecraft mc, BlockPos soil) {
        cancelBaritone();
        replantSoil = soil;
        replantStep = ReplantStep.GOTO;
        replantStepTicks = 0;
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(soil.above()));
    }

    private void handleReplant(Minecraft mc) {
        replantStepTicks++;
        if (!mc.level.getBlockState(replantSoil.above()).getCollisionShape(mc.level, replantSoil.above()).isEmpty()
                || !GROWABLE.contains(mc.level.getBlockState(replantSoil).getBlock())) {
            finishReplant(mc);
            return;
        }
        switch (replantStep) {
            case GOTO -> {
                if (RotationUtils.reachable(baritone.getPlayerContext(), replantSoil).isPresent()) {
                    cancelBaritone();
                    replantStep = ReplantStep.PLACE;
                    replantStepTicks = 0;
                } else if (replantStepTicks > 200) {
                    blacklistReplant(replantSoil);
                    finishReplant(mc);
                }
            }
            case PLACE -> {
                if (!selectSapling(mc)) {
                    finishReplant(mc);
                    return;
                }
                if (replantClickCooldown <= 0) {
                    placeSapling(mc, replantSoil);
                    replantClickCooldown = config.clickDelayTicks * 2;
                }
                if (replantStepTicks > 60) {
                    blacklistReplant(replantSoil);
                    finishReplant(mc);
                }
            }
        }
    }

    private void finishReplant(Minecraft mc) {
        replantSoil = null;
        replantStep = null;
        cancelBaritone();
    }

    private void endReplant() {
        replantSoil = null;
        replantStep = null;
    }

    private void blacklistReplant(BlockPos pos) {
        if (replantBlacklist.size() > 256) replantBlacklist.clear();
        replantBlacklist.add(pos.asLong());
    }

    private void placeSapling(Minecraft mc, BlockPos soil) {
        MenuActions.aimAt(mc, baritone, soil.above());
        Vec3 hitVec = new Vec3(soil.getX() + 0.5, soil.getY() + 1.0, soil.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, soil, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean selectSapling(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        Set<Item> wanted = config.woodFlavours.isEmpty() ? Woods.allSaplings() : Woods.saplings(config.woodFlavours);
        if (wanted.contains(inv.getItem(inv.getSelectedSlot()).getItem())) return true;
        for (int i = 0; i < 9; i++) {
            if (wanted.contains(inv.getItem(i).getItem()) && !inv.getItem(i).isEmpty()) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (wanted.contains(inv.getItem(i).getItem()) && !inv.getItem(i).isEmpty()) {
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, i, 8,
                        ContainerInput.SWAP, mc.player);
                inv.setSelectedSlot(8);
                return true;
            }
        }
        return false;
    }

    private void enterServiceChests(Minecraft mc) {
        int found = chestRoute.begin(mc, config.chestBoxes, config.includeEnderChests,
                config.clickDelayTicks, config.chestPathTimeoutTicks, false, serviceHandler);
        if (found > 0) {
            chat(mc, "Found §e" + found + "§r chest(s) to service.");
        }
    }

    private void tickServiceChests(Minecraft mc) {
        switch (chestRoute.tick(mc, baritone)) {
            case FINISHED -> finishService(mc);
            case EMPTY -> {
                chat(mc, "§cNo chests found in the selected area — stopping.");
                stop(mc);
            }
            case BLOCKED -> {
                BlockPos blocked = chestRoute.blockedChest();
                chat(mc, "§cCan't reach the chest at §e"
                        + (blocked != null ? blocked.toShortString() : "?")
                        + "§c without breaking blocks — stopping so no chest is skipped. "
                        + "Clear a path to it (or move it), then restart.");
                stop(mc);
            }
            case RUNNING -> { }
        }
    }

    private int nextSupplyWithdrawSlot(Minecraft mc, AbstractContainerMenu menu) {
        Inventory inv = mc.player.getInventory();
        if (config.targetAxes - ContainerService.countItem(inv, config.axeItem) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, config.axeItem);
            if (s != -1) return s;
        }
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, config.foodItem);
            if (s != -1) return s;
        }
        if (config.replant) {
            Set<Item> saplings = Woods.saplings(config.woodFlavours);
            if (config.targetSaplings - ContainerService.countMatching(inv, saplings::contains) > 0) {
                return ContainerService.nextWithdrawSlotMatching(menu, saplings::contains);
            }
        }
        return -1;
    }

    private boolean moreWorkToDo(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (config.targetAxes - ContainerService.countItem(inv, config.axeItem) > 0) return true;
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) return true;
        if (config.replant) {
            Set<Item> saplings = Woods.saplings(config.woodFlavours);
            if (config.targetSaplings - ContainerService.countMatching(inv, saplings::contains) > 0) return true;
        }
        return ContainerService.hasDepositable(mc.player.inventoryMenu, config.keepItems);
    }

    private void finishService(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        int axes = ContainerService.countItem(inv, config.axeItem);
        int food = ContainerService.countItem(inv, config.foodItem);
        if (moreWorkToDo(mc)) {
            chat(mc, "§eService incomplete (axes=" + axes + ", food=" + food
                    + ") — chests may be full or out of supplies. Continuing anyway.");
        } else {
            chat(mc, "Serviced chests (axes=" + axes + ", food=" + food + "). Back to chopping.");
        }
        setState(mc, LumberState.GO_TO_WORK);
    }

    private final class ServiceHandler implements ChestRoute.Handler {
        @Override
        public int nextDepositSlot(Minecraft mc, AbstractContainerMenu menu) {
            return ContainerService.nextDepositSlot(menu, config.keepItems);
        }

        @Override
        public int nextWithdrawSlot(Minecraft mc, AbstractContainerMenu menu) {
            return nextSupplyWithdrawSlot(mc, menu);
        }

        @Override
        public boolean moreWorkToDo(Minecraft mc) {
            return LumberWorker.this.moreWorkToDo(mc);
        }

        @Override
        public void chat(String msg) {
            LumberWorker.this.chat(Minecraft.getInstance(), msg);
        }
    }

    private void sendCommand(Minecraft mc, String command) {
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            conn.sendCommand(command);
            LOG.info("/{}", command);
        }
    }

    private boolean passable(Minecraft mc, BlockPos pos) {
        if (!mc.level.isLoaded(pos)) return false;
        return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
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
            baritone.getMineProcess().cancel();
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
            mc.player.sendSystemMessage(Component.literal("§2[Lumber]§r " + msg));
        }
    }
}
