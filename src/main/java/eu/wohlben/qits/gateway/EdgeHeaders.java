package eu.wohlben.qits.gateway;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;
import java.util.List;

/**
 * Everything the gateway rewrites on the way upstream — the only part of an exchange it touches at
 * all. The gateway forwards verbatim (paths and bodies pass through untouched), so this is header
 * work only, four jobs in this order:
 *
 * <ol>
 *   <li><b>Drop every reserved header.</b> {@code X-Qits-*} is the gateway's own namespace: it is
 *       what the gateway asserts about a request and what every upstream believes unconditionally.
 *       Anything arriving under that prefix is client-supplied and worthless, and is removed before
 *       anything else looks at the request. See {@link #RESERVED_PREFIX}.
 *   <li><b>Drop the configured compatibility headers.</b> The enumerated {@code Remote-*} / {@code
 *       X-Auth-Request-*} list ({@code qits.gateway.forwarded.strip-request-headers}) covers a
 *       deployment that still fronts the gateway with a forward-auth proxy, whose header names are
 *       the proxy vendor's rather than ours.
 *   <li><b>Assert the authenticated identity</b> — {@code X-Qits-User} and {@code X-Qits-User-Id},
 *       from {@link AssertedIdentity}. This <b>must</b> stay after the strip and in this same
 *       method: the forged header and the trusted one have the same name, so the code that writes
 *       the trusted value has to be downstream of the code that removes the forged one. An
 *       anonymous request asserts nothing, which is how an upstream sees "no name" rather than a
 *       name it cannot trust.
 *   <li><b>Describe the original client</b> with the {@code X-Forwarded-*} set. {@code
 *       X-Forwarded-For} is <i>set</i>, not appended: the gateway is the outermost hop, so any
 *       inbound value is client-supplied and worthless.
 * </ol>
 *
 * <p>Nothing here is route-specific, so a single instance is shared across every proxy.
 *
 * <p>{@code ProxyInterceptor} has no single abstract method — it is not a functional interface — so
 * this is a class rather than a lambda.
 */
final class EdgeHeaders implements ProxyInterceptor {

  /**
   * The gateway's reserved header namespace. Every header the gateway asserts about a request lives
   * under this prefix, and the strip rule is the same prefix — which is the whole point.
   *
   * <p>An enumerated strip list would be the wrong shape: its failure mode is adding a trusted
   * header and forgetting to extend the list, a silent, additive mistake no test naturally catches.
   * With one prefix doing both jobs it is structurally impossible to introduce a trusted header
   * that is not stripped. Deliberately <b>not</b> configurable — {@code
   * qits.gateway.forwarded.strip-request-headers} may be extended, but this cannot be shrunk away.
   */
  static final String RESERVED_PREFIX = "X-Qits-";

  /**
   * The principal <b>name</b>, not the id — it is what an upstream writes into an audit column, and
   * the platform's existing rows hold usernames.
   */
  static final String USER_HEADER = RESERVED_PREFIX + "User";

  /**
   * The stable subject id. Nothing reads it yet; it is asserted from the start because adding a
   * trusted header later means re-proving the strip rule, and carrying it now costs nothing.
   */
  static final String USER_ID_HEADER = RESERVED_PREFIX + "User-Id";

  private final GatewayConfig.Forwarded forwarded;

  EdgeHeaders(GatewayConfig.Forwarded forwarded) {
    this.forwarded = forwarded;
  }

  /**
   * Whether a header name belongs to the gateway's reserved namespace. Case-insensitive: HTTP
   * header names are, and a client that sends {@code x-qits-user} must be treated exactly like one
   * that sends {@code X-Qits-User}.
   */
  static boolean isReserved(String name) {
    return name != null
        && name.length() > RESERVED_PREFIX.length()
        && name.regionMatches(true, 0, RESERVED_PREFIX, 0, RESERVED_PREFIX.length());
  }

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    ProxyRequest request = context.request();
    HttpServerRequest inbound = request.proxiedRequest();

    // Snapshot the names first: removing from the map while iterating its own name view would
    // otherwise skip entries.
    for (String name : List.copyOf(request.headers().names())) {
      if (isReserved(name)) {
        request.headers().remove(name);
      }
    }

    for (String header : forwarded.stripRequestHeaders()) {
      String name = header.trim();
      if (!name.isEmpty()) {
        request.headers().remove(name);
      }
    }

    // Only now, with the namespace provably empty, is it safe to write into it.
    AssertedIdentity identity = AssertedIdentity.current();
    if (identity != null && identity.user() != null) {
      request.headers().set(USER_HEADER, identity.user());
      if (identity.userId() != null) {
        request.headers().set(USER_ID_HEADER, identity.userId());
      }
    }

    if (forwarded.enabled()) {
      SocketAddress remote = inbound.remoteAddress();
      if (remote != null && remote.hostAddress() != null) {
        request.headers().set("X-Forwarded-For", remote.hostAddress());
      }
      request
          .headers()
          .set("X-Forwarded-Proto", inbound.scheme() == null ? "http" : inbound.scheme());
      HostAndPort authority = inbound.authority();
      if (authority != null) {
        request.headers().set("X-Forwarded-Host", authority.host());
        if (authority.port() > 0) {
          request.headers().set("X-Forwarded-Port", String.valueOf(authority.port()));
        }
      }
    }
    return context.sendRequest();
  }
}
