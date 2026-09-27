package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.webhook;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /agent-webhook/events}. Plain JAX-RS (not Spring MVC) so it can
 * be mounted the same way regardless of deployment flavor.
 *
 * <p>JAX-RS instantiates resource classes via their no-arg constructor, so
 * there's no constructor-injection seam here; {@link AgentWebhookProcessEnginePlugin}
 * sets the shared {@link AgentWebhookService} once at engine startup via
 * {@link #setService(AgentWebhookService)}. This assumes a single process
 * engine per JVM, which holds for this MVP's target deployment; a multi-engine
 * host would need a per-engine-name lookup instead - noted as a follow-up in
 * the module README.
 *
 * <p>To mount this resource, register {@code AgentWebhookResource.class} with
 * the deployment's JAX-RS application (e.g. by overriding
 * {@code FluxnovaJerseyResourceConfig#registerAdditionalResources()} in a
 * Spring Boot deployment - see the module README).
 */
@Path("/agent-webhook")
public class AgentWebhookResource {

  private static final Logger LOG = LoggerFactory.getLogger(AgentWebhookResource.class);

  private static volatile AgentWebhookService service;

  public static void setService(AgentWebhookService agentWebhookService) {
    service = agentWebhookService;
  }

  @POST
  @Path("/events")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response handleEvent(byte[] rawBody, @HeaderParam("X-Lifecycle-Signature") String signatureHeader) {
    AgentWebhookService current = service;
    if (current == null) {
      return jsonResponse(503, "agent-webhook-plugin is not initialized");
    }
    try {
      WebhookOutcome outcome = current.handleEvent(rawBody, signatureHeader);
      return Response.ok("{\"status\":\"" + outcome.name() + "\"}").build();
    } catch (InvalidWebhookRequestException e) {
      LOG.info("Rejecting webhook delivery: {}", e.getMessage());
      return jsonResponse(e.getStatusCode(), e.getMessage());
    }
  }

  @GET
  @Path("/executions")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getExecutions(@QueryParam("processInstanceId") String processInstanceId) {
    AgentWebhookService current = service;
    if (current == null) {
      return jsonResponse(503, "agent-webhook-plugin is not initialized");
    }
    if (processInstanceId == null || processInstanceId.isBlank()) {
      return jsonResponse(400, "Missing required query param 'processInstanceId'");
    }
    List<AgentExecutionStatusDto> executions = current.listByProcessInstance(processInstanceId);
    return Response.ok(executions).build();
  }

  private Response jsonResponse(int status, String message) {
    return Response.status(status).entity("{\"error\":\"" + escapeJson(message) + "\"}").build();
  }

  private String escapeJson(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

}
