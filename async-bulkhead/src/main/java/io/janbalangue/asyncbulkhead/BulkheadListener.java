package io.janbalangue.asyncbulkhead;

/**
 * Observability hooks for {@link Bulkhead}.
 *
 * <p>Listeners are strictly best-effort and must not affect bulkhead semantics.
 * Implementations must be fast, non-blocking, and tolerant of being invoked concurrently.</p>
 *
 * <p><b>Exception containment:</b> Any exception thrown by listener methods is intentionally
 * swallowed. Listener failures must not change admission, rejection, or permit accounting.</p>
 *
 * <p>Listener callbacks are not guaranteed to be invoked on any particular thread, and no ordering
 * guarantees are provided under concurrency.</p>
 */
public interface BulkheadListener {
    /**
     * Called when a submission is rejected due to saturation.
     *
     * <p>The rejected operation supplier was not invoked and no user work was started.</p>
     */
    default void onRejected() {}

    /**
     * Called after a permit is acquired and before invoking the supplier.
     */
    default void onAdmitted() {}

    /**
     * Called exactly once per admitted operation when the bulkhead releases its permit.
     *
     * @param kind terminal outcome observed by the bulkhead
     * @param error non-null iff {@code kind == TerminalKind.FAILURE}; otherwise null
     */
    default void onReleased(TerminalKind kind, Throwable error) {}
}
