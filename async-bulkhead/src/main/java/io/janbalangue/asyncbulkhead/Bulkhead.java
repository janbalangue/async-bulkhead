package io.janbalangue.asyncbulkhead;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/**
 * An async bulkhead that limits the number of in-flight asynchronous operations.
 *
 * <p>The bulkhead enforces a fixed upper bound on concurrently in-flight operations.
 * An operation is considered <em>in-flight</em> from the moment a submission is
 * successfully admitted (a permit is acquired) until the {@link CompletionStage}
 * returned by {@link #submit(Supplier)} reaches a terminal state.</p>
 *
 * <p>A terminal state is defined as:</p>
 * <ul>
 *   <li>successful completion</li>
 *   <li>exceptional completion</li>
 *   <li>cancellation</li>
 * </ul>
 *
 * <p>Permit release is observed strictly at terminal completion of the returned
 * {@code CompletionStage}, not at supplier invocation time.</p>
 *
 * <p><strong>Overload behavior</strong></p>
 *
 * <p>If the bulkhead is saturated (the number of in-flight operations has reached
 * the configured limit), submissions are <strong>rejected immediately</strong>
 * and fail fast with {@link BulkheadRejectedException}. Rejected submissions
 * <strong>do not invoke</strong> the supplied operation.</p>
 *
 * <p>This bulkhead does not provide queuing, waiting, or timeouts in v0.x.
 * Rejection is the only overload behavior.</p>
 *
 * <p><strong>Failure semantics</strong></p>
 *
 * <p>Failures originating from the supplied operation (for example, if the supplier
 * throws or the returned stage completes exceptionally) are propagated
 * unchanged via the returned stage to the caller. The bulkhead does not wrap or reinterpret operation
 * failures.</p>
 *
 * <p>Invariant violations are surfaced as {@link IllegalStateException}.</p>
 *
 * <p><strong>Cancellation is not propagated.</strong> Cancelling the {@link CompletionStage}
 * returned by {@link #submit(Supplier)} does not attempt to cancel the supplied
 * operation or its returned stage. Cancellation only affects the returned stage
 * and permit accounting.</p>
 *
 * <p>This class is thread-safe.</p>
 *
 * @since 0.1.0
 */
public final class Bulkhead {

    private static final BulkheadListener NOOP = new BulkheadListener() {
    };

    private final int limit;
    private final Semaphore permits;
    private final BulkheadListener listener;

    /**
     * Creates a bulkhead with the given fixed concurrency limit.
     *
     * <p>The limit is the maximum number of admitted operations that may be in-flight concurrently.</p>
     *
     * @param limit maximum number of in-flight operations; must be positive
     * @throws IllegalArgumentException if {@code limit <= 0}
     */
    public Bulkhead(int limit) {
        this(limit, new Semaphore(limit), NOOP);
    }

    /**
     * Creates a bulkhead with the given fixed concurrency limit and listener.
     *
     * <p>The listener is optional. If {@code listener} is {@code null}, a no-op listener is used.</p>
     *
     * <p>Listener callbacks may be invoked concurrently and are best-effort:
     * exceptions thrown by the listener are swallowed and do not affect admission/release semantics.</p>
     *
     * @param limit    maximum number of in-flight operations; must be positive
     * @param listener optional listener for observability hooks (may be {@code null})
     * @throws IllegalArgumentException if {@code limit <= 0}
     */
    public Bulkhead(int limit, BulkheadListener listener) {
        this(limit, new Semaphore(limit), listener == null ? NOOP : listener);
    }

