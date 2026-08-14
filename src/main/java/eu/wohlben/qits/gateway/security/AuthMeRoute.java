package eu.wohlben.qits.gateway.security;

import io.quarkus.arc.All;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;

/**
 * {@code GET /api/auth/me} — auth introspection for the SPA: which target this build carries and
 * who is logged in, so the shell can render the user chip and, under {@code oauth}, the sign-out
 * link. The sibling {@code /api/auth/logout} has no handler by design: quarkus-oidc intercepts its
 * configured logout path inside the authentication mechanism itself.
 *
 * <p><b>A raw Vert.x route, not JAX-RS.</b> The monolith served this from a REST controller; the
 * gateway has no REST layer and must not grow one for a single endpoint that returns two strings —
 * every extension here is native-image size, build time and reflection surface.
 *
 * <p>Public (see {@link PublicPaths}): a browser with no session has to be able to ask who it is
 * without being challenged first, or the SPA cannot render a logged-out shell at all.
 *
 * <p><b>Why the target is read off the bean set rather than a config key.</b> {@code
 * qits.auth.variant} selects the build, but as a <em>runtime</em> config property it is overridable
 * by any environment variable — and a deployment that answered "local" while running an {@code
 * oauth} build, or the reverse, would be lying to the SPA about whether a login exists. Which
 * mechanism bean exists — {@link LocalAuthMechanism}, {@link EdgeAuthMechanism} or neither, which
 * is {@code oauth} — is decided at augmentation and cannot be moved afterwards, so asking the
 * container what was actually built in gives an answer that cannot drift from the build.
 */
@ApplicationScoped
public class AuthMeRoute {

  /** Ahead of {@code GatewayRouter}'s catch-all, so this path is served here, never proxied. */
  static final int ROUTE_ORDER = 100;

  static final String PATH = "/api/auth/me";

  /**
   * Every {@link LocalAuthMechanism} the build recorded — empty in an {@code oauth} build, one
   * entry in a {@code local} one. {@code @All List} rather than {@code Instance} because it
   * resolves to an empty list instead of throwing when the bean was conditioned out.
   */
  @Inject @All List<LocalAuthMechanism> localMechanism;

  /** The same introspection for the {@code edge} target. The two lists are never both non-empty. */
  @Inject @All List<EdgeAuthMechanism> edgeMechanism;

  void register(@Observes Router router) {
    router.get(PATH).order(ROUTE_ORDER).handler(this::handle);
  }

  private void handle(RoutingContext context) {
    context
        .response()
        .putHeader("Content-Type", "application/json; charset=utf-8")
        .putHeader("Cache-Control", "no-store")
        .end(
            new JsonObject().put("variant", variant()).put("username", username(context)).encode());
  }

  private String variant() {
    if (!localMechanism.isEmpty()) {
      return "local";
    }
    // `edge` answers from the same header-derived identity username() reads below, so the SPA's
    // user chip works unchanged. It renders no sign-out link for this target either: the session is
    // the edge's, and ending it is an idp call the edge's login pages own, not this process'.
    return edgeMechanism.isEmpty() ? "oauth" : "edge";
  }

  private String username(RoutingContext context) {
    if (!(context.user() instanceof QuarkusHttpUser user)) {
      return null;
    }
    SecurityIdentity identity = user.getSecurityIdentity();
    return identity == null || identity.isAnonymous() ? null : identity.getPrincipal().getName();
  }
}
