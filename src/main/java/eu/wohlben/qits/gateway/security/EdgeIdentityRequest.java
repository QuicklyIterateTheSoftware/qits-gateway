package eu.wohlben.qits.gateway.security;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/**
 * The credential of the {@code edge} build target: what {@code qits-platform-edge} put on the
 * request after it validated the browser session — a username, a subject id, and the role strings
 * as one comma-separated value.
 *
 * <p>A request type of its own rather than {@code TrustedAuthenticationRequest}, which the {@code
 * local} target uses: that one carries a principal name and nothing else, and this variant has two
 * more values to hand over. Roles especially have to survive, because {@code
 * qits.auth.required-role} is checked against them and because they are re-asserted downstream.
 *
 * <p>Deliberately a dumb carrier: it holds the header values verbatim and interprets none of them.
 * Splitting the role list, dropping blanks and deciding what a missing header means is {@link
 * EdgeIdentityProvider}'s, so there is one place to read the parsing rule.
 */
public final class EdgeIdentityRequest extends BaseAuthenticationRequest {

  private final String user;
  private final String userId;
  private final String roles;

  public EdgeIdentityRequest(String user, String userId, String roles) {
    this.user = user;
    this.userId = userId;
    this.roles = roles;
  }

  /** The principal name — never null: the mechanism does not build a request without one. */
  public String user() {
    return user;
  }

  /** The stable subject id, or {@code null} when the edge asserted none. */
  public String userId() {
    return userId;
  }

  /** The raw comma-separated role list, or {@code null} when the edge asserted none. */
  public String roles() {
    return roles;
  }
}
