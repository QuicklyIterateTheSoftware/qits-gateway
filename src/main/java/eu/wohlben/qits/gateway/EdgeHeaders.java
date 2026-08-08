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
 *   <li><b>Describe the original client</b> with the {@code X-Forwarded-*} set, using ordinary
 *       multi-hop semantics — see {@link #describeOriginalClient}. The gateway is no longer the
 *       outermost hop: {@code qits-platform-edge} binds the host port and forwards to this process,
 *       so an inbound {@code X-Forwarded-For} names the real client and must be extended rather
 *       than overwritten.
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

  /**
   * The forwarded set the gateway maintains. Listed so {@link #applyToUpgrade} can carry an outer
   * hop's values across its allow-list rebuild — they are deliberately <b>not</b> on {@link
   * #UPGRADE_HEADERS}, which is the handshake and nothing else.
   */
  private static final List<String> FORWARDED_HEADERS =
      List.of("X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-Port");

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
   * The {@code X-Forwarded-For} value to send upstream: {@code peer} <b>appended</b> to whatever an
   * outer hop already recorded, or {@code peer} alone when nothing did. Returns {@code null} when
   * there is nothing to write at all, which is the signal to leave the header off.
   *
   * <p>This is the whole of the multi-hop rule, and it replaced an unconditional set. The gateway
   * used to be the outermost hop, so an inbound value could only be client-supplied and overwriting
   * it was correct. {@code qits-platform-edge} now binds the host port in front of this process, so
   * the same overwrite would replace the real client's address with the edge container's on every
   * request.
   *
   * <p>The list reads left to right, oldest first, so the <b>first</b> entry is the original client
   * and every later one is a proxy that handled the request. Anything here that ever wants "the
   * client address" must read that first entry, never the last. Nothing in this repository reads
   * the header today.
   *
   * <p>The trade-off is the one every reverse proxy makes: a chain is only as trustworthy as the
   * hops that wrote it. With nothing in front of the gateway a client can invent the whole prefix.
   * Trust comes from knowing what fronts the deployment, never from the header.
   *
   * <p>Framework-free on purpose — it is the edge-case surface of the rule, and {@code
   * EdgeHeadersTest} pins it without booting the application.
   */
  static String forwardedFor(String existing, String peer) {
    String chain = existing == null ? "" : existing.trim();
    String hop = peer == null ? "" : peer.trim();
    if (hop.isEmpty()) {
      return chain.isEmpty() ? null : chain;
    }
    return chain.isEmpty() ? hop : chain + ", " + hop;
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
   * <p><b>The one exception to the rebuild</b> is the {@code X-Forwarded-*} set, which is carried
   * across it rather than dropped. It is not on {@link #UPGRADE_HEADERS} — that list stays the
   * handshake and nothing else — but a chain the edge wrote names the real client, and a socket
   * that restarted it would be the one thing on the platform attributed to the edge's own address.
   * It is client-supplied when nothing fronts the gateway, exactly as on an ordinary request, which
   * is what {@link #forwardedFor} spells out.
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

    // The allow-list is about to drop the forwarded set with everything else, so keep it: a socket
    // opened through the edge must name the same client an ordinary request through the edge does,
    // or the workspace terminals become the one thing on the platform attributed to the edge's own
    // address. Put back below, then extended by describeOriginalClient exactly as on a request.
    // Left empty when the deployment emits no forwarded set at all, so the restore is a no-op and
    // the handshake keeps dropping what the client sent.
    MultiMap outerHops = MultiMap.caseInsensitiveMultiMap();
    if (forwarded.enabled()) {
      for (String name : FORWARDED_HEADERS) {
        List<String> values = headers.getAll(name);
        if (!values.isEmpty()) {
          outerHops.set(name, values);
        }
      }
    }

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

    headers.addAll(outerHops);
    describeOriginalClient(headers, inbound);
  }

  /**
   * Write the {@code X-Forwarded-*} set into {@code headers}, which must already hold whatever the
   * inbound request carried. Both entry points end here, so an ordinary request and a handshake
   * describe their client identically.
   *
   * <p>{@code X-Forwarded-For} is <b>appended</b> to (see {@link #forwardedFor}). The other three
   * are <b>set only when absent</b>: they describe the address the client actually typed, which the
   * outermost hop is the only one that saw. The edge terminates TLS and forwards the original
   * {@code Host}, so overwriting {@code -Proto} here would turn every {@code https} exchange into
   * {@code http} for the upstream that reads it.
   *
   * <p>With nothing in front of the gateway no inbound value exists, "set if absent" is "set", and
   * a direct deployment behaves exactly as it did before the edge.
   *
   * <p>Repeated header lines are joined the way the RFC reads them — one comma-separated list — so
   * a client cannot split a chain across two lines and have only one of them survive.
   */
  private void describeOriginalClient(MultiMap headers, HttpServerRequest inbound) {
    if (!forwarded.enabled()) {
      return;
    }

    SocketAddress remote = inbound.remoteAddress();
    String chain =
        forwardedFor(
            String.join(", ", headers.getAll("X-Forwarded-For")),
            remote == null ? null : remote.hostAddress());
    if (chain != null) {
      headers.set("X-Forwarded-For", chain);
    }

    setIfAbsent(headers, "X-Forwarded-Proto", inbound.scheme() == null ? "http" : inbound.scheme());
    HostAndPort authority = inbound.authority();
    if (authority != null) {
      setIfAbsent(headers, "X-Forwarded-Host", authority.host());
      if (authority.port() > 0) {
        setIfAbsent(headers, "X-Forwarded-Port", String.valueOf(authority.port()));
      }
    }
  }

  /** Header names are case-insensitive here, so an outer hop's casing cannot cause a duplicate. */
  private static void setIfAbsent(MultiMap headers, String name, String value) {
    if (!headers.contains(name)) {
      headers.set(name, value);
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

    describeOriginalClient(request.headers(), inbound);
    return context.sendRequest();
  }
}
