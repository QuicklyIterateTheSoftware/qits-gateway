package eu.wohlben.qits.gateway;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@code GET /api/config.json} — the web components' identity relay, moved here from
 * qits-observability (`migration-path-conventions.md` §4 item 2). It is web-component
 * configuration, and the gateway is what serves the web components; it never was telemetry's, and
 * being owned by a service that may not even be deployed was the two-owner ambiguity the move
 * removes.
 *
 * <p><b>The path does not change and gets no segment.</b> {@code @qits/angular} fetches the
 * base-relative {@code api/config.json} pre-bootstrap, before the application exists to be
 * configured, so the address is fixed by a caller that cannot be told a new one. The gateway has no
 * segment of its own, which is what makes it the only component that can serve this path without
 * bending the convention.
 *
 * <p><b>A raw Vert.x route, not JAX-RS</b>, for the same reason as {@link
 * eu.wohlben.qits.gateway.security.AuthMeRoute}: this repo has no REST layer by design, and adding
 * {@code quarkus-rest} for one JSON document with two nullable sections would change what this
 * process is — native-image size, build time and reflection surface, on a binary that exists to
 * start in ~50 ms. Registered ahead of {@link GatewayRouter}'s catch-all, so it is served here and
 * never forwarded.
 *
 * <h2>What it relays</h2>
 *
 * The browser cannot read environment variables; this process can. Under a supervising qits the
 * managed container is injected with {@code OTEL_EXPORTER_OTLP_ENDPOINT}, {@code
 * OTEL_RESOURCE_ATTRIBUTES}, {@code OTEL_SERVICE_NAME} and {@code QITS_CAPTURE_ENDPOINT}; those
 * surface as the MicroProfile keys below, and the same names keep working now that the container
 * being managed is the gateway. Two independently nullable sections:
 *
 * <pre>{@code
 * { "telemetry": { "resourceAttributes": {…}, "serviceName": "webapp" } | null,
 *   "capture":   { "ingestUrl": "…",          "resourceAttributes": {…} } | null }
 * }</pre>
 *
 * <p>{@code telemetry} is null without an OTLP endpoint (SPA telemetry stays dark — the standalone
 * case), {@code capture} is null without a capture endpoint (no capture button). This relays; it
 * does not proxy, validate or stamp.
 *
 * <p><b>Why the {@code telemetry} section carries no URL</b>, which is the obvious question to have
 * about it: the endpoint is a <em>gate</em> and the section is an <em>identity</em>. Neither is an
 * address, because the browser never learns one. Telemetry takes two hops:
 *
 * <ol>
 *   <li>the browser POSTs OTLP protobuf <b>base-relative</b> to its own backend at {@code
 *       api/otel/v1/<signal>} — {@code @qits/angular} "talks only to its own backend" and carries
 *       no qits segment by design;
 *   <li>that backend's {@code OtelProxyResource} forwards it <b>server-side, byte-verbatim</b> to
 *       {@code ${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/<signal>} (404 when unconfigured).
 * </ol>
 *
 * <p>So the endpoint this class gates on is the <em>backend's</em> upstream — now {@code
 * /observability/api/otel} on qits-observability behind its gateway segment — and moving ingest
 * there is a deployment-value change, not a change to the library or to this file. Relaying the URL
 * to the browser would be relaying an address it has no use for and, on {@code qits-net}, often
 * cannot reach.
 *
 * <p>The two hops are easy to miss from here because qits is a <em>second implementer</em> of that
 * backend half and its variant is a tee rather than a proxy ({@code OtelForwarder}): qits already
 * <b>is</b> an OTLP receiver at that path, so relay and receiver were the same path in the same
 * process. Undoing exactly that conflation is what {@code migration-path-conventions.md} §4 item 2
 * is for.
 */
@ApplicationScoped
public class ConfigJsonRoute {

  /** Ahead of {@code GatewayRouter}'s catch-all, so this path is served here, never proxied. */
  static final int ROUTE_ORDER = 100;

  static final String PATH = "/api/config.json";

  @ConfigProperty(name = "otel.exporter.otlp.endpoint")
  Optional<String> otlpEndpoint;

  @ConfigProperty(name = "otel.resource.attributes")
  Optional<String> resourceAttributes;

  @ConfigProperty(name = "otel.service.name")
  Optional<String> serviceName;

  @ConfigProperty(name = "qits.capture.endpoint")
  Optional<String> captureEndpoint;

  /**
   * GET <b>and HEAD</b>. The second method is not politeness: the landing SPA ships a {@code
   * public/api/config.json} stub so a standalone {@code ng serve} has the shape
   * {@code @qits/angular} expects, and that stub lands in the packaged bundle as a static resource
   * at this very path. Route order decides between them — 100 here against Quinoa's 1060 — but
   * {@code router.get()} registers a route for GET <em>only</em>, so a HEAD used to fall past this
   * handler and be answered by the stub, with a {@code Cache-Control: public, immutable,
   * max-age=86400} that invited a client to keep it for a day. The two methods have to be one route
   * for the same reason the strip and the inject are one method in {@code EdgeHeaders}: a later
   * edit must not be able to separate them.
   */
  void register(@Observes Router router) {
    router
        .route(PATH)
        .method(io.vertx.core.http.HttpMethod.GET)
        .method(io.vertx.core.http.HttpMethod.HEAD)
        .order(ROUTE_ORDER)
        .handler(this::handle);
  }

  private void handle(RoutingContext context) {
    JsonObject body = new JsonObject();
    section(body, "telemetry", telemetry());
    section(body, "capture", capture());
    context
        .response()
        .putHeader("Content-Type", "application/json; charset=utf-8")
        .putHeader("Cache-Control", "no-store")
        .end(body.encode());
  }

  private JsonObject telemetry() {
    if (otlpEndpoint.isEmpty()) {
      return null;
    }
    return new JsonObject()
        .put("resourceAttributes", attributes())
        .put("serviceName", serviceName.orElse("webapp"));
  }

  // Carries its own copy of the resource attributes (same otel.resource.attributes source) so the
  // two sections stay independently nullable.
  private JsonObject capture() {
    if (captureEndpoint.isEmpty()) {
      return null;
    }
    return new JsonObject()
        .put("ingestUrl", captureEndpoint.get())
        .put("resourceAttributes", attributes());
  }

  /** An absent section is {@code null} in the document, not a missing key — the SPA gates on it. */
  private static void section(JsonObject body, String name, JsonObject value) {
    if (value == null) {
      body.putNull(name);
    } else {
      body.put(name, value);
    }
  }

  /** Parses the {@code OTEL_RESOURCE_ATTRIBUTES} {@code k=v,k=v} list (qits writes plain pairs). */
  private JsonObject attributes() {
    Map<String, Object> parsed = new LinkedHashMap<>();
    for (String pair : resourceAttributes.orElse("").split(",")) {
      String[] parts = pair.split("=", 2);
      if (parts.length == 2 && !parts[0].isBlank()) {
        parsed.put(parts[0].trim(), parts[1].trim());
      }
    }
    return new JsonObject(parsed);
  }
}
