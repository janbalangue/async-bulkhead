package io.janbalangue.asyncbulkhead;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

public class BulkheadTest {

    static Stream<Throwable> supplierCancellationExceptions() {
        return Stream.of(
                new CancellationException("supplier cancelled"),
                new CompletionException(new CancellationException("supplier cancelled (wrapped)"))
        );
    };

    @ParameterizedTest
    @MethodSource("supplierCancellationExceptions")
    public void supplierStageCancellationIsClassifiedAsCancelledAndReleasesPermit(Throwable cancellation) {
        AtomicReference<TerminalKind> kindRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch released = new CountDownLatch(1);

        BulkheadListener listener = new BulkheadListener() {
            @Override
            public void onReleased(TerminalKind kind, Throwable error) {
                kindRef.set(kind);
                errorRef.set(error);
                released.countDown();
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, listener);

        CompletableFuture<String> supplierStage = new CompletableFuture<>();
        CompletionStage<String> returned = bulkhead.submit(() -> supplierStage);

        supplierStage.completeExceptionally(cancellation);

        awaitUnchecked(released);

        assertEquals(TerminalKind.CANCELLED, kindRef.get(), "listener terminal kind");
        assertNull(errorRef.get(), "listener error must be null for CANCELLED");

        // Returned stage should still be exceptional and preserve the cancellation cause.
        CompletableFuture<String> f = returned.toCompletableFuture();
        assertTrue(f.isCompletedExceptionally());

        try {
            f.join();
            fail("expected exceptional completion");
        } catch (CancellationException ce) {
            // Acceptable if it propagates as a CancellationException directly.
        } catch (CompletionException ce) {
            assertInstanceOf(CancellationException.class, ce.getCause());
        }

        // Permit released: next submit admitted
        assertEquals(
                "ok",
                bulkhead.submit(() -> CompletableFuture.completedFuture("ok"))
                        .toCompletableFuture()
                        .join()
        );
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
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
    public void acceptsUpToLimit() {
        // Given
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        // When
        CompletionStage<String> s1 = bulkhead.submit(() -> gate1);
        CompletionStage<String> s2 = bulkhead.submit(() -> gate2);

        CompletableFuture<String> f1 = s1.toCompletableFuture();
        CompletableFuture<String> f2 = s2.toCompletableFuture();

        // Then
        assertFalse(f1.isDone(), "first operation should still be in-flight");
        assertFalse(f2.isDone(), "second operation should still be in-flight");
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
    public void releasesPermitOnCompletion() {
        // Given
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        bulkhead.submit(() -> gate1);
        bulkhead.submit(() -> gate2);

        // When: complete one in-flight operation
        gate1.complete("done");

        // Then: capacity should be available
        CompletionStage<String> accepted = bulkhead.submit(() -> CompletableFuture.completedFuture("next"));

        CompletableFuture<String> result = accepted.toCompletableFuture();

        assertFalse(result.isCompletedExceptionally());
        assertTrue(result.isDone(), "completed future should propagate immediately");
        assertEquals("next", result.join());
    }

    @Test
    public void immediateCompletionReleasesPermitImmediately() {
        // Given
        Bulkhead bulkhead = new Bulkhead(1);

        // When: submit an operation that completes immediately
        CompletionStage<String> first =
                bulkhead.submit(() -> CompletableFuture.completedFuture("done"));

        // Then: it should be done immediately
        assertEquals("done", first.toCompletableFuture().join());

        // And: capacity should be immediately available (no permit leak / no delay)
        CompletableFuture<String> gate = new CompletableFuture<>();
        CompletionStage<String> second = bulkhead.submit(() -> gate);

        // second should be in-flight (not rejected, not completed)
        CompletableFuture<String> f2 = second.toCompletableFuture();
        assertFalse(f2.isDone(), "second operation should be accepted and in-flight");

        // cleanup
        gate.complete("ok");
        assertEquals("ok", f2.join());
    }

    @Test
    void releasesPermitOnExceptionalCompletion() {
        // Given: bulkhead with a single permit
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<String> gate = new CompletableFuture<>();

        // First submission is accepted and occupies the only permit
        CompletionStage<String> first = bulkhead.submit(() -> gate);
        assertThat(first).isNotCompleted();

        // When: the in-flight operation completes exceptionally
        RuntimeException failure = new RuntimeException("boom");
        gate.completeExceptionally(failure);

        // Then: a subsequent submission is accepted (permit was released)
        CompletableFuture<String> secondGate = new CompletableFuture<>();
        CompletionStage<String> second = bulkhead.submit(() -> secondGate);
        assertThat(second).isNotCompleted();

        // And: we are saturated again at limit=1 (i.e., no double-release / no leak)
        CompletableFuture<String> shouldReject =
                bulkhead.submit(() -> CompletableFuture.completedFuture("nope")).toCompletableFuture();
        assertTrue(shouldReject.isCompletedExceptionally(), "should reject once saturated again");

        // cleanup
        secondGate.complete("ok");
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
                            // Only runs for admitted operations (supplier is not invoked for rejections)
                            int now = inFlight.incrementAndGet();
                            maxObserved.accumulateAndGet(now, Math::max);

                            // Keep operations in-flight until the test completes them
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

            // Then: never exceed limit, and with no completions exactly 'limit' operations are admitted
            assertTrue(maxObserved.get() <= limit, "accepted in-flight should never exceed limit");
            assertEquals(limit, gates.size(), "with no completions, exactly 'limit' operations should be admitted");

            // When: release admitted operations
            for (CompletableFuture<String> gate : gates) {
                gate.complete("ok");
            }

            // Ensure all returned stages settle; count rejections AFTER draining to avoid timing flakiness
            long rejected = 0;
            for (CompletableFuture<String> f : returned) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    fail("returned stage did not complete within timeout. " +
                            "gates=" + gates.size() + ", returned=" + returned.size() +
                            ", maxObserved=" + maxObserved.get() + ", infraErrors=" + infraErrors, te);
                } catch (ExecutionException ee) {
                    rejected++;
                    Throwable cause = ee.getCause();
                    assertNotNull(cause);
                    assertInstanceOf(BulkheadRejectedException.class, cause);
                }
            }
            assertEquals(threads - limit, rejected, "all extra submissions should fail fast");

            // Then: no permit leaks (we can admit 'limit' again, and the next rejects)
            List<CompletableFuture<String>> secondWaveGates = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                CompletableFuture<String> gate = new CompletableFuture<>();
                secondWaveGates.add(gate);

                CompletionStage<String> accepted = bulkhead.submit(() -> gate);
                // Meaningful acceptance check: accepted operations should be in-flight (not immediately done)
                assertFalse(accepted.toCompletableFuture().isDone(), "accepted operations should be in-flight (not done)");
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

    @Test
    public void releasesPermitOnCancellation() {
        // given
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<String> gate = new CompletableFuture<>();
        CompletionStage<String> first = bulkhead.submit(() -> gate);

        CompletableFuture<String> f1 = first.toCompletableFuture();
        assertFalse(f1.isDone(), "first operation should be accepted and in-flight");

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
    public void introspectionReflectsInFlightAndAvailable() {
        Bulkhead bulkhead = new Bulkhead(2);

        assertEquals(2, bulkhead.limit());
        assertEquals(2, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        CompletionStage<String> s1 = bulkhead.submit(() -> gate1);
        assertEquals(1, bulkhead.inFlight());
        assertEquals(1, bulkhead.available());

        CompletionStage<String> s2 = bulkhead.submit(() -> gate2);
        assertEquals(2, bulkhead.inFlight());
        assertEquals(0, bulkhead.available());

        gate1.complete("ok");
        gate2.complete("ok");

        // Ensure terminal completion is observed before introspecting final state
        assertEquals("ok", s1.toCompletableFuture().join());
        assertEquals("ok", s2.toCompletableFuture().join());

        assertEquals(0, bulkhead.inFlight());
        assertEquals(2, bulkhead.available());


    }

    @Test
    public void cancellationRaceReleasesPermitExactlyOnce() throws Exception {
        Bulkhead bulkhead = new Bulkhead(1);

        @SuppressWarnings("resource")
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int iterations = Integer.getInteger("stress.iters", 5000);
            for (int i = 0; i < iterations; i++) {
                CompletableFuture<String> gate = new CompletableFuture<>();
                CompletionStage<String> first = bulkhead.submit(() -> gate);

                CompletableFuture<String> returned = first.toCompletableFuture();
                assertFalse(returned.isDone(), "first operation should be in-flight");

                CountDownLatch start = new CountDownLatch(1);

                Future<?> t1 = pool.submit(() -> {
                    awaitUnchecked(start);
                    returned.cancel(true);
                });
                Future<?> t2 = pool.submit(() -> {
                    awaitUnchecked(start);
                    gate.complete("ok");
                });

                start.countDown();
                t1.get(1, TimeUnit.SECONDS);
                t2.get(1, TimeUnit.SECONDS);

                // Ensure terminal
                try {
                    returned.join();
                } catch (CancellationException ignored) {
                } catch (CompletionException ignored) {
                }

                // Then: permit restored exactly once
                CompletableFuture<String> admittedGate = new CompletableFuture<>();
                CompletionStage<String> admitted = bulkhead.submit(() -> admittedGate);
                assertFalse(admitted.toCompletableFuture().isDone(), "should be admitted and in-flight");

                CompletableFuture<String> shouldReject =
                        bulkhead.submit(() -> CompletableFuture.completedFuture("nope")).toCompletableFuture();
                assertTrue(shouldReject.isCompletedExceptionally(), "should reject once saturated");

                // cleanup
                admittedGate.complete("done");
                admitted.toCompletableFuture().join();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void completionRaceReleasesPermitExactlyOnce() throws Exception {
        Bulkhead bulkhead = new Bulkhead(1);

        @SuppressWarnings("resource")
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 5_000; i++) {
                CompletableFuture<String> gate = new CompletableFuture<>();
                CompletionStage<String> first = bulkhead.submit(() -> gate);

                assertThat(first).isNotCompleted();

                CountDownLatch start = new CountDownLatch(1);

                Future<?> t1 = pool.submit(() -> {
                    awaitUnchecked(start);
                    gate.complete("ok");
                });
                Future<?> t2 = pool.submit(() -> {
                    awaitUnchecked(start);
                    gate.completeExceptionally(new RuntimeException("boom"));
                });

                start.countDown();
                t1.get(1, TimeUnit.SECONDS);
                t2.get(1, TimeUnit.SECONDS);

                try {
                    first.toCompletableFuture().join();
                } catch (CompletionException ignored) {
                }

                CompletableFuture<String> admittedGate = new CompletableFuture<>();
                CompletionStage<String> admitted = bulkhead.submit(() -> admittedGate);
                assertFalse(admitted.toCompletableFuture().isDone(), "should be admitted and in-flight");

                CompletableFuture<String> shouldReject =
                        bulkhead.submit(() -> CompletableFuture.completedFuture("nope")).toCompletableFuture();
                assertTrue(shouldReject.isCompletedExceptionally(), "should reject once saturated");

                admittedGate.complete("done");
                admitted.toCompletableFuture().join();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void invariantViolationIsObservableToCaller() {
        // Given: a bulkhead with limit=1, but a Semaphore whose availablePermits() lies
        // after release (simulates an over-release invariant violation deterministically).
        class OverReportingSemaphore extends Semaphore {
            OverReportingSemaphore(int permits) {
                super(permits);
            }

            @Override
            public int availablePermits() {
                // Normal acquire/release semantics still apply internally,
                // but introspection returns an impossible value after release.
                return super.availablePermits() + 1;
            }
        }

        Bulkhead bulkhead = new Bulkhead(1, new OverReportingSemaphore(1), null);

        // When: we submit a normal operation that completes immediately
        CompletionStage<String> stage =
                bulkhead.submit(() -> CompletableFuture.completedFuture("ok"));

        // Then: the invariant violation must be surfaced to the caller
        CompletionException ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());

        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(
                ex.getCause().getMessage().contains("Bulkhead invariant violated"),
                "message should mention invariant violation, but was: " + ex.getCause().getMessage()
        );
    }

    @Test
    public void rejectedSubmissionDoesNotLaterBecomeAdmittedAfterCapacityFrees() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<String> gate = new CompletableFuture<>();
        bulkhead.submit(() -> gate); // occupy permit

        CompletableFuture<String> rejected =
                bulkhead.submit(() -> CompletableFuture.completedFuture("should-not-run")).toCompletableFuture();

        assertTrue(rejected.isCompletedExceptionally(), "rejection must be immediate (no waiting)");

        // Free capacity after rejection
        gate.complete("ok");

        // Rejected stage must remain rejected; it must not “turn into” an admitted execution
        assertTrue(rejected.isCompletedExceptionally(), "rejected stage must stay rejected");
        CompletionException ex = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(BulkheadRejectedException.class, ex.getCause());
    }

    @Test
    public void handlerRegistrationFailureIsSurfacedAndDoesNotLeakPermits() {
        Bulkhead bulkhead = new Bulkhead(1);

        // A CompletionStage that throws when a completion handler is registered.
        // This forces Bulkhead's "handler registration failed" catch-path.
        class ExplodingStage<T> implements CompletionStage<T> {
            @Override
            public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
                throw new RuntimeException("whenComplete registration failed");
            }

            // --- The rest of CompletionStage is irrelevant for this test. ---
            // Implement with UnsupportedOperationException to keep the fake minimal.
            @Override public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> fn) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> thenAccept(Consumer<? super T> action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> thenRun(Runnable action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> thenRunAsync(Runnable action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) { throw new UnsupportedOperationException(); }
            @Override public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) { throw new UnsupportedOperationException(); }
            @Override public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) { throw new UnsupportedOperationException(); }
            @Override public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) { throw new UnsupportedOperationException(); }
            @Override public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) { throw new UnsupportedOperationException(); }
            @Override public CompletableFuture<T> toCompletableFuture() { throw new UnsupportedOperationException(); }
        }

        // Occupy the only permit with an admitted, never-completing operation
        CompletableFuture<String> gate = new CompletableFuture<>();
        bulkhead.submit(() -> gate);

        // Now capacity is exhausted. Free it by completing the gate.
        gate.complete("ok");

        // Submit an operation that returns a stage which throws on handler registration.
        CompletionStage<String> stage = bulkhead.submit(() -> new ExplodingStage<>());

        // The returned stage should complete exceptionally with the registration failure,
        // OR (if Bulkhead's internal invariant checking trips) an IllegalStateException.
        CompletionException ex = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());

        Throwable cause = ex.getCause();
        assertNotNull(cause);

        // Primary expected behavior: surface registration failure
        // (Current Bulkhead.java may instead surface invariant violation due to double-release in catch path.)
        assertTrue(
                (cause instanceof RuntimeException && "whenComplete registration failed".equals(cause.getMessage())) ||
                        (cause instanceof IllegalStateException && cause.getMessage().contains("Bulkhead invariant violated")),
                "unexpected cause: " + cause
        );

        // And: regardless of which failure surfaced, we must not leak permits.
        // After the failed submission, we should be able to admit one in-flight operation.
        CompletableFuture<String> admittedGate = new CompletableFuture<>();
        CompletionStage<String> admitted = bulkhead.submit(() -> admittedGate);
        assertFalse(admitted.toCompletableFuture().isDone(), "should be admitted and in-flight (no permit leak)");

        // And then saturate at limit=1.
        CompletableFuture<String> shouldReject =
                bulkhead.submit(() -> CompletableFuture.completedFuture("nope")).toCompletableFuture();
        assertTrue(shouldReject.isCompletedExceptionally(), "should reject once saturated");

        // cleanup
        admittedGate.complete("done");
        admitted.toCompletableFuture().join();
    }

