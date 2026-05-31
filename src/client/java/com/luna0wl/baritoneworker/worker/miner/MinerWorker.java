package com.luna0wl.baritoneworker.worker.miner;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.RotationUtils;
import com.luna0wl.baritoneworker.worker.common.Baritones;
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
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The autonomous mining loop, driven once per client tick.
 *
 * <p>One cycle: teleport to the mine, run Baritone's {@code tunnel}, and watch
 * the inventory. When it fills up, reset the mine home to the new tunnel face
 * ({@code delhome}/{@code sethome}), teleport to base, deposit all loot into the
 * chests in the selected area, withdraw fresh pickaxes and baked potatoes, then
 * head back out and repeat.
 */
public final class MinerWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/worker");

    private final MinerConfig config;
    private final Teleporter teleporter = new Teleporter();

    private MinerState state = MinerState.IDLE;
    private int ticksInState;
    private IBaritone baritone;

    /** Ticks Baritone has been idle during TUNNELING (used to re-issue tunnel). */
    private int tunnelIdleTicks;

    // --- exposed-ore detour sub-state (inside TUNNELING) ---
    private enum OreStep { GOTO, BREAK }
    private Set<Block> oreTargets = Set.of();
    private BlockPos oreTarget;
    private OreStep oreStep;
    private int oreStepTicks;
    private final Set<Long> oreBlacklist = new HashSet<>();

    // --- chest servicing sub-state ---
    private enum ChestStep { PATH, OPEN, DEPOSIT, WITHDRAW, CLOSE }
    private final List<BlockPos> chestQueue = new ArrayList<>();
    private int chestIndex;
    private ChestStep chestStep = ChestStep.PATH;
    private int ticksInStep;
    private int clickCooldown;
    private int actionClicks;   // bounds deposit/withdraw loops per chest

    public MinerWorker(MinerConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------- public API

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
        baritone = null; // re-resolve for the current connection
        chat(mc, "§aStarted. mineHome=§e" + config.mineHome + "§a baseHome=§e" + config.baseHome
                + "§a chests=§e" + config.chestBoxes.size() + " box(es)");

        // Check supplies up front: if we're short on pickaxes or food, swing by
        // base to restock before heading out to mine.
        Inventory inv = mc.player.getInventory();
        int picks = ContainerService.countItem(inv, config.pickaxeItem);
        int food = ContainerService.countItem(inv, config.foodItem);
        if (picks < config.targetPickaxes || food < config.targetFood) {
            chat(mc, "Low on supplies (picks=" + picks + "/" + config.targetPickaxes
                    + ", food=" + food + "/" + config.targetFood + ") — restocking first.");
            setState(mc, MinerState.GO_TO_HOME);
        } else {
            setState(mc, MinerState.GO_TO_MINE);
        }
    }

    public void stop(Minecraft mc) {
        endOreDetour(mc);
        cancelBaritone();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        state = MinerState.IDLE;
        ticksInState = 0;
        chat(mc, "§cStopped.");
    }

    // ----------------------------------------------------------------- tick

    public void tick(Minecraft mc) {
        if (state == MinerState.IDLE) return;

        // Lost the world/connection: abort silently back to idle.
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
        if (clickCooldown > 0) clickCooldown--;

        switch (state) {
            case GO_TO_MINE -> tickGoToMine(mc);
            case TUNNELING -> tickTunneling(mc);
            case RESET_HOME -> tickResetHome(mc);
            case GO_TO_HOME -> tickGoToHome(mc);
            case SERVICE_CHESTS -> tickServiceChests(mc);
            default -> { /* IDLE handled above */ }
        }
    }

    // ----------------------------------------------------------- state logic

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

        // Grab ore exposed in the walls before pressing on.
        if (config.mineExposedOres) {
            if (oreTarget != null) {
                handleOreDetour(mc);
                return; // detour owns movement until the ore is dealt with
            }
            if (ticksInState % Math.max(1, config.oreScanInterval) == 0) {
                BlockPos ore = findSafeExposedOre(mc);
                if (ore != null) {
                    beginOreDetour(mc, ore);
                    return;
                }
            }
        }

        // Keep the tunnel alive: if Baritone goes idle (finished a finite tunnel or
        // hiccuped) while we still have room, re-issue the command.
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
            rememberMinePos(mc); // the new mine home is right here, at the fresh face
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

    // ------------------------------------------------------- chest servicing

    private void enterServiceChests(Minecraft mc) {
        chestQueue.clear();
        chestIndex = 0;
        chestStep = ChestStep.PATH;
        ticksInStep = 0;
        actionClicks = 0;
        chestQueue.addAll(ContainerService.scanChests(mc.level, config.chestBoxes, mc.player.blockPosition()));
        if (chestQueue.isEmpty()) {
            chat(mc, "§cNo chests found in the selected area — stopping.");
            stop(mc);
            return;
        }
        chat(mc, "Found §e" + chestQueue.size() + "§r chest(s) to service.");
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
                    setStep(ChestStep.DEPOSIT);
                } else if (clickCooldown <= 0) {
                    MenuActions.openChest(mc, baritone, chest);
                    clickCooldown = config.clickDelayTicks * 2;
                    if (ticksInStep > 100) {
                        chat(mc, "§eChest at " + chest.toShortString() + " wouldn't open — skipping.");
                        nextChest();
                    }
                }
            }
            case DEPOSIT -> {
                if (clickCooldown > 0) return;
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (!(menu instanceof ChestMenu)) { setStep(ChestStep.CLOSE); return; }
                int slot = ContainerService.nextDepositSlot(menu, config.keepItems);
                int bound = ContainerService.containerSlotCount(menu) + 40;
                if (slot == -1 || actionClicks > bound) {
                    actionClicks = 0;
                    setStep(ChestStep.WITHDRAW);
                    return;
                }
                MenuActions.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
                actionClicks++;
                clickCooldown = config.clickDelayTicks;
            }
            case WITHDRAW -> {
                if (clickCooldown > 0) return;
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (!(menu instanceof ChestMenu)) { setStep(ChestStep.CLOSE); return; }
                Inventory inv = mc.player.getInventory();
                int needPick = config.targetPickaxes - ContainerService.countItem(inv, config.pickaxeItem);
                int needFood = config.targetFood - ContainerService.countItem(inv, config.foodItem);
                int bound = ContainerService.containerSlotCount(menu) + 40;
                int slot = -1;
                if (needPick > 0) slot = ContainerService.nextWithdrawSlot(menu, config.pickaxeItem);
                if (slot == -1 && needFood > 0) slot = ContainerService.nextWithdrawSlot(menu, config.foodItem);
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
                // Stop early once everything is dumped and we're fully stocked.
                if (!moreWorkToDo(mc)) {
                    finishService(mc);
                }
            }
        }
    }

    private boolean moreWorkToDo(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (config.targetPickaxes - ContainerService.countItem(inv, config.pickaxeItem) > 0) return true;
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && !config.keepItems.contains(s.getItem())) return true;
        }
        return false;
    }

    private void finishService(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        int picks = ContainerService.countItem(inv, config.pickaxeItem);
        int food = ContainerService.countItem(inv, config.foodItem);
        if (moreWorkToDo(mc)) {
            chat(mc, "§eService incomplete (picks=" + picks + ", food=" + food
                    + ") — chests may be full or out of supplies. Continuing anyway.");
        } else {
            chat(mc, "Serviced chests (picks=" + picks + ", food=" + food + "). Back to mining.");
        }
        setState(mc, MinerState.GO_TO_MINE);
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

    private void runBaritone(String command) {
        if (baritone != null) {
            baritone.getCommandManager().execute(command);
        }
    }

    // ----------------------------------------------------------- tunnelling

    /**
     * Face the unmined wall, then run Baritone's {@code tunnel}. Without this,
     * {@code tunnel} digs whichever way you happen to face — and if that's back
     * down the corridor you've already cleared, Baritone walks the entire open
     * tunnel (miles) before it reaches solid blocks.
     */
    private void startTunnel(Minecraft mc) {
        Direction dig = pickTunnelDirection(mc);
        LocalPlayer p = mc.player;
        p.setYRot(dig.toYRot());
        p.setXRot(0.0f);
        p.yRotO = p.getYRot();
        p.xRotO = p.getXRot();
        runBaritone("tunnel");
    }

    /**
     * Choose the heading that digs into unmined rock. The direction with the
     * deepest open air is the way back toward the entrance, so we dig the
     * opposite way — which lands on the unmined face whether we're standing right
     * at it (one open direction: behind us) or part-way down the corridor (two
     * open directions: the far one is the entrance).
     */
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
            // Only override the heading if that way is actually a wall to mine
            // (i.e. we're standing at the face, with cleared tunnel behind us).
            BlockPos ahead = feet.relative(dig);
            if (!passable(mc, ahead) || !passable(mc, ahead.above())) {
                return dig;
            }
        }
        // No clear face to correct toward: trust the heading the home teleport
        // restored (which was set while facing the dig direction).
        return mc.player.getDirection();
    }

    /** Number of consecutive walk-through (foot+head clear) steps in direction d. */
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

    // ----------------------------------------------------- exposed-ore detour

    /**
     * Nearest target ore within {@code oreScanRadius} that is exposed (has an air
     * face we can mine from) and — if {@code avoidFluidBehindOre} — has no lava or
     * water touching it. Returns null if there's nothing worth detouring for.
     */
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

    /** A face of the ore that opens onto air, to mine it from. */
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
        // Mined away / changed (e.g. we already broke it) → resume tunnelling.
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
                if (oreStepTicks > 200) { // can't break it (no tool? unreachable) — give up
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
        startTunnel(mc); // resume, re-facing the unmined wall
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

    /** Make sure a diamond pickaxe is in hand before mining ore. */
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
                // number-key swap the main-inventory slot onto hotbar slot 8
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, i, 8,
                        ContainerInput.SWAP, mc.player);
                inv.setSelectedSlot(8);
                return;
            }
        }
        // No diamond pickaxe available — mine with whatever's held (slower, may not drop).
    }

    // ------------------------------------------------------- position cache

    private int[] posOf(Minecraft mc) {
        BlockPos p = mc.player.blockPosition();
        return new int[]{p.getX(), p.getY(), p.getZ()};
    }

    private void rememberMinePos(Minecraft mc) {
        config.minePos = posOf(mc);
        config.save();
    }

    /** True if we're already within {@code range} blocks of a cached position. */
    private boolean near(Minecraft mc, int[] pos, double range) {
        if (pos == null) return false;
        double dx = mc.player.getX() - (pos[0] + 0.5);
        double dy = mc.player.getY() - pos[1];
        double dz = mc.player.getZ() - (pos[2] + 0.5);
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    /** True if we're already inside the chest area (expanded by a margin). */
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
