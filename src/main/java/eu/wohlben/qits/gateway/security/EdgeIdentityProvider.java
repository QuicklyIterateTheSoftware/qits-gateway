package eu.wohlben.qits.gateway.security;

import eu.wohlben.qits.gateway.EdgeHeaders;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Completes {@link EdgeAuthMechanism}'s request into a {@link SecurityIdentity}: the principal is
 * the forwarded username, the {@code sub} attribute is the forwarded user id, and the roles are the
 * forwarded comma-separated list split into a set.
 *
 * <p><b>The role list is a comma-separated string because a role never contains a comma.</b> Role
 * strings are namespaced {@code $app:$resource:$role} (see user-authentication-plan.md), so a comma
 * is free to be the separator and no escaping scheme is needed on either side of the hop. Blanks
 * around an entry are trimmed and empty entries are dropped, so {@code "a, ,b,"} is exactly {@code
 * a} and {@code b} — a trailing comma from a naive join must not become a role named "".
 *
 * <p>Order is preserved ({@link LinkedHashSet}) and duplicates collapse, because the same set is
 * joined back together for the downstream {@code X-Qits-Roles} header: what a service is told is
 * then literally what {@link QitsAuthPolicy} checked, rather than a second copy of the inbound
 * string that could differ from it.
 *
 * <p>Guarded by the same build property as the mechanism it completes, for the same reason: without
 * it the request type it serves is never issued, and a stray provider in an {@code oauth} build is
 * a bean nothing can reach and everything has to reason about.
 */
@ApplicationScoped
@IfBuildProperty(name = "qits.auth.variant", stringValue = "edge")
public class EdgeIdentityProvider implements IdentityProvider<EdgeIdentityRequest> {

  @Override
  public Class<EdgeIdentityRequest> getRequestType() {
    return EdgeIdentityRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      EdgeIdentityRequest request, AuthenticationRequestContext context) {
    QuarkusSecurityIdentity.Builder builder =
        QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(request.user()));
    if (request.userId() != null) {
      // The same attribute name the local target publishes and quarkus-oidc's token subject lands
      // under, so AssertedIdentity reads one thing in all three targets.
      builder.addAttribute("sub", request.userId());
    }
    Set<String> roles = parseRoles(request.roles());
    roles.forEach(builder::addRole);
    if (!roles.isEmpty()) {
      // What EdgeHeaders re-asserts downstream. Carried as an identity ATTRIBUTE rather than read
      // back out of getRoles(), so that only an identity this provider built ever produces the
      // header: the oauth and local targets keep emitting no role header at all, which is the
      // "roles stop at the gateway" rule those targets still live by.
      builder.addAttribute(EdgeHeaders.ROLES_ATTRIBUTE, String.join(",", roles));
    }
    return Uni.createFrom().item(builder.build());
  }

  /**
   * Split the forwarded list; {@code null} or blank yields an empty set, not a role named "".
   *
   * <p>Case is left exactly as it arrived. Role comparison is exact everywhere in Quarkus ({@code
   * getRoles().contains(...)}) and the idp stores the strings as written, so a role that differs by
   * case is a different role — normalising here would invent matches that no other component makes.
   */
  private static Set<String> parseRoles(String header) {
    Set<String> roles = new LinkedHashSet<>();
    if (header == null) {
      return roles;
    }
    for (String role : header.split(",")) {
      String trimmed = role.trim();
      if (!trimmed.isEmpty()) {
        roles.add(trimmed);
      }
    }
    return roles;
  }
}
