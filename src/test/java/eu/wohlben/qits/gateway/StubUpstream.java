package eu.wohlben.qits.gateway;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A throwaway upstream that echoes back what the gateway forwarded — the request target and the
 * headers that matter for the edge contract — plus the service routes pointing at it.
 *
 * <p>The {@code x-qits-*} lines are what make the header contract testable from the outside: a
 * service believes those headers unconditionally, so "what actually arrived here" is the only
 * assertion worth making about them.
 *
 * <p>A JDK {@code HttpServer} on an ephemeral port, so the test needs no docker, no fixture and no
 * fixed port. Two real {@link QitsService} segments ({@code artifacts}, {@code observability}) are
 * pointed at the stub via {@code qits.gateway.proxy-hosts.*}; the config is handed to Quarkus at
 * start, which is the only way the port (known only after binding) can reach the route table.
 *
 * <p>A <em>second</em> upstream, on Vert.x, sits behind the {@code projects} segment and does the
 * same job for WebSocket upgrades: it accepts the handshake and sends back the headers it saw as
 * its first text frame. It has to be a different server because a JDK {@code HttpServer} cannot
 * answer an upgrade at all, and it has to exist because the upgrade path through {@code
 * vertx-http-proxy} carries a completely different header contract from the ordinary one — see
 * {@code EdgeHeaders.applyToUpgrade}.
 */
public class StubUpstream implements QuarkusTestResourceLifecycleManager {

  /** The header names the socket upstream reports back, in this order. */
  static final java.util.List<String> REPORTED_HANDSHAKE_HEADERS =
      java.util.List.of(
          "X-Qits-User",
          "X-Qits-User-Id",
          "X-Qits-Groups",
          "Remote-User",
          "Cookie",
          "Authorization",
          "Origin",
          "X-Forwarded-For",
          "X-Forwarded-Proto");

  private HttpServer server;
  private io.vertx.core.Vertx socketVertx;
  private io.vertx.core.http.HttpServer socketServer;

  @Override
  public Map<String, String> start() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Could not start the stub upstream", e);
    }
    server.createContext(
        "/",
        exchange -> {
          // Drained rather than ignored, so a test can assert that a body larger than Quarkus'
          // default wire limit actually arrived instead of only that the exchange returned 200.
          long received;
          try {
            received = exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
          } catch (java.io.IOException e) {
            received = -1;
          }
          String body =
              String.join(
                      "\n",
                      "path=" + exchange.getRequestURI(),
                      "method=" + exchange.getRequestMethod(),
                      "body-bytes=" + received,
                      // Kept on an ordinary request, deliberately. This is the opposite of what
                      // REPORTED_HANDSHAKE_HEADERS asserts about a WebSocket handshake, and the
                      // asymmetry IS the contract rather than a bug: a handshake rebuilds its
                      // headers from an allow-list that drops Authorization, while an ordinary
                      // request forwards it verbatim — which is the whole of how a registry push
                      // credential reaches qits-artifacts through this gateway.
                      "authorization=" + header(exchange, "Authorization"),
                      "host=" + header(exchange, "Host"),
                      "x-forwarded-for=" + header(exchange, "X-Forwarded-For"),
                      "x-forwarded-host=" + header(exchange, "X-Forwarded-Host"),
                      "x-forwarded-proto=" + header(exchange, "X-Forwarded-Proto"),
                      "remote-user=" + header(exchange, "Remote-User"),
                      "x-qits-user=" + header(exchange, "X-Qits-User"),
                      "x-qits-user-id=" + header(exchange, "X-Qits-User-Id"))
                  + "\n";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    // An upstream SPA, as Quarkus serves one by default: the document, the hashed bundle and the
    // unhashed favicon all carry the day-long immutable header. What the gateway must do with each
    // differs, and GatewayRoutingTest asserts exactly that split — only the hash-named file keeps
    // the header.
    server.createContext(
        "/artifacts/spa",
        exchange -> {
          exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
          String path = exchange.getRequestURI().getPath();
          String contentType;
          String payload;
          if (path.endsWith(".js")) {
            contentType = "application/javascript";
            payload = "console.log('stub')";
          } else if (path.endsWith(".ico")) {
            contentType = "image/x-icon";
            payload = "icon";
          } else {
            contentType = "text/html; charset=utf-8";
            payload = "<!doctype html><title>stub</title>";
          }
          byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.getResponseHeaders().set("Cache-Control", "public, immutable, max-age=86400");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();

    int port = server.getAddress().getPort();
    Map<String, String> config = new HashMap<>();
    // Two real services pointed at the stub — exercising host:port parsing and that distinct
    // segments each resolve to their own route.
    config.put("qits.gateway.proxy-hosts.artifacts", "127.0.0.1:" + port);
    config.put("qits.gateway.proxy-hosts.observability", "127.0.0.1:" + port);
    config.put("qits.gateway.proxy-hosts.projects", "127.0.0.1:" + startSocketUpstream());
    return config;
  }

  /**
   * The WebSocket half: accept any upgrade and immediately report what arrived, one {@code
   * name=value} per line, so a test asserts on "what the upstream actually saw" exactly as the HTTP
   * stub does. Absent headers report {@code -} rather than being omitted — a missing line and a
   * missing header would otherwise look the same.
   */
  private int startSocketUpstream() {
    socketVertx = io.vertx.core.Vertx.vertx();
    try {
      socketServer =
          socketVertx
              .createHttpServer()
              // A Vert.x server with only a webSocketHandler NPEs on any plain request (it emits a
              // null task), which surfaces as the client hanging until its own timeout rather than
              // as anything readable. Answer instead, so "the upgrade did not survive the proxy"
              // says so.
              .requestHandler(
                  req ->
                      req.response()
                          .setStatusCode(426)
                          .putHeader("Content-Type", "text/plain; charset=utf-8")
                          .end("this upstream only speaks WebSocket; got " + req.method() + "\n"))
              .webSocketHandler(
                  socket -> {
                    StringBuilder seen = new StringBuilder();
                    for (String name : REPORTED_HANDSHAKE_HEADERS) {
                      String value = socket.headers().get(name);
                      seen.append(name.toLowerCase(java.util.Locale.ROOT))
                          .append('=')
                          .append(value == null ? "-" : value)
                          .append('\n');
                    }
                    socket.writeTextMessage(seen.toString());
                  })
              .listen(0, "127.0.0.1")
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("Could not start the stub socket upstream", e);
    }
    return socketServer.actualPort();
  }

  private static String header(com.sun.net.httpserver.HttpExchange exchange, String name) {
    String value = exchange.getRequestHeaders().getFirst(name);
    return value == null ? "-" : value;
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
    if (socketVertx != null) {
      socketVertx.close();
    }
  }
}
