package com.luna0wl.baritoneworker.worker.miner;

/**
 * The high-level phases of one mining cycle. The worker is a tick-driven state
 * machine that walks these in order and then loops back to {@link #GO_TO_MINE}.
 *
 * <pre>
 *   IDLE
 *     └─(start)─► GO_TO_MINE ─► TUNNELING ─(inventory full)─► RESET_HOME
 *                    ▲                                            │
 *                    │                                           ▼
 *                  (loop) ◄──────── SERVICE_CHESTS ◄──────── GO_TO_HOME
 * </pre>
 *
 * Note: this worker does NOT eat. It only restocks baked potatoes from the
 * chests so a separate auto-eat mod always has food on hand.
 */
public enum MinerState {
    /** Not running. */
    IDLE,
    /** Sent {@code /home <mine>}; waiting for the teleport to settle. */
    GO_TO_MINE,
    /** At the tunnel face; running Baritone's {@code tunnel} and watching the inventory. */
    TUNNELING,
    /** Inventory full: {@code /delhome}, {@code /sethome}, {@code /home <base>} in sequence. */
    RESET_HOME,
    /** Sent {@code /home <base>}; waiting for the teleport to settle. */
    GO_TO_HOME,
    /** Visiting every chest in the selected area: deposit loot, then withdraw supplies. */
    SERVICE_CHESTS
}
