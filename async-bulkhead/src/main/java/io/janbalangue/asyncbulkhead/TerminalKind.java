package io.janbalangue.asyncbulkhead;

/**
 * Terminal outcome kinds used for bulkhead observability.
 *
 * <p>Each admitted operation reaches exactly one terminal state,
 * at which point capacity is released exactly once.</p>
 *
 * <p>This enum is purely observational and must not affect
 * admission or execution semantics.</p>
 */
public enum TerminalKind {

    /**
     * The operation completed successfully.
     */
    SUCCESS,

    /**
     * The operation completed exceptionally with a non-cancellation failure.
     */
    FAILURE,

    /**
     * The operation was cancelled.
     *
     * <p>Cancellation is treated as terminal for permit accounting
     * but is not propagated to underlying work.</p>
     */
    CANCELLED
}
