package io.janbalangue.bulkhead;

/**
 * Exception indicating that a {@link Bulkhead} rejected a submission because it
 * was saturated.
 *
 * <p>This exception is thrown <em>synchronously</em> by
 * {@link Bulkhead#submit(Supplier)} when the number of in-flight tasks has
 * reached the configured limit.</p>
 *
 * <p>A rejection means that:
 * <ul>
 *   <li>no permit was acquired</li>
 *   <li>the supplied task was not invoked</li>
 *   <li>no work was started</li>
 * </ul>
 * </p>
 *
 * <p>This exception represents an explicit overload signal. It is the only
 * rejection-related exception used by the bulkhead in v0.1.</p>
 *
 * @since 0.1.0
 */
public final class BulkheadRejectedException extends RuntimeException {

    public BulkheadRejectedException(String message) {
        super(message);
    }

    public BulkheadRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
