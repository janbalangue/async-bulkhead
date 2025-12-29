package io.janbalangue.bulkhead;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class BulkheadTest {

    @Test
    public void acceptsUpToLimit() {
        // given
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        // when
        CompletionStage<String> s1 = bulkhead.submit(() -> gate1);
        CompletionStage<String> s2 = bulkhead.submit(() -> gate2);

        CompletableFuture<String> f1 = s1.toCompletableFuture();
        CompletableFuture<String> f2 = s2.toCompletableFuture();

        // then
        assertFalse(f1.isDone(), "first task should still be in-flight");
        assertFalse(f2.isDone(), "second task should still be in-flight");
    }

    @Test
    public void rejectsWhenSaturated() {
        // given
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        bulkhead.submit(() -> gate1);
        bulkhead.submit(() -> gate2);

        // and: a supplier to observe for invocation
        AtomicBoolean called = new AtomicBoolean(false);

        // when: third submit should fail fast
        CompletableFuture<String> rejected =
                bulkhead.submit(() -> {
                    called.set(true);
                    return CompletableFuture.completedFuture("should-not-run");
                }).toCompletableFuture();

        // then: it should immediately be completed exceptionally
        assertTrue(rejected.isDone());
        assertTrue(rejected.isCompletedExceptionally());

        // and: the supplier is never invoked
        assertFalse(called.get());

        // prove it throws when observed
        CompletionException ex = assertThrows(CompletionException.class, rejected::join);
        assertEquals(BulkheadRejectedException.class, ex.getCause().getClass());
        assertEquals("Bulkhead saturated", ex.getCause().getMessage());
    }

    @Test
    public void releasesPermitOnCompletion() {
        // given
        Bulkhead bulkhead = new Bulkhead(2);

        CompletableFuture<String> gate1 = new CompletableFuture<>();
        CompletableFuture<String> gate2 = new CompletableFuture<>();

        bulkhead.submit(() -> gate1);
        bulkhead.submit(() -> gate2);

        // when: complete one in-flight task
        gate1.complete("done");

        // then: capacity should be available
        CompletionStage<String> accepted = bulkhead.submit(() -> CompletableFuture.completedFuture("next"));

        CompletableFuture<String> result = accepted.toCompletableFuture();

        assertFalse(result.isCompletedExceptionally());
        assertTrue(result.isDone(), "completed future should propagate immediately");
        assertEquals("next", result.join());
    }
}
