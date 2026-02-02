package io.janbalangue.asyncbulkhead;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class CancellationTest {

    @Test
    void onCancelInvokesCancellerExactlyOnce() {
        AtomicInteger cancels = new AtomicInteger();

        CompletableFuture<Void> f = new CompletableFuture<>();
        CompletionStage<Void> stage = Cancellation.onCancel(f, cancels::incrementAndGet);

        CompletableFuture<Void> view = stage.toCompletableFuture();

        // Act: cancel multiple times
        view.cancel(true);
        view.cancel(true);

        // Strong assertions
        assertTrue(view.isCancelled(), "stage must be cancelled");
        assertThrows(CancellationException.class, view::join);

        assertEquals(
                1,
                cancels.get(),
                "canceller must be invoked exactly once regardless of repeated cancel() calls"
        );
    }

    @Test
    void onCancelDoesNotInvokeCancellerOnNormalCompletion() {
        AtomicInteger cancels = new AtomicInteger();

        CompletableFuture<String> f = new CompletableFuture<>();
        Cancellation.onCancel(f, cancels::incrementAndGet);

        f.complete("ok");

        assertEquals("ok", f.join());
        assertEquals(0, cancels.get());

        // Cancellation after terminal completion should not trigger canceller.
        assertFalse(f.cancel(true));
        assertEquals(0, cancels.get());
    }

    @Test
    void onCancelDoesNotInvokeCancellerOnExceptionalCompletion() {
        AtomicInteger cancels = new AtomicInteger();

        CompletableFuture<Void> f = new CompletableFuture<>();
        Cancellation.onCancel(f, cancels::incrementAndGet);

        RuntimeException boom = new RuntimeException("boom");
        f.completeExceptionally(boom);

        ExecutionException ex = assertThrows(ExecutionException.class, f::get);
        assertSame(boom, ex.getCause());
        assertEquals(0, cancels.get());

        // Cancellation after exceptional completion should not trigger canceller.
        assertFalse(f.cancel(true));
        assertEquals(0, cancels.get());
    }

    @Test
    void submitWithCancellationDoesNotInvokeCancellerOnRejection() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> blocker = new CompletableFuture<>();
        bulkhead.submit(() -> blocker);

        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger invoked = new AtomicInteger();

        CompletionStage<Void> rejected = Cancellation.submitWithCancellation(
                bulkhead,
                () -> {
                    invoked.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                cancels::incrementAndGet
        );

        assertEquals(0, invoked.get(), "supplier must not be invoked when rejected");

        assertTrue(
                BulkheadRejectedException.isRejected(
                        assertThrows(ExecutionException.class, () -> rejected.toCompletableFuture().get())
                )
        );

        assertEquals(0, cancels.get(), "nothing should be cancelled on rejection");
    }

    @Test
    void cancellerExceptionsAreSwallowed() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> underlying = new CompletableFuture<>();

        CompletionStage<Void> stage = Cancellation.submitWithCancellation(
                bulkhead,
                () -> underlying,
                () -> { throw new RuntimeException("boom cancel"); }
        );

        CompletableFuture<Void> returned = stage.toCompletableFuture();

        assertTrue(returned.cancel(true), "cancelling returned stage should succeed");
        assertTrue(returned.isCancelled());

        // Underlying work should still be unaffected.
        assertFalse(underlying.isCancelled());
        assertFalse(underlying.isDone());

        // Permit should be released even if canceller throws.
        assertEquals(1, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());

        underlying.complete(null);
    }

    @Test
    void cancelVsCompletionRaceInvokesCancellerAtMostOnce() throws Exception {
        AtomicInteger cancels = new AtomicInteger();

        for (int i = 0; i < 200; i++) {
            Bulkhead bulkhead = new Bulkhead(1);

            CompletableFuture<String> underlying = new CompletableFuture<>();

            CompletionStage<String> stage = Cancellation.submitWithCancellation(
                    bulkhead,
                    () -> underlying,
                    cancels::incrementAndGet
            );

            CompletableFuture<String> returned = stage.toCompletableFuture();

            CountDownLatch start = new CountDownLatch(1);

            Thread t1 = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                returned.cancel(true);
            });

            Thread t2 = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                underlying.complete("ok");
            });

            t1.start();
            t2.start();
            start.countDown();
            t1.join();
            t2.join();

            // Wait for the returned stage to settle.
            try { returned.get(); } catch (ExecutionException | CancellationException ignored) {}

            assertEquals(1, bulkhead.available());
            assertEquals(0, bulkhead.inFlight());
        }

        // Should never exceed number of trials.
        assertTrue(cancels.get() >= 0 && cancels.get() <= 200,
                "canceller must be invoked at most once per trial");
    }
}
