package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
            route("observability", "/observability"),
            route("artifacts", "/artifacts"),
            route("workspaces", "/workspaces/api"));

    assertEquals("artifacts", matched(table, "/artifacts/blobs/abc"));
    assertEquals("observability", matched(table, "/observability/v1/traces"));
    // The longer prefix wins over the shorter one that also matches, whichever was declared first.
    assertEquals("workspaces", matched(table, "/workspaces/api/x"));
    // And nothing claims what no prefix covers — there is no catch-all to fall back to.
    assertEquals(null, matched(table, "/index.html"));
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
  void anUnmatchedPathHasNoRoute() {
    // There is no catch-all any more, so this is the ONLY outcome for an unclaimed path: no route,
    // and a 404 from the gateway itself rather than a forward to a default upstream.
    assertEquals(null, matched(table(route("artifacts", "/artifacts")), "/git/repo"));
    assertEquals(null, matched(table(route("artifacts", "/artifacts")), "/index.html"));
  }

  @Test
  void prefixesAreNormalised() {
    // Trailing slashes and a missing leading slash all name the same route.
    assertEquals("/artifacts", route("a", "/artifacts/").prefix());
    assertEquals("/artifacts", route("a", "artifacts").prefix());
  }

  /**
   * `/` and `""` used to normalise to the catch-all. With the monolith gone there is no upstream
   * entitled to every unclaimed path, so a blank prefix is rejected rather than quietly reviving
   * one — a config value that lost its content would otherwise turn a service route into a
   * catch-all.
   */
  @Test
  void anEmptyPrefixIsRejectedRatherThanBecomingACatchAll() {
    assertThrows(IllegalArgumentException.class, () -> route("a", "/"));
    assertThrows(IllegalArgumentException.class, () -> route("a", ""));
    assertThrows(IllegalArgumentException.class, () -> route("a", "  "));
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
            Map.of("artifacts", "qits-artifacts", "observability", "qits-observability:9000"));

    GatewayRoute artifacts =
        routes.stream().filter(r -> r.name().equals("artifacts")).findFirst().get();
    GatewayRoute observability =
        routes.stream().filter(r -> r.name().equals("observability")).findFirst().get();
    assertEquals("qits-artifacts:8080", artifacts.upstream());
    assertEquals("qits-observability:9000", observability.upstream());
  }

  /**
   * No configuration produces a catch-all any more. The monolith's app-host key is gone, so an
   * empty registry is an empty table — a gateway that routes nothing and reports not-ready, rather
   * than one that forwards everything somewhere.
   */
  @Test
  void anEmptyRegistryProducesAnEmptyTable() {
    RouteTable empty = RouteTable.of(RouteTable.buildRoutes(Map.of()));

    assertTrue(empty.isEmpty());
    assertEquals(null, matched(empty, "/index.html"));
    assertEquals(null, matched(empty, "/"));
  }

  @Test
  void anUnknownServiceSegmentIsRejected() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> RouteTable.buildRoutes(Map.of("nope", "qits-nope")));
    assertTrue(e.getMessage().contains("nope"));
  }
}
