package eu.wohlben.qits.gateway;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

  /**
   * The handshake headers a WebSocket upgrade keeps. Everything else is dropped — see {@link
   * #applyToUpgrade}, which is where the reason lives. Lower-case because the comparison is, and
   * because HTTP header names are.
   *
   * <p>This is an allow-list, which is the opposite shape from {@link #RESERVED_PREFIX}'s deny
   * rule, and that is the point: nothing under the reserved prefix can be on it, so a trusted
   * header invented later is structurally excluded here too and cannot arrive from a client. {@code
   * EdgeHeadersTest} pins that.
   */
  static final Set<String> UPGRADE_HEADERS =
      Set.of(
          // The upgrade itself. Vert.x rewrites Connection and drops Host on its own, but naming
          // them keeps this list readable as "an RFC 6455 handshake".
          "host",
          "upgrade",
          "connection",
          "sec-websocket-key",
          "sec-websocket-version",
          "sec-websocket-protocol",
          "sec-websocket-extensions",
          // Not required by the handshake, kept because an upstream may legitimately want to know
          // which origin opened the socket. It is client-supplied and must be read as such.
          "origin");

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

  /**
   * Whether {@code request} is a WebSocket upgrade handshake — the requests {@link
   * #handleProxyRequest} never sees.
   *
   * <p>Spelled out here rather than reusing {@code io.vertx.core.http.impl.HttpUtils}, which is
   * internal API. The three conditions are RFC 6455's: a GET, {@code Upgrade: websocket}, and
   * {@code Connection} naming the upgrade (it may carry other tokens, so this is a contains, not an
   * equals).
   */
  static boolean isWebSocketUpgrade(HttpServerRequest request) {
    if (request.method() != HttpMethod.GET) {
      return false;
    }
    String upgrade = request.getHeader(HttpHeaders.UPGRADE);
    String connection = request.getHeader(HttpHeaders.CONNECTION);
    return upgrade != null
        && upgrade.equalsIgnoreCase("websocket")
        && connection != null
        && connection.toLowerCase(Locale.ROOT).contains("upgrade");
  }

  /**
   * The same four jobs as {@link #handleProxyRequest}, applied to a WebSocket handshake.
   *
   * <p><b>Why this exists at all.</b> {@code vertx-http-proxy}'s {@code ReverseProxy.handle} calls
   * {@code handleWebSocketUpgrade} and <em>returns before installing the interceptor chain</em>, so
   * on an upgrade {@link #handleProxyRequest} never runs. The upgrade path then copies every header
   * off the inbound request except {@code Connection} and {@code Host}. Both halves of the contract
   * are lost at once: nothing under the reserved prefix is stripped, so {@code curl -H
   * 'X-Qits-User: admin' -H 'Upgrade: websocket' …} forges an identity through the front door; and
   * nothing is injected, so a legitimately authenticated socket arrives upstream anonymous. Every
   * WebSocket in the system runs through here, so this is not an edge case.
   *
   * <p><b>Why an allow-list and not the prefix strip.</b> On an ordinary request the gateway
   * forwards verbatim and removes only what it must. A handshake is not a request an upstream
   * answers; it is a protocol negotiation, and the only thing beyond it an upstream is entitled to
   * is what the gateway itself asserts. So this rebuilds the header set from {@link
   * #UPGRADE_HEADERS} instead of subtracting from the client's. {@code Cookie} is dropped with the
   * rest, deliberately: authentication terminates at the gateway, no upstream authenticates by
   * cookie, and a session cookie is exactly the sort of thing that should not travel further than
   * it has to.
   *
   * <p>Strip and inject stay in one method here for the same reason they do below — the forged
   * header and the trusted one have the same name, so the code that writes the trusted value must
   * be downstream of the code that removes the forged one. {@code GatewayRouter} calls this; it
   * must never perform it.
   *
   * <p>This mutates the inbound request's own header map, which is what the upgrade path reads.
   */
  void applyToUpgrade(HttpServerRequest inbound) {
    MultiMap headers = inbound.headers();
    // Snapshot the names first: removing from the map while iterating its own name view would
    // otherwise skip entries.
    for (String name : List.copyOf(headers.names())) {
      if (!UPGRADE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        headers.remove(name);
      }
    }

    // Only now, with everything the client sent provably gone, is it safe to assert anything.
    AssertedIdentity identity = AssertedIdentity.current();
    if (identity != null && identity.user() != null) {
      headers.set(USER_HEADER, identity.user());
      if (identity.userId() != null) {
        headers.set(USER_ID_HEADER, identity.userId());
      }
    }

    if (forwarded.enabled()) {
      SocketAddress remote = inbound.remoteAddress();
      if (remote != null && remote.hostAddress() != null) {
        headers.set("X-Forwarded-For", remote.hostAddress());
      }
      headers.set("X-Forwarded-Proto", inbound.scheme() == null ? "http" : inbound.scheme());
      HostAndPort authority = inbound.authority();
      if (authority != null) {
        headers.set("X-Forwarded-Host", authority.host());
        if (authority.port() > 0) {
          headers.set("X-Forwarded-Port", String.valueOf(authority.port()));
        }
      }
    }
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
