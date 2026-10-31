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
     * The stage produced by the operation was canceled.
     *
     * <p>Canceling the stage returned to the caller is not this outcome. That
     * cancellation is not propagated to underlying work, so the permit is held and
     * the outcome reported is whatever the underlying work eventually does.</p>
     */
    CANCELLED
}
