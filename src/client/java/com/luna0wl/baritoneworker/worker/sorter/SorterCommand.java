package com.luna0wl.baritoneworker.worker.sorter;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
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
            "start", "stop", "status", "area", "home", "assign", "reload", "categories", "save");

    private final SorterWorker worker;
    private final SorterConfig config;
    private final SortScheme scheme;

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
    }

    private void printStatus() {
        logDirect("§6BaritoneSorter§r — state: §e" + worker.getState());
        logDirect(" home=§e" + config.home + "§r  chestArea=§e" + config.chestBoxes.size() + "§r box(es)"
                + (config.hasArea() ? "" : " §c(not set — run #sorter area)"));
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
                "- Select the room with #sel 1 / #sel 2, then run #sorter area.",
                "- Tag chests: place a sign (one tag per line), or look at a chest and run",
                "  #sorter assign <tag...>, or edit config/baritoneworker/sortscheme.json.",
                "",
                "Usage:",
                "> sorter - show status",
                "> sorter start / stop",
                "> sorter area [clear] - capture the current selection as the chest area",
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
