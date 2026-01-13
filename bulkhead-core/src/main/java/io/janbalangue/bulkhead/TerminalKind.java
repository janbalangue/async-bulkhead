package io.janbalangue.bulkhead;

/**
 * Terminal outcome kinds used for bulkhead observability callbacks.
 */
public enum TerminalKind {
    /** The operation completed successfully. */
    SUCCESS,
    /** The operation completed exceptionally (including supplier failure or stage failure). */
    FAILURE,
    /** The returned stage was cancelled (cancellation is treated as terminal for permit accounting). */
    CANCELLED
}
