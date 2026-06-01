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

/**
 * The autonomous builder loop, driven once per client tick. Baritone builds the
 * schematic currently open in Litematica (or a schematic file); the worker watches
 * the build and, when it runs out of materials (Baritone prints "Missing materials …
 * Pausing"), teleports to the supply room, refills, teleports back, and resumes —
 * the same loop the miner uses, triggered by low materials instead of a full bag.
 *
 * <p>Refills are recipe-aware: the worker reads the schematic and subtracts what's
 * already placed in the world, so it only fetches the blocks the build still needs
 * — never block types the build doesn't use, and never more of a type than is left
 * to place (so repeat / partially-finished builds don't haul materials they already
 * used). If the schematic can't be read it falls back to restocking any block.
 *
 * <p>Unlike the miner the build site is world-anchored, so the work home is left
 * untouched by default (see {@link BuilderConfig#resetWorkHome}); the bot just
 * teleports back to the existing {@code build} home to resume.
 *
 * <p>Stops when the optional coordinate target is reached, when the build is complete
 * (nothing left to place), or when a refill can't find the blocks it still needs.
 */
public final class BuilderWorker {

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/builder");

    /** Fallback "materials" definition when we couldn't read the schematic: any placeable block. */
    private static final Predicate<Item> ANY_BLOCK = item -> item instanceof BlockItem;

    /** Don't world-diff schematics bigger than this (avoids a multi-second freeze on giant builds). */
    private static final long MAX_DIFF_VOLUME = 4_000_000L;

    /**
     * Blocks the build still needs → how many are left to place (the schematic's counts with
     * everything already placed in the world subtracted). {@code null} means we couldn't read
     * the schematic, in which case we fall back to {@link #ANY_BLOCK}. Refreshed against the
     * live world each time we run out, so repeat / partially-finished builds only ever fetch
     * what's actually still missing.
     */
    private Map<Item, Integer> required;
    private boolean warnedNoSchematic;
    /** True when the current build tiles via Baritone's buildRepeat (set by refreshRequired). */
    private boolean repeating;

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

    // --- RESET_HOME sub-state (only used when config.resetWorkHome) ---
    private enum HomeStep { SETTLE, DELHOME, SETHOME }
    private HomeStep homeStep = HomeStep.SETTLE;
    private int homeStepTicks;

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
        required = null;
        warnedNoSchematic = false;
        // For a file build with no explicit origin, anchor the schematic at the block
        // we're standing on RIGHT NOW — captured before GO_TO_WORK teleports us away.
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

        // Read the schematic's material list and subtract what's already placed in the
        // world, so we only ever fetch blocks the build still needs (handles repeat /
        // partially-finished builds — see refreshRequired).
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

