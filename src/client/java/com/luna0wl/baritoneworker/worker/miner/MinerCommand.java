package com.luna0wl.baritoneworker.worker.miner;

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
 * The {@code #miner} Baritone command — start/stop the worker and tune its
 * settings. Registered into Baritone's own command registry so it lives under
 * the familiar {@code #} prefix.
 */
public final class MinerCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "area", "pickaxe", "pickaxes", "food",
            "freeslots", "mine", "home", "ore", "save");

    private final MinerWorker worker;
    private final MinerConfig config;

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
                case "pickaxes", "pickaxe" -> doSupply(args, true);
                case "food" -> doSupply(args, false);
                case "freeslots" -> { config.stopAtFreeSlots = nextInt(args, config.stopAtFreeSlots); config.save(); logDirect("stopAtFreeSlots = " + config.stopAtFreeSlots); }
                case "mine" -> { config.mineHome = args.getString(); config.save(); logDirect("mineHome = " + config.mineHome); }
                case "home" -> { config.baseHome = args.getString(); config.save(); logDirect("baseHome = " + config.baseHome); }
                case "ore" -> doOre(args);
                default -> logDirect("Unknown subcommand '" + sub + "'. Try: " + String.join(", ", SUBS));
            }
        } catch (Exception e) {
            logDirect("§cError: " + e.getMessage());
        }
    }

    /**
     * {@code #miner pickaxe|food <n|item>} — a number sets how many to keep
     * stocked, anything else is treated as the item id to stock (e.g.
     * {@code netherite_pickaxe}, {@code cooked_beef}).
     */
    private void doSupply(IArgConsumer args, boolean pickaxe) {
        String label = pickaxe ? "pickaxe" : "food";
        if (!args.hasAny()) {
            logDirect(label + ": item=§e" + ItemNames.idOf(pickaxe ? config.pickaxeItem : config.foodItem)
                    + "§r keep=§e" + (pickaxe ? config.targetPickaxes : config.targetFood));
            return;
        }
        String s = args.getString().trim();
        try {
            int n = Math.max(0, Integer.parseInt(s));
            if (pickaxe) config.targetPickaxes = n; else config.targetFood = n;
            config.save();
            logDirect("keep " + label + " count = §e" + n);
        } catch (NumberFormatException e) {
            Item it = ItemNames.byId(s);
            if (it == null) {
                logDirect("§cUnknown item '" + s + "'. Give a registry id (e.g. §e"
                        + (pickaxe ? "netherite_pickaxe" : "cooked_beef") + "§c) or a number.");
                return;
            }
            if (pickaxe) config.setPickaxeItem(it); else config.setFoodItem(it);
            config.save();
            logDirect(label + " item = §e" + ItemNames.idOf(it));
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
        logDirect(" mineHome=§e" + config.mineHome + "§r baseHome=§e" + config.baseHome);
        logDirect(" pickaxe=§e" + ItemNames.idOf(config.pickaxeItem) + "§r×" + config.targetPickaxes
                + "§r  food=§e" + ItemNames.idOf(config.foodItem) + "§r×" + config.targetFood
                + "§r  stopAtFreeSlots=§e" + config.stopAtFreeSlots);
        logDirect(" chestArea=§e" + config.chestBoxes.size() + "§r box(es)"
                + (config.hasArea() ? "" : " §c(not set — run #miner area)"));
        logDirect(" exposedOreMining=" + (config.mineExposedOres ? "§aON" : "§cOFF") + "§r (#miner ore)");
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
                "- Select your chest room with #sel 1 / #sel 2, then run #miner area.",
                "",
                "Usage:",
                "> miner - show status",
                "> miner start / stop",
                "> miner area [clear] - capture the current selection as the chest area",
                "> miner pickaxe <n> - how many pickaxes to keep stocked (default 2)",
                "> miner pickaxe <item> - which pickaxe (e.g. netherite_pickaxe)",
                "> miner food <n> - how much food to keep stocked (default 64)",
                "> miner food <item> - which food (e.g. cooked_beef)",
                "> miner freeslots <n> - return to base at this many free slots (default 1)",
                "> miner mine <name> / miner home <name> - home names",
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
