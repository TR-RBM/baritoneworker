package com.luna0wl.baritoneworker.worker.common;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class Teleporter {

    private Vec3 preTeleportPos;
    private boolean jumped;
    private int jumpTick;
    private int ticks;

    public void begin(Minecraft mc) {
        preTeleportPos = mc.player.position();
        jumped = false;
        jumpTick = 0;
        ticks = 0;
    }

    public boolean arrived(Minecraft mc, int timeoutTicks, int settleTicks, double moveThreshold) {
        ticks++;
        if (!jumped) {
            boolean moved = preTeleportPos != null
                    && mc.player.position().distanceTo(preTeleportPos) > moveThreshold;
            if (moved || ticks >= timeoutTicks) {
                jumped = true;
                jumpTick = ticks;
            } else {
                return false;
            }
        }
        int sinceJump = ticks - jumpTick;
        if (sinceJump < settleTicks) return false;
        BlockPos feet = mc.player.blockPosition();
        if (!mc.level.isLoaded(feet)) return false;
        return mc.player.onGround() || sinceJump >= settleTicks * 4;
    }
}
