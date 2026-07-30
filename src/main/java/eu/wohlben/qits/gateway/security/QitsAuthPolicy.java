package eu.wohlben.qits.gateway.security;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The single authorization decision the system has, and now the only place it is made. A
 * <em>global</em> {@link HttpSecurityPolicy} ({@code name()} stays {@code null}): Quarkus mounts
 * the authentication/authorization handlers on the main Vert.x router ahead of every user route, so
 * this runs before {@code GatewayRouter}'s catch-all and therefore covers every path the gateway
 * would proxy, whatever it is and whichever upstream owns it.
 *
 * <p>Always enforcing — there is no runtime off switch. An unauthenticated gateway exists only as
 * the {@code local} build target, chosen by a build property that no environment variable and no
 * properties file can reach (see {@code LocalAuthMechanism}). Denying an anonymous identity
 * triggers the active mechanism's challenge (oauth: 302 code flow for real navigations, 499 for
 * everything else — see {@link NonNavigationRequestChecker}); denying an authenticated one yields
 * 403.
 *
 * <p><b>Roles stop here.</b> {@code qits.auth.required-role} is checked at this one point and no
 * groups header is emitted downstream, so no service can make — or accidentally appear to make — a
 * role decision of its own. A per-resource role decision would be a new design, not an extension of
 * this one.
 *
 * <p>The monolith's copy also stripped {@code quarkus.http.root-path} before matching, because a
 * qits running as a managed service carried a path prefix. The gateway is the front door and has no
 * root path, so that is gone rather than carried inert.
 */
@ApplicationScoped
public class QitsAuthPolicy implements HttpSecurityPolicy {

  @ConfigProperty(name = "qits.auth.required-role")
  Optional<String> requiredRole;

  @Override
  public Uni<CheckResult> checkPermission(
      RoutingContext context,
      Uni<SecurityIdentity> deferredIdentity,
      AuthorizationRequestContext requestContext) {
    // normalizedPath(): dot-segments and duplicate slashes are already collapsed, so a path like
    // /api/../git/x cannot spoof its way into a public prefix. The method rides along for the one
    // entry that is public for reads only (the registry).
    if (PublicPaths.isPublic(context.request().method().name(), context.normalizedPath())) {
      return CheckResult.permit();
    }
    return deferredIdentity.onItem().transform(this::decide);
  }

  private CheckResult decide(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
      return CheckResult.DENY; // anonymous deny → HttpAuthenticator sends the mechanism challenge
    }
    if (requiredRole.isPresent() && !identity.getRoles().contains(requiredRole.get())) {
      return CheckResult.DENY; // authenticated deny → 403
    }
    return CheckResult.PERMIT;
  }
}
