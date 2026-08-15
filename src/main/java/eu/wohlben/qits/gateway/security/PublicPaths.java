package eu.wohlben.qits.gateway.security;

/** The small, explicit surface which callers may reach without an authenticated identity. */
public final class PublicPaths {

  private PublicPaths() {}

  /** Expects a normalized path; see {@link QitsAuthPolicy}. */
  public static boolean isPublic(String method, String path) {
    return gatewaysOwn(path) || mirror(path);
  }

  /**
   * Gateway endpoints needed before a browser session exists, plus the gateway's orchestrator
   * health surface. A service's own management endpoints do not inherit this exception.
   */
  private static boolean gatewaysOwn(String path) {
    return path.equals("/q")
        || path.startsWith("/q/")
        || path.startsWith("/api/auth/")
        || path.equals("/api/config.json")
        || path.equals("/main-navigation");
  }

  /**
   * The mirror is the sole anonymous service. Machine protocols use commissioned OIDC clients and
   * their system roles; they must not acquire a gateway exemption merely because they lack a user
   * session.
   */
  private static boolean mirror(String path) {
    return path.equals("/mirror") || path.startsWith("/mirror/");
  }
}
