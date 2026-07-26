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
 * @param name the service segment (or {@code "qits"} for the catch-all); used in logs and health
 * @param prefix the normalised prefix: no trailing slash, and {@code ""} for the catch-all {@code
 *     /}
 * @param host upstream hostname (config only, never request-derived)
 * @param port upstream port
 */
public record GatewayRoute(String name, String prefix, String host, int port) {

  public GatewayRoute {
    prefix = normalisePrefix(prefix);
  }

  /** The route for a service: it claims {@code /<segment>} and forwards to the configured host. */
  public static GatewayRoute forService(QitsService service, String host, int port) {
    return new GatewayRoute(service.segment(), service.pathPrefix(), host, port);
  }

  /** The catch-all route to the qits monolith: claims {@code /}, matches everything unclaimed. */
  public static GatewayRoute catchAll(String host, int port) {
    return new GatewayRoute("qits", "/", host, port);
  }

  /**
   * {@code /} ⇒ {@code ""} (matches everything), otherwise a leading slash is enforced and a
   * trailing one dropped, so {@code /artifacts/} and {@code artifacts} are the same route.
   */
  static String normalisePrefix(String raw) {
    String p = raw == null ? "" : raw.trim();
    if (!p.startsWith("/")) {
      p = "/" + p;
    }
    while (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    return p.equals("/") ? "" : p;
  }

  /** True for the {@code /} entry, which claims every path no other route claims. */
  public boolean isCatchAll() {
    return prefix.isEmpty();
  }

  /**
   * Segment-aware prefix match: {@code /ci} must NOT capture {@code /cicd/x}, or a route table
   * would silently hijack a sibling's traffic. The prefix itself ({@code /artifacts}) and
   * everything under it ({@code /artifacts/…}) match.
   */
  public boolean matches(String path) {
    if (isCatchAll()) {
      return true;
    }
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  /** {@code host:port}, for logs and the health check. */
  public String upstream() {
    return host + ":" + port;
  }
}
