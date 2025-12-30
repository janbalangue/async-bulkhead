package io.janbalangue.bulkhead;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * An async bulkhead that limits the number of in-flight asynchronous operations.
 *
 * <p>The bulkhead enforces a fixed upper bound on concurrently in-flight operations.
 * A task is considered <em>in-flight</em> from the moment a submission is
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
 *
 * <p>Permit release is observed strictly at terminal completion of the returned
 * {@code CompletionStage}, not at supplier invocation time.</p>
 *
 * <h2>Overload behavior</h2>
 *
 * <p>If the bulkhead is saturated (the number of in-flight operations has reached
 * the configured limit), submissions are <strong>rejected immediately</strong>
 * and fail fast with {@link BulkheadRejectedException}. Rejected submissions
 * <strong>do not invoke</strong> the supplied task.</p>
 *
 * <p>This bulkhead does not provide queuing, waiting, or timeouts in v0.1.
 * Rejection is the only overload behavior.</p>
 *
 * <h2>Failure semantics</h2>
 *
 * <p>Failures originating from the supplied task (for example, if the supplier
 * throws or the returned stage completes exceptionally) are propagated
 * unchanged to the caller. The bulkhead does not wrap or reinterpret task
 * failures.</p>
 *
 * <p>This class is thread-safe.</p>
 *
 * @since 0.1.0
 */
public final class Bulkhead {

    private final Semaphore permits;

    /**
     * Creates a bulkhead with the given maximum number of in-flight operations.
     *
     * @param limit the maximum number of concurrently in-flight operations; must be positive
     * @throws IllegalArgumentException if {@code limit <= 0}
     */
    public Bulkhead(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        this.permits = new Semaphore(limit);
    }

    /**
     * Submits an async task to the bulkhead.
     *
     * <p>The task is only invoked if capacity is available. If the bulkhead is full,
     * the returned stage is completed exceptionally with {@link BulkheadRejectedException}
     * and the task is never started.</p>
     *
     * @param <T> the result type of the returned stage
     * @param task a supplier that produces the {@link CompletionStage} to execute if admitted
     * @return a {@link CompletionStage} representing the task’s completion, or a failed stage if rejected
     * @throws NullPointerException if {@code task} is null
     */
    public <T> CompletionStage<T> submit(Supplier<? extends CompletionStage<T>> task) {
        if (!permits.tryAcquire()) {
            return failed(new BulkheadRejectedException("Bulkhead is saturated"));
        }

        final CompletionStage<T> stage;
        try {
            stage = task.get();
            if (stage == null) {
                throw new NullPointerException("Supplier returned null CompletionStage");
            }
        } catch (Throwable t) {
            permits.release();
            return failed(t); // <-- return failed stage, don't throw
        }

        try {
            stage.whenComplete((r, e) -> permits.release());
        } catch (Throwable t) {
            permits.release();
            return failed(t); // <-- return failed stage, don't throw
        }

        return stage;
    }

    private static <T> CompletionStage<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }
}
