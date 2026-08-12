package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.http.HttpClientOptions;
import org.junit.jupiter.api.Test;

/**
 * The proxy client's two timeouts, which pull in opposite directions and are each a real outage
 * when they are wrong.
 *
 * <p>Framework-free on purpose: the options object is what {@code GatewayRouter.init} hands {@code
 * createHttpClient}, so reading it back proves the wiring without booting the application — and
 * without adding a {@code @QuarkusTest} restart, which the socket suite cannot afford.
 *
 * <p>Behaviour is not asserted here. A real connect timeout needs an address that black-holes the
 * SYN, and a blackhole answers differently in different network namespaces — the flip proof on
 * swarm is where that belongs.
 */
class GatewayProxyClientOptionsTest {

  @Test
  void theConnectTimeoutIsBoundedSoAnUnreadyUpstreamFails502Fast() {
    // Vert.x defaults to 60 000. Under swarm a name resolves to a VIP with no live task behind it,
    // so the connection is dropped rather than refused and the default hangs the request for a
    // full minute before the proxy's 502.
    assertEquals(5000, GatewayRouter.proxyClientOptions(5000).getConnectTimeout());
  }

  @Test
  void theIdleTimeoutStaysZero() {
    // The opposite direction, and the one that must never be "tidied" to match the line above:
    // zero is what keeps an SSE channel, an HMR socket and a multi-minute layer push alive.
    assertEquals(0, GatewayRouter.proxyClientOptions(5000).getIdleTimeout());
  }

  @Test
  void thePoolIsWideEnoughForAConcurrentLayerPush() {
    // Vert.x pools per origin and defaults to five, which one `docker push` saturates on its own.
    assertEquals(64, GatewayRouter.proxyClientOptions(5000).getMaxPoolSize());
  }

  @Test
  void nothingElseAboutTheClientIsAssumed() {
    // Keep-alive is the default; stated in the options and stated here, because a proxy that
    // reconnected per request would turn every hop into a handshake.
    HttpClientOptions options = GatewayRouter.proxyClientOptions(5000);
    assertEquals(true, options.isKeepAlive());
  }
}
