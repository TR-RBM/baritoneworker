package com.luna0wl.baritoneworker.worker.common;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks a single {@code /home} teleport. Essentials-style {@code /home} has a
 * warm-up delay (often several seconds), so a fixed timer is wrong: instead we
 * record the start position, wait until the player actually <i>jumps</i> by a
 * threshold, then let the destination chunk load and the player settle on the
 * ground before declaring arrival. If no jump is ever seen (we were already at
 * the destination) we proceed after a timeout.
 *
 * <p>One instance per worker; call {@link #begin} when the command is sent and
 * {@link #arrived} once per tick until it returns true.
 */
public final class Teleporter {

    private Vec3 preTeleportPos;
    private boolean jumped;
    private int jumpTick;
    private int ticks;

    /** Record the current position as the pre-teleport reference. */
    public void begin(Minecraft mc) {
        preTeleportPos = mc.player.position();
        jumped = false;
        jumpTick = 0;
        ticks = 0;
    }

    /**
     * Call once per tick while waiting for a teleport. Returns true once the
     * player has jumped and the destination has loaded/settled.
     */
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
