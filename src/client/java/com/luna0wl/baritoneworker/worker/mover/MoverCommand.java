package com.luna0wl.baritoneworker.worker.mover;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.selection.ISelection;
import baritone.api.utils.BetterBlockPos;
import com.luna0wl.baritoneworker.worker.common.ContainerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MoverCommand extends Command {

    private static final List<String> SUBS = List.of(
            "start", "stop", "status", "source", "dest", "mode", "save");

    private final MoverWorker worker;
    private final MoverConfig config;

    public MoverCommand(IBaritone baritone, MoverWorker worker, MoverConfig config) {
        super(baritone, "mover");
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
                case "source" -> doEndpoint(args, true);
                case "dest" -> doEndpoint(args, false);
                case "mode" -> doMode(args);
                default -> logDirect("Unknown subcommand '" + sub + "'. Try: " + String.join(", ", SUBS));
            }
        } catch (Exception e) {
            logDirect("§cError: " + e.getMessage());
        }
    }

    private void doEndpoint(IArgConsumer args, boolean source) {
        String which = source ? "source" : "dest";
        if (args.hasAny()) {
            String op = args.getString().toLowerCase(Locale.ROOT);
            if (op.equals("home")) {
                String name = args.hasAny() ? args.getString() : null;
                if (name == null) {
                    logDirect((source ? config.sourceHome : config.destHome) + " is the current " + which + " home.");
                    return;
                }
                if (source) config.sourceHome = name; else config.destHome = name;
                config.save();
                logDirect(which + "Home = §e" + name);
                return;
            }
            if (op.equals("clear")) {
                if (source) config.setSource(List.of()); else config.setDest(List.of());
                config.save();
                logDirect(which + " area cleared.");
                return;
            }
            logDirect("Usage: §e#mover " + which + "§r [home <name>|clear] — no arg captures the selection.");
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
        if (source) config.setSource(boxes); else config.setDest(boxes);
        config.save();
        int chests = ContainerService.scanChests(ctx.world(), boxes, ctx.playerFeet()).size();
        logDirect("Captured §e" + boxes.size() + "§r box(es) as the §e" + which + "§r area — §e" + chests + "§r chest(s).");
    }

    private void doMode(IArgConsumer args) {
        if (!args.hasAny()) {
            logDirect("mode = §e" + config.mode + "§r (copy = chest N→N; sort = re-sort at dest by scheme)");
            return;
        }
        String m = args.getString().toLowerCase(Locale.ROOT);
        switch (m) {
            case "copy" -> { config.mode = MoverConfig.Mode.COPY; config.save(); logDirect("mode = §eCOPY§r (positional, chest N → chest N)"); }
            case "sort" -> { config.mode = MoverConfig.Mode.SORT; config.save(); logDirect("mode = §eSORT§r (re-sort at destination by the sort scheme)"); }
            default -> logDirect("Usage: §e#mover mode§r copy|sort");
        }
    }

    private void printStatus() {
        logDirect("§bBaritoneMover§r — state: §e" + worker.getState());
        logDirect(" " + config.sourceHome + " → " + config.destHome + "§r  mode=§e" + config.mode);
        logDirect(" source=§e" + config.sourceBoxes.size() + "§r box(es)" + (config.hasSource() ? "" : " §c(not set)")
                + "§r  dest=§e" + config.destBoxes.size() + "§r box(es)" + (config.hasDest() ? "" : " §c(not set)"));
        logDirect(" §7Run with an empty inventory — the mover carries items between the rooms.");
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
        return "Move chests from one area to another";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of(
                "Moves the contents of every chest in a source area to a destination area,",
                "shuttling between two /home points.",
                "",
                "Setup:",
                "- Set Essentials homes by each room (defaults 'source' and 'Home').",
                "- Select the source room with #sel 1 / #sel 2, run #mover source.",
                "- Select the destination room, run #mover dest.",
                "- Run with an EMPTY inventory.",
                "",
                "Usage:",
                "> mover - show status",
                "> mover start / stop",
                "> mover source [home <name>|clear] - capture selection as source (or set its home)",
                "> mover dest [home <name>|clear] - capture selection as destination (or set its home)",
                "> mover mode copy|sort - copy = chest N→N; sort = re-sort at dest by the scheme",
                "",
                "SORT mode reuses the #sorter scheme (signs + sortscheme.json) at the destination."
        );
    }
}
