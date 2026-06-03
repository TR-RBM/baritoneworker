package com.luna0wl.baritoneworker.worker.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class ContainerService {

    private ContainerService() {}

    private static final Logger LOG = LoggerFactory.getLogger("baritoneworker/scan");

    private static final long MAX_SCAN_VOLUME = 8_000_000L;

    public static List<BlockPos> scanChests(Level level, List<int[]> boxes, BlockPos origin) {
        return scanChests(level, boxes, origin, false);
    }

    public static List<BlockPos> scanChests(Level level, List<int[]> boxes, BlockPos origin, boolean includeEnderChests) {
        List<BlockPos> out = new ArrayList<>();
        long scanned = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        if (++scanned > MAX_SCAN_VOLUME) {
                            LOG.warn("Chest scan hit the {}-block cap; selection is huge — found {} so far. "
                                    + "Tighten the selection around just the chest room.", MAX_SCAN_VOLUME, out.size());
                            return finish(out, origin);
                        }
                        cursor.set(x, y, z);
                        if (!level.isLoaded(cursor)) continue;
                        if (isStorage(level, cursor, includeEnderChests)) {
                            out.add(cursor.immutable());
                        }
                    }
                }
            }
        }
        return finish(out, origin);
    }

    public static String describeArea(Level level, List<int[]> boxes, boolean includeEnderChests) {
        boolean loaded = boxesLoaded(level, boxes);
        int chests = 0, rightHalves = 0, barrels = 0, enders = 0;
        long total = 0, loadedBlocks = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[3]; x++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        total++;
                        cursor.set(x, y, z);
                        if (!level.isLoaded(cursor)) continue;
                        loadedBlocks++;
                        BlockState state = level.getBlockState(cursor);
                        if (state.getBlock() instanceof BarrelBlock) {
                            barrels++;
                        } else if (state.getBlock() instanceof EnderChestBlock) {
                            enders++;
                        } else if (state.getBlock() instanceof ChestBlock) {
                            if (state.hasProperty(BlockStateProperties.CHEST_TYPE)
                                    && state.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.RIGHT) {
                                rightHalves++;
                            } else {
                                chests++;
                            }
                        }
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("loaded=").append(loaded ? "yes" : "§cNO§r");
        sb.append(", blocksLoaded=").append(loadedBlocks).append("/").append(total);
        sb.append(", chests=").append(chests);
        sb.append(", barrels=").append(barrels);
        sb.append(", enderChests=").append(enders).append(includeEnderChests ? "(serviced)" : "(ignored)");
        if (rightHalves > 0) {
            sb.append(" §e[").append(rightHalves)
                    .append(" double-chest right-half(s) skipped — your selection is one block off the left half]");
        }
        return sb.toString();
    }

    public static boolean boxesLoaded(Level level, List<int[]> boxes) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int[] b : boxes) {
            int minCx = b[0] >> 4, maxCx = b[3] >> 4;
            int minCz = b[2] >> 4, maxCz = b[5] >> 4;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    cursor.set((cx << 4), b[1], (cz << 4));
                    if (!level.isLoaded(cursor)) return false;
                }
            }
        }
        return true;
    }

    private static List<BlockPos> finish(List<BlockPos> out, BlockPos origin) {
        out.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        return out;
    }

    public static boolean isStorageAt(Level level, BlockPos pos, boolean includeEnderChests) {
        return isStorage(level, pos, includeEnderChests);
    }

    private static boolean isStorage(Level level, BlockPos pos, boolean includeEnderChests) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BarrelBlock) return true;
        if (includeEnderChests && state.getBlock() instanceof EnderChestBlock) return true;
        if (state.getBlock() instanceof ChestBlock) {
            return !(state.hasProperty(BlockStateProperties.CHEST_TYPE)
                    && state.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.RIGHT);
        }
        return false;
    }

    public static List<String> readChestTags(Level level, BlockPos chest) {
        List<String> tags = new ArrayList<>();
        BlockPos[] around = {
                chest.above(),
                chest.relative(Direction.NORTH),
                chest.relative(Direction.SOUTH),
                chest.relative(Direction.EAST),
                chest.relative(Direction.WEST),
        };
        for (BlockPos p : around) {
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof SignBlockEntity sign) {
                collectSignLines(sign.getFrontText(), tags);
                collectSignLines(sign.getBackText(), tags);
            }
        }
        return tags;
    }

    private static void collectSignLines(SignText text, List<String> out) {
        for (int i = 0; i < 4; i++) {
            Component line = text.getMessage(i, false);
            String s = line.getString().trim().toLowerCase(Locale.ROOT);
            if (!s.isBlank() && !out.contains(s)) out.add(s);
        }
    }

    public static int freeSlots(Inventory inv) {
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) free++;
        }
        return free;
    }

    public static int countItem(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    public static int countMatching(Inventory inv, Predicate<Item> match) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && match.test(s.getItem())) n += s.getCount();
        }
        return n;
    }

    public static int containerSlotCount(AbstractContainerMenu menu) {
        return menu.slots.size() - 36;
    }

    public static int nextDepositSlotMatching(AbstractContainerMenu menu, Predicate<Item> shouldDeposit) {
        int container = containerSlotCount(menu);
        for (int i = container; i < menu.slots.size(); i++) {
            ItemStack s = menu.slots.get(i).getItem();
            if (!s.isEmpty() && shouldDeposit.test(s.getItem())) {
                return i;
            }
        }
        return -1;
    }

    public static int nextWithdrawSlot(AbstractContainerMenu menu, Item want) {
        int container = containerSlotCount(menu);
        for (int i = 0; i < container; i++) {
            ItemStack s = menu.slots.get(i).getItem();
            if (s.is(want)) return i;
        }
        return -1;
    }

    public static int nextWithdrawSlotMatching(AbstractContainerMenu menu, Predicate<Item> want) {
        int container = containerSlotCount(menu);
        for (int i = 0; i < container; i++) {
            ItemStack s = menu.slots.get(i).getItem();
            if (!s.isEmpty() && want.test(s.getItem())) return i;
        }
        return -1;
    }

    public static int firstNonEmptyContainerSlot(AbstractContainerMenu menu) {
        int container = containerSlotCount(menu);
        for (int i = 0; i < container; i++) {
            if (!menu.slots.get(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    public static boolean hasFreePlayerSlot(AbstractContainerMenu menu) {
        for (int i = containerSlotCount(menu); i < menu.slots.size(); i++) {
            if (menu.slots.get(i).getItem().isEmpty()) return true;
        }
        return false;
    }

    public static boolean hasEmptyContainerSlot(AbstractContainerMenu menu) {
        int container = containerSlotCount(menu);
        for (int i = 0; i < container; i++) {
            if (menu.slots.get(i).getItem().isEmpty()) return true;
        }
        return false;
    }
}
