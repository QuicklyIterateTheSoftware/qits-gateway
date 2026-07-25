package eu.wohlben.qits.gateway;

import java.util.Optional;

/**
 * One resolved entry of the route table: an inbound path prefix and the upstream it delegates to.
 *
 * <p>Deliberately a plain value type with no Quarkus or Vert.x types on it, so the matching and
 * path-rewriting rules — the part with actual edge cases — are unit-testable without booting an
 * application (see {@code RouteTableTest}).
 *
 * @param name the configuration key the route was declared under; used in logs and health output
 * @param prefix the normalised prefix: no trailing slash, and {@code ""} for the catch-all {@code
 *     /}
 * @param host upstream hostname (config only, never request-derived)
 * @param port upstream port
 * @param stripPrefix whether {@link #prefix} is removed from the path before forwarding
 * @param authority optional {@code Host} header override for the upstream
 */
public record GatewayRoute(
    String name,
    String prefix,
    String host,
    int port,
    boolean stripPrefix,
    Optional<String> authority) {

  public GatewayRoute {
    prefix = normalisePrefix(prefix);
  }

  /**
   * {@code /} ⇒ {@code ""} (matches everything), otherwise a leading slash is enforced and a
   * trailing one dropped, so {@code /api/artifacts/} and {@code api/artifacts} are the same route.
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
   * Segment-aware prefix match: {@code /api/art} must NOT capture {@code /api/artifacts/x}, or a
   * route table would silently hijack a sibling's traffic. The prefix itself ({@code
   * /api/artifacts}) and everything under it ({@code /api/artifacts/…}) match.
   */
  public boolean matches(String path) {
    if (isCatchAll()) {
      return true;
    }
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  /**
   * The path to send upstream. Verbatim unless {@link #stripPrefix} is on, in which case the prefix
   * is removed and the remainder always keeps its leading slash ({@code /api/artifacts} ⇒ {@code
   * /}, not the empty string, which is not a legal request target).
   */
  public String rewrite(String path) {
    if (!stripPrefix || isCatchAll()) {
      return path;
    }
    String rest = path.substring(prefix.length());
    return rest.isEmpty() ? "/" : rest;
  }

  /** {@code host:port}, for logs and the health check. */
  public String upstream() {
    return host + ":" + port;
  }
}
