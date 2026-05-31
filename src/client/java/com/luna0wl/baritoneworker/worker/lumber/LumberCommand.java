package com.luna0wl.baritoneworker.worker.lumber;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.ItemNames;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The {@code #lumber} Baritone command — start/stop the lumber worker and tune
 * its settings. Mirrors {@code #miner}, but for wood: an axe instead of a
 * pickaxe, wood flavours instead of ore groups, and a replant toggle.
 */
public final class LumberCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "area", "axe", "food", "wood",
            "replant", "sapling", "freeslots", "work", "home", "save");

    private final LumberWorker worker;
    private final LumberConfig config;

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
                case "axe" -> doSupply(args, Supply.AXE);
                case "food" -> doSupply(args, Supply.FOOD);
                case "sapling" -> doSupply(args, Supply.SAPLING);
                case "wood" -> doWood(args);
                case "replant" -> doReplant(args);
                case "freeslots" -> { config.stopAtFreeSlots = nextInt(args, config.stopAtFreeSlots); config.save(); logDirect("stopAtFreeSlots = " + config.stopAtFreeSlots); }
                case "work" -> { config.workHome = args.getString(); config.save(); logDirect("workHome = " + config.workHome); }
                case "home" -> { config.baseHome = args.getString(); config.save(); logDirect("baseHome = " + config.baseHome); }
                default -> logDirect("Unknown subcommand '" + sub + "'. Try: " + String.join(", ", SUBS));
            }
        } catch (Exception e) {
            logDirect("§cError: " + e.getMessage());
        }
    }

    private enum Supply { AXE, FOOD, SAPLING }

    /**
     * {@code #lumber axe|food|sapling <n|item>} — a number sets how many to keep
     * stocked; anything else is the item id to stock (axe/food only — saplings
     * track the selected wood flavours automatically).
     */
    private void doSupply(IArgConsumer args, Supply which) {
        String label = which.name().toLowerCase(Locale.ROOT);
        if (!args.hasAny()) {
            switch (which) {
                case AXE -> logDirect("axe: item=§e" + ItemNames.idOf(config.axeItem) + "§r keep=§e" + config.targetAxes);
                case FOOD -> logDirect("food: item=§e" + ItemNames.idOf(config.foodItem) + "§r keep=§e" + config.targetFood);
                case SAPLING -> logDirect("sapling: keep=§e" + config.targetSaplings + "§r (tracks selected wood flavours)");
            }
            return;
        }
        String s = args.getString().trim();
        try {
            int n = Math.max(0, Integer.parseInt(s));
            switch (which) {
                case AXE -> config.targetAxes = n;
                case FOOD -> config.targetFood = n;
                case SAPLING -> config.targetSaplings = n;
            }
            config.save();
            logDirect("keep " + label + " count = §e" + n);
        } catch (NumberFormatException e) {
            if (which == Supply.SAPLING) {
                logDirect("§cSaplings can't be pinned to one item — they follow the wood flavours. Give a number.");
                return;
            }
            Item it = ItemNames.byId(s);
            if (it == null) {
                logDirect("§cUnknown item '" + s + "'. Give a registry id (e.g. §e"
                        + (which == Supply.AXE ? "netherite_axe" : "cooked_beef") + "§c) or a number.");
                return;
            }
            if (which == Supply.AXE) config.setAxeItem(it); else config.setFoodItem(it);
            config.save();
            logDirect(label + " item = §e" + ItemNames.idOf(it));
        }
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
        // woodFlavours holds the *selected* set; empty = all flavours.
        if (g.equals("all")) {
            if (include) config.woodFlavours.clear();                 // empty = every flavour
            else config.woodFlavours.addAll(Woods.names());           // then exclude-all clears below
            if (!include) config.woodFlavours.clear();                // exclude all = harvest nothing -> treat as none selected
        } else if (Woods.isFlavour(g)) {
            // Materialise the explicit set the first time a single flavour is touched.
            if (config.woodFlavours.isEmpty() && !include) config.woodFlavours.addAll(Woods.names());
            if (include) config.woodFlavours.add(g);
            else config.woodFlavours.remove(g);
            // Selecting every flavour individually == "all": normalise back to empty.
            if (config.woodFlavours.containsAll(Woods.names())) config.woodFlavours.clear();
        } else {
            logDirect("§cUnknown flavour '" + g + "'. Known: §e" + String.join(", ", Woods.names()));
            return;
        }
        config.rebuildKeep();
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

    private void doArea(IArgConsumer args) {
        if (args.hasAny() && args.getString().equalsIgnoreCase("clear")) {
            config.clearArea();
            config.save();
            logDirect("Chest area cleared.");
            return;
        }
        ISelection[] sels = baritone.getSelectionManager().getSelections();
        if (sels == null || sels.length == 0) {
            logDirect("§cNo Baritone selection found. Make one with §e#sel 1§c / §e#sel 2§c first.");
            return;
        }
        List<int[]> boxes = new ArrayList<>();
        for (ISelection s : sels) {
            BetterBlockPos mn = s.min();
            BetterBlockPos mx = s.max();
            boxes.add(new int[]{mn.x, mn.y, mn.z, mx.x, mx.y, mx.z});
        }
        config.setArea(boxes);
        config.save();
        int chests = ContainerService.scanChests(ctx.world(), boxes, ctx.playerFeet()).size();
        logDirect("Captured §e" + boxes.size() + "§r selection box(es) holding §e" + chests + "§r chest(s)/barrel(s).");
        if (chests == 0) {
            logDirect("§eNo chests detected in the selection yet — make sure the area is loaded and actually contains chests.");
        }
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
        logDirect(" workHome=§e" + config.workHome + "§r baseHome=§e" + config.baseHome);
        logDirect(" axe=§e" + ItemNames.idOf(config.axeItem) + "§r×" + config.targetAxes
                + "§r  food=§e" + ItemNames.idOf(config.foodItem) + "§r×" + config.targetFood
                + "§r  stopAtFreeSlots=§e" + config.stopAtFreeSlots);
        logDirect(" woods=§e" + harvested());
        logDirect(" replant=" + (config.replant ? "§aON" : "§cOFF") + "§r×" + config.targetSaplings + "§r (#lumber replant)");
        logDirect(" chestArea=§e" + config.chestBoxes.size() + "§r box(es)"
                + (config.hasArea() ? "" : " §c(not set — run #lumber area)"));
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
                "- Select your chest room with #sel 1 / #sel 2, then run #lumber area.",
                "",
                "Usage:",
                "> lumber - show status",
                "> lumber start / stop",
                "> lumber area [clear] - capture the current selection as the chest area",
                "> lumber axe <n> / axe <item> - how many / which axe to keep (default 1, diamond_axe)",
                "> lumber food <n> / food <item> - how much / which food to keep (default 64)",
                "> lumber wood include|exclude <flavour> - pick wood types (default: all)",
                "  flavours: " + String.join(" ", Woods.names()),
                "> lumber replant on|off - replant saplings on cleared ground (default off)",
                "> lumber sapling <n> - how many saplings to keep when replanting (default 16)",
                "> lumber freeslots <n> - return to base at this many free slots (default 1)",
                "> lumber work <name> / lumber home <name> - home names"
        );
    }
}
