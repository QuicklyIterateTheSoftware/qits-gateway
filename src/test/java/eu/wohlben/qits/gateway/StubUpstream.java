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
 * headers that matter for the edge contract — plus the two routes pointing at it.
 *
 * <p>A JDK {@code HttpServer} on an ephemeral port, so the test needs no docker, no fixture and no
 * fixed port. The routes are handed to Quarkus as configuration at start, which is the only way the
 * port (known only after binding) can reach the route table.
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
                      "x-forwarded-prefix=" + header(exchange, "X-Forwarded-Prefix"),
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
    // A prefix-stripping route and a verbatim one, so both forwarding shapes are exercised.
    config.put("qits.gateway.routes.stub.path-prefix", "/stub");
    config.put("qits.gateway.routes.stub.host", "127.0.0.1");
    config.put("qits.gateway.routes.stub.port", String.valueOf(port));
    config.put("qits.gateway.routes.stub.strip-prefix", "true");
    config.put("qits.gateway.routes.verbatim.path-prefix", "/verbatim");
    config.put("qits.gateway.routes.verbatim.host", "127.0.0.1");
    config.put("qits.gateway.routes.verbatim.port", String.valueOf(port));
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
