package com.luna0wl.baritoneworker.worker.mover;

/**
 * The phases of moving one source chest's contents to the destination. The
 * worker shuttles between two {@code /home}s: take a source chest's items, carry
 * them to the destination, deposit, and repeat for the next source chest.
 *
 * <pre>
 *   IDLE ─(start)─► GO_TO_SOURCE ─► TAKE ─► GO_TO_DEST ─► PUT ─┐
 *                        ▲                                      │
 *                        └──────────────(more to move)─────────┘
 * </pre>
 */
public enum MoverState {
    /** Not running. */
    IDLE,
    /** Sent {@code /home <source>}; waiting for the teleport to settle. */
    GO_TO_SOURCE,
    /** At the source room; emptying the current source chest into the inventory. */
    TAKE,
    /** Sent {@code /home <dest>}; waiting for the teleport to settle. */
    GO_TO_DEST,
    /** At the destination room; depositing the carried items. */
    PUT
}
