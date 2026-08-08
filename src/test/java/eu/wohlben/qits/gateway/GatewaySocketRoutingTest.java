package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The header contract on a <b>WebSocket upgrade</b> — a different code path from every other test
 * here, and one that had no coverage at all.
 *
 * <p><b>What was wrong.</b> {@code vertx-http-proxy}'s {@code ReverseProxy.handle} short-circuits
 * to its upgrade path and returns <em>before</em> installing the interceptor chain, so {@link
 * EdgeHeaders#handleProxyRequest} never ran on a handshake. The upgrade path then copied every
 * header off the inbound request except {@code Connection} and {@code Host}. Both halves of the
 * contract were lost at once: a client-supplied {@code X-Qits-User} reached the upstream unchanged
 * (a complete authentication bypass, through the one door the prefix strip did not cover), and a
 * genuinely authenticated socket arrived anonymous. Every WebSocket in qits — the workspace
 * terminals, the agent chats, the dev-server HMR channels — goes through here.
 *
 * <p><b>Why the {@code local} target.</b> These need a real client opening a real socket, and
 * {@code @TestSecurity} does not reach one: it is plumbed for the test HTTP client, so the
 * handshake is challenged before routing runs. The {@code local} build authenticates <em>every</em>
 * request as one fixed identity with no client-side plumbing at all, which is exactly what a socket
 * needs — and {@code LocalVariantTest} already relies on local and oauth emitting byte-identical
 * {@code X-Qits-*} headers, so asserting the contract on one target asserts it on both.
 */
@QuarkusTest
@TestProfile(GatewaySocketRoutingTest.LocalTarget.class)
@WithTestResource(StubUpstream.class)
class GatewaySocketRoutingTest {

  /** The same build-property flip {@code LocalVariantTest} uses; see its javadoc for why. */
  public static class LocalTarget implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.auth.variant", "local",
          "qits.auth.local.user", "localdev",
          "quarkus.oidc.enabled", "false");
    }
  }

  @Test
  void aSocketCarriesTheAssertedIdentityUpstream() throws Exception {
    // The whole point: an authenticated socket must arrive upstream named, exactly as an
    // authenticated request does. Everything downstream believes this header unconditionally, so a
    // socket that arrives anonymous is a socket no upstream can attribute to anyone.
    assertLine(handshake(builder -> {}), "x-qits-user=localdev");
  }

  @Test
  void aForgedIdentityDoesNotSurviveASocketHandshake() throws Exception {
    String seen =
        handshake(
            builder ->
                builder
                    .header("X-Qits-User", "attacker")
                    .header("X-Qits-User-Id", "00000000")
                    .header("X-Qits-Groups", "admin")
                    .header("Remote-User", "attacker"));

    assertLine(seen, "x-qits-user=localdev");
    // Not merely "the forged value was overwritten": every reserved name a client can invent is
    // gone, including ones the gateway does not assert today and might tomorrow.
    assertLine(seen, "x-qits-groups=-");
    assertLine(seen, "remote-user=-");
    assertFalse(
        seen.contains("attacker"), "no client-supplied identity may reach an upstream:\n" + seen);
    assertFalse(seen.contains("00000000"), seen);
  }

  @Test
  void aSocketHandshakeForwardsNoCredentials() throws Exception {
    // Authentication terminates at the gateway and nothing upstream authenticates by cookie or
    // bearer, so neither has any business travelling further than the front door. This is where the
    // upgrade path differs from an ordinary request on purpose: it forwards an allow-list rather
    // than everything-minus-the-reserved-prefix.
    String seen =
        handshake(
            builder ->
                builder
                    .header("Cookie", "q_session=secret")
                    .header("Authorization", "Bearer secret"));

    assertLine(seen, "cookie=-");
    assertLine(seen, "authorization=-");
    assertFalse(seen.contains("secret"), seen);
  }

  @Test
  void aSocketStillDescribesTheOriginalClient() throws Exception {
    String seen = handshake(builder -> {});

    assertLine(seen, "x-forwarded-for=127.0.0.1");
    assertLine(seen, "x-forwarded-proto=http");
  }

  @Test
  void aSocketBehindTheEdgeCarriesTheRealClientThroughTheRebuild() throws Exception {
    // The forwarded set is the one thing the allow-list rebuild carries across rather than drops,
    // and this is why. Every workspace terminal and agent chat opens through here; a handshake that
    // restarted the chain would make sockets the only traffic on the platform attributed to
    // qits-platform-edge's own address rather than to whoever opened them.
    String seen =
        handshake(
            builder ->
                builder
                    .header("X-Forwarded-For", "203.0.113.7")
                    .header("X-Forwarded-Proto", "https"));

    assertLine(seen, "x-forwarded-for=203.0.113.7, 127.0.0.1");
    assertLine(seen, "x-forwarded-proto=https");
  }

  @Test
  void theHandshakeItselfStillReachesTheUpstream() throws Exception {
    // The negative case for the allow-list: rebuild too aggressively and the upgrade never
    // completes at all. Getting a frame back at all is what proves Sec-WebSocket-* survived.
    assertTrue(handshake(builder -> {}).contains("x-qits-user="), "the socket opened and answered");
  }

  /**
   * Open a socket through the gateway to the stub socket upstream and return the {@code name=value}
   * report it sends as its first frame.
   */
  private static String handshake(Consumer<WebSocket.Builder> customize) throws Exception {
    CompletableFuture<String> first = new CompletableFuture<>();
    WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
    customize.accept(builder);
    WebSocket socket =
        builder
            .buildAsync(
                URI.create("ws://127.0.0.1:" + RestAssured.port + "/projects/socket"),
                new WebSocket.Listener() {
                  private final StringBuilder buffer = new StringBuilder();

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                      first.complete(buffer.toString());
                    }
                    webSocket.request(1);
                    return null;
                  }
                })
            .get(15, TimeUnit.SECONDS);
    try {
      return first.get(15, TimeUnit.SECONDS);
    } finally {
      socket.abort();
    }
  }

  private static void assertLine(String seen, String expected) {
    assertTrue(
        seen.lines().anyMatch(expected::equals),
        "expected a line `" + expected + "`, the upstream saw:\n" + seen);
  }
}
