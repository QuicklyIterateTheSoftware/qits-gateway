package eu.wohlben.qits.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The gateway's single source of truth for "which component owns this path". It is built from the
 * {@link QitsService} registry: each {@code qits.gateway.proxy-hosts.<segment>} entry becomes a
 * route to that service, and nothing else becomes anything.
 *
 * <p><b>There is no catch-all.</b> A path no route claims is a 404 here, not a forward to some
 * default upstream. That used to be the qits monolith, which this deployment does not have and will
 * not run beside: qits is deployed clean, as these services and nothing else. An unrouted path is
 * therefore a configuration gap, and answering 404 is what makes it visible instead of quietly
 * shipping every unmatched request somewhere.
 *
 * <p>Resolution is <b>longest prefix wins</b>, regardless of declaration order, so adding a service
 * never depends on where it lands in a properties file.
 */
@ApplicationScoped
public class RouteTable {

  /** The upstream port assumed when a {@code proxy-hosts} value names no {@code :port}. */
  static final int DEFAULT_UPSTREAM_PORT = 8080;

  private final List<GatewayRoute> routes;

  @Inject
  public RouteTable(GatewayConfig config) {
    this(buildRoutes(config.proxyHosts()));
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

  /**
   * Resolve the configured registry into routes — framework-free, so the segment validation and the
   * {@code host[:port]} parsing are unit-testable without CDI.
   *
   * <p>Every {@code proxy-hosts} key must name a known {@link QitsService}: an unknown segment is a
   * misconfiguration (typo, or a service that does not exist) that would silently route nowhere or,
   * worse, somewhere unintended, so it fails startup loudly rather than being skipped.
   *
   * @throws IllegalArgumentException if a {@code proxy-hosts} key is not a known service, or its
   *     value has no host
   */
  static List<GatewayRoute> buildRoutes(Map<String, String> proxyHosts) {
    List<GatewayRoute> resolved = new ArrayList<>();
    for (Map.Entry<String, String> entry : proxyHosts.entrySet()) {
      QitsService service =
          QitsService.forSegment(entry.getKey())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Unknown qits service '"
                              + entry.getKey()
                              + "' in qits.gateway.proxy-hosts; known services are: "
                              + QitsService.knownSegments()));
      resolved.add(toServiceRoute(service, entry.getValue()));
    }
    return resolved;
  }

  /** Turn a {@code proxy-hosts} value ({@code host} or {@code host:port}) into a service route. */
  private static GatewayRoute toServiceRoute(QitsService service, String hostPort) {
    String value = hostPort == null ? "" : hostPort.trim();
    int colon = value.lastIndexOf(':');
    String host = colon < 0 ? value : value.substring(0, colon);
    int port =
        colon < 0 ? DEFAULT_UPSTREAM_PORT : Integer.parseInt(value.substring(colon + 1).trim());
    if (host.isEmpty()) {
      throw new IllegalArgumentException(
          "qits.gateway.proxy-hosts." + service.segment() + " has no host");
    }
    return GatewayRoute.forService(service, host, port);
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
