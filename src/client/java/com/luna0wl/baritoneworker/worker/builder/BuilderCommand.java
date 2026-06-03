package com.luna0wl.baritoneworker.worker.builder;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.utils.BetterBlockPos;
import com.luna0wl.baritoneworker.worker.common.AreaSelection;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.ItemCategories;
import com.luna0wl.baritoneworker.worker.common.WorkerEquip;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class BuilderCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "area", "corner1", "corner2", "keep", "work", "home",
            "litematic", "file", "origin", "stopat", "sethome", "builds", "ender", "save");

    private final BuilderWorker worker;
    private final BuilderConfig config;
    private final AreaSelection areaSel = new AreaSelection();

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
            String raw = args.getString();
            String sub = raw.toLowerCase(Locale.ROOT);
            switch (sub) {
                case "start" -> worker.start(ctx.minecraft());
                case "stop" -> worker.stop(ctx.minecraft());
                case "status" -> printStatus();
                case "save" -> { config.save(); logDirect("Settings saved."); }
                case "area" -> doArea(args);
                case "corner1" -> captureCorner(false);
                case "corner2" -> captureCorner(true);
                case "keep" -> doKeep(args);
                case "work" -> { config.workHome = args.getString(); config.save(); logDirect("buildHome = " + config.workHome); }
                case "home" -> { config.baseHome = args.getString(); config.save(); logDirect("baseHome = " + config.baseHome); }
                case "litematic" -> { config.litematicIndex = Math.max(0, nextInt(args, config.litematicIndex)); config.save(); logDirect("litematic placement index = §e" + config.litematicIndex); }
                case "file" -> doFile(args);
                case "origin" -> doOrigin(args);
                case "stopat" -> doStopAt(args);
                case "sethome" -> doSetHome(args);
                case "builds" -> doBuilds(args);
                case "ender" -> doEnder(args);
                default -> {

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

    private void doKeep(IArgConsumer args) {
        if (!args.hasAny()) { printKeep(); return; }
        String op = args.getString().toLowerCase(Locale.ROOT);
        switch (op) {
            case "list" -> printKeep();
            case "clear" -> { config.equip.clear(); config.save(); logDirect("Keep list cleared."); }
            case "add", "set" -> {
                if (!args.hasAny()) { logDirect("Usage: §e#builder keep add <item|category> <count>"); return; }
                String token = args.getString().trim();
                int count = nextInt(args, 1);
                if (!config.equip.add(token, count)) {
                    logDirect("§cUnknown item/category '" + token + "'. Use a registry id (e.g. §ecooked_beef§c) "
                            + "or a category (§e" + String.join(", ", ItemCategories.names()) + "§c).");
                    return;
                }
                config.save();
                logDirect("keep §e" + token + "§r ×" + count);
            }
            case "remove" -> {
                if (!args.hasAny()) { logDirect("Usage: §e#builder keep remove <item|category>"); return; }
                String token = args.getString().trim();
                boolean removed = config.equip.remove(token);
                config.save();
                logDirect(removed ? "Removed §e" + token + "§r from the keep list." : "§cNot in keep list: " + token);
            }
            default -> logDirect("Usage: §e#builder keep add|remove|set|clear|list <item|category> <count>");
        }
    }

    private void printKeep() {
        logDirect("§9Keep list§r (withdrawn from the supply area alongside build blocks):");
        if (config.equip.isEmpty()) {
            logDirect("  §7(empty)");
            return;
        }
        for (WorkerEquip.Entry e : config.equip.entries()) {
            logDirect("  §e" + e.token() + "§r ×" + e.count() + (e.isCategory() ? " §7(category)" : ""));
        }
    }

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

    private void doEnder(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("ender-chests = " + (config.includeEnderChests ? "§aon" : "§coff")
                    + "§r — " + (config.includeEnderChests
                        ? "ender chests in the supply area are restocked from too."
                        : "ender chests in the supply area are ignored."));
            logDirect("Usage: §e#builder ender on|off");
            return;
        }
        String a = args.getString().toLowerCase(Locale.ROOT);
        boolean on = a.equals("on") || a.equals("true") || a.equals("yes") || a.equals("1") || a.equals("include");
        boolean off = a.equals("off") || a.equals("false") || a.equals("no") || a.equals("0") || a.equals("ignore");
        if (!on && !off) {
            logDirect("Usage: §e#builder ender on|off");
            return;
        }
        config.includeEnderChests = on;
        config.save();
        logDirect("ender-chests = " + (on ? "§aon§r — will also restock from ender chests."
                : "§coff§r — ender chests are ignored."));
    }

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

    private void captureCorner(boolean second) {
        BlockPos feet = ctx.playerFeet();
        if (second) areaSel.setCorner2(feet);
        else areaSel.setCorner1(feet);
        logDirect("§aSupply corner " + (second ? "2" : "1") + " = §r"
                + feet.getX() + "," + feet.getY() + "," + feet.getZ());
        if (!areaSel.ready()) {
            logDirect("§7Now stand on the opposite corner and run §e#builder corner" + (second ? "1" : "2") + "§7.");
            return;
        }
        List<int[]> boxes = areaSel.boxes();
        config.setArea(boxes);
        config.save();
        int chests = ContainerService.scanChests(ctx.world(), boxes, ctx.playerFeet(), config.includeEnderChests).size();
        logDirect("Captured the supply area holding §e" + chests + "§r supply chest(s).");
        if (chests == 0) {
            logDirect("§eNo chests detected yet — make sure the area is loaded and contains chests.");
        }
    }

    private void doArea(IArgConsumer args) {
        String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        switch (op) {
            case "clear" -> { config.clearArea(); areaSel.clear(); config.save(); logDirect("Supply area cleared."); }
            case "corner1" -> captureCorner(false);
            case "corner2" -> captureCorner(true);
            case "status" -> logDirect("Supply area: §e" + config.chestBoxes.size() + "§r box(es) "
                    + (config.hasArea() ? "" : "§c(not set)") + "  pending: " + areaSel.status());
            default -> logDirect("Usage: §e#builder area corner1|corner2|clear|status§r (or §e#builder corner1/corner2§r).");
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

    private static String posStr(int[] p) {
        return p == null ? "?" : p[0] + " " + p[1] + " " + p[2];
    }

    private void printStatus() {
        logDirect("§dBaritoneBuilder§r — state: §e" + worker.getState());
        logDirect(" buildHome=§e" + config.workHome + "§r baseHome=§e" + config.baseHome);
        logDirect(" source=" + (config.hasSchematicFile()
                ? "§efile " + config.schematicFile + "§r origin=" + (config.buildOriginPos != null ? "§e" + posStr(config.buildOriginPos) : "§eauto")
                : "§eopen Litematica #" + config.litematicIndex));
        logDirect(" keep=§e" + (config.equip.isEmpty() ? "(empty)" : config.equip.serialize()));
        logDirect(" supplyArea=§e" + config.chestBoxes.size() + "§r box(es)"
                + (config.hasArea() ? "" : " §c(not set — run #builder corner1 / corner2)")
                + "§r enderChests=" + (config.includeEnderChests ? "§aon" : "§coff"));
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
                "- Stand on one corner of the supply room and run #builder corner1, then the",
                "  opposite corner and #builder corner2.",
                "",
                "Usage:",
                "> builder - show status",
                "> builder start / stop",
                "> builder corner1 / corner2 - capture the supply area by standing on its corners",
                "> builder area clear|status - manage the supply area",
                "> builder keep add <item|category> <count> - extra items to withdraw (e.g. 'keep add food 64')",
                "> builder keep remove <item|category> / keep clear / keep list",
                "> builder work <name> / builder home <name> - build-site and base home names",
                "> builder litematic <index> - which open Litematica placement to build (default 0)",
                "> builder <file.litematic> - shortcut: build a schematic file and start (like #build)",
                "> builder file <name|clear> - build a schematic file from schematics/ (clear = open placement)",
                "> builder origin here | <x> <y> <z> | clear - fixed corner for the file build (clear = auto)",
                "> builder stopat here | <x> <y> <z> | radius <n> | clear - stop when this spot is reached",
                "> builder sethome on|off - move the 'build' home to where it stops each trip (default off)",
                "> builder builds <n|infinite> - builds' worth of materials to carry per supply trip (default 1)",
                "> builder ender on|off - also restock from ender chests in the area (default off)",
                "",
                "Two build sources: the schematic open in Litematica (default), or a schematic FILE",
                "from your schematics/ folder (set with 'file', just like Baritone's own #build —",
                "Litematica not required). The build resumes where it left off; stopat is handy for",
                "ending an otherwise-endless buildRepeat."
        );
    }
}
