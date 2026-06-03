package com.luna0wl.baritoneworker.worker.miner;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.utils.BetterBlockPos;
import com.luna0wl.baritoneworker.worker.common.AreaSelection;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.DebugLog;
import com.luna0wl.baritoneworker.worker.common.ItemCategories;
import com.luna0wl.baritoneworker.worker.common.WorkerEquip;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MinerCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "area", "corner1", "corner2", "restock", "keep",
            "freeslots", "mine", "home", "ore", "ender", "sorter", "save", "debug");

    private final MinerWorker worker;
    private final MinerConfig config;
    private final AreaSelection areaSel = new AreaSelection();
    private final AreaSelection restockSel = new AreaSelection();

    public MinerCommand(IBaritone baritone, MinerWorker worker, MinerConfig config) {
        super(baritone, "miner");
        this.worker = worker;
        this.config = config;
    }

    @Override
    public void execute(String label, IArgConsumer args) {
        try {
            if (!args.hasAny()) {
                printStatus();
                return;
            }
            String sub = args.getString().toLowerCase(Locale.ROOT);
            switch (sub) {
                case "start" -> worker.start(ctx.minecraft());
                case "stop" -> worker.stop(ctx.minecraft());
                case "status" -> printStatus();
                case "save" -> { config.save(); logDirect("Settings saved."); }
                case "area" -> doArea(args);
                case "corner1" -> captureCorner(areaSel, false, false);
                case "corner2" -> captureCorner(areaSel, true, false);
                case "restock" -> doRestock(args);
                case "keep" -> doKeep(args);
                case "freeslots" -> { config.stopAtFreeSlots = nextInt(args, config.stopAtFreeSlots); config.save(); logDirect("stopAtFreeSlots = " + config.stopAtFreeSlots); }
                case "mine" -> { config.mineHome = args.getString(); config.save(); logDirect("mineHome = " + config.mineHome); }
                case "home" -> { config.baseHome = args.getString(); config.save(); logDirect("baseHome = " + config.baseHome); }
                case "ore" -> doOre(args);
                case "ender" -> doEnder(args);
                case "sorter" -> doSorter(args);
                case "debug" -> doDebug(args);
                default -> logDirect("Unknown subcommand '" + sub + "'. Try: " + String.join(", ", SUBS));
            }
        } catch (Exception e) {
            logDirect("§cError: " + e.getMessage());
        }
    }

    private void doKeep(IArgConsumer args) {
        if (!args.hasAny()) { printKeep(); return; }
        String op = args.getString().toLowerCase(Locale.ROOT);
        switch (op) {
            case "list" -> printKeep();
            case "clear" -> { config.equip.clear(); config.save(); logDirect("Keep list cleared."); }
            case "add", "set" -> {
                if (!args.hasAny()) { logDirect("Usage: §e#miner keep add <item|category> <count>"); return; }
                String token = args.getString().trim();
                int count = nextInt(args, 1);
                if (!config.equip.add(token, count)) {
                    logDirect("§cUnknown item/category '" + token + "'. Use a registry id (e.g. §enetherite_pickaxe§c) "
                            + "or a category (§e" + String.join(", ", ItemCategories.names()) + "§c).");
                    return;
                }
                config.save();
                logDirect("keep §e" + token + "§r ×" + count);
            }
            case "remove" -> {
                if (!args.hasAny()) { logDirect("Usage: §e#miner keep remove <item|category>"); return; }
                String token = args.getString().trim();
                boolean removed = config.equip.remove(token);
                config.save();
                logDirect(removed ? "Removed §e" + token + "§r from the keep list." : "§cNot in keep list: " + token);
            }
            default -> logDirect("Usage: §e#miner keep add|remove|set|clear|list <item|category> <count>");
        }
    }

    private void printKeep() {
        logDirect("§9Keep list§r (kept on deposit, topped up on restock):");
        if (config.equip.isEmpty()) {
            logDirect("  §7(empty)");
            return;
        }
        for (WorkerEquip.Entry e : config.equip.entries()) {
            logDirect("  §e" + e.token() + "§r ×" + e.count() + (e.isCategory() ? " §7(category)" : ""));
        }
    }

    private void captureCorner(AreaSelection sel, boolean second, boolean restock) {
        BlockPos feet = ctx.playerFeet();
        String name = restock ? "Restock" : "Chest";
        if (second) sel.setCorner2(feet);
        else sel.setCorner1(feet);
        logDirect("§a" + name + " corner " + (second ? "2" : "1") + " = §r"
                + feet.getX() + "," + feet.getY() + "," + feet.getZ());
        if (!sel.ready()) {
            logDirect("§7Now stand on the opposite corner and run §e#miner "
                    + (restock ? "restock " : "") + "corner" + (second ? "1" : "2") + "§7.");
            return;
        }
        List<int[]> boxes = sel.boxes();
        if (restock) config.setRestock(boxes); else config.setArea(boxes);
        config.save();
        int chests = ContainerService.scanChests(ctx.world(), boxes, ctx.playerFeet(), config.includeEnderChests).size();
        logDirect("Captured the " + name.toLowerCase(Locale.ROOT) + " area holding §e" + chests + "§r chest(s)/barrel(s).");
        logDirect("Area: " + ContainerService.describeArea(ctx.world(), boxes, config.includeEnderChests));
        if (chests == 0) {
            logDirect("§eNo chests detected yet — make sure the area is loaded and contains chests.");
        }
    }

    private void doArea(IArgConsumer args) {
        String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        switch (op) {
            case "clear" -> { config.clearArea(); areaSel.clear(); config.save(); logDirect("Chest area cleared."); }
            case "corner1" -> captureCorner(areaSel, false, false);
            case "corner2" -> captureCorner(areaSel, true, false);
            case "status" -> {
                logDirect("Chest area: §e" + config.chestBoxes.size() + "§r box(es) "
                        + (config.hasArea() ? "" : "§c(not set)") + "  pending: " + areaSel.status());
                if (config.hasArea()) {
                    logDirect("Area: " + ContainerService.describeArea(ctx.world(), config.chestBoxes, config.includeEnderChests));
                }
            }
            default -> logDirect("Usage: §e#miner area corner1|corner2|clear|status§r (or §e#miner corner1/corner2§r).");
        }
    }

    private void doRestock(IArgConsumer args) {
        String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        switch (op) {
            case "clear" -> { config.clearRestock(); restockSel.clear(); config.save(); logDirect("Restock area cleared — restocking falls back to the chest area."); }
            case "corner1" -> captureCorner(restockSel, false, true);
            case "corner2" -> captureCorner(restockSel, true, true);
            case "home" -> {
                if (!args.hasAny()) { logDirect("restockHome = §e" + (config.restockHome.isBlank() ? "(shares base home)" : config.restockHome)); return; }
                String h = args.getString().trim();
                config.restockHome = h.equalsIgnoreCase("clear") ? "" : h;
                config.save();
                logDirect("restockHome = §e" + (config.restockHome.isBlank() ? "(shares base home)" : config.restockHome));
            }
            case "status" -> {
                logDirect("Restock area: §e" + config.restockBoxes.size() + "§r box(es) "
                        + (config.hasRestock() ? "" : "§7(unset — uses the chest area)")
                        + "  restockHome=§e" + (config.restockHome.isBlank() ? "(base)" : config.restockHome)
                        + "  pending: " + restockSel.status());
                if (config.hasRestock()) {
                    logDirect("Area: " + ContainerService.describeArea(ctx.world(), config.restockBoxes, config.includeEnderChests));
                }
            }
            default -> logDirect("Usage: §e#miner restock corner1|corner2|clear|status|home <name>");
        }
    }

    private void doDebug(IArgConsumer args) {
        if (args.hasAny()) {
            String v = args.getString().toLowerCase(Locale.ROOT);
            if (v.equals("scan")) {
                doDebugScan(args);
                return;
            }
            if (v.equals("dump")) {
                doDebugDump(args);
                return;
            }
            config.debug = v.equals("on") || v.equals("true") || v.equals("1");
            config.save();
            DebugLog.setEnabled(config.debug);
            if (config.debug) DebugLog.reset();
            logDirect("Miner debug logging: " + (config.debug ? "§aON" : "§cOFF")
                    + " §7(file: config/baritoneworker/debug.log)");
            if (config.debug) logDirect("§7Now run a service cycle — state is written to the log file.");
            return;
        }
        logDirect("§6=== miner debug snapshot ===");
        BetterBlockPos p = ctx.playerFeet();
        logDirect("player=" + p.x + "," + p.y + "," + p.z
                + "  baseHome=" + config.baseHome
                + "  homePos=" + (config.homePos == null ? "unset"
                        : config.homePos[0] + "," + config.homePos[1] + "," + config.homePos[2]));
        if (!config.hasArea()) {
            logDirect("§cNo chest area set — run #miner area.");
            return;
        }
        for (int[] b : config.chestBoxes) {
            int cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
            double dist = Math.sqrt(Math.pow(p.x - cx, 2) + Math.pow(p.y - cy, 2) + Math.pow(p.z - cz, 2));
            logDirect("box=[" + b[0] + "," + b[1] + "," + b[2] + " .. " + b[3] + "," + b[4] + "," + b[5]
                    + "] center=" + cx + "," + cy + "," + cz
                    + " dist=" + String.format(Locale.ROOT, "%.1f", dist));
        }
        logDirect("Area: " + ContainerService.describeArea(ctx.world(), config.chestBoxes, config.includeEnderChests));
        logDirect("baritone: pathing=" + baritone.getPathingBehavior().isPathing());
        BetterBlockPos pp = ctx.playerFeet();
        DebugLog.write("snapshot", "player=" + pp.x + "," + pp.y + "," + pp.z
                + " homePos=" + (config.homePos == null ? "unset"
                        : config.homePos[0] + "," + config.homePos[1] + "," + config.homePos[2])
                + " boxes=" + config.chestBoxes.size()
                + " area={" + ContainerService.describeArea(ctx.world(), config.chestBoxes, config.includeEnderChests) + "}");
    }

    private void doDebugDump(IArgConsumer args) {
        int r = args.hasAny() ? Math.max(1, Math.min(48, nextInt(args, 16))) : 16;
        BetterBlockPos p = ctx.playerFeet();
        Level w = ctx.world();
        DebugLog.write("dump", "=== dump r=" + r + " around " + p.x + "," + p.y + "," + p.z + " ===");
        int scanned = 0, unloaded = 0, containers = 0, serviceable = 0;
        java.util.TreeMap<String, Integer> byType = new java.util.TreeMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = p.x - r; x <= p.x + r; x++) {
            for (int y = p.y - r; y <= p.y + r; y++) {
                for (int z = p.z - r; z <= p.z + r; z++) {
                    cursor.set(x, y, z);
                    if (!w.isLoaded(cursor)) { unloaded++; continue; }
                    scanned++;
                    BlockState st = w.getBlockState(cursor);
                    if (st.isAir()) continue;
                    BlockEntity be = w.getBlockEntity(cursor);
                    boolean isContainer = be instanceof Container
                            || ContainerService.isStorageAt(w, cursor, true);
                    if (!isContainer) continue;
                    containers++;
                    boolean svc = ContainerService.isStorageAt(w, cursor, config.includeEnderChests);
                    if (svc) serviceable++;
                    String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    byType.merge(id + (svc ? "" : " §c[not serviced]"), 1, Integer::sum);
                    DebugLog.write("dump", "  " + x + "," + y + "," + z + "  " + id
                            + "  serviceable=" + svc);
                }
            }
        }
        DebugLog.write("dump", "scanned=" + scanned + " unloaded=" + unloaded
                + " containers=" + containers + " serviceable=" + serviceable);
        for (java.util.Map.Entry<String, Integer> e : byType.entrySet()) {
            DebugLog.write("dump", "  type " + e.getKey() + " x" + e.getValue());
        }
        logDirect("§aDump complete: §e" + containers + "§r container(s) (§e" + serviceable
                + "§r serviceable) within " + r + " blocks → written to config/baritoneworker/debug.log");
        if (containers > serviceable) {
            logDirect("§e" + (containers - serviceable) + " container(s) are NOT serviced by the miner "
                    + "(only chests/barrels are). See the log for their types.");
        }
    }

    private void doDebugScan(IArgConsumer args) {
        int r = args.hasAny() ? Math.max(1, Math.min(64, nextInt(args, 32))) : 32;
        BetterBlockPos p = ctx.playerFeet();
        List<int[]> box = new ArrayList<>();
        box.add(new int[]{p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r});
        List<BlockPos> found = ContainerService.scanChests(ctx.world(), box, p, config.includeEnderChests);
        logDirect("§6Scan r=" + r + " around " + p.x + "," + p.y + "," + p.z + ": §e" + found.size()
                + "§r chest(s)/barrel(s) in loaded chunks.");
        if (found.isEmpty()) {
            logDirect("§eNothing nearby — move closer to your chests and try again, or raise the radius (#miner debug scan 64).");
            return;
        }
        int minx = found.get(0).getX(), miny = found.get(0).getY(), minz = found.get(0).getZ();
        int maxx = minx, maxy = miny, maxz = minz;
        for (BlockPos b : found) {
            minx = Math.min(minx, b.getX()); maxx = Math.max(maxx, b.getX());
            miny = Math.min(miny, b.getY()); maxy = Math.max(maxy, b.getY());
            minz = Math.min(minz, b.getZ()); maxz = Math.max(maxz, b.getZ());
        }
        logDirect("§aSelect this box: §f" + minx + "," + miny + "," + minz + "  ..  " + maxx + "," + maxy + "," + maxz);
        int show = Math.min(8, found.size());
        for (int i = 0; i < show; i++) {
            BlockPos b = found.get(i);
            logDirect("  " + b.getX() + "," + b.getY() + "," + b.getZ());
        }
        if (found.size() > show) logDirect("  ... and " + (found.size() - show) + " more");
    }

    private void doOre(IArgConsumer args) {
        if (!args.hasAny()) {
            printOreStatus();
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        switch (a) {
            case "on" -> { config.mineExposedOres = true; config.save(); logDirect("Exposed-ore mining: §aON"); }
            case "off" -> { config.mineExposedOres = false; config.save(); logDirect("Exposed-ore mining: §cOFF§r (plain tunnelling)"); }
            case "status" -> printOreStatus();
            case "radius" -> {
                config.oreScanRadius = Math.max(1, Math.min(16, nextInt(args, config.oreScanRadius)));
                config.save();
                logDirect("oreScanRadius = " + config.oreScanRadius);
            }
            case "fluidcheck" -> {
                String v = args.hasAny() ? args.getString() : "on";
                config.avoidFluidBehindOre = !v.equalsIgnoreCase("off");
                config.save();
                logDirect("avoidFluidBehindOre = " + config.avoidFluidBehindOre
                        + (config.avoidFluidBehindOre ? " §7(skips ore touching lava/water)" : ""));
            }
            case "exclude" -> doOreExcludeInclude(args, true);
            case "include" -> doOreExcludeInclude(args, false);
            default -> logDirect("Usage: §e#miner ore§r on|off|status|radius <n>|fluidcheck on|off|exclude <group>|include <group>");
        }
    }

    private void doOreExcludeInclude(IArgConsumer args, boolean exclude) {
        if (!args.hasAny()) {
            logDirect("Ore groups: §e" + String.join(", ", Ores.groupNames()) + "§r (or 'all')");
            return;
        }
        String g = args.getString().toLowerCase(Locale.ROOT);
        if (g.equals("all")) {
            if (exclude) config.excludedOreGroups.addAll(Ores.groupNames());
            else config.excludedOreGroups.clear();
        } else if (Ores.isGroup(g)) {
            if (exclude) config.excludedOreGroups.add(g);
            else config.excludedOreGroups.remove(g);
        } else {
            logDirect("§cUnknown ore group '" + g + "'. Known: §e" + String.join(", ", Ores.groupNames()));
            return;
        }
        config.save();
        logDirect("Now mining: §e" + minedGroups());
    }

    private String minedGroups() {
        List<String> g = new ArrayList<>();
        for (String n : Ores.groupNames()) {
            if (!config.excludedOreGroups.contains(n)) g.add(n);
        }
        return g.isEmpty() ? "(none)" : String.join(", ", g);
    }

    private void printOreStatus() {
        logDirect("§9Exposed-ore mining§r: " + (config.mineExposedOres ? "§aON" : "§cOFF")
                + "§r  fluidCheck=§e" + config.avoidFluidBehindOre + "§r  radius=§e" + config.oreScanRadius);
        logDirect(" mining: §e" + minedGroups());
        if (!config.excludedOreGroups.isEmpty()) {
            logDirect(" excluded: §c" + String.join(", ", config.excludedOreGroups));
        }
    }

    private void doEnder(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("ender-chests = " + (config.includeEnderChests ? "§aon" : "§coff")
                    + "§r — " + (config.includeEnderChests
                        ? "ender chests in the area are serviced too."
                        : "ender chests in the area are ignored."));
            logDirect("Usage: §e#miner ender on|off");
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        boolean on = a.equals("on") || a.equals("true") || a.equals("yes") || a.equals("1") || a.equals("include");
        boolean off = a.equals("off") || a.equals("false") || a.equals("no") || a.equals("0") || a.equals("ignore");
        if (!on && !off) {
            logDirect("Usage: §e#miner ender on|off");
            return;
        }
        config.includeEnderChests = on;
        config.save();
        logDirect("ender-chests = " + (on ? "§aon§r — will also service ender chests."
                : "§coff§r — ender chests are ignored."));
    }

    private void doSorter(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("sort-on-deposit = " + (config.useSorter ? "§aon" : "§coff")
                    + "§r — " + (config.useSorter
                        ? "each deposited item goes straight into the chest tagged for it."
                        : "deposits into the first free chest (no sorting)."));
            logDirect("Usage: §e#miner sorter on|off§r (tag chests with signs or §e#sorter assign§r)");
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        boolean on = a.equals("on") || a.equals("true") || a.equals("yes") || a.equals("1");
        boolean off = a.equals("off") || a.equals("false") || a.equals("no") || a.equals("0");
        if (!on && !off) {
            logDirect("Usage: §e#miner sorter on|off");
            return;
        }
        config.useSorter = on;
        config.save();
        logDirect("sort-on-deposit = " + (on
                ? "§aon§r — deposits land in the chest tagged for them (signs / sortscheme.json)."
                : "§coff§r — deposits into the first free chest."));
    }

    private int nextInt(IArgConsumer args, int fallback) {
        if (!args.hasAny()) return fallback;
        try {
            return Integer.parseInt(args.getString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void printStatus() {
        logDirect("§9BaritoneWorker§r — state: §e" + worker.getState());
        logDirect(" mineHome=§e" + config.mineHome + "§r baseHome=§e" + config.baseHome
                + "§r stopAtFreeSlots=§e" + config.stopAtFreeSlots);
        logDirect(" keep=§e" + (config.equip.isEmpty() ? "(empty)" : config.equip.serialize()));
        logDirect(" chestArea=§e" + config.chestBoxes.size() + "§r box(es)"
                + (config.hasArea() ? "" : " §c(not set — run #miner corner1 / corner2)")
                + "§r restockArea=§e" + config.restockBoxes.size() + "§r box(es)"
                + (config.hasRestock() ? "" : " §7(uses chest area)"));
        logDirect(" exposedOreMining=" + (config.mineExposedOres ? "§aON" : "§cOFF") + "§r (#miner ore)"
                + "§r enderChests=" + (config.includeEnderChests ? "§aon" : "§coff")
                + "§r sortOnDeposit=" + (config.useSorter ? "§aon" : "§coff"));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            String prefix = args.peekString().toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Autonomous Baritone strip-mining worker";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Drives an autonomous mine → tunnel → deposit → restock loop.",
                "",
                "Setup:",
                "- Set Essentials homes 'mine' (at the tunnel face, looking down the tunnel)",
                "  and 'Home' (by your storage room).",
                "- Stand on one corner of your chest room and run #miner corner1, then the",
                "  opposite corner and #miner corner2.",
                "",
                "Usage:",
                "> miner - show status",
                "> miner start / stop",
                "> miner corner1 / corner2 - capture the chest (deposit) area by standing on its corners",
                "> miner area clear|status - manage the chest area",
                "> miner restock corner1|corner2|clear|status - a separate supply area to restock from",
                "> miner restock home <name|clear> - teleport to this home before restocking (default: base)",
                "> miner keep add <item|category> <count> - keep/restock e.g. 'keep add pickaxe 2',",
                "    'keep add stone_pickaxe 1', 'keep add diamond_pickaxe 1', 'keep add sword 1'",
                "> miner keep remove <item|category> / keep clear / keep list",
                "> miner freeslots <n> - return to base at this many free slots (default 1)",
                "> miner mine <name> / miner home <name> - home names",
                "> miner ender on|off - also service ender chests in the area (default off)",
                "> miner sorter on|off - deposit each item into the chest tagged for it (signs/sortscheme; default off)",
                "",
                "Exposed-ore mining (optional, off by default):",
                "> miner ore on|off - detour to grab ore exposed in the tunnel walls",
                "> miner ore fluidcheck on|off - skip ore touching lava/water (default on)",
                "> miner ore exclude <group> / include <group> - e.g. exclude coal",
                "> miner ore radius <n> - how far to look for ore (default 6)",
                "  groups (stone + deepslate): coal iron copper gold redstone lapis diamond emerald",
                "",
                "Start/stop is also bound to a keybind (see Controls > Misc)."
        );
    }
}
