package io.janbalangue.bulkhead;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * An async bulkhead that limits the number of concurrently in-flight asynchronous tasks.
 * <p>
 * The bulkhead is submission-based and async-first. It does not execute tasks itself;
 * it only controls whether work is allowed to start.
 * <p>
 * <strong>Core guarantees:</strong>
 * <ul>
 *   <li>A maximum number of in-flight tasks is enforced.</li>
 *   <li>Submissions never block.</li>
 *   <li>When saturated, submissions fail fast.</li>
 *   <li>If a submission is rejected, the task supplier is not invoked.</li>
 *   <li>Permits are released when tasks complete (success, failure, or cancellation).</li>
 * </ul>
 * <p>
 * This class is thread-safe.
 *
 * <p><strong>Note:</strong> This bulkhead does not provide queuing, retries, or fallback
 * behavior. Such features are intentionally out of scope for v0.x.
 */
public final class Bulkhead {

    private final Semaphore permits;
    private int limit;

    /**
     * Creates a bulkhead that allows up to {@code limit} concurrent in-flight tasks.
     *
     * @param limit the maximum number of concurrently in-flight tasks; must be positive
     * @throws IllegalArgumentException if {@code limit} is less than or equal to zero
     */
    public Bulkhead(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        this.permits = new Semaphore(limit);
    }

    private static <T> CompletionStage<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    /**
     * Submits an asynchronous task to the bulkhead.
     * <p>
     * If capacity is available, the bulkhead acquires a permit and invokes the supplied
     * task exactly once. The returned {@link CompletionStage} is observed, and the permit
     * is released when that stage completes (normally, exceptionally, or via cancellation).
     * <p>
     * If the bulkhead is saturated at submission time:
     * <ul>
     *   <li>the submission is rejected immediately (fail-fast)</li>
     *   <li>the supplier is <strong>not</strong> invoked</li>
     *   <li>the returned stage completes exceptionally with {@link BulkheadRejectedException}</li>
     * </ul>
     *
     * @param task a supplier producing a {@link CompletionStage} representing the task
     * @param <T>  the task result type
     * @return a {@link CompletionStage} representing the task result, or an exceptional
     * stage if the submission is rejected
     * @throws NullPointerException if {@code task} is null
     */
    public <T> CompletionStage<T> submit(Supplier<? extends CompletionStage<T>> task) {
        Objects.requireNonNull(task, "task");

        // fail fast when saturated
        if (!permits.tryAcquire()) {
            return failed(new BulkheadRejectedException("Bulkhead saturated"));
        }

        try {
            // run task (supplier should produce a stage)
            CompletionStage<T> stage = Objects.requireNonNull(task.get(), "task returned null");

            // always release permit on completion
            stage.whenComplete((value, error) -> permits.release());
            return stage;
        } catch (Throwable t) {
            permits.release();
            return failed(t);
        }
    }
}
