package eu.wohlben.qits.gateway.security;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The {@code local} build target's "authentication": every request is authenticated as one fixed
 * local identity — no IdP, no header, no login. The deliberately open target, for trusted LOCAL
 * starts where standing up an identity provider is pointless overhead.
 *
 * <p>It exists so that the unauthenticated build stays <em>reachable</em> for testing while staying
 * <em>impossible to switch on by accident</em>, and it earns its keep beyond that: a local gateway
 * synthesizes an identity and emits the same {@code X-Qits-*} headers as an authenticated one, so
 * every downstream path is byte-identical between targets. Test and production differ in exactly
 * one component.
 *
 * <p><b>SECURITY — why this is a build target and not a config flag.</b> {@link IfBuildProperty} is
 * evaluated at augmentation and baked into the recorded bean set. In a build that did not name
 * {@code -Dqits.variant=local}, this bean <em>does not exist</em>: no environment variable, no
 * properties file, no config source of any ordinal can bring it back and open a production gateway.
 * That is the whole reason the variant is a build property rather than a runtime key, and it is why
 * this file must never grow a runtime condition.
 *
 * <p>A {@code local} gateway is <b>open</b> — anyone who can reach it is the local user. It must
 * never be internet-exposed. This is the ONLY build that runs unauthenticated; the {@code oauth}
 * target never degrades to it under any circumstances.
 */
@ApplicationScoped
@IfBuildProperty(name = "qits.auth.variant", stringValue = "local")
public class LocalAuthMechanism implements HttpAuthenticationMechanism {

  @ConfigProperty(name = "qits.auth.local.user")
  String localUser;

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    // Always the fixed local identity — through the IdentityProviderManager (not built inline) so
    // SecurityIdentityAugmentors keep working and the roles provider can attach the configured
    // roles, which is what lets qits.auth.required-role be exercised locally.
    return identityProviderManager.authenticate(
        HttpSecurityUtils.setRoutingContextAttribute(
            new TrustedAuthenticationRequest(localUser), context));
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    // Unreachable in practice (every request authenticates), but a policy that somehow denies gets
    // a plain 401 rather than a redirect — this target owns no login to send anyone to.
    return Uni.createFrom().item(new ChallengeData(401, null, null));
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of(TrustedAuthenticationRequest.class);
  }
}
