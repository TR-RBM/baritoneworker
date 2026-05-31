package com.luna0wl.baritoneworker.worker.lumber;

/**
 * The phases of one lumber cycle — the same shape as the miner's, but "tunnel"
 * is replaced by Baritone's {@code mine} on the selected log types, optionally
 * pausing to replant a sapling.
 *
 * <pre>
 *   IDLE
 *     └─(start)─► GO_TO_WORK ─► HARVEST ─(inventory full)─► RESET_HOME
 *                    ▲                                          │
 *                    │                                         ▼
 *                  (loop) ◄──────── SERVICE_CHESTS ◄──────── GO_TO_HOME
 * </pre>
 */
public enum LumberState {
    /** Not running. */
    IDLE,
    /** Sent {@code /home <work>}; waiting for the teleport to settle. */
    GO_TO_WORK,
    /** At the forest; running Baritone's {@code mine} on logs (with optional replant). */
    HARVEST,
    /** Inventory full: {@code /delhome}, {@code /sethome}, {@code /home <base>} in sequence. */
    RESET_HOME,
    /** Sent {@code /home <base>}; waiting for the teleport to settle. */
    GO_TO_HOME,
    /** Visiting every chest in the selected area: deposit logs, then withdraw supplies. */
    SERVICE_CHESTS
}
