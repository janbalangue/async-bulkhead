package io.janbalangue.bulkhead;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

public class BulkheadTest {

    @Test
    public void acceptsUpToLimit() {
        // Given
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        // When
        CompletionStage<String> s1 =  bulkhead.submit(() -> gate1);
        CompletionStage<String> s2 = bulkhead.submit(() -> gate2);

        CompletableFuture<String> f1 = s1.toCompletableFuture();
        CompletableFuture<String> f2 = s2.toCompletableFuture();

        // Then
        assertFalse(f1.isDone(), "first task should still be in-flight");
        assertFalse(f2.isDone(), "second task should still be in-flight");
    }

    @Test
    public void rejectsWhenSaturated() {
        // Given
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        bulkhead.submit(() -> gate1);
        bulkhead.submit(() -> gate2);

        // And: a supplier to observe for invocation
        AtomicBoolean called = new AtomicBoolean(false);

        // When: third submit should fail fast
        CompletableFuture<String> rejected =
                bulkhead.submit(() -> {
                    called.set(true);
                    return CompletableFuture.completedFuture("should-not-run");
                }).toCompletableFuture();

        // Then: it should immediately be completed exceptionally
        assertTrue(rejected.isDone());
        assertTrue(rejected.isCompletedExceptionally());

        // And: the supplier is never invoked
        assertFalse(called.get());

        // prove it throws when observed
        CompletionException ex = assertThrows(CompletionException.class, rejected::join);
        assertEquals(BulkheadRejectedException.class, ex.getCause().getClass());
        assertEquals("Bulkhead is saturated", ex.getCause().getMessage());
    }

    @Test
    public void releasesPermitOnCompletion() {
        // Given
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        bulkhead.submit(() -> gate1);
        bulkhead.submit(() -> gate2);

        // When: complete one in-flight task
        gate1.complete("done");

        // Then: capacity should be available
        CompletionStage<String> accepted = bulkhead.submit(() -> CompletableFuture.completedFuture("next"));

        CompletableFuture<String> result = accepted.toCompletableFuture();

        assertFalse(result.isCompletedExceptionally());
        assertTrue(result.isDone(), "completed future should propagate immediately");
        assertEquals("next", result.join());
    }

    @Test
    public void supplierThrowingCompletesExceptionallyAndReleasesPermit() {
        // Given
        Bulkhead bulkhead = new Bulkhead(1);
        AtomicBoolean invoked = new AtomicBoolean(false);

        // When: supplier throws
        CompletionStage<String> first = bulkhead.submit(() -> {
            invoked.set(true);
            throw new RuntimeException("boom");
        });

        // Then: supplier was invoked and stage failed with the thrown error
        assertTrue(invoked.get(), "supplier should be invoked when capacity is available");

        CompletionException ex =
                assertThrows(CompletionException.class, () -> first.toCompletableFuture().join());
        assertInstanceOf(RuntimeException.class, ex.getCause());
        assertEquals("boom", ex.getCause().getMessage());

        // And: permit was released (next submission should be accepted)
        CompletionStage<String> second =
                bulkhead.submit(() -> CompletableFuture.completedFuture("ok"));

        assertEquals("ok", second.toCompletableFuture().join());
    }

    @Test
    public void supplierReturningNullCompletesExceptionallyAndReleasesPermit() {
        // Given
        Bulkhead bulkhead = new Bulkhead(1);

        // When: supplier returns null stage
        CompletionStage<String> first = bulkhead.submit(() -> null);

        // Then: stage fails with NPE (message is defined by Bulkhead)
        CompletionException ex =
                assertThrows(CompletionException.class, () -> first.toCompletableFuture().join());
        assertInstanceOf(NullPointerException.class, ex.getCause());
        assertEquals("Supplier returned null CompletionStage", ex.getCause().getMessage());

        // And: permit was released (next submission should be accepted)
        CompletionStage<String> second =
                bulkhead.submit(() -> CompletableFuture.completedFuture("ok"));

        assertEquals("ok", second.toCompletableFuture().join());
    }

    @Test
    public void immediateCompletionReleasesPermitImmediately() {
        // Given
        Bulkhead bulkhead = new Bulkhead(1);

        // When: submit a task that completes immediately
        CompletionStage<String> first =
                bulkhead.submit(() -> CompletableFuture.completedFuture("done"));

        // Then: it should be done immediately
        assertEquals("done", first.toCompletableFuture().join());

        // And: capacity should be immediately available (no permit leak / no delay)
        CompletableFuture<String> gate = new CompletableFuture<>();
        CompletionStage<String> second = bulkhead.submit(() -> gate);

        // second should be in-flight (not rejected, not completed)
        CompletableFuture<String> f2 = second.toCompletableFuture();
        assertFalse(f2.isDone(), "second task should be accepted and in-flight");

        // cleanup
        gate.complete("ok");
        assertEquals("ok", f2.join());
    }