    Bulkhead(int limit, Semaphore permits, BulkheadListener listener) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive: " + limit);
        this.limit = limit;
        this.permits = permits;
        this.listener = (listener == null) ? NOOP : listener;
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable ignored) {
            // Intentionally ignored: listeners must not affect bulkhead semantics.
        }
    }

    /**
     * Returns the configured maximum number of operations that may be in flight concurrently.
     *
     * @return the fixed concurrency limit
     */
    public int limit() {
        return limit;
    }

    /**
     * Returns the number of permits currently available for admission.
     *
     * <p>This is an instantaneous, advisory snapshot and is not linearizable.
     * The value may change immediately due to concurrent submissions or completions.
     * Do not use this value to predict whether the next submission will be admitted.</p>
     *
     * @return permits currently available
     * @throws IllegalStateException if internal permit accounting invariants are violated
     */
    public int available() {
        return checkedAvailablePermits();
    }

    /**
     * Returns the number of operations currently in flight.
     *
     * <p>This is derived as {@code limit() - available()} and is an instantaneous, advisory snapshot.
     * It is not linearizable and may change immediately due to concurrent submissions or completions.</p>
     *
     * @return current in-flight count
     * @throws IllegalStateException if internal permit accounting invariants are violated
     */
    public int inFlight() {
        return limit - checkedAvailablePermits();
    }

    private static <T> CompletionStage<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    private int checkedAvailablePermits() {
        int p = permits.availablePermits();
        if (p < 0 || p > limit) {
            throw new IllegalStateException(
                    "Bulkhead invariant violated: available permits=" + p + " (limit=" + limit + ")"
            );
        }
        return p;
    }

    private void releaseChecked() {
        permits.release();
        int p = permits.availablePermits();
        if (p < 0 || p > limit) {
            throw new IllegalStateException(
                    "Bulkhead invariant violated after release: availablePermits=" + p + " (limit=" + limit + ")"
            );
        }
    }

    private static boolean isCancellation(Throwable t) {
        if (t instanceof CancellationException) return true;
        if (t instanceof CompletionException) {
            Throwable cause = ((CompletionException) t).getCause();
            return cause instanceof CancellationException;
        }
        return false;
    }

    /**
     * Submits an async operation to the bulkhead.
     *
     * <p><strong>Outcomes</strong></p>
     * <ul>
     *   <li><b>Admitted:</b> a permit is acquired, then {@code operation} is invoked exactly once,
     *       and the returned stage governs permit lifetime until terminal completion.</li>
     *   <li><b>Rejected:</b> if no permit is available at submission time, the operation is not invoked
     *       and a stage failed with {@link BulkheadRejectedException} is returned.</li>
     * </ul>
     *
     * <p><strong>completion</strong></p>
     * <p>A permit is released exactly once when the returned stage reaches a terminal state:
     * success, exceptional completion, or cancellation.</p>
     *
     * <p><strong>Cancellation</strong></p>
     * <p>Cancelling the stage returned by this method releases the permit (if not already released),
     * but does not attempt to cancel or interrupt the underlying operation.</p>
     *
     *<p><strong>Cold work requirement</strong></p>
     *<p>The supplier must be <em>cold</em>: it must not start work until invoked after admission.</p>
     *
     * @param <T> result type
     * @param operation supplier producing the stage to execute if admitted (must not be {@code null})
     * @return a stage representing the operation, or a failed stage if rejected
     * @throws NullPointerException if {@code operation} is {@code null} or returns {@code null}
     */
    public <T> CompletionStage<T> submit(Supplier<? extends CompletionStage<T>> operation) {
        Objects.requireNonNull(operation);

        if (!permits.tryAcquire()) {
            safe(listener::onRejected);
            return failed(new BulkheadRejectedException("Bulkhead is saturated"));
        }
        safe(listener::onAdmitted);

        final CompletionStage<T> stage;
        try {
            stage = operation.get();
            if (stage == null) {
                throw new NullPointerException("Supplier returned null CompletionStage");
            }
        } catch (Throwable t) {
            releaseChecked();
            safe(() -> listener.onReleased(TerminalKind.FAILURE, t));
            return failed(t); // <-- return failed stage, don't throw
        }

        final AtomicBoolean released = new AtomicBoolean(false);

        final CompletableFuture<T> out = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (released.compareAndSet(false, true)) {
                    try {
                        releaseChecked();
                    } catch (Throwable t) {
                        this.completeExceptionally(t);
                        return false;
                    }
                    safe(() -> listener.onReleased(TerminalKind.CANCELLED, null));
                }
                return super.cancel(mayInterruptIfRunning);
            }
        };

        try {
            stage.whenComplete((r, e) -> {
                Throwable releaseError = null;

                if (released.compareAndSet(false, true)) {
                    try {
                        releaseChecked();
                    } catch (Throwable t) {
                        releaseError = t;
                    }

                    if (releaseError == null) {
                        if (e != null) {
                            if (isCancellation(e)) safe(() -> listener.onReleased(TerminalKind.CANCELLED, null));
                            else safe(() -> listener.onReleased(TerminalKind.FAILURE, e));
                        } else {
                            safe(() -> listener.onReleased(TerminalKind.SUCCESS, null));
                        }
                    }

                }

                if (releaseError != null) {
                    out.completeExceptionally(releaseError);
                    return;
                }

                if (e != null) out.completeExceptionally(e);
                else out.complete(r);
            });
        } catch (Throwable t) {
            if (released.compareAndSet(false, true)) {
                releaseChecked(); // if this throws, do NOT emit onReleased
                safe(() -> listener.onReleased(TerminalKind.FAILURE, t));
            }
            return failed(t);
        }
        return out;
    }
}

