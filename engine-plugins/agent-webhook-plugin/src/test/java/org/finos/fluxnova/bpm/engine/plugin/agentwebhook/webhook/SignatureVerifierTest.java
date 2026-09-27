package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {

  private final SignatureVerifier verifier = new SignatureVerifier();

  @Test
  void acceptsCorrectSignature() throws Exception {
    byte[] body = "{\"id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);
    String secret = "topsecret";
    String header = "sha256=" + hmacHex(secret, body);

    assertThat(verifier.isValid(secret, body, header)).isTrue();
  }

  @Test
  void rejectsTamperedBody() throws Exception {
    byte[] originalBody = "{\"id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);
    String secret = "topsecret";
    String header = "sha256=" + hmacHex(secret, originalBody);

    byte[] tamperedBody = "{\"id\":\"evt-2\"}".getBytes(StandardCharsets.UTF_8);
    assertThat(verifier.isValid(secret, tamperedBody, header)).isFalse();
  }

  @Test
  void rejectsWrongSecret() throws Exception {
    byte[] body = "{\"id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);
    String header = "sha256=" + hmacHex("topsecret", body);

    assertThat(verifier.isValid("wrongsecret", body, header)).isFalse();
  }

  @Test
  void rejectsMissingHeaderWhenSecretConfigured() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertThat(verifier.isValid("topsecret", body, null)).isFalse();
  }

  @Test
  void rejectsHeaderWithoutPrefix() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertThat(verifier.isValid("topsecret", body, "deadbeef")).isFalse();
  }

  @Test
  void skipsVerificationWhenNoSecretConfigured() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertThat(verifier.isValid(null, body, null)).isTrue();
    assertThat(verifier.isValid("", body, "sha256=garbage")).isTrue();
  }

  private String hmacHex(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] raw = mac.doFinal(body);
    StringBuilder sb = new StringBuilder();
    for (byte b : raw) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

}
