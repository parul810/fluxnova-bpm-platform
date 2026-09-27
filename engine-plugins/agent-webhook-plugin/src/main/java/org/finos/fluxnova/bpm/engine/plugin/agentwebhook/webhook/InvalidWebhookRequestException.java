package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

/**
 * Raised for a request the webhook must reject outright: bad/missing
 * signature (401), or a payload that cannot be correlated to a known
 * execution or is missing required fields (400/404). Carries the HTTP status
 * the resource layer should return.
 */
public class InvalidWebhookRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int statusCode;

  public InvalidWebhookRequestException(String message, int statusCode) {
    super(message);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }

}
