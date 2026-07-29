package eu.wohlben.qits.gateway;

/**
 * One resolved entry of the route table: an inbound path prefix and the upstream it delegates to.
 * The gateway forwards verbatim, so a route is purely "which path prefix maps to which upstream" —
 * no path rewriting.
 *
 * <p>Deliberately a plain value type with no Quarkus or Vert.x types on it, so the matching rules
 * and the {@code host[:port]} parsing — the parts with actual edge cases — are unit-testable
 * without booting an application (see {@code RouteTableTest}).
 *
 * @param name the service segment; used in logs and the health check. <b>Not unique</b> — a service
 *     claiming more than one prefix produces one route per prefix, all under its name, because a
 *     service with two prefixes is still one component. The <em>prefix</em> is what identifies a
 *     route, which is why {@code GatewayRouter} keys its proxies on the record rather than the
 *     name.
 * @param prefix the normalised prefix: leading slash, no trailing slash
 * @param host upstream hostname (config only, never request-derived)
 * @param port upstream port
 */
public record GatewayRoute(String name, String prefix, String host, int port) {

  public GatewayRoute {
    prefix = normalisePrefix(prefix);
  }

  /**
   * The route for one of a service's {@link QitsService#pathPrefixes() claimed prefixes},
   * forwarding to the configured host. The prefix is passed in rather than derived, because a
   * service may claim several and they all reach the same upstream from the same configuration
   * entry.
   */
  public static GatewayRoute forService(QitsService service, String prefix, String host, int port) {
    return new GatewayRoute(service.segment(), prefix, host, port);
  }

  /**
   * A leading slash is enforced and a trailing one dropped, so {@code /artifacts/} and {@code
   * artifacts} are the same route.
   *
   * <p>An empty prefix is rejected rather than normalised. It used to mean the {@code /} catch-all
   * to the monolith; with that gone there is no upstream entitled to every unclaimed path, and
   * silently reviving one from a blank config value is exactly how a gateway starts forwarding
   * traffic nobody routed.
   */
  static String normalisePrefix(String raw) {
    String p = raw == null ? "" : raw.trim();
    if (!p.startsWith("/")) {
      p = "/" + p;
    }
    while (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    if (p.equals("/")) {
      throw new IllegalArgumentException(
          "A gateway route must claim a path prefix; '/' would be a catch-all, and there is no"
              + " longer an upstream that serves every unclaimed path.");
    }
    return p;
  }

  /**
   * Segment-aware prefix match: {@code /ci} must NOT capture {@code /cicd/x}, or a route table
   * would silently hijack a sibling's traffic. The prefix itself ({@code /artifacts}) and
   * everything under it ({@code /artifacts/…}) match. Nothing matches a path no route claims — the
   * gateway answers 404 rather than guessing.
   */
  public boolean matches(String path) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  /** {@code host:port}, for logs and the health check. */
  public String upstream() {
    return host + ":" + port;
  }
}
