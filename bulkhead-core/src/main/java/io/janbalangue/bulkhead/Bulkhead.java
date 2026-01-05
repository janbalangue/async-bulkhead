package io.janbalangue.bulkhead;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <h2>Overload behavior</h2>
 *
 * <p>If the bulkhead is saturated (the number of in-flight operations has reached
 * the configured limit), submissions are <strong>rejected immediately</strong>
 * and fail fast with {@link BulkheadRejectedException}. Rejected submissions
 * <strong>do not invoke</strong> the supplied operation.</p>
 *
 * <p>This bulkhead does not provide queuing, waiting, or timeouts in v0.x.
 * Rejection is the only overload behavior.</p>
 *
 * <h2>Failure semantics</h2>
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

    private final int limit;
    private final Semaphore permits;

    /**
     * Creates a bulkhead with the given maximum number of in-flight operations.
     *
     * @param limit the maximum number of concurrently in-flight operations; must be positive
     * @throws IllegalArgumentException if {@code limit <= 0}
     */
    public Bulkhead(int limit) {
        this(limit, new Semaphore(limit));
    }

    Bulkhead(int limit, Semaphore permits) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        this.limit = limit;
        this.permits = permits;
    }

    /**
     * Returns the maximum number of operations that may be in flight concurrently.
     *
     * @return the configured maximum number of concurrently in-flight operations
     */
    public int limit() {
        return limit;
    }

    /**
     * Returns the number of permits currently available for admission.
     *
     * @return the number of permits currently available (i.e., additional operations that could be admitted now)
     * @throws IllegalStateException if internal permit accounting invariants are violated
     */
    public int available() {
        return checkedAvailablePermits();
    }

    /**
     * Returns the number of operations currently in flight.
     *
     * @return the current number of in-flight operations (admitted but not yet terminal)
     * @throws IllegalStateException if internal permit accounting invariants are violated
     */
    public int inFlight() {
        return limit - checkedAvailablePermits();
    }

    /**
     * Submits an async operation to the bulkhead.
     *
     * <p>The operation is only invoked if capacity is available. If the bulkhead is full,
     * the returned stage is completed exceptionally with {@link BulkheadRejectedException}
     * and the operation is never started.</p>
     *
     * <p>If {@code operation} is null, or returns null, the returned stage completes exceptionally
     * with {@link NullPointerException}.</p>
     *
     * @param <T> the result type of the returned stage
     * @param operation a supplier that produces the {@link CompletionStage} to execute if admitted
     * @return a {@link CompletionStage} representing the operation’s completion, or a failed stage if rejected
     */
    public <T> CompletionStage<T> submit(Supplier<? extends CompletionStage<T>> operation) {
        if (!permits.tryAcquire()) {
            return failed(new BulkheadRejectedException("Bulkhead is saturated"));
        }

        final CompletionStage<T> stage;
        try {
            stage = operation.get();
            if (stage == null) {
                throw new NullPointerException("Supplier returned null CompletionStage");
            }
        } catch (Throwable t) {
            releaseChecked();
            return failed(t); // <-- return failed stage, don't throw
        }

        final AtomicBoolean released = new AtomicBoolean(false);

        final CompletableFuture<T> out = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (!cancelled) {
                    return false;
                }

                // Cancellation is a terminal state: release admission exactly once.
                if (released.compareAndSet(false, true)) {
                    releaseChecked();
                }
                return true;
            }
        };

        try {
            stage.whenComplete((r, e) -> {
                // Always release exactly once when the underlying stage becomes terminal.
                if (released.compareAndSet(false, true)) {
                    try {
                        releaseChecked();
                    } catch (Throwable invariant) {
                        out.completeExceptionally(invariant);
                        return;
                    }
                }

                // Propagate operation result only if release succeeded (or was already done).
                if (e != null) {
                    out.completeExceptionally(e);
                } else {
                    out.complete(r);
                }
            });
        } catch (Throwable t) {
            // If handler registration fails, treat as terminal and release exactly once.
            // No cancellation/underlying completion callback will ever fire.
            try {
                released.set(true);
                releaseChecked();
            } catch (Throwable invariant) {
                invariant.addSuppressed(t);
                return failed(invariant);
            }
            return failed(t); // preserve prior behavior: registration failure -> failed stage
        }
        return out;
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
}
