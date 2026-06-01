package com.luna0wl.baritoneworker.worker.builder;

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
 * The {@code #builder} Baritone command — runs the open Litematica schematic
 * unattended, fetching more blocks from a supply area when it runs dry, and
 * stopping at an optional coordinate target.
 */
public final class BuilderCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "area", "food", "work", "home",
            "litematic", "file", "origin", "stopat", "sethome", "builds", "save");

    private final BuilderWorker worker;
    private final BuilderConfig config;

    public BuilderCommand(IBaritone baritone, BuilderWorker worker, BuilderConfig config) {
        super(baritone, "builder");
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
            String raw = args.getString();              // original case — needed for filenames
            String sub = raw.toLowerCase(Locale.ROOT);   // case-insensitive subcommand matching
            switch (sub) {
                case "start" -> worker.start(ctx.minecraft());
                case "stop" -> worker.stop(ctx.minecraft());
                case "status" -> printStatus();
                case "save" -> { config.save(); logDirect("Settings saved."); }
                case "area" -> doArea(args);
                case "food" -> doFood(args);
                case "work" -> { config.workHome = args.getString(); config.save(); logDirect("buildHome = " + config.workHome); }
                case "home" -> { config.baseHome = args.getString(); config.save(); logDirect("baseHome = " + config.baseHome); }
                case "litematic" -> { config.litematicIndex = Math.max(0, nextInt(args, config.litematicIndex)); config.save(); logDirect("litematic placement index = §e" + config.litematicIndex); }
                case "file" -> doFile(args);
                case "origin" -> doOrigin(args);
                case "stopat" -> doStopAt(args);
                case "sethome" -> doSetHome(args);
                case "builds" -> doBuilds(args);
                default -> {
                    // Bare filename like `#builder ZMinus.litematic` → set the file and start, à la #build.
                    // Use the original-case `raw`; filenames are case-sensitive on Linux/macOS.
                    if (raw.contains(".")) {
                        config.schematicFile = raw;
                        config.save();
                        logDirect("Schematic file = §e" + raw + "§r — starting.");
                        worker.start(ctx.minecraft());
                    } else {
                        logDirect("Unknown subcommand '" + sub + "'. Try: " + String.join(", ", SUBS));
                    }
                }
            }
        } catch (Exception e) {
            logDirect("§cError: " + e.getMessage());
        }
    }

    private void doFood(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("food: item=§e" + ItemNames.idOf(config.foodItem) + "§r keep=§e" + config.targetFood);
            return;
        }
        String s = args.getString().trim();
        try {
            config.targetFood = Math.max(0, Integer.parseInt(s));
            config.save();
            logDirect("keep food count = §e" + config.targetFood);
        } catch (NumberFormatException e) {
            Item it = ItemNames.byId(s);
            if (it == null) {
                logDirect("§cUnknown item '" + s + "'. Give a registry id (e.g. §ecooked_beef§c) or a number.");
                return;
            }
            config.setFoodItem(it);
            config.save();
            logDirect("food item = §e" + ItemNames.idOf(it));
        }
    }

    /** {@code #builder stopat here | <x> <y> <z> | radius <n> | clear} */
    private void doStopAt(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect(config.hasStop()
                    ? "stopAt = §e" + posStr(config.stopPos) + "§r  radius=§e" + config.stopRadius
                    : "stopAt = §c(not set)§r — buildRepeat would run until you stop it.");
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        switch (a) {
            case "clear" -> { config.stopPos = null; config.save(); logDirect("Stop target cleared."); }
            case "radius" -> {
                config.stopRadius = Math.max(1, nextInt(args, (int) config.stopRadius));
                config.save();
                logDirect("stopRadius = §e" + config.stopRadius);
            }
            case "here" -> {
                BetterBlockPos p = ctx.playerFeet();
                config.stopPos = new int[]{p.x, p.y, p.z};
                config.save();
                logDirect("Will stop near §e" + posStr(config.stopPos) + "§r (your current spot).");
            }
            default -> {
                try {
                    int x = Integer.parseInt(a);
                    int y = Integer.parseInt(args.getString().trim());
                    int z = Integer.parseInt(args.getString().trim());
                    config.stopPos = new int[]{x, y, z};
                    config.save();
                    logDirect("Will stop near §e" + posStr(config.stopPos) + "§r.");
                } catch (Exception e) {
                    logDirect("Usage: §e#builder stopat§r here | <x> <y> <z> | radius <n> | clear");
                }
            }
        }
    }

    /** {@code #builder sethome on|off} — whether to delhome/sethome the work home when materials run out. */
    private void doSetHome(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("sethome-on-leaving = " + (config.resetWorkHome ? "§aon" : "§coff")
                    + "§r — " + (config.resetWorkHome
                        ? "moves the '" + config.workHome + "' home to where it stops each trip."
                        : "keeps your existing '" + config.workHome + "' home untouched."));
            logDirect("Usage: §e#builder sethome on|off");
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        boolean on = a.equals("on") || a.equals("true") || a.equals("yes") || a.equals("1");
        boolean off = a.equals("off") || a.equals("false") || a.equals("no") || a.equals("0");
        if (!on && !off) {
            logDirect("Usage: §e#builder sethome on|off");
            return;
        }
        config.resetWorkHome = on;
        config.save();
        logDirect("sethome-on-leaving = " + (on ? "§aon§r — will /delhome+/sethome '" + config.workHome
                + "' where it stops each trip." : "§coff§r — keeps your existing '" + config.workHome + "' home."));
    }

    /** {@code #builder builds <n|infinite>} — how many builds' worth of materials to carry per trip. */
    private void doBuilds(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("builds-per-trip = §e" + buildsStr() + "§r (full bills of materials carried per supply trip).");
            logDirect("Usage: §e#builder builds <n|infinite>");
            return;
        }
        String a = args.getString().trim().toLowerCase(Locale.ROOT);
        if (a.equals("infinite") || a.equals("inf") || a.equals("all") || a.equals("max")) {
            config.materialBuilds = 0;
        } else {
            try {
                config.materialBuilds = Math.max(0, Integer.parseInt(a));
            } catch (NumberFormatException e) {
                logDirect("Usage: §e#builder builds <n|infinite>§r (a count, or 'infinite' to fill the bag).");
                return;
            }
        }
        config.save();
        logDirect("builds-per-trip = §e" + buildsStr() + "§r.");
    }

    private String buildsStr() {
        return config.materialBuilds <= 0 ? "infinite (fill the bag)" : Integer.toString(config.materialBuilds);
    }

    /** {@code #builder file <name> | clear} — build a schematic file instead of the open Litematica placement. */
    private void doFile(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect(config.hasSchematicFile()
                    ? "file = §e" + config.schematicFile
                    : "file = §c(none)§r — building the open Litematica placement (#" + config.litematicIndex + ").");
            return;
        }
        String name = args.getString().trim();
        if (name.equalsIgnoreCase("clear")) {
            config.schematicFile = "";
            config.save();
            logDirect("Cleared schematic file — back to the open Litematica placement.");
            return;
        }
        config.schematicFile = name;
        config.save();
        logDirect("Schematic file = §e" + name + "§r (from §eschematics/§r). Run §e#builder start§r to build it.");
    }

    /** {@code #builder origin here | <x> <y> <z> | clear} — fixed corner for the file build. */
    private void doOrigin(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect(config.buildOriginPos != null
                    ? "origin = §e" + posStr(config.buildOriginPos)
                    : "origin = §c(auto)§r — anchored at the build site when it starts placing.");
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        switch (a) {
            case "clear" -> { config.buildOriginPos = null; config.save(); logDirect("Origin cleared (auto)."); }
            case "here" -> {
                BetterBlockPos p = ctx.playerFeet();
                config.buildOriginPos = new int[]{p.x, p.y, p.z};
                config.save();
                logDirect("Origin = §e" + posStr(config.buildOriginPos) + "§r (your current spot).");
            }
            default -> {
                try {
                    int x = Integer.parseInt(a);
                    int y = Integer.parseInt(args.getString().trim());
                    int z = Integer.parseInt(args.getString().trim());
                    config.buildOriginPos = new int[]{x, y, z};
                    config.save();
                    logDirect("Origin = §e" + posStr(config.buildOriginPos) + "§r.");
                } catch (Exception e) {
                    logDirect("Usage: §e#builder origin§r here | <x> <y> <z> | clear");
                }
            }
        }
    }

    private void doArea(IArgConsumer args) {
        if (args.hasAny() && args.getString().equalsIgnoreCase("clear")) {
            config.clearArea();
            config.save();
            logDirect("Supply area cleared.");
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
        logDirect("Captured §e" + boxes.size() + "§r box(es) holding §e" + chests + "§r supply chest(s).");
    }

    private int nextInt(IArgConsumer args, int fallback) {
        if (!args.hasAny()) return fallback;
        try {
            return Integer.parseInt(args.getString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String posStr(int[] p) {
        return p == null ? "?" : p[0] + " " + p[1] + " " + p[2];
    }

    private void printStatus() {
        logDirect("§dBaritoneBuilder§r — state: §e" + worker.getState());
        logDirect(" buildHome=§e" + config.workHome + "§r baseHome=§e" + config.baseHome);
        logDirect(" source=" + (config.hasSchematicFile()
                ? "§efile " + config.schematicFile + "§r origin=" + (config.buildOriginPos != null ? "§e" + posStr(config.buildOriginPos) : "§eauto")
                : "§eopen Litematica #" + config.litematicIndex));
        logDirect(" food=§e" + ItemNames.idOf(config.foodItem) + "§r×" + config.targetFood);
        logDirect(" supplyArea=§e" + config.chestBoxes.size() + "§r box(es)"
                + (config.hasArea() ? "" : " §c(not set — run #builder area)"));
        logDirect(" stopAt=" + (config.hasStop() ? "§e" + posStr(config.stopPos) + "§r r=" + config.stopRadius : "§c(off)"));
        logDirect(" sethome-on-leaving=" + (config.resetWorkHome ? "§aon" : "§coff")
                + "§r builds-per-trip=§e" + buildsStr());
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
        return "Build an open Litematica schematic, restocking blocks as needed";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Builds the schematic currently open in Litematica with Baritone, and when it",
                "runs out of blocks it fetches more from a supply area, then resumes — hands-free.",
                "",
                "Setup:",
                "- Open/place your schematic in Litematica.",
                "- Set Essentials homes 'build' (at the build site) and 'Home' (by your supply room).",
                "- Stock the supply room with the blocks the schematic needs (+ food).",
                "- Select the supply room with #sel 1 / #sel 2, then run #builder area.",
                "",
                "Usage:",
                "> builder - show status",
                "> builder start / stop",
                "> builder area [clear] - capture the current selection as the supply area",
                "> builder food <n|item> - how much / which food to keep (default 64)",
                "> builder work <name> / builder home <name> - build-site and base home names",
                "> builder litematic <index> - which open Litematica placement to build (default 0)",
                "> builder <file.litematic> - shortcut: build a schematic file and start (like #build)",
                "> builder file <name|clear> - build a schematic file from schematics/ (clear = open placement)",
                "> builder origin here | <x> <y> <z> | clear - fixed corner for the file build (clear = auto)",
                "> builder stopat here | <x> <y> <z> | radius <n> | clear - stop when this spot is reached",
                "> builder sethome on|off - move the 'build' home to where it stops each trip (default off)",
                "> builder builds <n|infinite> - builds' worth of materials to carry per supply trip (default 1)",
                "",
                "Two build sources: the schematic open in Litematica (default), or a schematic FILE",
                "from your schematics/ folder (set with 'file', just like Baritone's own #build —",
                "Litematica not required). The build resumes where it left off; stopat is handy for",
                "ending an otherwise-endless buildRepeat."
        );
    }
}
