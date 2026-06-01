package com.luna0wl.baritoneworker.worker.builder;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.RotationUtils;
import com.luna0wl.baritoneworker.worker.common.Baritones;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.ItemNames;
import com.luna0wl.baritoneworker.worker.common.MenuActions;
import com.luna0wl.baritoneworker.worker.common.Teleporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class BuilderWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/builder");

    private static final Predicate<Item> ANY_BLOCK = item -> item instanceof BlockItem;

    private Map<Item, Integer> required;
    private boolean warnedNoSchematic;
    private boolean materialFallback;

    private final BuilderConfig config;
    private final Teleporter teleporter = new Teleporter();

    private BuilderState state = BuilderState.IDLE;
    private int ticksInState;
    private IBaritone baritone;

    private boolean becameActive;
    private int idleTicks;
    private boolean nudged;

    private BlockPos fileOrigin;

    private enum HomeStep { SETTLE, DELHOME, SETHOME }
    private HomeStep homeStep = HomeStep.SETTLE;
    private int homeStepTicks;

    private int goToWorkRetries;

    private boolean workPosKnownThisSession;

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
        required = null;
        warnedNoSchematic = false;
        goToWorkRetries = 0;
        workPosKnownThisSession = false;

        fileOrigin = (config.hasSchematicFile() && config.buildOriginPos == null)
                ? mc.player.blockPosition()
                : null;
        String source = config.hasSchematicFile()
                ? "§a file=§e" + config.schematicFile
                    + "§a origin=§e" + (config.buildOriginPos != null ? posStr(config.buildOriginPos) : posStr(blockArr(fileOrigin)))
                : "§a litematic=§e#" + config.litematicIndex;
        chat(mc, "§aStarted. buildHome=§e" + config.workHome + "§a baseHome=§e" + config.baseHome
                + source
                + (config.hasArea() ? "" : "§a (no supply area — build-only, won't restock)")
                + (config.hasStop() ? "§a stopAt=§e" + posStr(config.stopPos) : ""));

        refreshRequired(mc);
        if (required != null && required.isEmpty()) {
            chat(mc, "§aNothing left to place — the build is already complete. Stopping.");
            state = BuilderState.IDLE;
            return;
        }

        int blocks = ContainerService.countMatching(mc.player.getInventory(), this::isMaterial);
        if (blocks == 0) {
            if (!config.hasArea()) {
                chat(mc, "§cNo usable blocks on hand and no supply area set. Put the build's blocks in your inventory, "
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

    private void setState(Minecraft mc, BuilderState s) {
        state = s;
        ticksInState = 0;
        onEnter(mc, s);
    }

    private void onEnter(Minecraft mc, BuilderState s) {
        switch (s) {
            case GO_TO_WORK -> {
                goToWorkRetries = 0;
                if (near(mc, config.workPos, config.skipTeleportRange)) {
                    chat(mc, "Already at the build site — building.");
                    cancelBaritone();
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
            case RESET_HOME -> {
                cancelBaritone();
                homeStep = HomeStep.SETTLE;
                homeStepTicks = 0;
            }
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
        if (!teleportArrived(mc)) return;

        boolean canVerify = workPosKnownThisSession && config.workPos != null;
        boolean landed = canVerify && near(mc, config.workPos, Math.max(config.skipTeleportRange, 4.0));
        if (canVerify && !landed) {
            if (goToWorkRetries < Math.max(0, config.teleportRetries)) {
                goToWorkRetries++;
                chat(mc, "§eTeleport didn't land at the build home — retrying §ehome " + config.workHome
                        + "§e (" + goToWorkRetries + "/" + config.teleportRetries + ").");
                teleporter.begin(mc);
                sendCommand(mc, "home " + config.workHome);
                return;
            }
            chat(mc, "§cCouldn't land at the build home after " + config.teleportRetries
                    + " tries — stopping so I don't wander off. Check that §e/home " + config.workHome
                    + "§c works and points at the build.");
            stop(mc);
            return;
        }

        goToWorkRetries = 0;
        cancelBaritone();
        if (!canVerify || landed) {
            rememberWorkPos(mc);
        }
        chat(mc, "At the build site — building.");
        setState(mc, BuilderState.BUILD);
    }

    private void tickBuild(Minecraft mc) {

        if (config.hasStop() && near(mc, config.stopPos, config.stopRadius)) {
            chat(mc, "Reached the stop coordinates — done.");
            stop(mc);
            return;
        }

        if (baritone.getBuilderProcess().isPaused()) {

            refreshRequired(mc);
            Inventory inv = mc.player.getInventory();
            if (required != null && !stillNeedMaterials(inv)) {

                chat(mc, "§eBaritone paused but I already hold the needed materials — the build looks blocked "
                        + "(an unreachable or unplaceable spot). Stopping; check it and resume manually.");
                stop(mc);
                return;
            }
            int blocks = ContainerService.countMatching(inv, this::isMaterial);
            if (!config.hasArea()) {
                chat(mc, "§eOut of materials (usable blocks=" + blocks + ") and no supply area set — "
                        + "resume manually after refilling, or set an area with §e#sel 1§e/§e#sel 2§e then §e#builder area§e. Stopping.");
                stop(mc);
                return;
            }
            chat(mc, "Out of materials (usable blocks=" + blocks + ") — Baritone paused, restocking " + neededSummary() + ".");
            cancelBaritone();
            setState(mc, BuilderState.RESET_HOME);
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

            if (idleTicks == Math.max(1, config.idleReissueTicks) && !nudged) {
                nudged = true;
                issueBuild(mc);
            } else if (idleTicks > config.resumeGraceTicks) {
                chat(mc, "§eNothing to build — schematic complete, no open Litematica placement, or out of supplies. Stopping.");
                stop(mc);
            }
        } else {

            if (idleTicks > Math.max(1, config.idleReissueTicks)) {
                if (!baritone.getBuilderProcess().isActive()) {
                    chat(mc, "§aBuild complete — Baritone reports done. Stopping.");
                    stop(mc);
                } else if (!nudged) {

                    nudged = true;
                    idleTicks = 0;
                    issueBuild(mc);
                } else if (idleTicks > config.resumeGraceTicks) {
                    chat(mc, "§eBuilder stalled — has a schematic but isn't placing or paused. Stopping; resume manually if needed.");
                    stop(mc);
                }
            }
        }
    }

    private void tickResetHome(Minecraft mc) {
        int g = Math.max(1, config.commandGapTicks);

        if (!config.resetWorkHome) {
            if (ticksInState >= g) {
                chat(mc, "Keeping build home — heading to base.");
                setState(mc, BuilderState.GO_TO_HOME);
            }
            return;
        }

        homeStepTicks++;
        switch (homeStep) {
            case SETTLE -> {
                boolean grounded = mc.player.onGround() && homeStepTicks >= g;
                if (grounded || homeStepTicks > Math.max(g, 100)) {
                    sendCommand(mc, "delhome " + config.workHome);
                    chat(mc, "§7/delhome " + config.workHome);
                    advanceHomeStep(HomeStep.DELHOME);
                }
            }
            case DELHOME -> {
                if (homeStepTicks >= g) {
                    sendCommand(mc, "sethome " + config.workHome);
                    rememberWorkPos(mc);
                    BlockPos p = mc.player.blockPosition();
                    chat(mc, "§7/sethome " + config.workHome + "§r at §e" + p.getX() + " " + p.getY() + " " + p.getZ()
                            + "§r — will teleport back here to resume.");
                    advanceHomeStep(HomeStep.SETHOME);
                }
            }
            case SETHOME -> {
                if (homeStepTicks >= g) {
                    setState(mc, BuilderState.GO_TO_HOME);
                }
            }
        }
    }

    private void advanceHomeStep(HomeStep s) {
        homeStep = s;
        homeStepTicks = 0;
    }

    private void tickGoToHome(Minecraft mc) {
        if (teleportArrived(mc)) {
            config.homePos = posOf(mc);
            config.save();
            chat(mc, "At base — fetching materials.");
            setState(mc, BuilderState.SERVICE_CHESTS);
        }
    }

    private void issueBuild(Minecraft mc) {
        if (baritone == null) return;
        if (config.hasSchematicFile()) {
            issueFileBuild(mc);
        } else {
            baritone.getBuilderProcess().buildOpenLitematic(config.litematicIndex);
        }
    }

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

    private boolean isMaterial(Item item) {
        return required != null ? required.containsKey(item) : ANY_BLOCK.test(item);
    }

    private boolean needMore(Inventory inv, Item item) {
        if (required == null) return true;
        int perBuild = required.getOrDefault(item, 0);
        if (perBuild <= 0) return false;
        if (config.materialBuilds <= 0) return true;
        return ContainerService.countItem(inv, item) < perBuild * config.materialBuilds;
    }

    private boolean stillNeedMaterials(Inventory inv) {
        if (required == null) return true;
        for (Item it : required.keySet()) {
            if (needMore(inv, it)) return true;
        }
        return false;
    }

    private String neededSummary() {
        if (required == null || required.isEmpty()) return "blocks";
        return required.entrySet().stream()
                .limit(6)
                .map(e -> e.getValue() + "x " + ItemNames.idOf(e.getKey()))
                .collect(Collectors.joining(", "))
                + (required.size() > 6 ? ", …" : "");
    }

    private void refreshRequired(Minecraft mc) {
        SchematicRef ref = loadSchematic(mc);
        if (ref == null) {
            required = null;
            if (!warnedNoSchematic) {
                warnedNoSchematic = true;
                chat(mc, "§eCouldn't read the build's material list — falling back to restocking any blocks. "
                        + "(Open the Litematica placement, or build from a schematic file.)");
            }
            return;
        }
        IStaticSchematic sch = ref.schematic();
        Map<Item, Integer> counts = new LinkedHashMap<>();
        long total = 0;
        for (int x = 0; x < sch.widthX(); x++) {
            for (int y = 0; y < sch.heightY(); y++) {
                for (int z = 0; z < sch.lengthZ(); z++) {
                    BlockState desired = sch.getDirect(x, y, z);
                    if (desired == null || desired.isAir()) continue;
                    Item item = desired.getBlock().asItem();
                    if (item == Items.AIR) continue;
                    counts.merge(item, 1, Integer::sum);
                    total++;
                }
            }
        }
        boolean firstRead = required == null || required.isEmpty();
        required = counts;
        warnedNoSchematic = false;
        if (firstRead && !counts.isEmpty()) {
            chat(mc, "Build needs §e" + total + "§r block(s) across §e" + counts.size()
                    + "§r type(s): " + neededSummary() + ".");
        }
    }

    private SchematicRef loadSchematic(Minecraft mc) {
        try {
            if (config.hasSchematicFile()) {
                File f = resolveSchematic(mc);
                if (!f.exists()) return null;
                Optional<ISchematicFormat> fmt = BaritoneAPI.getProvider().getSchematicSystem().getByFile(f);
                if (fmt.isEmpty()) return null;
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    IStaticSchematic sch = fmt.get().parse(in);
                    return sch == null ? null : new SchematicRef(sch, resolveOrigin(mc));
                }
            }

            Class<?> helper = Class.forName("baritone.utils.schematic.litematica.LitematicaHelper");
            if (!(boolean) helper.getMethod("isLitematicaPresent").invoke(null)) return null;
            if (!(boolean) helper.getMethod("hasLoadedSchematic", int.class).invoke(null, config.litematicIndex)) return null;
            Object tuple = helper.getMethod("getSchematic", int.class).invoke(null, config.litematicIndex);
            @SuppressWarnings("unchecked")
            Tuple<IStaticSchematic, Vec3i> t = (Tuple<IStaticSchematic, Vec3i>) tuple;
            Vec3i o = t.getB();
            return new SchematicRef(t.getA(), new BlockPos(o.getX(), o.getY(), o.getZ()));
        } catch (Throwable e) {
            LOG.warn("Couldn't read schematic for material list: {}", e.toString());
            return null;
        }
    }

    private record SchematicRef(IStaticSchematic schematic, BlockPos origin) {}

    private void enterServiceChests(Minecraft mc) {
        chestQueue.clear();
        chestIndex = 0;
        chestStep = ChestStep.PATH;
        ticksInStep = 0;
        actionClicks = 0;
        materialFallback = false;
        blocksBeforeService = ContainerService.countMatching(mc.player.getInventory(), this::isMaterial);
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

    private int nextSupplyWithdrawSlot(Minecraft mc, AbstractContainerMenu menu) {
        Inventory inv = mc.player.getInventory();
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, config.foodItem);
            if (s != -1) return s;
        }
        if (ContainerService.freeSlots(inv) > 0) {
            Predicate<Item> want = materialFallback
                    ? ANY_BLOCK
                    : item -> isMaterial(item) && needMore(inv, item);
            return ContainerService.nextWithdrawSlotMatching(menu, want);
        }
        return -1;
    }

    private boolean moreWorkToDo(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) return true;
        if (ContainerService.freeSlots(inv) <= 0) return false;
        return materialFallback || stillNeedMaterials(inv);
    }

    private void finishService(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        Predicate<Item> counted = materialFallback ? ANY_BLOCK : this::isMaterial;
        int blocks = ContainerService.countMatching(inv, counted);
        int food = ContainerService.countItem(inv, config.foodItem);
        if (blocks == 0) {
            if (!materialFallback && required != null) {
                materialFallback = true;
                chestIndex = 0;
                setStep(ChestStep.PATH);
                chat(mc, "§eNone of the build's listed blocks were in the chests (looking for: " + neededSummary()
                        + ") — taking whatever blocks are there instead.");
                return;
            }
            chat(mc, "§cThe supply chests have no blocks at all — stopping.");
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
        workPosKnownThisSession = true;
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

    private static int[] blockArr(BlockPos p) {
        return p == null ? null : new int[]{p.getX(), p.getY(), p.getZ()};
    }

    private void chat(Minecraft mc, String msg) {
        LOG.info(msg.replaceAll("§.", ""));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§d[Builder]§r " + msg));
        }
    }
}
