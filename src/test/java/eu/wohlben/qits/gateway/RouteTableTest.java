package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The matching rules and the config-to-route resolution, tested without booting an application —
 * this is where the edge cases live (segment-aware prefixes, longest-match precedence, host:port
 * parsing, unknown-service rejection).
 */
class RouteTableTest {

  private static GatewayRoute route(String name, String prefix) {
    return new GatewayRoute(name, prefix, "upstream-" + name, 8080);
  }

  private static RouteTable table(GatewayRoute... routes) {
    return RouteTable.of(List.of(routes));
  }

  private static String matched(RouteTable table, String path) {
    return table.match(path).map(GatewayRoute::name).orElse(null);
  }

  @Test
  void longestPrefixWinsRegardlessOfDeclarationOrder() {
    RouteTable table =
        table(
            route("qits", "/"),
            route("artifacts", "/artifacts"),
            route("observability", "/observability"));

    assertEquals("artifacts", matched(table, "/artifacts/blobs/abc"));
    assertEquals("observability", matched(table, "/observability/v1/traces"));
    assertEquals("qits", matched(table, "/index.html"));
  }

  @Test
  void prefixMatchingIsSegmentAware() {
    RouteTable table = table(route("artifacts", "/artifacts"));

    assertEquals("artifacts", matched(table, "/artifacts"));
    assertEquals("artifacts", matched(table, "/artifacts/"));
    assertEquals("artifacts", matched(table, "/artifacts/blobs"));
    // A path that merely starts with the same characters belongs to somebody else.
    assertEquals(null, matched(table, "/artifactsx"));
    assertEquals(null, matched(table, "/art"));
  }

  @Test
  void unmatchedPathHasNoRouteWhenThereIsNoCatchAll() {
    assertEquals(null, matched(table(route("artifacts", "/artifacts")), "/git/repo"));
  }

  @Test
  void prefixesAreNormalised() {
    // Trailing slashes and a missing leading slash all name the same route; `/` is the catch-all.
    assertEquals("/artifacts", route("a", "/artifacts/").prefix());
    assertEquals("/artifacts", route("a", "artifacts").prefix());
    assertTrue(route("a", "/").isCatchAll());
    assertTrue(route("a", "").isCatchAll());
  }

  @Test
  void anEmptyTableRoutesNothing() {
    assertTrue(table().isEmpty());
    assertEquals(null, matched(table(), "/anything"));
    assertFalse(table(route("artifacts", "/artifacts")).isEmpty());
  }

  // --- buildRoutes: the config -> route resolution --------------------------------------------

  @Test
  void eachServiceBecomesARouteAtItsSegmentPrefix() {
    List<GatewayRoute> routes =
        RouteTable.buildRoutes(
            Optional.empty(),
            8080,
            Map.of("artifacts", "qits-artifacts", "observability", "qits-observability"));
    RouteTable table = RouteTable.of(routes);

    assertEquals("artifacts", matched(table, "/artifacts/blobs"));
    assertEquals("observability", matched(table, "/observability/v1"));
    assertEquals(2, routes.size());
  }

  @Test
  void aBareHostGetsTheDefaultPortAndHostPortIsParsed() {
    List<GatewayRoute> routes =
        RouteTable.buildRoutes(
            Optional.empty(),
            8080,
            Map.of("artifacts", "qits-artifacts", "observability", "qits-observability:9000"));

    GatewayRoute artifacts =
        routes.stream().filter(r -> r.name().equals("artifacts")).findFirst().get();
    GatewayRoute observability =
        routes.stream().filter(r -> r.name().equals("observability")).findFirst().get();
    assertEquals("qits-artifacts:8080", artifacts.upstream());
    assertEquals("qits-observability:9000", observability.upstream());
  }

  @Test
  void theAppHostBecomesTheCatchAllOnlyWhenPresent() {
    RouteTable withApp = RouteTable.of(RouteTable.buildRoutes(Optional.of("qits"), 8080, Map.of()));
    assertEquals("qits", matched(withApp, "/index.html"));

    RouteTable withoutApp = RouteTable.of(RouteTable.buildRoutes(Optional.empty(), 8080, Map.of()));
    assertEquals(null, matched(withoutApp, "/index.html"));
  }

  @Test
  void anUnknownServiceSegmentIsRejected() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> RouteTable.buildRoutes(Optional.empty(), 8080, Map.of("nope", "qits-nope")));
    assertTrue(e.getMessage().contains("nope"));
  }
}
