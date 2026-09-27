package org.finos.fluxnova.bpm.engine.plugin.agentwebhook.invoker;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;

import org.finos.fluxnova.bpm.engine.plugin.agentwebhook.AgentWebhookProperties;

/**
 * Builds the {@link AgentEndpoint} for one external task. Each value comes from
 * the task's extension properties when set (so the modeler can configure an
 * agent per task, the way a service task carries its own URL) and otherwise
 * from the plugin's defaults.
 *
 * <p>Because a model author can now choose the URL, it is validated here and,
 * when {@code allowedAgentHosts} is configured, restricted to those hosts.
 */
public class AgentEndpointResolver {

  public static final String PROP_BASE_URL = "agent.baseUrl";
  public static final String PROP_APP_NAME = "agent.appName";
  public static final String PROP_USER_ID = "agent.userId";
  public static final String PROP_PROTOCOL = "agent.protocol";

  public static final String DEFAULT_PROTOCOL = "adk";

  private final AgentWebhookProperties defaults;

  public AgentEndpointResolver(AgentWebhookProperties defaults) {
    this.defaults = defaults;
  }

  public AgentEndpoint resolve(Map<String, String> extensionProperties) {
    Map<String, String> ext = extensionProperties == null ? Map.of() : extensionProperties;

    String baseUrl = stripTrailingSlash(firstNonBlank(ext.get(PROP_BASE_URL), defaults.getAdkBaseUrl()));
    String appName = firstNonBlank(ext.get(PROP_APP_NAME), defaults.getAppName());
    String userId = firstNonBlank(ext.get(PROP_USER_ID), defaults.getUserId());
    String protocol = firstNonBlank(ext.get(PROP_PROTOCOL), DEFAULT_PROTOCOL).toLowerCase(Locale.ROOT);

    validateBaseUrl(baseUrl);
    return new AgentEndpoint(baseUrl, appName, userId, protocol);
  }

  private void validateBaseUrl(String baseUrl) {
    URI uri;
    try {
      uri = new URI(baseUrl);
    } catch (URISyntaxException e) {
      throw new AgentInvocationException("Agent base URL '" + baseUrl + "' is not a valid URL");
    }
    String scheme = uri.getScheme();
    if (uri.getHost() == null || scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new AgentInvocationException("Agent base URL '" + baseUrl + "' must be an http(s) URL with a host");
    }
    if (!defaults.getAllowedAgentHosts().isEmpty()
        && !defaults.getAllowedAgentHosts().contains(uri.getHost().toLowerCase(Locale.ROOT))) {
      throw new AgentInvocationException("Agent host '" + uri.getHost() + "' is not in allowedAgentHosts");
    }
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return preferred != null && !preferred.isBlank() ? preferred.trim() : fallback;
  }

  private static String stripTrailingSlash(String url) {
    if (url == null) {
      return "";
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

}
