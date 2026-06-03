package com.luna0wl.baritoneworker.worker.lumber;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import com.luna0wl.baritoneworker.worker.common.AreaSelection;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.ItemCategories;
import com.luna0wl.baritoneworker.worker.common.WorkerEquip;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class LumberCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "area", "corner1", "corner2", "restock", "keep", "wood",
            "replant", "sapling", "freeslots", "work", "home", "ender", "sorter", "save");

    private final LumberWorker worker;
    private final LumberConfig config;
    private final AreaSelection areaSel = new AreaSelection();
    private final AreaSelection restockSel = new AreaSelection();

    public LumberCommand(IBaritone baritone, LumberWorker worker, LumberConfig config) {
        super(baritone, "lumber");
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
                case "sapling" -> doSapling(args);
                case "wood" -> doWood(args);
                case "replant" -> doReplant(args);
                case "freeslots" -> { config.stopAtFreeSlots = nextInt(args, config.stopAtFreeSlots); config.save(); logDirect("stopAtFreeSlots = " + config.stopAtFreeSlots); }
                case "work" -> { config.workHome = args.getString(); config.save(); logDirect("workHome = " + config.workHome); }
                case "home" -> { config.baseHome = args.getString(); config.save(); logDirect("baseHome = " + config.baseHome); }
                case "ender" -> doEnder(args);
                case "sorter" -> doSorter(args);
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
                if (!args.hasAny()) { logDirect("Usage: §e#lumber keep add <item|category> <count>"); return; }
                String token = args.getString().trim();
                int count = nextInt(args, 1);
                if (!config.equip.add(token, count)) {
                    logDirect("§cUnknown item/category '" + token + "'. Use a registry id (e.g. §enetherite_axe§c) "
                            + "or a category (§e" + String.join(", ", ItemCategories.names()) + "§c).");
                    return;
                }
                config.save();
                logDirect("keep §e" + token + "§r ×" + count);
            }
            case "remove" -> {
                if (!args.hasAny()) { logDirect("Usage: §e#lumber keep remove <item|category>"); return; }
                String token = args.getString().trim();
                boolean removed = config.equip.remove(token);
                config.save();
                logDirect(removed ? "Removed §e" + token + "§r from the keep list." : "§cNot in keep list: " + token);
            }
            default -> logDirect("Usage: §e#lumber keep add|remove|set|clear|list <item|category> <count>");
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
        logDirect("  §7saplings ×" + config.targetSaplings + " (when replant is on)");
    }

    private void doSapling(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("sapling: keep=§e" + config.targetSaplings + "§r (tracks selected wood flavours when replanting)");
            return;
        }
        config.targetSaplings = Math.max(0, nextInt(args, config.targetSaplings));
        config.save();
        logDirect("keep saplings = §e" + config.targetSaplings);
    }

    private void doWood(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("§9Wood flavours§r: §e" + harvested());
            logDirect(" known: §e" + String.join(", ", Woods.names()) + "§r (or 'all')");
            logDirect(" usage: §e#lumber wood include|exclude <flavour>");
            return;
        }
        String op = args.getString().toLowerCase(Locale.ROOT);
        boolean include;
        if (op.equals("include")) include = true;
        else if (op.equals("exclude")) include = false;
        else {
            logDirect("Usage: §e#lumber wood include|exclude <flavour|all>");
            return;
        }
        if (!args.hasAny()) {
            logDirect("Which flavour? Known: §e" + String.join(", ", Woods.names()) + "§r (or 'all')");
            return;
        }
        String g = args.getString().toLowerCase(Locale.ROOT);

        if (g.equals("all")) {
            if (include) config.woodFlavours.clear();
            else config.woodFlavours.addAll(Woods.names());
            if (!include) config.woodFlavours.clear();
        } else if (Woods.isFlavour(g)) {

            if (config.woodFlavours.isEmpty() && !include) config.woodFlavours.addAll(Woods.names());
            if (include) config.woodFlavours.add(g);
            else config.woodFlavours.remove(g);

            if (config.woodFlavours.containsAll(Woods.names())) config.woodFlavours.clear();
        } else {
            logDirect("§cUnknown flavour '" + g + "'. Known: §e" + String.join(", ", Woods.names()));
            return;
        }
        config.save();
        logDirect("Now harvesting: §e" + harvested());
    }

    private String harvested() {
        return config.woodFlavours.isEmpty() ? "all" : String.join(", ", config.woodFlavours);
    }

    private void doReplant(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("§9Replant§r: " + (config.replant ? "§aON" : "§cOFF") + "§r  keepSaplings=§e" + config.targetSaplings
                    + "§r  radius=§e" + config.replantRadius);
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        switch (a) {
            case "on" -> { config.setReplant(true); config.save(); logDirect("Replant: §aON§r (best-effort — plants a held sapling on cleared ground)"); }
            case "off" -> { config.setReplant(false); config.save(); logDirect("Replant: §cOFF§r (chop-and-haul only)"); }
            case "radius" -> {
                config.replantRadius = Math.max(1, Math.min(16, nextInt(args, config.replantRadius)));
                config.save();
                logDirect("replantRadius = " + config.replantRadius);
            }
            default -> logDirect("Usage: §e#lumber replant§r on|off|radius <n>");
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
            logDirect("§7Now stand on the opposite corner and run §e#lumber "
                    + (restock ? "restock " : "") + "corner" + (second ? "1" : "2") + "§7.");
            return;
        }
        List<int[]> boxes = sel.boxes();
        if (restock) config.setRestock(boxes); else config.setArea(boxes);
        config.save();
        int chests = ContainerService.scanChests(ctx.world(), boxes, ctx.playerFeet(), config.includeEnderChests).size();
        logDirect("Captured the " + name.toLowerCase(Locale.ROOT) + " area holding §e" + chests + "§r chest(s)/barrel(s).");
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
            case "status" -> logDirect("Chest area: §e" + config.chestBoxes.size() + "§r box(es) "
                    + (config.hasArea() ? "" : "§c(not set)") + "  pending: " + areaSel.status());
            default -> logDirect("Usage: §e#lumber area corner1|corner2|clear|status§r (or §e#lumber corner1/corner2§r).");
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
            case "status" -> logDirect("Restock area: §e" + config.restockBoxes.size() + "§r box(es) "
                    + (config.hasRestock() ? "" : "§7(unset — uses the chest area)")
                    + "  restockHome=§e" + (config.restockHome.isBlank() ? "(base)" : config.restockHome)
                    + "  pending: " + restockSel.status());
            default -> logDirect("Usage: §e#lumber restock corner1|corner2|clear|status|home <name>");
        }
    }

    private void doEnder(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("ender-chests = " + (config.includeEnderChests ? "§aon" : "§coff")
                    + "§r — " + (config.includeEnderChests
                        ? "ender chests in the area are serviced too."
                        : "ender chests in the area are ignored."));
            logDirect("Usage: §e#lumber ender on|off");
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        boolean on = a.equals("on") || a.equals("true") || a.equals("yes") || a.equals("1") || a.equals("include");
        boolean off = a.equals("off") || a.equals("false") || a.equals("no") || a.equals("0") || a.equals("ignore");
        if (!on && !off) {
            logDirect("Usage: §e#lumber ender on|off");
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
            logDirect("Usage: §e#lumber sorter on|off§r (tag chests with signs or §e#sorter assign§r)");
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        boolean on = a.equals("on") || a.equals("true") || a.equals("yes") || a.equals("1");
        boolean off = a.equals("off") || a.equals("false") || a.equals("no") || a.equals("0");
        if (!on && !off) {
            logDirect("Usage: §e#lumber sorter on|off");
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
        logDirect("§2BaritoneLumber§r — state: §e" + worker.getState());
        logDirect(" workHome=§e" + config.workHome + "§r baseHome=§e" + config.baseHome
                + "§r stopAtFreeSlots=§e" + config.stopAtFreeSlots);
        logDirect(" keep=§e" + (config.equip.isEmpty() ? "(empty)" : config.equip.serialize()));
        logDirect(" woods=§e" + harvested());
        logDirect(" replant=" + (config.replant ? "§aON" : "§cOFF") + "§r×" + config.targetSaplings + "§r (#lumber replant)");
        logDirect(" chestArea=§e" + config.chestBoxes.size() + "§r box(es)"
                + (config.hasArea() ? "" : " §c(not set — run #lumber corner1 / corner2)")
                + "§r restockArea=§e" + config.restockBoxes.size() + "§r box(es)"
                + (config.hasRestock() ? "" : " §7(uses chest area)")
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
        return "Autonomous Baritone lumber worker";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Drives an autonomous forest → mine logs → deposit → restock loop.",
                "",
                "Setup:",
                "- Set Essentials homes 'wood' (in the forest) and 'Home' (by your storage room).",
                "- Stand on one corner of your chest room and run #lumber corner1, then the",
                "  opposite corner and #lumber corner2.",
                "",
                "Usage:",
                "> lumber - show status",
                "> lumber start / stop",
                "> lumber corner1 / corner2 - capture the chest (deposit) area by standing on its corners",
                "> lumber area clear|status - manage the chest area",
                "> lumber restock corner1|corner2|clear|status - a separate supply area to restock from",
                "> lumber restock home <name|clear> - teleport to this home before restocking (default: base)",
                "> lumber keep add <item|category> <count> - keep/restock e.g. 'keep add axe 1', 'keep add food 64'",
                "> lumber keep remove <item|category> / keep clear / keep list",
                "> lumber wood include|exclude <flavour> - pick wood types (default: all)",
                "  flavours: " + String.join(" ", Woods.names()),
                "> lumber replant on|off - replant saplings on cleared ground (default off)",
                "> lumber sapling <n> - how many saplings to keep when replanting (default 16)",
                "> lumber freeslots <n> - return to base at this many free slots (default 1)",
                "> lumber work <name> / lumber home <name> - home names",
                "> lumber ender on|off - also service ender chests in the area (default off)",
                "> lumber sorter on|off - deposit each item into the chest tagged for it (signs/sortscheme; default off)"
        );
    }
}
