package com.luna0wl.baritoneworker.worker.miner;

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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public final class MinerWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/worker");

    private final MinerConfig config;
    private final Teleporter teleporter = new Teleporter();
    private final ChestRoute chestRoute = new ChestRoute();
    private final ChestRoute.Handler serviceHandler = new ServiceHandler();

    private MinerState state = MinerState.IDLE;
    private int ticksInState;
    private IBaritone baritone;

    private int tunnelIdleTicks;

    private enum OreStep { GOTO, BREAK }
    private Set<Block> oreTargets = Set.of();
    private BlockPos oreTarget;
    private OreStep oreStep;
    private int oreStepTicks;
    private final Set<Long> oreBlacklist = new HashSet<>();

    public MinerWorker(MinerConfig config) {
        this.config = config;
    }

    public boolean isRunning() {
        return state != MinerState.IDLE;
    }

    public MinerState getState() {
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
            chat(mc, "§cNo chest area set. Make a Baritone selection (#sel 1 / #sel 2) then run §e#miner area§c.");
            return;
        }
        baritone = null;
        chestRoute.abort();
        chat(mc, "§aStarted. mineHome=§e" + config.mineHome + "§a baseHome=§e" + config.baseHome
                + "§a chests=§e" + config.chestBoxes.size() + " box(es)");

        Inventory inv = mc.player.getInventory();
        int picks = ContainerService.countItem(inv, config.pickaxeItem);
        int shovels = ContainerService.countItem(inv, config.shovelItem);
        int food = ContainerService.countItem(inv, config.foodItem);
        if (picks < config.targetPickaxes || shovels < config.targetShovels || food < config.targetFood) {
            chat(mc, "Low on supplies (picks=" + picks + "/" + config.targetPickaxes
                    + ", shovels=" + shovels + "/" + config.targetShovels
                    + ", food=" + food + "/" + config.targetFood + ") — restocking first.");
            setState(mc, MinerState.GO_TO_HOME);
        } else {
            setState(mc, MinerState.GO_TO_MINE);
        }
    }

    public void stop(Minecraft mc) {
        endOreDetour(mc);
        cancelBaritone();
        chestRoute.abort();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        state = MinerState.IDLE;
        ticksInState = 0;
        chat(mc, "§cStopped.");
    }

    public void tick(Minecraft mc) {
        if (state == MinerState.IDLE) return;

        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            state = MinerState.IDLE;
            baritone = null;
            return;
        }
        if (!ensureBaritone(mc)) {
            chat(mc, "§cLost Baritone — stopping.");
            state = MinerState.IDLE;
            return;
        }

        ticksInState++;

        switch (state) {
            case GO_TO_MINE -> tickGoToMine(mc);
            case TUNNELING -> tickTunneling(mc);
            case RESET_HOME -> tickResetHome(mc);
            case GO_TO_HOME -> tickGoToHome(mc);
            case SERVICE_CHESTS -> tickServiceChests(mc);
            default -> { }
        }
    }

    private void setState(Minecraft mc, MinerState s) {
        state = s;
        ticksInState = 0;
        onEnter(mc, s);
    }

    private void onEnter(Minecraft mc, MinerState s) {
        switch (s) {
            case GO_TO_MINE -> {
                if (near(mc, config.minePos, config.skipTeleportRange)) {
                    chat(mc, "Already at the tunnel face — skipping teleport.");
                    setState(mc, MinerState.TUNNELING);
                } else {
                    teleporter.begin(mc);
                    sendCommand(mc, "home " + config.mineHome);
                }
            }
            case TUNNELING -> {
                tunnelIdleTicks = 0;
                oreTargets = Ores.targetSet(config.excludedOreGroups);
                oreTarget = null;
                oreBlacklist.clear();
                startTunnel(mc);
            }
            case RESET_HOME -> cancelBaritone();
            case GO_TO_HOME -> {
                if (alreadyAtBase(mc)) {
                    chat(mc, "Already at base — servicing chests without teleport.");
                    setState(mc, MinerState.SERVICE_CHESTS);
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

    private void tickGoToMine(Minecraft mc) {
        if (teleportArrived(mc)) {
            rememberMinePos(mc);
            chat(mc, "At the mine — tunneling.");
            setState(mc, MinerState.TUNNELING);
        }
    }

    private void tickTunneling(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (ContainerService.freeSlots(inv) <= config.stopAtFreeSlots) {
            chat(mc, "Inventory full — returning to base.");
            endOreDetour(mc);
            cancelBaritone();
            setState(mc, MinerState.RESET_HOME);
            return;
        }

        if (config.mineExposedOres) {
            if (oreTarget != null) {
                handleOreDetour(mc);
                return;
            }
            if (ticksInState % Math.max(1, config.oreScanInterval) == 0) {
                BlockPos ore = findSafeExposedOre(mc);
                if (ore != null) {
                    beginOreDetour(mc, ore);
                    return;
                }
            }
        }

        boolean active = baritone.getPathingBehavior().isPathing() || baritone.getBuilderProcess().isActive();
        if (active) {
            tunnelIdleTicks = 0;
        } else if (++tunnelIdleTicks > 60) {
            tunnelIdleTicks = 0;
            startTunnel(mc);
        }
    }

    private void tickResetHome(Minecraft mc) {
        int g = Math.max(1, config.commandGapTicks);
        if (ticksInState == g) {
            sendCommand(mc, "delhome " + config.mineHome);
        } else if (ticksInState == 2 * g) {
            sendCommand(mc, "sethome " + config.mineHome);
            rememberMinePos(mc);
        } else if (ticksInState >= 3 * g) {
            chat(mc, "Mine home reset to current face — heading to base.");
            setState(mc, MinerState.GO_TO_HOME);
        }
    }

    private void tickGoToHome(Minecraft mc) {
        if (teleportArrived(mc)) {
            config.homePos = posOf(mc);
            config.save();
            chat(mc, "At base — servicing chests.");
            setState(mc, MinerState.SERVICE_CHESTS);
        }
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
        int needPick = config.targetPickaxes - ContainerService.countItem(inv, config.pickaxeItem);
        int needShovel = config.targetShovels - ContainerService.countItem(inv, config.shovelItem);
        int needFood = config.targetFood - ContainerService.countItem(inv, config.foodItem);
        int slot = -1;
        if (needPick > 0) slot = ContainerService.nextWithdrawSlot(menu, config.pickaxeItem);
        if (slot == -1 && needShovel > 0) slot = ContainerService.nextWithdrawSlot(menu, config.shovelItem);
        if (slot == -1 && needFood > 0) slot = ContainerService.nextWithdrawSlot(menu, config.foodItem);
        return slot;
    }

    private boolean moreWorkToDo(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (config.targetPickaxes - ContainerService.countItem(inv, config.pickaxeItem) > 0) return true;
        if (config.targetShovels - ContainerService.countItem(inv, config.shovelItem) > 0) return true;
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) return true;
        return ContainerService.hasDepositable(mc.player.inventoryMenu, config.keepItems);
    }

    private void finishService(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        int picks = ContainerService.countItem(inv, config.pickaxeItem);
        int shovels = ContainerService.countItem(inv, config.shovelItem);
        int food = ContainerService.countItem(inv, config.foodItem);
        if (moreWorkToDo(mc)) {
            chat(mc, "§eService incomplete (picks=" + picks + ", shovels=" + shovels + ", food=" + food
                    + ") — chests may be full or out of supplies. Continuing anyway.");
        } else {
            chat(mc, "Serviced chests (picks=" + picks + ", shovels=" + shovels + ", food=" + food + "). Back to mining.");
        }
        setState(mc, MinerState.GO_TO_MINE);
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
            return MinerWorker.this.moreWorkToDo(mc);
        }

        @Override
        public void chat(String msg) {
            MinerWorker.this.chat(Minecraft.getInstance(), msg);
        }
    }

    private void sendCommand(Minecraft mc, String command) {
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            conn.sendCommand(command);
            LOG.info("/{}", command);
        }
    }

    private void runBaritone(String command) {
        if (baritone != null) {
            baritone.getCommandManager().execute(command);
        }
    }

    private void startTunnel(Minecraft mc) {
        Direction dig = pickTunnelDirection(mc);
        LocalPlayer p = mc.player;
        p.setYRot(dig.toYRot());
        p.setXRot(0.0f);
        p.yRotO = p.getYRot();
        p.xRotO = p.getXRot();
        runBaritone("tunnel");
    }

    private Direction pickTunnelDirection(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        Direction deepest = null;
        int maxDepth = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int depth = openDepth(mc, feet, d, config.tunnelScanDepth);
            if (depth > maxDepth) {
                maxDepth = depth;
                deepest = d;
            }
        }
        if (deepest != null && maxDepth >= 2) {
            Direction dig = deepest.getOpposite();
            BlockPos ahead = feet.relative(dig);
            if (!passable(mc, ahead) || !passable(mc, ahead.above())) {
                return dig;
            }
        }
        return mc.player.getDirection();
    }

    private int openDepth(Minecraft mc, BlockPos feet, Direction d, int cap) {
        int depth = 0;
        for (int i = 1; i <= cap; i++) {
            BlockPos foot = feet.relative(d, i);
            if (passable(mc, foot) && passable(mc, foot.above())) {
                depth++;
            } else {
                break;
            }
        }
        return depth;
    }

    private boolean passable(Minecraft mc, BlockPos pos) {
        if (!mc.level.isLoaded(pos)) return false;
        return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private BlockPos findSafeExposedOre(Minecraft mc) {
        if (oreTargets.isEmpty()) return null;
        BlockPos feet = mc.player.blockPosition();
        int r = config.oreScanRadius;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!mc.level.isLoaded(m)) continue;
                    Block b = mc.level.getBlockState(m).getBlock();
                    if (!oreTargets.contains(b)) continue;
                    if (oreBlacklist.contains(m.asLong())) continue;
                    if (!isExposed(mc, m)) continue;
                    if (config.avoidFluidBehindOre && hasFluidNeighbour(mc, m)) continue;
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

    private boolean isExposed(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (passable(mc, pos.relative(d))) return true;
        }
        return false;
    }

    private boolean hasFluidNeighbour(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (!mc.level.getFluidState(pos.relative(d)).isEmpty()) return true;
        }
        return false;
    }

    private Direction exposedFace(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (passable(mc, pos.relative(d))) return d;
        }
        return Direction.UP;
    }

    private void beginOreDetour(Minecraft mc, BlockPos ore) {
        cancelBaritone();
        oreTarget = ore;
        oreStep = OreStep.GOTO;
        oreStepTicks = 0;
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(ore));
        chat(mc, "Grabbing exposed " + mc.level.getBlockState(ore).getBlock().getName().getString()
                + " at " + ore.toShortString());
    }

    private void handleOreDetour(Minecraft mc) {
        oreStepTicks++;
        if (!oreTargets.contains(mc.level.getBlockState(oreTarget).getBlock())) {
            finishOreDetour(mc);
            return;
        }
        switch (oreStep) {
            case GOTO -> {
                if (RotationUtils.reachable(baritone.getPlayerContext(), oreTarget).isPresent()) {
                    cancelBaritone();
                    oreStep = OreStep.BREAK;
                    oreStepTicks = 0;
                } else if (oreStepTicks > 400) {
                    blacklist(oreTarget);
                    finishOreDetour(mc);
                }
            }
            case BREAK -> {
                selectPickaxe(mc);
                MenuActions.aimAt(mc, baritone, oreTarget);
                Direction face = exposedFace(mc, oreTarget);
                if (!mc.gameMode.isDestroying()) {
                    mc.gameMode.startDestroyBlock(oreTarget, face);
                } else {
                    mc.gameMode.continueDestroyBlock(oreTarget, face);
                }
                mc.player.swing(InteractionHand.MAIN_HAND);
                if (oreStepTicks > 200) {
                    mc.gameMode.stopDestroyBlock();
                    blacklist(oreTarget);
                    finishOreDetour(mc);
                }
            }
        }
    }

    private void finishOreDetour(Minecraft mc) {
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        oreTarget = null;
        oreStep = null;
        cancelBaritone();
        startTunnel(mc);
    }

    private void endOreDetour(Minecraft mc) {
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        oreTarget = null;
        oreStep = null;
    }

    private void blacklist(BlockPos pos) {
        if (oreBlacklist.size() > 256) oreBlacklist.clear();
        oreBlacklist.add(pos.asLong());
    }

    private void selectPickaxe(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (inv.getItem(inv.getSelectedSlot()).is(config.pickaxeItem)) return;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(config.pickaxeItem)) {
                inv.setSelectedSlot(i);
                return;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(config.pickaxeItem)) {
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, i, 8,
                        ContainerInput.SWAP, mc.player);
                inv.setSelectedSlot(8);
                return;
            }
        }
    }

    private int[] posOf(Minecraft mc) {
        BlockPos p = mc.player.blockPosition();
        return new int[]{p.getX(), p.getY(), p.getZ()};
    }

    private void rememberMinePos(Minecraft mc) {
        config.minePos = posOf(mc);
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

    private void chat(Minecraft mc, String msg) {
        LOG.info(msg.replaceAll("§.", ""));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§9[Miner]§r " + msg));
        }
    }
}
