package eu.wohlben.qits.gateway;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * What the gateway asserts about a request, carried from the route handler to {@link EdgeHeaders}.
 *
 * <p><b>Why a Vert.x context local and not a parameter.</b> The two halves of this hand-off cannot
 * see each other: {@link GatewayRouter} holds the {@code RoutingContext} — and so the authenticated
 * identity — while {@code EdgeHeaders} is a {@code ProxyInterceptor} that is handed only a {@code
 * ProxyContext}, one shared instance across every route. {@code ProxyContext} has its own attribute
 * map, but nothing outside an interceptor can seed it.
 *
 * <p>Quarkus runs each request on its own <em>duplicated</em> Vert.x context, and {@code
 * HttpProxy.handle} runs the interceptor on that same context, so a context local is request-scoped
 * by construction — no cleanup, and no chance of one request reading another's identity.
 *
 * <p>Strip and inject both happen in {@code EdgeHeaders}, in that order, deliberately: the header
 * the gateway asserts and the header a client might forge have the same name, so whichever code
 * writes the trusted value has to be downstream of the code that removes the untrusted one.
 * Splitting them across two components is how that ordering gets broken by a later edit.
 */
final class AssertedIdentity {

  private static final String CONTEXT_KEY = "qits.gateway.asserted-identity";

  private final String user;
  private final String userId;
  private final String roles;

  private AssertedIdentity(String user, String userId, String roles) {
    this.user = user;
    this.userId = userId;
    this.roles = roles;
  }

  /** The principal name, or {@code null} for an anonymous request. */
  String user() {
    return user;
  }

  /** The stable subject id, or {@code null} when the identity carries none. */
  String userId() {
    return userId;
  }

  /**
   * The comma-separated role set to forward, or {@code null} when this build has none to forward —
   * which is every target but {@code edge}. See {@link #roles(SecurityIdentity)}.
   */
  String roles() {
    return roles;
  }

  /**
   * Record what this request authenticated as, for the interceptor to assert upstream. Anonymous
   * identities are stored as an absent user rather than skipped, so the interceptor never has to
   * distinguish "no identity" from "never ran".
   */
  static void record(SecurityIdentity identity) {
    Context context = Vertx.currentContext();
    if (context == null) {
      return;
    }
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      context.putLocal(CONTEXT_KEY, new AssertedIdentity(null, null, null));
      return;
    }
    context.putLocal(
        CONTEXT_KEY,
        new AssertedIdentity(identity.getPrincipal().getName(), userId(identity), roles(identity)));
  }

  /** What this request authenticated as, or {@code null} if nothing recorded one. */
  static AssertedIdentity current() {
    Context context = Vertx.currentContext();
    return context == null ? null : context.getLocal(CONTEXT_KEY);
  }

  /**
   * The subject id, which is not the principal. Under oauth the principal is {@code
   * preferred_username} (see the {@code quarkus.oidc.token.principal-claim} default this repo
   * ships) and the id is the token's {@code sub}; the {@code local} target has no token and
   * publishes its own {@code sub} attribute instead.
   */
  private static String userId(SecurityIdentity identity) {
    if (identity.getPrincipal() instanceof JsonWebToken token) {
      return token.getSubject();
    }
    Object subject = identity.getAttribute("sub");
    return subject == null ? null : subject.toString();
  }

  /**
   * The role set to forward, read from the {@link EdgeHeaders#ROLES_ATTRIBUTE} attribute rather
   * than from {@code getRoles()}.
   *
   * <p>That is the whole of the "which target forwards roles" decision, and it is deliberately not
   * a variant check. Only the {@code edge} target's identity provider sets the attribute, because
   * only that target has roles that came from somewhere a service could act on. The {@code oauth}
   * and {@code local} targets keep the older rule — the one role check the system has is {@code
   * qits.auth.required-role}, made in {@code QitsAuthPolicy} and never repeated downstream — and
   * they keep it without this class knowing which build it is in.
   */
  private static String roles(SecurityIdentity identity) {
    Object roles = identity.getAttribute(EdgeHeaders.ROLES_ATTRIBUTE);
    return roles == null ? null : roles.toString();
  }
}
