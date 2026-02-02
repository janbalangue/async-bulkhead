package io.janbalangue.asyncbulkhead;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Opt-in cancellation wiring helpers.
 *
 * <p>This library's core {@link Bulkhead} semantics treat cancellation as a terminal outcome for
 * capacity accounting, but do <em>not</em> propagate cancellation to the underlying work.</p>
 *
 * <p>This utility lets callers explicitly attach a best-effort cancellation hook to the
 * {@link CompletionStage} returned by the bulkhead.</p>
 *
 * <p><strong>Notes</strong>:</p>
 * <ul>
 *   <li>The hook runs at most once.</li>
 *   <li>If the stage completes normally or exceptionally (non-cancellation), the hook is not run.</li>
 *   <li>If the hook throws, the exception is swallowed.</li>
 *   <li>This does not guarantee interruption/stop of underlying work; it only invokes your hook.</li>
 * </ul>
 */
public final class Cancellation {

    private Cancellation() {
        // utility
    }

    /**
     * Attaches a cancellation hook to the returned stage.
     *
     * <p>The hook is invoked when {@code stage.toCompletableFuture()} completes with a
     * {@link CancellationException} (including when wrapped in a {@link CompletionException}).</p>     *
     * <p>The returned value is the same {@code stage} instance, for fluent composition.</p>
     *
     * @param stage     the stage to observe for cancellation
     * @param canceller user-provided cancellation hook (invoked at most once)
     * @param <T>       stage result type
     * @return the same {@code stage} instance
     */
    public static <T> CompletionStage<T> onCancel(CompletionStage<T> stage, Runnable canceller) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(canceller, "canceller");

        CompletableFuture<T> view = stage.toCompletableFuture();
        AtomicBoolean once = new AtomicBoolean(false);

        view.whenComplete((v, err) -> {
            if (!isCancellation(err)) {
                return;
            }
            if (once.compareAndSet(false, true)) {
                safeRun(canceller);
            }
        });

        return stage;
    }

    /**
     * Convenience helper: submit to the bulkhead and wire an opt-in cancellation hook.
     *
     * <p>If the submission is rejected, the supplier is not invoked (bulkhead invariant) and
     * the canceller is not invoked (nothing to cancel).</p>
     * <p>This method does not change {@link Bulkhead} cancellation semantics; it composes
     * cancellation behavior <em>on top</em> of the bulkhead's returned stage.</p>
     *
     * @param bulkhead  target bulkhead
     * @param supplier  supplier producing the stage if admitted
     * @param canceller user-provided cancellation hook
     * @param <T>       stage result type
     * @return the stage returned by {@link Bulkhead#submit(Supplier)}
     */
    public static <T> CompletionStage<T> submitWithCancellation(
            Bulkhead bulkhead,
            Supplier<? extends CompletionStage<T>> supplier,
            Runnable canceller
    ) {
        Objects.requireNonNull(bulkhead, "bulkhead");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(canceller, "canceller");

        CompletionStage<T> stage = bulkhead.submit(supplier);
        onCancel(stage, canceller);
        return stage;
    }

    private static boolean isCancellation(Throwable err) {
        if (err == null) {
            return false;
        }
        if (err instanceof CancellationException) {
            return true;
        }
        if (err instanceof CompletionException ce) {
            return ce.getCause() instanceof CancellationException;
        }
        return false;
    }

    private static void safeRun(Runnable r) {
        try {
            r.run();
        } catch (Throwable ignored) {
            // best-effort hook: never let user cancellation logic interfere with completion pipeline
        }
    }
}
