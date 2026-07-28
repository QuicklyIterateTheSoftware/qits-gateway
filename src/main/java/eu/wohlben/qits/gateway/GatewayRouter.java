package eu.wohlben.qits.gateway;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The front door itself: one catch-all Vert.x route that resolves the owning component from the
 * {@link RouteTable} and streams the exchange to it verbatim.
 *
 * <p><b>Why a raw route and not JAX-RS.</b> The gateway must not interpret bodies — a REST layer
 * would buffer and re-encode what should pass through untouched. {@code vertx-http-proxy} streams
 * request and response bodies and forwards WebSocket upgrades by default, which is what keeps qits'
 * SSE channels, the git smart-HTTP protocol, dev-server HMR sockets and large artifact uploads
 * working through the hub.
 *
 * <p><b>Ordering.</b> The route is registered last ({@link #ROUTE_ORDER}) so the gateway's own
 * endpoints — health under {@code quarkus.http.non-application-root-path} — are served locally
 * rather than proxied, even when a {@code /} catch-all route exists. Paths under that root are
 * additionally passed to {@code next()} explicitly, so a management path with no handler 404s here
 * instead of leaking to an upstream.
 *
 * <p><b>Security posture.</b> Upstream origins come exclusively from configuration; no request
 * component ever selects a host or port. An unmatched path is answered with 404 by the gateway,
 * which never opens a connection. This is the same "resolve the target from our own state, never
 * from the request" rule qits' in-process proxies follow.
 *
 * <p>Authentication has already happened by the time this handler runs — Quarkus mounts the
 * security handlers on the main router ahead of every user route, so an unauthorized request is
 * challenged before it can select an upstream. What is left to do here is hand the resulting
 * identity to {@link EdgeHeaders}, which is the only component that writes it onto the outbound
 * request. See {@link AssertedIdentity} for why that hand-off looks the way it does.
 */
@ApplicationScoped
public class GatewayRouter {

  /**
   * Late enough to sit behind every framework-registered route (Quarkus' static-resource route sits
   * at 10_000), so the gateway is the fallback for everything nothing else claimed.
   */
  public static final int ROUTE_ORDER = Integer.MAX_VALUE - 1000;

  private static final Logger LOG = Logger.getLogger(GatewayRouter.class);

  @Inject Vertx vertx;

  @Inject RouteTable routeTable;

  @Inject GatewayConfig config;

  @ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "/q")
  String nonApplicationRootPath;

  /**
   * One reusable proxy per route — the origin is fixed, so there is nothing to build per request.
   */
  private final Map<String, HttpProxy> proxies = new HashMap<>();

  private HttpClient client;

  void init(@Observes Router router) {
    client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true));
    // Nothing the interceptor does is route-specific (verbatim forwarding), so one is shared.
    EdgeHeaders edgeHeaders = new EdgeHeaders(config.forwarded());
    for (GatewayRoute route : routeTable.routes()) {
      proxies.put(
          route.name(),
          HttpProxy.reverseProxy(client)
              .origin(route.port(), route.host())
              .addInterceptor(edgeHeaders));
    }
    router.route().order(ROUTE_ORDER).handler(this::handle);
  }

  void logTable(@Observes StartupEvent ignored) {
    if (routeTable.isEmpty()) {
      LOG.warn("No routes configured — every request will be answered with 404.");
      return;
    }
    routeTable
        .routes()
        .forEach(
            r -> LOG.infof("route %-14s %-16s -> %s", r.name(), r.prefix() + "/*", r.upstream()));
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    if (path.equals(nonApplicationRootPath) || path.startsWith(nonApplicationRootPath + "/")) {
      // The gateway's own management surface: never proxied, even under a `/` catch-all.
      rc.next();
      return;
    }

    Optional<GatewayRoute> route = routeTable.match(path);
    if (route.isEmpty()) {
      rc.response()
          .setStatusCode(404)
          .putHeader("Content-Type", "text/plain; charset=utf-8")
          .end("No qits component is routed here.\n");
      return;
    }
    // Reaching here means the security policy already permitted the request, so this is the
    // authenticated identity (or an anonymous one on a public path). EdgeHeaders cannot see the
    // RoutingContext, so hand it over before the proxy takes the request.
    AssertedIdentity.record(
        rc.user() instanceof QuarkusHttpUser user ? user.getSecurityIdentity() : null);
    proxies.get(route.get().name()).handle(rc.request());
  }
}
