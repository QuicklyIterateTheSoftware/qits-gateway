package eu.wohlben.qits.gateway;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;

/**
 * A write to the OCI registry never leaves this process. {@code POST}, {@code PUT}, {@code PATCH}
 * and {@code DELETE} under {@code /v2} are answered {@code 403} here; {@code GET} and {@code HEAD}
 * are untouched and route exactly as before.
 *
 * <p><b>Why the gateway refuses rather than the policy.</b> No legitimate writer pushes through the
 * front door: producers inside the network dial qits-artifacts by its {@code qits-net} name, and an
 * external push goes to qits-platform-edge's registry vhost, which is where the anonymous-read /
 * Bearer-write split is made. The gateway is a browser door. Leaving the decision to {@link
 * eu.wohlben.qits.gateway.security.QitsAuthPolicy} would have made it a decision about
 * authentication, and the {@code local} build target has no authentication to make it with — a
 * gateway packaged with {@code -Dqits.variant=local} answered an anonymous blob upload {@code 202}.
 * This rule is not auth: it is a route that refuses, so both targets refuse identically.
 *
 * <p>It is also independent of the route table. A deployment with no artifacts entry has no {@code
 * /v2} route to reach, and this still answers — the refusal states what the gateway does with a
 * push, not what happens to be configured behind it.
 *
 * <p><b>403 and not 405.</b> The method is understood, and the registry itself would accept it;
 * what is missing is permission to send it <em>here</em>. The body is the OCI error envelope with
 * code {@code DENIED}, so a docker client prints the message instead of a bare status — and the
 * message names the door that does accept a push.
 *
 * <p>Matched on {@link RoutingContext#normalizedPath()}, so {@code /api/../v2/…} cannot spell its
 * way past the prefix — the same reason the auth policy matches on it.
 */
final class RegistryWriteBlock {

  /**
   * Ahead of everything that could answer a request: the SPA's static assets (1060), the proxy
   * ({@link GatewayRouter#ROUTE_ORDER}) and the SPA fallback (40 000). Behind {@link
   * EdgeCacheControl} at 0, which only installs a hook and yields.
   */
  static final int ROUTE_ORDER = 10;

  /** The OCI error envelope, which is what a registry client knows how to read. */
  static final String REFUSAL =
      "{\"errors\":[{\"code\":\"DENIED\",\"message\":"
          + "\"the gateway carries no registry writes; push to the edge registry vhost"
          + " with an idp Bearer token\"}]}";

  private RegistryWriteBlock() {}

  /** Answers a registry write, or yields. */
  static void refuseWrites(RoutingContext rc) {
    if (!refuses(rc.request().method().name(), rc.normalizedPath())) {
      rc.next();
      return;
    }
    rc.response()
        .setStatusCode(403)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
        .end(REFUSAL);
  }

  /**
   * Everything under {@code /v2} that is not a read. Spelled as "not GET, not HEAD" rather than as
   * a list of write methods on purpose: the registry surface this covers is a protocol we do not
   * own, so an unlisted method must be refused rather than forwarded.
   */
  static boolean refuses(String method, String path) {
    return isRegistry(path) && !"GET".equals(method) && !"HEAD".equals(method);
  }

  /**
   * The registry root and everything under it — and nothing that merely starts with the letters.
   */
  private static boolean isRegistry(String path) {
    return path != null && (path.equals("/v2") || path.startsWith("/v2/"));
  }
}
