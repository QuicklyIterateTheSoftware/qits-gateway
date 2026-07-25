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
 * all. Three jobs, in this order:
 *
 * <ol>
 *   <li><b>Drop spoofable identity headers.</b> qits' {@code forwardauth} variant believes its
 *       identity headers ({@code Remote-User} & co.) unconditionally, on the contract that whatever
 *       fronts it strips client-supplied copies. Once the gateway <i>is</i> that front door, this
 *       is where the contract is honoured — an inbound {@code Remote-User: admin} from the internet
 *       must never reach an upstream. Configured as {@code
 *       qits.gateway.forwarded.strip-request-headers}.
 *   <li><b>Rewrite the path</b> for prefix-stripping routes, announcing the removed prefix as
 *       {@code X-Forwarded-Prefix} so the upstream can still build absolute URLs that work from
 *       outside.
 *   <li><b>Describe the original client</b> with the {@code X-Forwarded-*} set. {@code
 *       X-Forwarded-For} is <i>set</i>, not appended: the gateway is the outermost hop, so any
 *       inbound value is client-supplied and worthless.
 * </ol>
 *
 * <p>{@code ProxyInterceptor} has no single abstract method — it is not a functional interface — so
 * this is a class rather than a lambda.
 */
final class EdgeHeaders implements ProxyInterceptor {

  private final GatewayRoute route;
  private final GatewayConfig.Forwarded forwarded;

  EdgeHeaders(GatewayRoute route, GatewayConfig.Forwarded forwarded) {
    this.route = route;
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

    if (route.stripPrefix() && !route.isCatchAll()) {
      request.setURI(rewriteUri(request.getURI()));
    }
    route
        .authority()
        .ifPresent(value -> request.setAuthority(HostAndPort.parseAuthority(value, route.port())));

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
      if (route.stripPrefix() && !route.isCatchAll()) {
        request.headers().set("X-Forwarded-Prefix", route.prefix());
      }
    }
    return context.sendRequest();
  }

  /** Strip the route prefix from the path while leaving the query string untouched. */
  private String rewriteUri(String uri) {
    int query = uri.indexOf('?');
    if (query < 0) {
      return route.rewrite(uri);
    }
    return route.rewrite(uri.substring(0, query)) + uri.substring(query);
  }
}
