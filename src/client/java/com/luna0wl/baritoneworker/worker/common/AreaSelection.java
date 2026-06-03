package com.luna0wl.baritoneworker.worker.common;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class AreaSelection {

    private int[] corner1;
    private int[] corner2;

    public void setCorner1(BlockPos p) {
        corner1 = new int[]{p.getX(), p.getY(), p.getZ()};
    }

    public void setCorner2(BlockPos p) {
        corner2 = new int[]{p.getX(), p.getY(), p.getZ()};
    }

    public boolean hasCorner1() {
        return corner1 != null;
    }

    public boolean hasCorner2() {
        return corner2 != null;
    }

    public boolean ready() {
        return corner1 != null && corner2 != null;
    }

    public void clear() {
        corner1 = null;
        corner2 = null;
    }

    public int[] box() {
        if (!ready()) return null;
        return new int[]{
                Math.min(corner1[0], corner2[0]),
                Math.min(corner1[1], corner2[1]),
                Math.min(corner1[2], corner2[2]),
                Math.max(corner1[0], corner2[0]),
                Math.max(corner1[1], corner2[1]),
                Math.max(corner1[2], corner2[2])};
    }

    public List<int[]> boxes() {
        return ready() ? List.of(box()) : List.of();
    }

    public String status() {
        return "corner1=" + posStr(corner1) + " corner2=" + posStr(corner2)
                + (ready() ? " box=" + boxStr(box()) : "");
    }

    private static String posStr(int[] p) {
        return p == null ? "unset" : p[0] + "," + p[1] + "," + p[2];
    }

    private static String boxStr(int[] b) {
        return b[0] + "," + b[1] + "," + b[2] + " .. " + b[3] + "," + b[4] + "," + b[5];
    }
}