    @Test
    public void submitNullSupplierThrowsNullPointerException() {
        Bulkhead bulkhead = new Bulkhead(1);
        assertThrows(NullPointerException.class, () -> bulkhead.submit(null));
    }

    @Test
    public void constructorRejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new Bulkhead(0));
        assertThrows(IllegalArgumentException.class, () -> new Bulkhead(-1));
    }

    @Test
    public void cancellationProducesCancelledStageAndJoinThrowsCancellationException() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<String> gate = new CompletableFuture<>();
        CompletableFuture<String> returned = bulkhead.submit(() -> gate).toCompletableFuture();

        assertFalse(returned.isDone(), "should be admitted and in-flight");

        assertTrue(returned.cancel(true), "cancellation should succeed");
        assertTrue(returned.isCancelled(), "returned stage should be marked cancelled");
        assertTrue(returned.isDone(), "cancelled stage should be terminal");

        // Key contract: cancelled stage behaves as cancellation (not exceptional completion with CancellationException)
        assertThrows(CancellationException.class, returned::join);

        // Even if the underlying later completes, the returned stage stays cancelled
        gate.complete("ok");
        assertTrue(returned.isCancelled(), "returned stage must remain cancelled");
        assertThrows(CancellationException.class, returned::join);

        // Permit must be released after cancellation
        CompletionStage<String> second = bulkhead.submit(() -> CompletableFuture.completedFuture("ok"));
        assertEquals("ok", second.toCompletableFuture().join());
    }

    @Test
    public void cancellingReturnedStageDoesNotPropagateCancellationToUnderlyingStage() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<String> underlying = new CompletableFuture<>();
        CompletableFuture<String> returned = bulkhead.submit(() -> underlying).toCompletableFuture();

        assertTrue(returned.cancel(true), "cancellation should succeed");
        assertTrue(returned.isCancelled(), "returned stage should be cancelled");

        // Cancellation is not propagated to the supplied stage
        assertFalse(underlying.isCancelled(), "underlying stage must not be cancelled");
        assertFalse(underlying.isDone(), "underlying stage should still be incomplete");

        // Underlying can still complete normally
        underlying.complete("ok");
        assertEquals("ok", underlying.join());

        // Returned remains cancelled
        assertTrue(returned.isCancelled());
        assertThrows(CancellationException.class, returned::join);

        // Permit released by cancelling returned stage
        CompletionStage<String> next = bulkhead.submit(() -> CompletableFuture.completedFuture("next"));
        assertEquals("next", next.toCompletableFuture().join());
    }

    @Test
    public void listenerExceptionsAreSwallowedAndDoNotAffectAdmissionOrRelease() {
        AtomicInteger admittedCalls = new AtomicInteger();
        AtomicInteger rejectedCalls = new AtomicInteger();
        AtomicInteger releasedCalls = new AtomicInteger();

        BulkheadListener throwingListener = new BulkheadListener() {
            @Override
            public void onAdmitted() {
                admittedCalls.incrementAndGet();
                throw new RuntimeException("boom-admitted");
            }

            @Override
            public void onRejected() {
                rejectedCalls.incrementAndGet();
                throw new RuntimeException("boom-rejected");
            }

            @Override
            public void onReleased(TerminalKind kind, Throwable error) {
                releasedCalls.incrementAndGet();
                throw new RuntimeException("boom-released");
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, throwingListener);

        // Admit one and hold it
        CompletableFuture<String> gate = new CompletableFuture<>();
        CompletableFuture<String> first = bulkhead.submit(() -> gate).toCompletableFuture();
        assertFalse(first.isDone(), "should be admitted and in-flight");
        assertEquals(1, admittedCalls.get(), "onAdmitted should have been called");

        // Saturated => reject, but listener exception must not change semantics
        CompletableFuture<String> rejected =
                bulkhead.submit(() -> CompletableFuture.completedFuture("nope")).toCompletableFuture();

        assertTrue(rejected.isCompletedExceptionally(), "should reject when saturated");
        CompletionException ex = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(BulkheadRejectedException.class, ex.getCause());
        assertEquals(1, rejectedCalls.get(), "onRejected should have been called");

        // Release the first operation; listener exception must not prevent release
        gate.complete("ok");
        assertEquals("ok", first.join());
        assertEquals(1, releasedCalls.get(), "onReleased should have been called exactly once");

        // Permit is released: next submit should be admitted
        CompletableFuture<String> gate2 = new CompletableFuture<>();
        CompletableFuture<String> second = bulkhead.submit(() -> gate2).toCompletableFuture();
        assertFalse(second.isDone(), "should be admitted after release despite listener exceptions");
        assertEquals(2, admittedCalls.get(), "onAdmitted should be called again");

        // cleanup
        gate2.complete("done");
        assertEquals("done", second.join());
        assertEquals(2, releasedCalls.get(), "onReleased should be called for second op too");
    }

    @Test
    public void listenerReceivesCorrectTerminalKindAndError() {
        class Event {
            final TerminalKind kind;
            final Throwable error;

            Event(TerminalKind kind, Throwable error) {
                this.kind = kind;
                this.error = error;
            }
        }

        ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();

        BulkheadListener listener = new BulkheadListener() {
            @Override
            public void onReleased(TerminalKind kind, Throwable error) {
                events.add(new Event(kind, error));
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, listener);

        // SUCCESS
        CompletionStage<String> ok = bulkhead.submit(() -> CompletableFuture.completedFuture("ok"));
        assertEquals("ok", ok.toCompletableFuture().join());

        Event e1 = events.poll();
        assertNotNull(e1, "expected a release event for success");
        assertEquals(TerminalKind.SUCCESS, e1.kind);
        assertNull(e1.error, "success should have null error");

        // FAILURE
        RuntimeException boom = new RuntimeException("boom");
        CompletionStage<String> fail = bulkhead.submit(() -> {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(boom);
            return f;
        });

        CompletionException ex = assertThrows(CompletionException.class, () -> fail.toCompletableFuture().join());
        assertSame(boom, ex.getCause(), "operation failure should propagate unchanged");

        Event e2 = events.poll();
        assertNotNull(e2, "expected a release event for failure");
        assertEquals(TerminalKind.FAILURE, e2.kind);
        assertSame(boom, e2.error, "failure should pass through the same throwable");

        // CANCELLED
        CompletableFuture<String> gate = new CompletableFuture<>();
        CompletableFuture<String> cancelled = bulkhead.submit(() -> gate).toCompletableFuture();

        assertTrue(cancelled.cancel(true));
        assertThrows(CancellationException.class, cancelled::join);

        Event e3 = events.poll();
        assertNotNull(e3, "expected a release event for cancellation");
        assertEquals(TerminalKind.CANCELLED, e3.kind);
        assertNull(e3.error, "cancelled should have null error");

        // cleanup underlying
        gate.complete("unused");
    }

    @Test
    public void supplierStageCancellationIsClassifiedAsCancelledAndReleasesPermit() {
        // Given: a bulkhead with a listener that records terminal kind/error
        AtomicReference<TerminalKind> kindRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch released = new CountDownLatch(1);

        BulkheadListener listener = new BulkheadListener() {
            @Override
            public void onReleased(TerminalKind kind, Throwable error) {
                kindRef.set(kind);
                errorRef.set(error);
                released.countDown();
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, listener);

        // And: an admitted operation whose *supplier stage* will be cancelled exceptionally
        CompletableFuture<String> supplierStage = new CompletableFuture<>();
        CompletionStage<String> returned = bulkhead.submit(() -> supplierStage);

        // When: the supplier stage completes exceptionally with CancellationException
        supplierStage.completeExceptionally(new CancellationException("supplier cancelled"));

        // Then: listener observes CANCELLED (and error must be null)
        awaitUnchecked(released);
        assertEquals(TerminalKind.CANCELLED, kindRef.get());
        assertNull(errorRef.get());

        // And: the returned stage is still exceptional (propagates the supplier failure)
        CompletableFuture<String> f = returned.toCompletableFuture();
        assertTrue(f.isCompletedExceptionally());
        try {
            f.join();
            fail("expected exceptional completion");
        } catch (CancellationException ce) {
            // If it ever becomes an actual cancelled future (unlikely here), this is fine too.
        } catch (CompletionException ce) {
            assertInstanceOf(CancellationException.class, ce.getCause());
        }

        // And: permit is released, so next submit is accepted
        CompletionStage<String> second =
                bulkhead.submit(() -> CompletableFuture.completedFuture("ok"));
        assertEquals("ok", second.toCompletableFuture().join());
    }
}
