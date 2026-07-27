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

  private AssertedIdentity(String user, String userId) {
    this.user = user;
    this.userId = userId;
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
      context.putLocal(CONTEXT_KEY, new AssertedIdentity(null, null));
      return;
    }
    context.putLocal(
        CONTEXT_KEY, new AssertedIdentity(identity.getPrincipal().getName(), userId(identity)));
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
}
