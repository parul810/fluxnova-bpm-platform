package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

/**
 * All of these represent successful (2xx) webhook handling - the delivery
 * guarantee is "at least once", so anything the consumer has already dealt
 * with (a duplicate, a stale out-of-order event) is still a success from the
 * sender's point of view. Only {@link AgentWebhookService} throwing rejects
 * the delivery (invalid signature, unknown execution, malformed payload).
 */
public enum WebhookOutcome {

  /** New event, state transitioned normally (and the external task was completed/failed if terminal). */
  ACCEPTED,

  /** Event id already processed for this execution - no-op. */
  DUPLICATE,

  /** Execution had already reached a terminal state - late/duplicate terminal event, no-op. */
  ALREADY_TERMINAL,

  /** Event's seq is <= the last applied seq - superseded by a later event already processed, no-op. */
  STALE_EVENT_RECORDED,

  /** A seq gap was detected; event persisted and state updated, but a terminal event was NOT completed/failed
   *  on the external task - flagged for reconciliation instead, per design doc section 4/13. */
  TERMINAL_BLOCKED_BY_GAP

}
