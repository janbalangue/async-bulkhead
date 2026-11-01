package io.janbalangue.asyncbulkhead;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hammers one bulkhead from many threads to check the guarantees that only matter under contention.
 */
@Timeout(60)
final class BulkheadStressTest {

    private static final int LIMIT = 4;
    private static final int SUBMITTERS = 16;
    private static final int WORKERS = 2;
    private static final int ROUNDS = 500;
    private static final int SUBMISSIONS_PER_THREAD = 20_000;

    @Test
    void racingSubmissionsAdmitExactlyTheLimit() throws Exception {
        ExecutorService submitters = Executors.newFixedThreadPool(SUBMITTERS);
        try {
            // Spinning threads slow each other down on a starved machine, so stop early there
            // rather than fail on the timeout.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

            // Loop to increase likelihood of exercising the race on various schedulers.
            for (int round = 0; round < ROUNDS && System.nanoTime() - deadline < 0; round++) {
                // A limit of 1 puts the whole race on the first permit; higher limits move it later.
                int limit = round % LIMIT + 1;
                Bulkhead bulkhead = new Bulkhead(limit);
                CompletableFuture<String> held = new CompletableFuture<>();
                AtomicInteger invoked = new AtomicInteger();
                AtomicInteger ready = new AtomicInteger();

                List<Future<CompletionStage<String>>> submissions = new ArrayList<>();
                for (int i = 0; i < SUBMITTERS; i++) {
                    submissions.add(submitters.submit(() -> {
                        // Spin instead of blocking, so every thread is running when the last one arrives.
                        ready.incrementAndGet();
                        while (ready.get() < SUBMITTERS) {
                            Thread.yield();
                        }

                        return bulkhead.submit(() -> {
                            invoked.incrementAndGet();
                            return held;
                        });
                    }));
                }

                int rejected = 0;
                for (Future<CompletionStage<String>> submission : submissions) {
                    if (submission.get().toCompletableFuture().isCompletedExceptionally()) {
                        rejected++;
                    }
                }

                assertEquals(SUBMITTERS - limit, rejected);
                assertEquals(limit, invoked.get());
                assertEquals(limit, bulkhead.inFlight());

                held.complete("ok");

                assertEquals(limit, bulkhead.available());
            }
        } finally {
            submitters.shutdownNow();
        }
    }

    @Test
    void mixedOutcomesStayWithinTheLimitAndReturnEveryPermit() throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();

        Bulkhead bulkhead = new Bulkhead(LIMIT, new BulkheadListener() {
            @Override public void onAdmitted() { admitted.incrementAndGet(); }
            @Override public void onRejected() { rejected.incrementAndGet(); }
            @Override public void onReleased(TerminalKind kind, Throwable error) { released.incrementAndGet(); }
        });

        AtomicInteger started = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        ExecutorService submitters = Executors.newFixedThreadPool(SUBMITTERS);
        ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        try {
            CyclicBarrier start = new CyclicBarrier(SUBMITTERS);

            List<Future<Void>> finished = new ArrayList<>();
            for (int s = 0; s < SUBMITTERS; s++) {
                finished.add(submitters.submit(() -> {
                    start.await();

                    for (int i = 0; i < SUBMISSIONS_PER_THREAD; i++) {
                        int n = i;

                        Supplier<CompletionStage<String>> work = () -> {
                            started.incrementAndGet();
                            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                            return finishWork(n, running, workers);
                        };

                        // 2, 3 and 5 share no factors, so every mix of path, cancel and outcome comes up.
                        CompletionStage<String> returned = switch (n % 3) {
                            case 0 -> bulkhead.submit(work);
                            case 1 -> bulkhead.submitOrElse(work, () -> CompletableFuture.completedFuture("fallback"));
                            default -> bulkhead.submitOrElseValue(work, () -> "fallback");
                        };

                        // The work keeps its permit, so this must not let anything else in.
                        if (n % 2 == 0) {
                            returned.toCompletableFuture().cancel(true);
                        }
                    }
                    return null;
                }));
            }

            for (Future<Void> submitter : finished) {
                submitter.get();
            }

            workers.shutdown();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "work should finish");
        } finally {
            submitters.shutdownNow();
            workers.shutdownNow();
        }

        assertEquals(LIMIT, peak.get(), "work should reach the limit and never pass it");
        assertTrue(rejected.get() > 0, "the run should saturate the bulkhead");
        assertEquals(SUBMITTERS * SUBMISSIONS_PER_THREAD, admitted.get() + rejected.get());
        assertEquals(admitted.get(), started.get(), "only admitted work should start");
        assertEquals(admitted.get(), released.get(), "every admitted operation should release once");
        assertEquals(LIMIT, bulkhead.available());
        assertEquals(0, bulkhead.inFlight());
    }

    /**
     * Ends one operation in the way {@code n} picks. Work stops counting as running just before
     * it finishes, so {@code running} can never be higher than the number of permits held.
     */
    private static CompletionStage<String> finishWork(int n, AtomicInteger running, Executor workers) {
        CompletableFuture<String> stage = new CompletableFuture<>();
        switch (n % 5) {
            case 0 -> workers.execute(() -> {
                running.decrementAndGet();
                stage.complete("ok");
            });
            case 1 -> workers.execute(() -> {
                running.decrementAndGet();
                stage.completeExceptionally(new IllegalStateException("failed"));
            });
            case 2 -> workers.execute(() -> {
                running.decrementAndGet();
                stage.cancel(true);
            });
            case 3 -> {
                running.decrementAndGet();
                stage.complete("ok");
            }
            default -> {
                running.decrementAndGet();
                throw new IllegalStateException("supplier failed");
            }
        }
        return stage;
    }
}
