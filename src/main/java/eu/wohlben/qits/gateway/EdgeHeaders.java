package eu.wohlben.qits.gateway;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;

/**
 * Everything the gateway rewrites on the way upstream — the only part of an exchange it touches at
 * all. The gateway forwards verbatim (paths and bodies pass through untouched), so this is header
 * work only, two jobs in this order:
 *
 * <ol>
 *   <li><b>Drop spoofable identity headers.</b> qits' {@code forwardauth} variant believes its
 *       identity headers ({@code Remote-User} & co.) unconditionally, on the contract that whatever
 *       fronts it strips client-supplied copies. Once the gateway <i>is</i> that front door, this
 *       is where the contract is honoured — an inbound {@code Remote-User: admin} from the internet
 *       must never reach an upstream. Configured as {@code
 *       qits.gateway.forwarded.strip-request-headers}.
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

  private final GatewayConfig.Forwarded forwarded;

  EdgeHeaders(GatewayConfig.Forwarded forwarded) {
    this.forwarded = forwarded;
  }

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    ProxyRequest request = context.request();
    HttpServerRequest inbound = request.proxiedRequest();

    for (String header : forwarded.stripRequestHeaders()) {
      String name = header.trim();
      if (!name.isEmpty()) {
        request.headers().remove(name);
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