        // Out of materials: Baritone prints "Missing materials ... Pausing" and sets the
        // builder paused. It does NOT clear the schematic, so isActive() keeps returning
        // true — the build just sits there doing nothing. A pause is ALWAYS "needs blocks",
        // never completion (with buildrepeat it just needs materials for the next tile), so
        // detect it explicitly and run the restock loop (base → refill → resume).
        if (baritone.getBuilderProcess().isPaused()) {
            // We're still standing at the build (chunks loaded), so recompute the shopping
            // list against the live world before we go.
            refreshRequired(mc);
            Inventory inv = mc.player.getInventory();
            if (required != null && !stillNeedMaterials(inv)) {
                // Paused, yet we already carry everything the build still needs — fetching
                // more can't help; it's blocked on something else (unreachable / unplaceable).
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
            // Builder hasn't started since we (re)issued it.
            if (idleTicks == Math.max(1, config.idleReissueTicks) && !nudged) {
                nudged = true;
                issueBuild(mc); // nudge once in case the first issue didn't take
            } else if (idleTicks > config.resumeGraceTicks) {
                chat(mc, "§eNothing to build — schematic complete, no open Litematica placement, or out of supplies. Stopping.");
                stop(mc);
            }
        } else {
            // Was building and is now idle WITHOUT being paused. Out-of-materials is handled
            // above (it pauses), so the only real reason to land here is that Baritone is done:
            // when it finishes — or hits the buildrepeat count — it clears the schematic, so
            // isActive() flips to false. Trust that, NOT a one-tile material diff (which would
            // read "complete" mid-repeat the moment the base tile is placed).
            if (idleTicks > Math.max(1, config.idleReissueTicks)) {
                if (!baritone.getBuilderProcess().isActive()) {
                    chat(mc, "§aBuild complete — Baritone reports done. Stopping.");
                    stop(mc);
                } else if (!nudged) {
                    // Still holding a schematic but stalled (not paused, not pathing) — nudge once.
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

        // Default: the build site is world-anchored, so we DON'T move the work home — just
        // settle a moment, then head to base and teleport back to the existing 'build' home.
        if (!config.resetWorkHome) {
            if (ticksInState >= g) {
                chat(mc, "Keeping build home — heading to base.");
                setState(mc, BuilderState.GO_TO_HOME);
            }
            return;
        }

        // sethome-on-leaving is ON: move the 'build' home to where we stopped. Out of
        // materials Baritone often pauses the bot mid-air on the structure, so wait until
        // we're settled on the ground before capturing the spot (else /sethome lands on a
        // junk position we'd then teleport back into). Each /delhome and /sethome is spaced
        // and echoed with coordinates so it's visible whether it actually fired.
        homeStepTicks++;
        switch (homeStep) {
            case SETTLE -> {
                boolean grounded = mc.player.onGround() && homeStepTicks >= g;
                if (grounded || homeStepTicks > Math.max(g, 100)) { // ground, or give up waiting
                    sendCommand(mc, "delhome " + config.workHome);
                    chat(mc, "§7/delhome " + config.workHome);
                    advanceHomeStep(HomeStep.DELHOME);
                }
            }
            case DELHOME -> {
                if (homeStepTicks >= g) {
                    sendCommand(mc, "sethome " + config.workHome);
                    rememberWorkPos(mc); // the new build home is right here, where we left off
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

    // --------------------------------------------------- material accounting

    /** A material we care about: a block the build still needs (or any block if the recipe is unknown). */
    private boolean isMaterial(Item item) {
        return required != null ? required.containsKey(item) : ANY_BLOCK.test(item);
    }

    /** Should we pull more of this item, given how many the build has left to place? */
    private boolean needMore(Inventory inv, Item item) {
        if (required == null) return true; // unknown recipe → top off the bag (old behavior)
        int perBuild = required.getOrDefault(item, 0);
        if (perBuild <= 0) return false;
        if (repeating && config.materialBuilds <= 0) return true; // infinite → fill the bag
        int target = repeating ? perBuild * config.materialBuilds : perBuild;
        return ContainerService.countItem(inv, item) < target;
    }

    /** True while the build still needs a block type we don't yet carry enough of. */
    private boolean stillNeedMaterials(Inventory inv) {
        if (required == null) return true;
        for (Item it : required.keySet()) {
            if (needMore(inv, it)) return true;
        }
        return false;
    }

    /** Short list of what's still needed, e.g. "5x cobbled_deepslate, 12x oak_planks". */
    private String neededSummary() {
        if (required == null || required.isEmpty()) return "blocks";
        return required.entrySet().stream()
                .limit(6)
                .map(e -> e.getValue() + "x " + ItemNames.idOf(e.getKey()))
                .collect(Collectors.joining(", "))
                + (required.size() > 6 ? ", …" : "");
    }

    /**
     * Recompute {@link #required}: read the build's schematic and, cell by cell, count only
     * the blocks not already placed correctly in the live world — so a repeat or partially
     * finished build only asks for what's genuinely still missing, and never re-fetches
     * blocks it already used. Call this while standing at the build (chunks loaded). On any
     * failure leaves {@code required = null}, falling back to the old any-block behavior.
     */
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
        BlockPos origin = ref.origin();
        long volume = (long) sch.widthX() * sch.heightY() * sch.lengthZ();
        // With buildrepeat the schematic tiles past the base origin, so a one-tile world diff
        // would wrongly read "all placed" once the base tile is done. When repeating, skip the
        // diff and count a full per-tile bill of materials instead — completion is then decided
        // by Baritone clearing the schematic (see tickBuild), not by this list emptying.
        Vec3i repeat = BaritoneAPI.getSettings().buildRepeat.value;
        repeating = repeat != null && (repeat.getX() != 0 || repeat.getY() != 0 || repeat.getZ() != 0);
        boolean diff = !repeating && mc.level != null && origin != null && volume <= MAX_DIFF_VOLUME;
        Map<Item, Integer> counts = new LinkedHashMap<>();
        BlockPos.MutableBlockPos wp = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sch.widthX(); x++) {
            for (int y = 0; y < sch.heightY(); y++) {
                for (int z = 0; z < sch.lengthZ(); z++) {
                    BlockState desired = sch.getDirect(x, y, z);
                    if (desired == null || desired.isAir()) continue;
                    Item item = desired.getBlock().asItem();
                    if (item == Items.AIR) continue;
                    if (diff) {
                        wp.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        if (mc.level.isLoaded(wp)) {
                            BlockState cur = mc.level.getBlockState(wp);
                            // Right block already there → already built, no longer needed.
                            if (!cur.isAir() && cur.getBlock().asItem() == item) continue;
                        }
                    }
                    counts.merge(item, 1, Integer::sum);
                }
            }
        }
        required = counts;
        warnedNoSchematic = false;
    }

    /** Load the build's schematic and its world origin — a file build, or the open Litematica placement. */
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
            // Open Litematica placement: Baritone's helper lives in the impl jar (not on our
            // compile classpath), so reach it reflectively — the same call buildOpenLitematic makes.
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

    // ------------------------------------------------------- chest servicing

    private void enterServiceChests(Minecraft mc) {
        chestQueue.clear();
        chestIndex = 0;
        chestStep = ChestStep.PATH;
        ticksInStep = 0;
        actionClicks = 0;
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

    /**
     * Next supply slot to pull: food up to target, then only blocks the build still
     * needs — and only up to how many are left to place. We never pull a block type the
     * schematic doesn't use, nor more of a type than {@link #required} still calls for
     * (which already has the placed/used blocks subtracted), so a repeat or nearly-done
     * build won't haul materials it no longer needs.
     */
    private int nextSupplyWithdrawSlot(Minecraft mc, AbstractContainerMenu menu) {
        Inventory inv = mc.player.getInventory();
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) {
            int s = ContainerService.nextWithdrawSlot(menu, config.foodItem);
            if (s != -1) return s;
        }
        if (ContainerService.freeSlots(inv) > 0) {
            return ContainerService.nextWithdrawSlotMatching(menu,
                    item -> isMaterial(item) && needMore(inv, item));
        }
        return -1;
    }

    private boolean moreWorkToDo(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (config.targetFood - ContainerService.countItem(inv, config.foodItem) > 0) return true;
        // Done once the bag is full, or we already hold everything the build still needs.
        return ContainerService.freeSlots(inv) > 0 && stillNeedMaterials(inv);
    }

    private void finishService(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        int blocks = ContainerService.countMatching(inv, this::isMaterial);
        int food = ContainerService.countItem(inv, config.foodItem);
        if (blocks == 0) {
            chat(mc, "§cSupply chests have none of the blocks this build needs — stopping.");
            stop(mc);
            return;
        }
        if (blocks <= blocksBeforeService) {
            chat(mc, "§eCouldn't add more of the needed blocks (chests low) — building with what's on hand (usable blocks=" + blocks + ").");
        } else {
            chat(mc, "Restocked (usable blocks=" + blocks + ", food=" + food + "). Back to building.");
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
