package io.janbalangue.asyncbulkhead;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Signals that a bulkhead rejected a submission because its concurrency limit
 * was exhausted.
 *
 * <p>This exception represents an <em>intentional</em>, fail-fast overload signal.
 * It does <strong>not</strong> indicate execution failure.</p>
 *
 * <p>When this exception is observed, the associated supplier was <em>not</em>
 * invoked and no user work was started.</p>
 *
 * <p>Rejection is a normal control signal and should be handled explicitly
 * (for example: fast-fail, fallback, degradation, or redirecting work
 * elsewhere).</p>
 */
public final class BulkheadRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a rejection exception with a default diagnostic message.
     */
    public BulkheadRejectedException() {
        super("bulkhead saturated");
    }

    /**
     * Creates a rejection exception with a custom diagnostic message.
     *
     * <p>The message is intended for observability and debugging only and
     * must not be used for programmatic classification.</p>
     *
     * @param message diagnostic message
     */
    public BulkheadRejectedException(String message) {
        super(message);
    }

    /**
     * Creates a rejection exception with a custom diagnostic message and cause.
     *
     * <p>The cause is typically used for propagation through async pipelines
     * and does not indicate execution failure.</p>
     *
     * @param message diagnostic message
     * @param cause   originating cause
     */
    public BulkheadRejectedException(String message, Throwable cause) {
        super(message, cause);
    }

    private BulkheadRejectedException(
            String message,
            Throwable cause,
            boolean enableSuppression,
            boolean writableStackTrace
    ) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /**
     * Creates a stackless rejection exception for internal, high-frequency paths.
     *
     * <p>This variant avoids stack trace allocation and is intended for
     * performance-sensitive rejection paths. It is not exposed as part of
     * the public API.</p>
     */
    static BulkheadRejectedException stackless(String message) {
        return new BulkheadRejectedException(message, null, false, false);
    }

    /**
     * Unwraps common asynchronous wrapper exceptions
     * ({@link CompletionException}, {@link ExecutionException})
     * to reveal the underlying cause.
     *
     * <p>If {@code t} is not wrapped in one of these types, it is returned
     * unchanged.</p>
     *
     * @param t throwable to unwrap (may be {@code null})
     * @return the unwrapped throwable, or {@code null} if {@code t} is {@code null}
     * @since 0.4.0
     */
    public static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof CompletionException || cur instanceof ExecutionException) {
            Throwable next = cur.getCause();
            if (next == null) break;
            cur = next;
        }
        return cur;
    }

    /**
     * Returns the underlying {@code BulkheadRejectedException} if {@code t}
     * represents a bulkhead rejection, or {@code null} otherwise.
     *
     * @param t throwable to inspect (may be {@code null})
     * @return the unwrapped {@code BulkheadRejectedException}, or {@code null} if not present
     */
    public static BulkheadRejectedException unwrapRejected(Throwable t) {
        Throwable u = unwrap(t);
        return (u instanceof BulkheadRejectedException bre) ? bre : null;
    }

    /**
     * Returns {@code true} if {@code t} represents a bulkhead rejection,
     * either directly or when wrapped in common async exception types.
     *
     * @param t throwable to inspect (may be {@code null})
     * @return the unwrapped {@code BulkheadRejectedException}, or {@code null} if not present
     * @since 0.4.0
     */
    public static boolean isRejected(Throwable t) {
        return unwrapRejected(t) != null;
    }
}
