package com.luna0wl.baritoneworker.worker.sorter;

/**
 * The phases of one sorting run. The worker teleports to the chest room, scans
 * it, then alternates COLLECT (pull misplaced items into the inventory) and
 * DISTRIBUTE (carry them to the chest tagged for them) until a full pass moves
 * nothing.
 *
 * <pre>
 *   IDLE ─(start)─► GO_TO_HOME ─► SCAN ─► COLLECT ⇄ DISTRIBUTE ─(stable)─► IDLE
 * </pre>
 */
public enum SortState {
    /** Not running. */
    IDLE,
    /** Sent {@code /home <home>}; waiting for the teleport to settle. */
    GO_TO_HOME,
    /** At the chest room; building the chest list and their tags. */
    SCAN,
    /** Visiting chests, pulling out items that belong in a different chest. */
    COLLECT,
    /** Visiting chests, depositing carried items into the chest tagged for them. */
    DISTRIBUTE
}
