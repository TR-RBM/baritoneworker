package com.luna0wl.baritoneworker.worker.sorter;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import com.luna0wl.baritoneworker.worker.common.AreaSelection;
import com.luna0wl.baritoneworker.worker.common.ContainerService;
import com.luna0wl.baritoneworker.worker.common.ItemCategories;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class SorterCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "area", "corner1", "corner2", "home", "assign", "reload", "categories", "save");

    private final SorterWorker worker;
    private final SorterConfig config;
    private final SortScheme scheme;
    private final AreaSelection areaSel = new AreaSelection();

    public SorterCommand(IBaritone baritone, SorterWorker worker, SorterConfig config, SortScheme scheme) {
        super(baritone, "sorter");
        this.worker = worker;
        this.config = config;
        this.scheme = scheme;
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
                case "corner1" -> captureCorner(false);
                case "corner2" -> captureCorner(true);
                case "home" -> { config.home = args.getString(); config.save(); logDirect("home = " + config.home); }
                case "assign" -> doAssign(args);
                case "reload" -> { scheme.load(); logDirect("Sort scheme reloaded."); }
                case "categories" -> logDirect("Built-in categories: §e" + String.join(", ", ItemCategories.names()));
                default -> logDirect("Unknown subcommand '" + sub + "'. Try: " + String.join(", ", SUBS));
            }
        } catch (Exception e) {
            logDirect("§cError: " + e.getMessage());
        }
    }

    private void doAssign(IArgConsumer args) {
        HitResult hr = ctx.minecraft().hitResult;
        if (!(hr instanceof BlockHitResult bhr)) {
            logDirect("§cLook directly at a chest/barrel first.");
            return;
        }
        BlockPos pos = bhr.getBlockPos();
        List<String> tags = new ArrayList<>();
        while (args.hasAny()) {
            String t = args.getString().trim().toLowerCase(Locale.ROOT);
            if (!t.isBlank()) tags.add(t);
        }
        if (tags.isEmpty()) {
            logDirect("§cGive at least one tag, e.g. §e#sorter assign ores§c or §e#sorter assign logs planks§c.");
            return;
        }
        scheme.assign(pos, tags);
        logDirect("Pinned §e" + String.join(", ", tags) + "§r to the chest at §e" + pos.toShortString() + "§r.");
    }

    private void captureCorner(boolean second) {
        BlockPos feet = ctx.playerFeet();
        if (second) areaSel.setCorner2(feet);
        else areaSel.setCorner1(feet);
        logDirect("§aChest corner " + (second ? "2" : "1") + " = §r"
                + feet.getX() + "," + feet.getY() + "," + feet.getZ());
        if (!areaSel.ready()) {
            logDirect("§7Now stand on the opposite corner and run §e#sorter corner" + (second ? "1" : "2") + "§7.");
            return;
        }
        List<int[]> boxes = areaSel.boxes();
        config.setArea(boxes);
        config.save();
        int chests = ContainerService.scanChests(ctx.world(), boxes, ctx.playerFeet()).size();
        logDirect("Captured the chest area holding §e" + chests + "§r chest(s)/barrel(s).");
        if (chests == 0) {
            logDirect("§eNo chests detected yet — make sure the area is loaded and contains chests.");
        }
    }

    private void doArea(IArgConsumer args) {
        String op = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        switch (op) {
            case "clear" -> { config.clearArea(); areaSel.clear(); config.save(); logDirect("Chest area cleared."); }
            case "corner1" -> captureCorner(false);
            case "corner2" -> captureCorner(true);
            case "status" -> logDirect("Chest area: §e" + config.chestBoxes.size() + "§r box(es) "
                    + (config.hasArea() ? "" : "§c(not set)") + "  pending: " + areaSel.status());
            default -> logDirect("Usage: §e#sorter area corner1|corner2|clear|status§r (or §e#sorter corner1/corner2§r).");
        }
    }

    private void printStatus() {
        logDirect("§6BaritoneSorter§r — state: §e" + worker.getState());
        logDirect(" home=§e" + config.home + "§r  chestArea=§e" + config.chestBoxes.size() + "§r box(es)"
                + (config.hasArea() ? "" : " §c(not set — run #sorter corner1 / corner2)"));
        logDirect(" Tag chests with signs (a line per tag) or pin them in sortscheme.json / #sorter assign.");
        logDirect(" Tags = a category (" + String.join(", ", ItemCategories.names()) + "), a scheme group, or an item id.");
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
        return "Sort chests in an area by tag scheme";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Organizes the chests in a selected area so each item lands in the chest",
                "tagged for it. Tags come from signs on/by a chest, a sortscheme.json, or",
                "in-game #sorter assign.",
                "",
                "Setup:",
                "- Set an Essentials home by your chest room (default 'Home').",
                "- Stand on one corner of the room and run #sorter corner1, then the opposite",
                "  corner and #sorter corner2.",
                "- Tag chests: place a sign (one tag per line), or look at a chest and run",
                "  #sorter assign <tag...>, or edit config/baritoneworker/sortscheme.json.",
                "",
                "Usage:",
                "> sorter - show status",
                "> sorter start / stop",
                "> sorter corner1 / corner2 - capture the chest area by standing on its corners",
                "> sorter area clear|status - manage the chest area",
                "> sorter home <name> - the chest-room home (default Home)",
                "> sorter assign <tag...> - pin tags onto the chest you're looking at",
                "> sorter reload - reload sortscheme.json from disk",
                "> sorter categories - list the built-in category tags",
                "",
                "A tag is a built-in category (ores, logs, food...), a custom group from the",
                "scheme json, or a literal item id (e.g. diamond / minecraft:diamond)."
        );
    }
}
