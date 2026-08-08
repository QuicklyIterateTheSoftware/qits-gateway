package eu.wohlben.qits.gateway;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.RouteConstants;
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
 * <p><b>Ordering.</b> See {@link #ROUTE_ORDER}: the proxy sits between the landing SPA's static
 * assets and the SPA's deep-link fallback, so the route table decides precedence and the SPA gets
 * only what no route claimed. Paths under {@code quarkus.http.non-application-root-path} are passed
 * to {@code next()} explicitly, so the gateway's own management surface is served locally rather
 * than proxied.
 *
 * <p><b>Security posture.</b> Upstream origins come exclusively from configuration; no request
 * component ever selects a host or port. An unmatched path makes the gateway open no connection at
 * all — it yields to whatever is layered behind it. This is the same "resolve the target from our
 * own state, never from the request" rule qits' in-process proxies follow.
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
   * <b>The route table decides precedence, and this number is how.</b> The proxy is deliberately
   * <em>not</em> last: it runs inside a window between two of the landing SPA's handlers, which is
   * what lets a configured route beat the SPA without any list of paths being kept by hand.
   *
   * <p>The orders it is wedged between, all read off the jars this project builds against (Quinoa
   * 2.8.2, Quarkus 3.34.6) rather than assumed:
   *
   * <table>
   *   <caption>Vert.x route orders on the main router, low to high</caption>
   *   <tr><th>Order</th><th>Handler</th><th>Where it is written</th></tr>
   *   <tr><td>100</td><td>{@link ConfigJsonRoute}, {@code AuthMeRoute}</td>
   *       <td>this repo — the gateway's own {@code /api} surface</td></tr>
   *   <tr><td>1060</td><td>the SPA's static assets</td>
   *       <td>Quarkus {@code GeneratedStaticResourcesProcessor}; Quinoa hands it the built bundle
   *           as {@code GeneratedStaticResourceBuildItem}s</td></tr>
   *   <tr><td>10 000</td><td>{@code RouteConstants.ROUTE_ORDER_DEFAULT}</td>
   *       <td>Quarkus — where an unordered route lands</td></tr>
   *   <tr><td><b>20 000</b></td><td><b>this route</b></td>
   *       <td>{@code RouteConstants.ROUTE_ORDER_AFTER_DEFAULT}</td></tr>
   *   <tr><td>40 000</td><td>the SPA deep-link fallback</td>
   *       <td>Quinoa {@code QuinoaRecorder.QUINOA_SPA_ROUTE_ORDER}</td></tr>
   * </table>
   *
   * <p>Both bounds are load-bearing:
   *
   * <ul>
   *   <li><b>After 1060</b>, so the SPA's own {@code /index.html}, hashed JS and CSS are served as
   *       files. A proxy ahead of that would have to know which paths are assets, which is the
   *       hand-kept list this ordering exists to delete. It is also why {@code /} never reaches
   *       this handler: the static route answers it.
   *   <li><b>Before 40 000</b>, so a path the route table claims is <em>proxied</em> rather than
   *       answered with {@code index.html} and {@code 200 text/html}. This is the whole change: the
   *       SPA fallback used to run first and {@code quarkus.quinoa.ignored-path-prefixes} had to
   *       re-state every proxied segment to hold it off — a copy of the platform's service list
   *       that silently swallowed any segment missing from it. Now the SPA is asked last and the
   *       list is back down to the gateway's own machine surface ({@code /api,/q}).
   * </ul>
   *
   * <p>{@code ROUTE_ORDER_AFTER_DEFAULT} rather than a bare number: the intent is "after everything
   * this application registers normally, before the SPA's catch-everything", and 20 000 is the name
   * Quarkus gives the first half of that. Anything in {@code (1060, 40000)} would work; a value at
   * or past 40 000 silently restores the old behaviour, which is why the bound is spelled here.
   */
  public static final int ROUTE_ORDER = RouteConstants.ROUTE_ORDER_AFTER_DEFAULT;

  private static final Logger LOG = Logger.getLogger(GatewayRouter.class);

  @Inject Vertx vertx;

  @Inject RouteTable routeTable;

  @Inject GatewayConfig config;

  @ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "/q")
  String nonApplicationRootPath;

  /**
   * One reusable proxy per route — the origin is fixed, so there is nothing to build per request.
   *
   * <p>Keyed by the route record itself, not by {@code route.name()}. A service may claim more than
   * one prefix (qits-artifacts claims {@code /artifacts} and the registry's {@code /v2}), so a name
   * is shared by several routes and keying on it would silently collapse them into whichever was
   * written last. A record's value equality makes the key exactly as unique as a route is, so the
   * mistake is not available rather than merely avoided.
   */
  private final Map<GatewayRoute, HttpProxy> proxies = new HashMap<>();

  private HttpClient client;

  /**
   * Held rather than scoped to {@link #init} because a WebSocket upgrade never reaches the
   * interceptor chain — see {@link EdgeHeaders#applyToUpgrade}. This handler has to invoke it.
   */
  private EdgeHeaders edgeHeaders;

  void init(@Observes Router router) {
    client =
        vertx.createHttpClient(
            new HttpClientOptions()
                .setKeepAlive(true)
                // Vert.x pools per ORIGIN and defaults to FIVE connections, behind an unbounded
                // wait queue. A single `docker push` opens up to five concurrent layer uploads on
                // its own (--max-concurrent-uploads defaults to 5) and each holds its connection
                // for the whole of a multi-minute transfer — so on the default, one push saturates
                // the artifacts origin and everything else routed there (blob reads served as <img>
                // srcs, `git clone`, the CI post-receive fetches) queues behind it with nothing
                // logged anywhere to say why.
                .setMaxPoolSize(64)
                // Stated rather than inherited. Zero — no client-side idle timeout — is already the
                // default and has to stay: quarkus.http.idle-timeout=1H keeps the inbound half of a
                // long exchange alive, and a timeout here would sever exactly what that setting
                // exists for: SSE channels, HMR sockets, and a slow layer push.
                .setIdleTimeout(0));
    // Nothing the interceptor does is route-specific (verbatim forwarding), so one is shared.
    edgeHeaders = new EdgeHeaders(config.forwarded());
    for (GatewayRoute route : routeTable.routes()) {
      proxies.put(
          route,
          HttpProxy.reverseProxy(client)
              .origin(route.port(), route.host())
              .addInterceptor(edgeHeaders));
    }
    // Order 0: before every document handler (the SPA's static assets at 1060, its fallback at
    // 40_000, the proxy above), so the headers-end hook exists whichever of them answers.
    router.route().order(0).handler(HtmlCacheControl::install);
    router.route().order(ROUTE_ORDER).handler(this::handle);
  }

  void logTable(@Observes StartupEvent ignored) {
    if (routeTable.isEmpty()) {
      // Not "every request 404s" any more: with no route claiming anything, every path falls
      // through to the landing SPA and answers 200. That is a quieter failure than a 404, which is
      // exactly why RouteTableHealthCheck reports NOT READY on an empty table — the readiness probe
      // is what makes an unconfigured gateway visible, not the response code a browser sees.
      LOG.warn("No routes configured — nothing is proxied and readiness will report DOWN.");
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
      // The gateway's own management surface: never proxied, whatever a route table says. It is
      // also spelled in quarkus.quinoa.ignored-path-prefixes, which is what stops the SPA fallback
      // behind this route from turning a mistyped /q path into a 200 text/html.
      rc.next();
      return;
    }

    Optional<GatewayRoute> route = routeTable.match(path);
    if (route.isEmpty()) {
      // Nothing is routed here, so this handler has no opinion and yields — it does NOT answer.
      // What answers depends on what is layered behind it, and both outcomes are intended:
      //   * a packaged gateway: Quinoa's SPA fallback (order 40_000) re-routes to index.html, so an
      //     unclaimed path is the landing page. That is the contract — the front door serves the
      //     platform's page for anything no service claimed.
      //   * a path in quarkus.quinoa.ignored-path-prefixes (/api, /q — the gateway's own machine
      //     surface), or a non-GET, or the whole test suite where Quinoa is off: nothing is behind
      //     this route, so Vert.x answers its own 404. A mistyped MACHINE path must not be handed a
      //     web page, and this is what keeps that true.
      // The gateway still opens no connection for an unmatched path; yielding is not forwarding.
      rc.next();
      return;
    }
    // Reaching here means the security policy already permitted the request, so this is the
    // authenticated identity (or an anonymous one on a public path). EdgeHeaders cannot see the
    // RoutingContext, so hand it over before the proxy takes the request.
    AssertedIdentity.record(
        rc.user() instanceof QuarkusHttpUser user ? user.getSecurityIdentity() : null);
    // A WebSocket upgrade bypasses the interceptor chain inside vertx-http-proxy, so the header
    // contract has to be applied to the inbound request before the proxy takes it. EdgeHeaders
    // still owns both halves of it; this only decides which of its two entry points applies.
    if (EdgeHeaders.isWebSocketUpgrade(rc.request())) {
      edgeHeaders.applyToUpgrade(rc.request());
    }
    proxies.get(route.get()).handle(rc.request());
  }
}
