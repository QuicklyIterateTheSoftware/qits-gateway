package eu.wohlben.qits.gateway.security;

import io.quarkus.arc.All;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The single authorization decision the system has, and now the only place it is made. A
 * <em>global</em> {@link HttpSecurityPolicy} ({@code name()} stays {@code null}): Quarkus mounts
 * the authentication/authorization handlers on the main Vert.x router ahead of every user route, so
 * this runs before {@code GatewayRouter}'s catch-all and therefore covers every path the gateway
 * would proxy, whatever it is and whichever upstream owns it.
 *
 * <p>Always enforcing — there is no runtime off switch. A gateway that admits an unauthenticated
 * request exists only as a build target, chosen by a build property that no environment variable
 * and no properties file can reach (see {@code LocalAuthMechanism}, {@code EdgeAuthMechanism}).
 * Denying an anonymous identity triggers the active mechanism's challenge (oauth: 302 code flow for
 * real navigations, 499 for everything else — see {@link NonNavigationRequestChecker}); denying an
 * authenticated one yields 403.
 *
 * <p><b>The {@code edge} target permits an anonymous request, and enforces a required role on every
 * request that is not anonymous.</b> Permitting is right because {@code qits-platform-edge} is the
 * single ingress and has already refused unauthenticated browser traffic — a request that arrives
 * here with no identity headers is the edge's session gate switched off, or an internal dial on
 * {@code qits-net} that never crossed the edge at all, and challenging either would take the
 * platform down rather than protect it. Enforcing is right because the forwarded roles are the real
 * ones: once the edge has said who this is, {@code qits.auth.required-role} means exactly what it
 * means in the other targets.
 *
 * <p><b>Roles stop here in the {@code oauth} and {@code local} targets.</b> {@code
 * qits.auth.required-role} is checked at this one point and no role header is emitted downstream,
 * so no service can make — or accidentally appear to make — a role decision of its own. The {@code
 * edge} target does forward the set it was given ({@code X-Qits-Roles}), because it did not mint
 * those roles and the services are the plan's eventual readers of them; nothing downstream enforces
 * one today, and a per-resource role decision is still a new design rather than an extension of
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

  /**
   * Every {@link EdgeAuthMechanism} the build recorded — empty in an {@code oauth} or {@code local}
   * build, one entry in an {@code edge} one. The same build-set introspection {@link AuthMeRoute}
   * uses, and for the same reason: {@code qits.auth.variant} is a build property, but as a
   * <em>runtime</em> config key it is overridable by any environment variable, and a policy that
   * asked the config whether to permit anonymous requests could be opened by one. The bean set is
   * decided at augmentation and cannot be moved afterwards. {@code @All List} rather than {@code
   * Instance} because it resolves to an empty list instead of throwing when the bean was
   * conditioned out.
   */
  @Inject @All List<EdgeAuthMechanism> edgeMechanism;

  @Override
  public Uni<CheckResult> checkPermission(
      RoutingContext context,
      Uni<SecurityIdentity> deferredIdentity,
      AuthorizationRequestContext requestContext) {
    // normalizedPath(): dot-segments and duplicate slashes are already collapsed, so a path like
    // /api/../git/x cannot spoof its way into a public prefix. The method rides along because no
    // caller here would have to reconstruct it; no allowlist entry reads it today.
    if (PublicPaths.isPublic(context.request().method().name(), context.normalizedPath())) {
      return CheckResult.permit();
    }
    return deferredIdentity.onItem().transform(this::decide);
  }

  private CheckResult decide(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
      // In the edge target an anonymous request is the edge's flag-off state or an internal dial,
      // and the required-role check below deliberately does not apply to it: there is no identity
      // to
      // check a role against, and refusing here would refuse the two callers this target expects to
      // see headerless. Every other target challenges.
      return edgeMechanism.isEmpty() ? CheckResult.DENY : CheckResult.PERMIT;
    }
    if (requiredRole.isPresent() && !identity.getRoles().contains(requiredRole.get())) {
      return CheckResult.DENY; // authenticated deny → 403
    }
    return CheckResult.PERMIT;
  }
}
