package com.luna0wl.baritoneworker.worker.common;

import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class MenuActions {

    private MenuActions() {}

    public static void openChest(Minecraft mc, IBaritone baritone, BlockPos chest) {
        LocalPlayer p = mc.player;
        aimAt(mc, baritone, chest);
        Direction face = horizontalFaceToward(p, chest);
        Vec3 hitVec = new Vec3(
                chest.getX() + 0.5 + face.getStepX() * 0.5,
                chest.getY() + 0.5,
                chest.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, chest, false);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
    }

    public static void aimAt(Minecraft mc, IBaritone baritone, BlockPos pos) {
        LocalPlayer p = mc.player;
        IPlayerContext ctx = baritone.getPlayerContext();
        Optional<Rotation> reach = RotationUtils.reachable(ctx, pos);
        Rotation r = reach.orElseGet(() -> RotationUtils.calcRotationFromVec3d(
                p.getEyePosition(), Vec3.atCenterOf(pos), new Rotation(p.getYRot(), p.getXRot())));
        p.setYRot(r.getYaw());
        p.setXRot(r.getPitch());
        p.yRotO = p.getYRot();
        p.xRotO = p.getXRot();
    }

    public static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
    }

    private static Direction horizontalFaceToward(LocalPlayer p, BlockPos chest) {
        double dx = p.getX() - (chest.getX() + 0.5);
        double dz = p.getZ() - (chest.getZ() + 0.5);
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
