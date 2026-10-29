package com.halvorai.ingest;

/**
 * The client's counters. Every emitted event ends up in exactly one of accepted, duplicates,
 * rejected, dropped or a dropped* counter, or is still queued or spooled.
 *
 * @param emitted events passed to emit
 * @param queued events waiting to be sent
 * @param accepted events the API accepted
 * @param duplicates events the API already had
 * @param rejected events the API rejected
 * @param dropped events the API will never accept, or that could not be delivered or spooled
 * @param droppedInvalid events that failed validation
 * @param droppedDisabled events emitted while the client was disabled by a configuration error
 * @param droppedClosed events emitted after close
 * @param retries retried requests
 * @param spooled events written to the spool
 * @param replayed spooled events delivered later
 */
public record Stats(
    long emitted, long queued, long accepted, long duplicates, long rejected, long dropped,
    long droppedInvalid, long droppedDisabled, long droppedClosed, long retries, long spooled, long replayed) {}
