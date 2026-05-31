package com.luna0wl.baritoneworker.worker.builder;

/**
 * The phases of one build cycle. Baritone builds the open Litematica schematic;
 * when it runs out of materials the worker resets the build home to the current
 * spot, fetches more blocks from a chest area, returns, and resumes — the same
 * loop the miner uses, but triggered by low materials instead of a full
 * inventory.
 *
 * <pre>
 *   IDLE
 *     └─(start)─► GO_TO_WORK ─► BUILD ─(out of materials)─► RESET_HOME
 *                    ▲                                          │
 *                    │                                         ▼
 *                  (loop) ◄──────── SERVICE_CHESTS ◄──────── GO_TO_HOME
 * </pre>
 */
public enum BuilderState {
    /** Not running. */
    IDLE,
    /** Sent {@code /home <build>}; waiting for the teleport to settle. */
    GO_TO_WORK,
    /** At the build site; building the open Litematica schematic and watching materials. */
    BUILD,
    /** Out of materials: {@code /delhome}, {@code /sethome}, {@code /home <base>} in sequence. */
    RESET_HOME,
    /** Sent {@code /home <base>}; waiting for the teleport to settle. */
    GO_TO_HOME,
    /** Visiting every chest in the supply area: withdraw food + building blocks. */
    SERVICE_CHESTS
}
