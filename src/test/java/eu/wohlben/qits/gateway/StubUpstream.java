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
 * <p>A JDK {@code HttpServer} on an ephemeral port, so the test needs no docker, no fixture and no
 * fixed port. Two real {@link QitsService} segments ({@code artifacts}, {@code otel}) are pointed
 * at the stub via {@code qits.gateway.proxy-hosts.*}; the config is handed to Quarkus at start,
 * which is the only way the port (known only after binding) can reach the route table.
 */
public class StubUpstream implements QuarkusTestResourceLifecycleManager {

  private HttpServer server;

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
          String body =
              String.join(
                      "\n",
                      "path=" + exchange.getRequestURI(),
                      "host=" + header(exchange, "Host"),
                      "x-forwarded-for=" + header(exchange, "X-Forwarded-For"),
                      "x-forwarded-host=" + header(exchange, "X-Forwarded-Host"),
                      "x-forwarded-proto=" + header(exchange, "X-Forwarded-Proto"),
                      "remote-user=" + header(exchange, "Remote-User"))
                  + "\n";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
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
    config.put("qits.gateway.proxy-hosts.otel", "127.0.0.1:" + port);
    return config;
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
  }
}
