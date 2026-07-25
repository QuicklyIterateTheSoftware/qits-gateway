package eu.wohlben.qits.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The gateway's single source of truth for "which component owns this path" — the thing the qits
 * epic wants named once instead of re-declared in three places.
 *
 * <p>Resolution is <b>longest prefix wins</b>: {@code /api/artifacts} beats {@code /api} beats the
 * {@code /} catch-all, regardless of declaration order, so adding a more specific route never
 * depends on where it lands in a properties file. Disabled routes are dropped at construction.
 */
@ApplicationScoped
public class RouteTable {

  private final List<GatewayRoute> routes;

  @Inject
  public RouteTable(GatewayConfig config) {
    this(fromConfig(config.routes()));
  }

  private RouteTable(List<GatewayRoute> routes) {
    this.routes =
        routes.stream()
            // Longest prefix first, then by name so the order is stable for equal-length prefixes
            // (which can only happen for genuinely different paths).
            .sorted(
                Comparator.comparingInt((GatewayRoute r) -> r.prefix().length())
                    .reversed()
                    .thenComparing(GatewayRoute::name))
            .toList();
  }

  /** Test seam: build a table straight from resolved routes, with no configuration source. */
  public static RouteTable of(List<GatewayRoute> routes) {
    return new RouteTable(routes);
  }

  private static List<GatewayRoute> fromConfig(Map<String, GatewayConfig.Route> configured) {
    return configured.entrySet().stream()
        .filter(e -> e.getValue().enabled())
        .map(
            e ->
                new GatewayRoute(
                    e.getKey(),
                    e.getValue().pathPrefix(),
                    e.getValue().host(),
                    e.getValue().port(),
                    e.getValue().stripPrefix(),
                    e.getValue().authority()))
        .toList();
  }

  /** The route claiming {@code path}, or empty when nothing does (⇒ the gateway answers 404). */
  public Optional<GatewayRoute> match(String path) {
    return routes.stream().filter(r -> r.matches(path)).findFirst();
  }

  /** All active routes, longest prefix first. */
  public List<GatewayRoute> routes() {
    return routes;
  }

  public boolean isEmpty() {
    return routes.isEmpty();
  }
}
