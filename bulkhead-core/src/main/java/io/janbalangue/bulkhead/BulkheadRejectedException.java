package io.janbalangue.bulkhead;

/**
 * Exception indicating that a bulkhead rejected a task because it was saturated.
 *
 * <p>This exception is used to signal <em>intentional</em> rejection due to capacity limits,
 * not task execution failure.</p>
 *
 * <p>When this exception is observed, the associated task supplier was not invoked and
 * no user work was started.</p>
 */
public final class BulkheadRejectedException extends RuntimeException {

    /**
     * Creates a rejection exception with a message.
     *
     * @param message detail message
     */
    public BulkheadRejectedException(String message) {
        super(message);
    }

    /**
     * Creates a rejection exception with a message and cause.
     *
     * @param message detail message
     * @param cause the cause
     */
    public BulkheadRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
