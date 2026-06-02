package com.luna0wl.baritoneworker.worker.digger;

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

public final class DiggerCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "area", "region", "supply", "dump",
            "pickaxe", "shovel", "food", "buckets", "freeslots",
            "work", "fluid", "breakmove", "sethome", "ender", "save");

    private final DiggerWorker worker;
    private final DiggerConfig config;

    public DiggerCommand(IBaritone baritone, DiggerWorker worker, DiggerConfig config) {
        super(baritone, "digger");
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
                case "area", "region" -> doRegion(args);
                case "supply" -> doChestArea(args, true);
                case "dump" -> doChestArea(args, false);
                case "pickaxe", "pickaxes" -> doTool(args, Tool.PICKAXE);
                case "shovel", "shovels" -> doTool(args, Tool.SHOVEL);
                case "food" -> doTool(args, Tool.FOOD);
                case "buckets", "bucket" -> { config.targetBuckets = Math.max(0, nextInt(args, config.targetBuckets)); config.save(); logDirect("keep buckets = §e" + config.targetBuckets); }
                case "freeslots" -> { config.stopAtFreeSlots = nextInt(args, config.stopAtFreeSlots); config.save(); logDirect("stopAtFreeSlots = " + config.stopAtFreeSlots); }
                case "work" -> { config.workHome = args.getString(); config.save(); logDirect("workHome = " + config.workHome); }
                case "fluid" -> doToggle(args, "fluid handling (bucket lava / drain water)", v -> config.handleFluids = v, () -> config.handleFluids);
                case "breakmove" -> doToggle(args, "let Baritone break while repositioning", v -> config.breakWhileMoving = v, () -> config.breakWhileMoving);
                case "sethome" -> doToggle(args, "advance the work home each trip", v -> config.advanceWorkHome = v, () -> config.advanceWorkHome);
                case "ender" -> doToggle(args, "service ender chests", v -> config.includeEnderChests = v, () -> config.includeEnderChests);
                default -> logDirect("Unknown subcommand '" + sub + "'. Try: " + String.join(", ", SUBS));
            }
        } catch (Exception e) {
            logDirect("§cError: " + e.getMessage());
        }
    }

    private enum Tool { PICKAXE, SHOVEL, FOOD }

    private void doTool(IArgConsumer args, Tool tool) {
        String label = tool.name().toLowerCase(Locale.ROOT);
        if (!args.hasAny()) {
            logDirect(label + ": item=§e" + ItemNames.idOf(item(tool)) + "§r keep=§e" + target(tool));
            return;
        }
        String s = args.getString().trim();
        try {
            int n = Math.max(0, Integer.parseInt(s));
            setTarget(tool, n);
            config.save();
            logDirect("keep " + label + " count = §e" + n);
        } catch (NumberFormatException e) {
            Item it = ItemNames.byId(s);
            if (it == null) {
                logDirect("§cUnknown item '" + s + "'. Give a registry id or a number.");
                return;
            }
            setItem(tool, it);
            config.save();
            logDirect(label + " item = §e" + ItemNames.idOf(it));
        }
    }

    private Item item(Tool t) {
        return switch (t) {
            case PICKAXE -> config.pickaxeItem;
            case SHOVEL -> config.shovelItem;
            case FOOD -> config.foodItem;
        };
    }

    private int target(Tool t) {
        return switch (t) {
            case PICKAXE -> config.targetPickaxes;
            case SHOVEL -> config.targetShovels;
            case FOOD -> config.targetFood;
        };
    }

    private void setTarget(Tool t, int n) {
        switch (t) {
            case PICKAXE -> config.targetPickaxes = n;
            case SHOVEL -> config.targetShovels = n;
            case FOOD -> config.targetFood = n;
        }
    }

    private void setItem(Tool t, Item it) {
        switch (t) {
            case PICKAXE -> config.setPickaxeItem(it);
            case SHOVEL -> config.setShovelItem(it);
            case FOOD -> config.setFoodItem(it);
        }
    }

    private void doRegion(IArgConsumer args) {
        if (args.hasAny() && args.getString().equalsIgnoreCase("clear")) {
            config.digBoxes.clear();
            config.save();
            logDirect("Dig region cleared.");
            return;
        }
        List<int[]> boxes = selectionBoxes();
        if (boxes == null) return;
        config.setRegion(boxes);
        config.save();
        long cells = 0;
        for (int[] b : boxes) {
            cells += (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        }
        logDirect("Dig region = §e" + boxes.size() + "§r box(es), §e" + cells + "§r block-cells. "
                + "Set homes §e" + config.workHome + "§r (dig site) and §e" + config.supplyHome + "§r (chests).");
    }

    private void doChestArea(IArgConsumer args, boolean supply) {
        String which = supply ? "supply" : "dump";
        if (args.hasAny()) {
            String a = args.peekString().toLowerCase(Locale.ROOT);
            if (a.equals("clear")) {
                args.getString();
                if (supply) config.supplyBoxes.clear(); else config.dumpBoxes.clear();
                config.save();
                logDirect(which + " area cleared.");
                return;
            }
            if (a.equals("home")) {
                args.getString();
                String name = args.getString();
                if (supply) config.supplyHome = name; else config.dumpHome = name;
                config.save();
                logDirect(which + "Home = §e" + name);
                return;
            }
        }
        List<int[]> boxes = selectionBoxes();
        if (boxes == null) return;
        if (supply) config.setSupply(boxes); else config.setDump(boxes);
        config.save();
        int chests = ContainerService.scanChests(ctx.world(), boxes, ctx.playerFeet()).size();
        logDirect("Captured §e" + boxes.size() + "§r box(es) holding §e" + chests + "§r chest(s)/barrel(s) for "
                + which + (chests == 0 ? " §e(none detected yet — make sure the chests are loaded)." : "."));
    }

    private List<int[]> selectionBoxes() {
        ISelection[] sels = baritone.getSelectionManager().getSelections();
        if (sels == null || sels.length == 0) {
            logDirect("§cNo Baritone selection found. Make one with §e#sel 1§c / §e#sel 2§c first.");
            return null;
        }
        List<int[]> boxes = new ArrayList<>();
        for (ISelection s : sels) {
            BetterBlockPos mn = s.min();
            BetterBlockPos mx = s.max();
            boxes.add(new int[]{mn.x, mn.y, mn.z, mx.x, mx.y, mx.z});
        }
        return boxes;
    }

    private interface BoolGetter { boolean get(); }
    private interface BoolSetter { void set(boolean v); }

    private void doToggle(IArgConsumer args, String label, BoolSetter setter, BoolGetter getter) {
        if (!args.hasAny()) {
            logDirect(label + " = " + (getter.get() ? "§aon" : "§coff"));
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        boolean on = a.equals("on") || a.equals("true") || a.equals("yes") || a.equals("1");
        boolean off = a.equals("off") || a.equals("false") || a.equals("no") || a.equals("0");
        if (!on && !off) {
            logDirect("Usage: on|off");
            return;
        }
        setter.set(on);
        config.save();
        logDirect(label + " = " + (on ? "§aon" : "§coff"));
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
        logDirect("§6Digger§r — state: §e" + worker.getState());
        logDirect(" region=§e" + config.digBoxes.size() + "§r box(es)"
                + (config.hasRegion() ? "" : " §c(not set — run #digger area)"));
        logDirect(" workHome=§e" + config.workHome + "§r supplyHome=§e" + config.supplyHome
                + "§r dumpHome=§e" + (config.dumpHome.isBlank() ? "(supply)" : config.dumpHome));
        logDirect(" supply=§e" + config.supplyBoxes.size() + "§r box(es)"
                + (config.hasSupply() ? "" : " §c(not set — run #digger supply)")
                + "§r dump=§e" + config.dumpBoxes.size() + "§r box(es)"
                + (config.twoArea() ? "" : " §7(uses supply chests)"));
        logDirect(" pickaxe=§e" + ItemNames.idOf(config.pickaxeItem) + "§r×" + config.targetPickaxes
                + "§r shovel=§e" + ItemNames.idOf(config.shovelItem) + "§r×" + config.targetShovels);
        logDirect(" food=§e" + ItemNames.idOf(config.foodItem) + "§r×" + config.targetFood
                + "§r buckets=§e×" + config.targetBuckets + "§r freeslots=§e" + config.stopAtFreeSlots);
        logDirect(" fluids=" + (config.handleFluids ? "§aon" : "§coff")
                + "§r breakmove=" + (config.breakWhileMoving ? "§aon" : "§coff")
                + "§r sethome=" + (config.advanceWorkHome ? "§aon" : "§coff")
                + "§r ender=" + (config.includeEnderChests ? "§aon" : "§coff"));
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
        return "Autonomous area-excavation / terraforming worker";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Digs out a whole selected region top-down, hauls the spoil to chests and",
                "restocks tools — unattended. Breaks each block itself (holding until it's gone),",
                "so it doesn't suffer Baritone's jittery area-clear in this version.",
                "",
                "Setup:",
                "- Select the region to excavate with #sel 1 / #sel 2, then #digger area.",
                "- Set Essentials homes: 'dig' (a safe spot at the region) and 'Home' (by your chests).",
                "- Select your tool/food/bucket chests and run #digger supply.",
                "- Optional: select a separate (large) set of chests for spoil and run #digger dump",
                "  (with /sethome for it, then #digger dump home <name>). Omit it to reuse the supply chests.",
                "- Stock spare pickaxes, shovels, food and empty buckets in the supply chests.",
                "",
                "Usage:",
                "> digger - show status",
                "> digger start / stop",
                "> digger area [clear] - capture the selection as the region to dig",
                "> digger supply [clear | home <name>] - tool/food/bucket chests",
                "> digger dump [clear | home <name>] - spoil chests (optional; defaults to supply)",
                "> digger pickaxe/shovel/food <n|item> - how many / which to keep stocked",
                "> digger buckets <n> - empty buckets to keep for fluids (default 4)",
                "> digger freeslots <n> - haul spoil out at this many free slots (default 1)",
                "> digger fluid on|off - bucket lava (collect) and drain water (default on)",
                "> digger breakmove on|off - let Baritone break blocks to reposition (default on)",
                "> digger sethome on|off - move the 'dig' home to the work face each trip (default on)",
                "> digger ender on|off - also service ender chests (default off)",
                "> digger work <name> - the dig-site home name"
        );
    }
}
