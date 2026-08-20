package com.synanton.gpu.domain.model;

/**
 * Classifies a runtime interaction outcome for the caller's retry decision.
 *
 * <p>Classification is based on HTTP status codes and response headers from the runtime,
 * NOT on caught Java exceptions. Generic IOException/RuntimeException retry loops are forbidden.
 */
public enum RetryDisposition {

    /**
     * The runtime never accepted the request. Safe to retry with the same request_id;
     * no side effects have occurred.
     */
    NOT_ACCEPTED,

    /**
     * The runtime received and may have begun executing the request, but the outcome is unknown
     * (e.g. connection dropped after 2xx acknowledgement). Caller must call GetStatus before retrying.
     */
    ACCEPTED_UNKNOWN,

    /**
     * The runtime may have completed the request, but the response was lost.
     * Caller must call GetStatus; retrying may duplicate the operation.
     */
    COMPLETED_UNKNOWN,

    /**
     * The runtime definitively failed to execute. Do not retry without understanding the cause.
     */
    DEFINITELY_FAILED
}
