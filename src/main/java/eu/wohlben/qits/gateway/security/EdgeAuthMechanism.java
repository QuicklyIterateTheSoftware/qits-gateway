package eu.wohlben.qits.gateway.security;

import eu.wohlben.qits.gateway.EdgeHeaders;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * The {@code edge} build target's authentication: the identity is what {@code qits-platform-edge}
 * already established, carried on {@code X-Qits-User}, {@code X-Qits-User-Id} and {@code
 * X-Qits-Roles}. No login happens in this process at all — the session cookie is introspected at
 * the edge, and this gateway reads the result.
 *
 * <p><b>Why trusting an inbound header is sound here, and only here.</b> The edge is the single
 * ingress: it binds the host port, it strips every {@code X-Qits-*} a client sent before it decides
 * anything, and it injects these three from the session it validated. So a header that arrives at
 * this process was written by the edge or by something already inside {@code qits-net} — and a
 * process on {@code qits-net} can dial any service directly and assert whatever it likes without
 * involving the gateway at all. This target therefore adds no exposure; it consumes the same
 * forward-auth contract the five services downstream already consume, one hop earlier. That the
 * internal network is trusted is a known, named property of the platform, not a consequence of this
 * variant.
 *
 * <p><b>No headers means anonymous, not refused.</b> Two ordinary cases produce a headerless
 * request: the edge's session gate is switched off (the rollout flag), or something on the internal
 * network dialled the gateway directly. Neither is an attack, and the edge is what refuses
 * unauthenticated browser traffic — see {@link QitsAuthPolicy}, which permits an anonymous request
 * in this target for exactly that reason.
 *
 * <p><b>SECURITY — why this is a build target and not a config flag.</b> The same reason as {@link
 * LocalAuthMechanism}: {@link IfBuildProperty} is evaluated at augmentation, so in an {@code oauth}
 * build this bean does not exist and no config source of any ordinal can bring it back. That
 * matters more here than for {@code local}, because this mechanism believes a header — a runtime
 * switch that could turn it on would be a header-forgery bypass one environment variable away.
 */
@ApplicationScoped
@IfBuildProperty(name = "qits.auth.variant", stringValue = "edge")
public class EdgeAuthMechanism implements HttpAuthenticationMechanism {

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String user = trimmedHeader(context, EdgeHeaders.USER_HEADER);
    if (user == null) {
      // Anonymous. A null item is how a mechanism says "no credential here"; Quarkus then supplies
      // the anonymous identity, and the policy decides what that is worth.
      return Uni.createFrom().nullItem();
    }
    // Through the IdentityProviderManager rather than built inline, for the same reason the local
    // target does it: SecurityIdentityAugmentors keep working, and the parsing rule lives in one
    // place (EdgeIdentityProvider) instead of in the mechanism that happens to read the headers.
    return identityProviderManager.authenticate(
        HttpSecurityUtils.setRoutingContextAttribute(
            new EdgeIdentityRequest(
                user,
                trimmedHeader(context, EdgeHeaders.USER_ID_HEADER),
                trimmedHeader(context, EdgeHeaders.ROLES_HEADER)),
            context));
  }

  /** A header's value, or {@code null} when it is absent or carries only whitespace. */
  private static String trimmedHeader(RoutingContext context, String name) {
    String value = context.request().getHeader(name);
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    // Effectively unreachable: this target's policy permits anonymous requests, so the one denial
    // left is an authenticated caller missing qits.auth.required-role, which is a 403 rather than a
    // challenge. A plain 401 if it is ever reached — this process owns no login to redirect to, and
    // the edge is where a browser gets sent to one.
    return Uni.createFrom().item(new ChallengeData(401, null, null));
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    // EdgeIdentityRequest only. TrustedAuthenticationRequest is deliberately NOT here: it is the
    // local target's credential, it carries a bare username, and accepting it would put a second
    // identity shape into a build whose whole point is that the edge is the one source of one.
    return Set.of(EdgeIdentityRequest.class);
  }
}
