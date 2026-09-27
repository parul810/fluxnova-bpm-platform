package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies {@code X-Lifecycle-Signature: sha256=<HMAC-SHA256(body, secret)>}
 * per {@code external_agent.md} / design doc section 14. Verification is
 * skipped entirely (event accepted) only when no secret is configured -
 * matching "support webhook signature verification when a signing secret is
 * configured" from the MVP instructions, not "always require one".
 */
public class SignatureVerifier {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String SIGNATURE_PREFIX = "sha256=";

  /**
   * @param secret configured webhook secret; {@code null}/blank disables verification
   * @param rawBody the exact bytes of the request body the signature was computed over
   * @param signatureHeader value of {@code X-Lifecycle-Signature}, e.g. {@code "sha256=<hex>"}
   * @return true if verification is disabled, or the signature is present and matches
   */
  public boolean isValid(String secret, byte[] rawBody, String signatureHeader) {
    if (secret == null || secret.isBlank()) {
      return true;
    }
    if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
      return false;
    }
    String providedHex = signatureHeader.substring(SIGNATURE_PREFIX.length()).trim();
    String expectedHex = hmacHex(secret, rawBody);
    return constantTimeEquals(expectedHex, providedHex);
  }

  private String hmacHex(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] raw = mac.doFinal(body);
      return toHex(raw);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Unable to compute HMAC-SHA256 signature", e);
    }
  }

  private String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  private boolean constantTimeEquals(String expectedHex, String providedHex) {
    return MessageDigest.isEqual(
        expectedHex.getBytes(StandardCharsets.US_ASCII),
        providedHex.getBytes(StandardCharsets.US_ASCII));
  }

}