    @Test
    void releasesPermitOnExceptionalCompletion() {
        // Given: bulkhead with a single permit
        Bulkhead bulkhead = new Bulkhead(1);

        // A controllable gate representing an in-flight task
        CompletableFuture<String> gate = new CompletableFuture<>();

        // First submission is accepted and occupies the only permit
        CompletionStage<String> first =
                bulkhead.submit(() -> gate);

        assertThat(first).isNotCompleted();

        // When: the in-flight task completes exceptionally
        RuntimeException failure = new RuntimeException("boom");
        gate.completeExceptionally(failure);

        // Then: a subsequent submission is accepted (permit was released)
        CompletableFuture<String> secondGate = new CompletableFuture<>();

        CompletionStage<String> second =
                bulkhead.submit(() -> secondGate);

        assertThat(second).isNotCompleted();
    }

    @Test
    public void releasesPermitOnCancellation() {
        // given
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<String> gate = new CompletableFuture<>();
        CompletionStage<String> first = bulkhead.submit(() -> gate);

        CompletableFuture<String> f1 = first.toCompletableFuture();
        assertFalse(f1.isDone(), "first task should be accepted and in-flight");

        // when: cancel the returned future (terminal state = cancellation)
        assertTrue(f1.cancel(true), "cancellation should succeed");
        assertTrue(f1.isCancelled());
        assertTrue(f1.isDone());

        // then: permit should be released, so next submit is accepted
        CompletionStage<String> second =
                bulkhead.submit(() -> CompletableFuture.completedFuture("ok"));

        assertEquals("ok", second.toCompletableFuture().join());
    }

    @Test
    public void concurrentSubmissionsNeverExceedLimitAndNoPermitLeaks() throws Exception {
        // Given
        int limit = 3;
        int threads = 24;
        Bulkhead bulkhead = new Bulkhead(limit);

        CyclicBarrier startBarrier = new CyclicBarrier(threads);
        CountDownLatch submitted = new CountDownLatch(threads);

        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);

        ConcurrentLinkedQueue<CompletableFuture<String>> gates = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<CompletableFuture<String>> returned = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> infraErrors = new ConcurrentLinkedQueue<>();

        @SuppressWarnings("resource")
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        // start all submitters at once
                        startBarrier.await();

                        CompletionStage<String> stage = bulkhead.submit(() -> {
                            // Only runs for admitted tasks (supplier is not invoked for rejections)
                            int now = inFlight.incrementAndGet();
                            maxObserved.accumulateAndGet(now, Math::max);

                            // Keep tasks in-flight until the test completes them
                            CompletableFuture<String> gate = new CompletableFuture<>();
                            gates.add(gate);

                            // Track in-flight decrement when the admitted stage completes
                            gate.whenComplete((v, e) -> inFlight.decrementAndGet());
                            return gate;
                        });

                        returned.add(stage.toCompletableFuture());
                    } catch (Throwable t) {
                        // Infrastructure errors (barrier broken/interruption/etc.) should fail the test,
                        // not be mistaken for rejections.
                        infraErrors.add(t);
                    } finally {
                        submitted.countDown();
                    }
                });
            }

            // submissions must be non-blocking / fail-fast
            assertTrue(submitted.await(5, TimeUnit.SECONDS),
                    "all submitters should finish promptly (no blocking)");

            assertTrue(infraErrors.isEmpty(), "infra errors: " + infraErrors);

            // Then: never exceed limit, and with no completions exactly 'limit' tasks are admitted
            assertTrue(maxObserved.get() <= limit, "accepted in-flight should never exceed limit");
            assertEquals(limit, gates.size(), "with no completions, exactly 'limit' tasks should be admitted");

            // When: release admitted tasks
            for (CompletableFuture<String> gate : gates) {
                gate.complete("ok");
            }

            // Ensure all returned stages settle; count rejections AFTER draining to avoid timing flakiness
            long rejected = 0;
            for (CompletableFuture<String> f : returned) {
                try {
                    f.join();
                } catch (CompletionException ce) {
                    rejected++;

                    Throwable cause = ce.getCause();
                    assertNotNull(cause, "CompletionException should have a cause");
                    assertInstanceOf(BulkheadRejectedException.class, cause, "expected BulkheadRejectedException but got: " + cause);
                }
            }
            assertEquals(threads - limit, rejected, "all extra submissions should fail fast");

            // Then: no permit leaks (we can admit 'limit' again, and the next rejects)
            List<CompletableFuture<String>> secondWaveGates = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                CompletableFuture<String> gate = new CompletableFuture<>();
                secondWaveGates.add(gate);

                CompletionStage<String> accepted = bulkhead.submit(() -> gate);
                // Meaningful acceptance check: accepted tasks should be in-flight (not immediately done)
                assertFalse(accepted.toCompletableFuture().isDone(), "accepted tasks should be in-flight (not done)");
            }

            CompletableFuture<String> shouldReject =
                    bulkhead.submit(() -> CompletableFuture.completedFuture("nope")).toCompletableFuture();
            assertTrue(shouldReject.isCompletedExceptionally(), "should reject once saturated again");

            // cleanup
            secondWaveGates.forEach(g -> g.complete("done"));
        } finally {
            pool.shutdownNow();
        }
    }
}
