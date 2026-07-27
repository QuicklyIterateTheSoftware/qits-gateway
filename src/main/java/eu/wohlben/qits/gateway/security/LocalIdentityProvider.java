package eu.wohlben.qits.gateway.security;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Completes {@link LocalAuthMechanism}'s request into a {@link SecurityIdentity}: the principal is
 * the fixed local username, and roles come from the optional {@code qits.auth.local.groups}
 * comma-separated config.
 *
 * <p>The roles are live here, unlike in the services. The gateway is where {@code
 * qits.auth.required-role} is checked, so the {@code local} target has to be able to carry a role
 * or that check could not be exercised without an IdP.
 *
 * <p>Guarded by the same build property as the mechanism it completes: without it the {@code
 * TrustedAuthenticationRequest} it serves is never issued, and leaving a stray identity provider in
 * an {@code oauth} build would be a bean nothing can reach and everything has to reason about.
 */
@ApplicationScoped
@IfBuildProperty(name = "qits.auth.variant", stringValue = "local")
public class LocalIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

  @ConfigProperty(name = "qits.auth.local.groups")
  Optional<String> groups;

  @Override
  public Class<TrustedAuthenticationRequest> getRequestType() {
    return TrustedAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
    QuarkusSecurityIdentity.Builder builder =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(request.getPrincipal()))
            // The subject id the gateway asserts as X-Qits-User-Id. A build with no IdP has no
            // token to take a `sub` from, and the fixed username is the only stable id it has —
            // which is enough, because the point is that the downstream header set is identical in
            // both targets.
            .addAttribute("sub", request.getPrincipal());
    groups.ifPresent(
        g ->
            Stream.of(g.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .forEach(builder::addRole));
    return Uni.createFrom().item(builder.build());
  }
}
