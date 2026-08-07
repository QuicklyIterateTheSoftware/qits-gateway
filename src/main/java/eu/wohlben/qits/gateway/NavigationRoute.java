package eu.wohlben.qits.gateway;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@code GET /main-navigation} — the platform's left navigation, answered by the process that knows
 * what it routes.
 *
 * <p>It used to be a compile-time list of eight {@code {label, href}} entries inside
 * {@code @qits/ui-components}, i.e. a second source of truth for what the platform serves,
 * published as an npm package and updated by a release of that package. It lagged exactly the way a
 * copy does: {@code /platform-docs/} was routed for a while with no entry pointing at it. The
 * gateway holds the route table, so the navigation is derived from the route table and the copy is
 * gone.
 *
 * <p><b>A raw Vert.x route, not JAX-RS</b>, for the same reason as {@link ConfigJsonRoute} and
 * {@code AuthMeRoute}: this repo has no REST layer by design, and one JSON document is not worth
 * what {@code quarkus-rest} costs a binary that exists to start in ~50 ms. Registered at order 100,
 * ahead of {@link GatewayRouter}'s catch-all, so the path is served here and never forwarded.
 *
 * <h2>What it answers</h2>
 *
 * <pre>{@code
 * {"links":[{"label":"Home","href":"/"},{"label":"CI","href":"/ci/"}]}
 * }</pre>
 *
 * <p>An <b>object</b> with a {@code links} array rather than a bare array, so the gateway can grow
 * a second field later without every SPA in the platform needing a release to keep parsing this
 * one. A bare array has no room to say anything more than its elements.
 *
 * <h2>Where the links come from</h2>
 *
 * From {@link RouteTable#routes()}, not from {@link QitsService#values()}, and that is the whole
 * point of moving it here: only what is <em>actually proxied</em> appears. A service the deployment
 * did not configure has no route, so it gets no link — and the docs SPA got its entry precisely
 * when {@code platform-docs} was routed, with nothing to release and nobody to remind. The rules:
 *
 * <ul>
 *   <li><b>Home is prepended unconditionally.</b> The landing SPA is this process' own static
 *       output served by Quinoa — not a {@link QitsService}, in no route table, and never absent,
 *       because it is compiled into the binary.
 *   <li><b>One link per service, not per route.</b> qits-artifacts produces two routes ({@code
 *       /artifacts} and the extra {@code /v2}), and {@code /v2} must never appear: it is the
 *       address docker hardcodes for the OCI Distribution API, not a page a human can open.
 *   <li><b>No label, no link</b> — see {@link QitsService#navigationLabel()}.
 *   <li><b>Order is the enum's</b> {@link QitsService#navigationPosition()}, never the route
 *       table's: that one is sorted longest-prefix-first, which is a matching concern and would put
 *       the menu in an order nobody chose.
 * </ul>
 */
@ApplicationScoped
public class NavigationRoute {

  /** Ahead of {@code GatewayRouter}'s catch-all, so this path is served here, never proxied. */
  static final int ROUTE_ORDER = 100;

  static final String PATH = "/main-navigation";

  /** The landing page's label. It is the gateway's own root, so it has no service to carry it. */
  static final String HOME_LABEL = "Home";

  @Inject RouteTable routeTable;

  /**
   * One navigation entry: what a user reads, and where the anchor points.
   *
   * <p>A plain value type with no Vert.x on it, so the derivation below is unit-testable without
   * booting an application — the same rule the route table's matching follows.
   *
   * @param label the display label, from {@link QitsService#navigationLabel()}
   * @param href the address, <b>with a trailing slash</b> ({@code /ci/}). The consuming library
   *     normalises both sides when it decides which entry is current, but it renders the anchor
   *     verbatim — so the slash is what a user sees in the status bar and copies out of it.
   */
  public record Link(String label, String href) {}

  /**
   * GET <b>and HEAD</b>, on one route. This is not politeness, and {@link ConfigJsonRoute} records
   * where the lesson came from: {@code router.get()} registers a route for GET <em>only</em>, so a
   * HEAD fell past that handler and was answered instead by a static stub in the packaged bundle,
   * carrying {@code Cache-Control: public, immutable, max-age=86400} — a day-long cache hint on a
   * document that must not be held at all. Nothing ships a {@code /main-navigation} stub today, but
   * the SPA fallback behind this route answers HEAD as well, so a GET-only route here has the same
   * shape of hole. The two methods stay one route so a later edit cannot separate them.
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
    JsonArray links = new JsonArray();
    for (Link link : links(routeTable.routes())) {
      links.add(new JsonObject().put("label", link.label()).put("href", link.href()));
    }
    context
        .response()
        .putHeader("Content-Type", "application/json; charset=utf-8")
        // The route table is a DEPLOYMENT FACT: it changes when a service is added to the topology,
        // and a browser holding yesterday's copy renders a menu missing the thing it was told to
        // go and use. Same reasoning as /api/config.json, and the same header.
        .putHeader("Cache-Control", "no-store")
        .end(new JsonObject().put("links", links).encode());
  }

  /**
   * The navigation for a route table: Home, then one entry per labelled service that has a route,
   * in the enum's declared order.
   *
   * <p>Takes and returns framework-free values on purpose, so every edge case above — the {@code
   * /v2} dedupe, the unlabelled skip, an empty table — is a plain JUnit test rather than a booted
   * application (see {@code NavigationLinksTest}).
   *
   * <p>A route is mapped back to its service through {@link GatewayRoute#name()}, which <em>is</em>
   * the service segment ({@link GatewayRoute#forService}), so {@link QitsService#forSegment}
   * resolves it exactly. Nothing here parses a prefix: {@code /v2} would resolve to no service if
   * it were tried, and both of artifacts' routes carry the name {@code artifacts} — which is also
   * what makes the dedupe a plain {@code distinct()} over services rather than a rule about {@code
   * /v2}.
   */
  static List<Link> links(List<GatewayRoute> routes) {
    List<Link> links = new ArrayList<>();
    links.add(new Link(HOME_LABEL, "/"));
    routes.stream()
        .map(route -> QitsService.forSegment(route.name()))
        .flatMap(Optional::stream)
        .distinct()
        .filter(service -> service.navigationLabel().isPresent())
        .sorted(Comparator.comparingInt(QitsService::navigationPosition))
        .map(service -> new Link(service.navigationLabel().orElseThrow(), href(service)))
        .forEach(links::add);
    return List.copyOf(links);
  }

  /** {@code /ci/} — the segment prefix with a trailing slash; see {@link Link#href()}. */
  private static String href(QitsService service) {
    return service.pathPrefix() + "/";
  }
}
