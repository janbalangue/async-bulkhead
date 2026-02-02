package io.janbalangue.asyncbulkhead;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class BulkheadTest {

    @Test
    void admitsUpToLimit() {
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> fa = new CompletableFuture<>();
        CompletableFuture<String> fb = new CompletableFuture<>();

        CompletionStage<String> a = bulkhead.submit(() -> fa);
        CompletionStage<String> b = bulkhead.submit(() -> fb);

        assertEquals(0, bulkhead.available());
        assertEquals(2, bulkhead.inFlight());

        fa.complete("a");
        fb.complete("b");

        assertEquals("a", a.toCompletableFuture().join());
        assertEquals("b", b.toCompletableFuture().join());

        assertEquals(2, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());
    }


    @Test
    void rejectsWhenSaturated() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> blocker = new CompletableFuture<>();

        bulkhead.submit(() -> blocker);

        CompletionStage<Void> rejected =
                bulkhead.submit(() -> CompletableFuture.completedFuture(null));

        Throwable t = assertThrows(ExecutionException.class,
                () -> rejected.toCompletableFuture().get());

        assertTrue(BulkheadRejectedException.isRejected(t));
    }

    @Test
    void supplierIsNotInvokedOnRejection() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> blocker = new CompletableFuture<>();
        bulkhead.submit(() -> blocker);

        AtomicBoolean invoked = new AtomicBoolean(false);

        CompletionStage<Void> rejected =
                bulkhead.submit(() -> {
                    invoked.set(true);
                    return CompletableFuture.completedFuture(null);
                });

        assertFalse(invoked.get());

        assertTrue(
                BulkheadRejectedException.isRejected(
                        assertThrows(ExecutionException.class,
                                () -> rejected.toCompletableFuture().get())
                )
        );
    }

    @Test
    void releasesPermitOnNormalCompletion() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletionStage<String> stage =
                bulkhead.submit(() -> CompletableFuture.completedFuture("ok"));

        assertEquals("ok", stage.toCompletableFuture().join());
        assertEquals(1, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());
    }

    @Test
    void releasesPermitOnExceptionalCompletion() {
        Bulkhead bulkhead = new Bulkhead(1);

        RuntimeException boom = new RuntimeException("boom");

        CompletionStage<Void> stage =
                bulkhead.submit(() -> {
                    CompletableFuture<Void> f = new CompletableFuture<>();
                    f.completeExceptionally(boom);
                    return f;
                });

        ExecutionException ex =
                assertThrows(ExecutionException.class,
                        () -> stage.toCompletableFuture().get());

        assertSame(boom, ex.getCause());
        assertEquals(1, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());
    }

    @Test
    void releasesPermitOnCancellation() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> f = new CompletableFuture<>();

        CompletionStage<Void> stage =
                bulkhead.submit(() -> f);

        assertTrue(stage.toCompletableFuture().cancel(true));

        assertEquals(1, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());
    }

    @Test
    void cancellationDoesNotCancelUnderlyingStage() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> underlying = new CompletableFuture<>();

        CompletionStage<Void> stage =
                bulkhead.submit(() -> underlying);

        stage.toCompletableFuture().cancel(true);

        assertFalse(underlying.isCancelled());
        assertFalse(underlying.isDone());

        underlying.complete(null);
    }

    @Test
    void submitOrElseRunsFallbackOnRejection() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> blocker = new CompletableFuture<>();
        bulkhead.submit(() -> blocker);

        CompletionStage<String> result =
                bulkhead.submitOrElse(
                        () -> CompletableFuture.completedFuture("primary"),
                        () -> CompletableFuture.completedFuture("fallback")
                );

        assertEquals("fallback", result.toCompletableFuture().join());
    }

    @Test
    void submitOrElseSwallowsOnAdmittedListenerExceptionsAndStillRunsPrimary() {
        AtomicBoolean admittedCalled = new AtomicBoolean(false);
        AtomicBoolean primaryInvoked = new AtomicBoolean(false);
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        BulkheadListener listener = new BulkheadListener() {
            @Override
            public void onAdmitted() {
                admittedCalled.set(true);
                throw new RuntimeException("boom admitted");
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, listener);

        CompletionStage<String> result =
                bulkhead.submitOrElse(
                        () -> {
                            primaryInvoked.set(true);
                            return CompletableFuture.completedFuture("primary");
                        },
                        () -> {
                            fallbackInvoked.set(true);
                            return CompletableFuture.completedFuture("fallback");
                        }
                );

        assertEquals("primary", result.toCompletableFuture().join());
        assertTrue(admittedCalled.get(), "onAdmitted should be invoked");
        assertTrue(primaryInvoked.get(), "primary must still be invoked even if onAdmitted throws");
        assertFalse(fallbackInvoked.get(), "fallback must not run on successful admission");

        assertEquals(1, bulkhead.available(), "permit must be released");
        assertEquals(0, bulkhead.inFlight(), "no in-flight permits after completion");
    }

    @Test
    void submitOrElseFailsWhenPrimaryReturnsNullCompletionStage() {
        AtomicBoolean primaryInvoked = new AtomicBoolean(false);
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        Bulkhead bulkhead = new Bulkhead(1);

        CompletionStage<String> result =
                bulkhead.submitOrElse(
                        () -> {
                            primaryInvoked.set(true);
                            return null; // broken supplier
                        },
                        () -> {
                            fallbackInvoked.set(true);
                            return CompletableFuture.completedFuture("fallback");
                        }
                );

        CompletionException ex =
                assertThrows(
                        CompletionException.class,
                        () -> result.toCompletableFuture().join()
                );

        assertTrue(primaryInvoked.get(), "primary must be invoked on admission");
        assertFalse(fallbackInvoked.get(), "fallback must not run on primary failure");

        // Exact exception type is intentionally not over-specified,
        // but it must not be classified as rejection.
        assertFalse(
                BulkheadRejectedException.isRejected(ex),
                "null CompletionStage is a programming error, not rejection"
        );

        assertEquals(1, bulkhead.available(), "permit must be released");
        assertEquals(0, bulkhead.inFlight(), "no permits in flight after failure");
    }

    @Test
    void submitOrElseValueReturnsFallbackValueOnRejection() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> blocker = new CompletableFuture<>();
        bulkhead.submit(() -> blocker);

        CompletionStage<String> result =
                bulkhead.submitOrElseValue(
                        () -> CompletableFuture.completedFuture("primary"),
                        () -> "fallback"
                );

        assertEquals("fallback", result.toCompletableFuture().join());
    }

    @Test
    void listenerIsCalledExactlyOncePerAdmission() {
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        BulkheadListener listener = new BulkheadListener() {
            @Override public void onAdmitted() { admitted.incrementAndGet(); }
            @Override public void onRejected() { rejected.incrementAndGet(); }
            @Override public void onReleased(TerminalKind kind, Throwable error) {
                released.incrementAndGet();
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, listener);

        CompletableFuture<Void> blocker = new CompletableFuture<>();
        bulkhead.submit(() -> blocker);

        CompletionStage<Void> rejectedStage =
                bulkhead.submit(() -> CompletableFuture.completedFuture(null));

        assertThrows(ExecutionException.class,
                () -> rejectedStage.toCompletableFuture().get());

        blocker.complete(null);

        assertEquals(1, admitted.get());
        assertEquals(1, released.get());
        assertEquals(1, rejected.get());
    }


    @Test
    void cancellationIsClassifiedAsCancelled() {
        AtomicInteger cancelled = new AtomicInteger();

        BulkheadListener listener = new BulkheadListener() {
            @Override
            public void onReleased(TerminalKind kind, Throwable error) {
                if (kind == TerminalKind.CANCELLED) cancelled.incrementAndGet();
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, listener);

        CompletionStage<Void> stage =
                bulkhead.submit(() -> new CompletableFuture<>());

        stage.toCompletableFuture().cancel(true);

        assertEquals(1, cancelled.get());
    }

    @Test
    void listenerExceptionsAreSwallowedAndDoNotAffectSemantics() {
        AtomicInteger releasedCalls = new AtomicInteger();

        BulkheadListener listener = new BulkheadListener() {
            @Override public void onAdmitted() { throw new RuntimeException("boom admitted"); }
            @Override public void onRejected() { throw new RuntimeException("boom rejected"); }
            @Override public void onReleased(TerminalKind kind, Throwable error) {
                releasedCalls.incrementAndGet();
                throw new RuntimeException("boom released");
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, listener);

        CompletableFuture<Void> blocker = new CompletableFuture<>();
        CompletionStage<Void> admitted = bulkhead.submit(() -> blocker);

        // Saturate and ensure rejection still happens (despite listener throwing).
        CompletionStage<Void> rejected = bulkhead.submit(() -> CompletableFuture.completedFuture(null));
        assertTrue(BulkheadRejectedException.isRejected(
                assertThrows(ExecutionException.class, () -> rejected.toCompletableFuture().get())
        ));

        // Completing admitted work should still release permit.
        blocker.complete(null);
        admitted.toCompletableFuture().join();

        assertEquals(1, releasedCalls.get(), "release callback should be invoked once (even if it throws)");
        assertEquals(1, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());
    }

    @Test
    void completionVsCancellationRaceReleasesExactlyOnce() throws Exception {
        AtomicInteger released = new AtomicInteger();

        BulkheadListener listener = new BulkheadListener() {
            @Override public void onReleased(TerminalKind kind, Throwable error) { released.incrementAndGet(); }
        };

        // Loop to increase likelihood of exercising the race on various schedulers.
        for (int i = 0; i < 200; i++) {
            Bulkhead bulkhead = new Bulkhead(1, listener);

            CompletableFuture<String> underlying = new CompletableFuture<>();
            CompletionStage<String> stage = bulkhead.submit(() -> underlying);
            CompletableFuture<String> returned = stage.toCompletableFuture();

            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
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

        // 200 admissions => 200 releases, exactly once per admitted operation.
        assertEquals(200, released.get());
    }

    @Test
    void submitOrElseAndSubmitOrElseValueNeverInvokePrimaryOnRejection() {
        Bulkhead bulkhead = new Bulkhead(1);

        CompletableFuture<Void> blocker = new CompletableFuture<>();
        bulkhead.submit(() -> blocker);

        AtomicBoolean primaryInvoked1 = new AtomicBoolean(false);
        CompletionStage<String> a =
                bulkhead.submitOrElse(
                        () -> {
                            primaryInvoked1.set(true);
                            return CompletableFuture.completedFuture("primary");
                        },
                        () -> CompletableFuture.completedFuture("fallback")
                );

        assertEquals("fallback", a.toCompletableFuture().join());
        assertFalse(primaryInvoked1.get(), "primary supplier must not be invoked when rejected");

        AtomicBoolean primaryInvoked2 = new AtomicBoolean(false);
        CompletionStage<String> b =
                bulkhead.submitOrElseValue(
                        () -> {
                            primaryInvoked2.set(true);
                            return CompletableFuture.completedFuture("primary");
                        },
                        () -> "fallback"
                );

        assertEquals("fallback", b.toCompletableFuture().join());
        assertFalse(primaryInvoked2.get(), "primary supplier must not be invoked when rejected");
    }

    @Test
    void rejectionClassificationHelpersHandleCommonWrapperExceptions() {
        BulkheadRejectedException base = new BulkheadRejectedException("bulkhead saturated");

        assertTrue(BulkheadRejectedException.isRejected(base));
        assertSame(base, BulkheadRejectedException.unwrapRejected(base));

        CompletionException ce = new CompletionException(base);
        assertTrue(BulkheadRejectedException.isRejected(ce));
        assertSame(base, BulkheadRejectedException.unwrapRejected(ce));

        ExecutionException ee = new ExecutionException(base);
        assertTrue(BulkheadRejectedException.isRejected(ee));
        assertSame(base, BulkheadRejectedException.unwrapRejected(ee));

        RuntimeException other = new RuntimeException("nope");
        assertFalse(BulkheadRejectedException.isRejected(other));
        assertNull(BulkheadRejectedException.unwrapRejected(other));

        assertSame(other, BulkheadRejectedException.unwrap(other));
    }

    @Test
    void failsSubmissionAndReleasesPermitIfTerminalHandlerRegistrationThrows() {
        Bulkhead bulkhead = new Bulkhead(1);

        AtomicBoolean invoked = new AtomicBoolean(false);

        CompletionStage<String> stage = bulkhead.submit(() -> {
            invoked.set(true);
            return new FailingTerminalRegistrationStage<>();
        });

        assertTrue(invoked.get(), "supplier should have been invoked (admitted) before registration failure");

        // Submission should fail because the bulkhead couldn't register terminal observation.
        assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get());

        // Capacity must be released immediately to prevent permit leaks.
        assertEquals(1, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());
    }

    @Test
    void releaseCheckedFailure_completesReturnedExceptionally_andOnReleasedHasNonNullReleaseError() throws Exception {
        AtomicBoolean onReleasedCalled = new AtomicBoolean(false);
        AtomicReference<TerminalKind> kindRef = new AtomicReference<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();

        BulkheadListener listener = new BulkheadListener() {
            @Override
            public void onReleased(TerminalKind kind, Throwable error) {
                onReleasedCalled.set(true);
                kindRef.set(kind);
                errRef.set(error);
            }
        };

        Bulkhead bulkhead = new Bulkhead(1, listener);

        CompletableFuture<String> underlying = new CompletableFuture<>();
        CompletableFuture<String> returned =
                bulkhead.submit(() -> underlying).toCompletableFuture();

        // Reflectively grab the semaphore (or access directly if test is in same package)
        Field f = Bulkhead.class.getDeclaredField("permits");
        f.setAccessible(true);
        Semaphore sem = (Semaphore) f.get(bulkhead);

        // Safe to assert BEFORE corruption
        assertEquals(0, bulkhead.available(), "permit should be consumed after admission");

        // Corrupt: after this, calling bulkhead.available()/inFlight() is expected to throw
        sem.release(2);

        // Trigger terminal path (releaseChecked should now fail and complete returned exceptionally)
        underlying.complete("ok");

        ExecutionException ex = assertThrows(ExecutionException.class, returned::get);
        assertNotNull(ex.getCause());
        assertInstanceOf(IllegalStateException.class, ex.getCause());

        assertTrue(onReleasedCalled.get(), "onReleased must be invoked even if releaseChecked() fails");
        assertEquals(TerminalKind.FAILURE, kindRef.get(), "releaseChecked failure must be FAILURE");

        Throwable releaseError = errRef.get();
        assertNotNull(releaseError, "releaseError must be non-null when releaseChecked fails");
        assertInstanceOf(IllegalStateException.class, releaseError);
    }


// -----------------------------------------------------------------------------
// TestStages (adversarial CompletionStage implementations)
//
// These helpers intentionally violate "normal" CompletionStage expectations to
// prove bulkhead invariants hold even when user-supplied stages misbehave.
// -----------------------------------------------------------------------------


    /**
     * A malicious {@link CompletionStage} that throws when terminal-observation is
     * registered via {@link #whenComplete(BiConsumer)}.
     *
     * Why this exists:
     * - Bulkhead permit accounting must NOT rely on well-behaved CompletionStages.
     * - If terminal completion observation cannot be registered, the submission must
     *   fail and capacity must be released immediately to prevent permit leaks.
     *
     * This stage delegates all other methods to an internal {@link CompletableFuture}
     * so it is usable as a drop-in adversarial stage in multiple tests.
     */
    private static final class FailingTerminalRegistrationStage<T> implements CompletionStage<T> {
        private final CompletableFuture<T> delegate = new CompletableFuture<>();

        @Override
        public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
            throw new RuntimeException("boom: terminal registration failed");
        }

        // ---- delegate everything else ----
        @Override public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> fn) { return delegate.thenApply(fn); }
        @Override public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn) { return delegate.thenApplyAsync(fn); }
        @Override public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) { return delegate.thenApplyAsync(fn, executor); }

        @Override public CompletionStage<Void> thenAccept(Consumer<? super T> action) { return delegate.thenAccept(action); }
        @Override public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) { return delegate.thenAcceptAsync(action); }
        @Override public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) { return delegate.thenAcceptAsync(action, executor); }

        @Override public CompletionStage<Void> thenRun(Runnable action) { return delegate.thenRun(action); }
        @Override public CompletionStage<Void> thenRunAsync(Runnable action) { return delegate.thenRunAsync(action); }
        @Override public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) { return delegate.thenRunAsync(action, executor); }

        @Override public <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) { return delegate.thenCombine(other, fn); }
        @Override public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) { return delegate.thenCombineAsync(other, fn); }
        @Override public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) { return delegate.thenCombineAsync(other, fn, executor); }

        @Override public <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) { return delegate.thenAcceptBoth(other, action); }
        @Override public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) { return delegate.thenAcceptBothAsync(other, action); }
        @Override public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) { return delegate.thenAcceptBothAsync(other, action, executor); }

        @Override public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) { return delegate.runAfterBoth(other, action); }
        @Override public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) { return delegate.runAfterBothAsync(other, action); }
        @Override public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) { return delegate.runAfterBothAsync(other, action, executor); }

        @Override public <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) { return delegate.applyToEither(other, fn); }
        @Override public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) { return delegate.applyToEitherAsync(other, fn); }
        @Override public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) { return delegate.applyToEitherAsync(other, fn, executor); }

        @Override public CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) { return delegate.acceptEither(other, action); }
        @Override public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) { return delegate.acceptEitherAsync(other, action); }
        @Override public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) { return delegate.acceptEitherAsync(other, action, executor); }

        @Override public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) { return delegate.runAfterEither(other, action); }
        @Override public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) { return delegate.runAfterEitherAsync(other, action); }
        @Override public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) { return delegate.runAfterEitherAsync(other, action, executor); }

        @Override public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) { return delegate.thenCompose(fn); }
        @Override public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) { return delegate.thenComposeAsync(fn); }
        @Override public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) { return delegate.thenComposeAsync(fn, executor); }

        @Override public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn) { return delegate.exceptionally(fn); }

        @Override public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) { return delegate.whenCompleteAsync(action); }
        @Override public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) { return delegate.whenCompleteAsync(action, executor); }

        @Override public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) { return delegate.handle(fn); }
        @Override public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) { return delegate.handleAsync(fn); }
        @Override public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) { return delegate.handleAsync(fn, executor); }

        @Override public CompletableFuture<T> toCompletableFuture() { return delegate; }
    }
}
