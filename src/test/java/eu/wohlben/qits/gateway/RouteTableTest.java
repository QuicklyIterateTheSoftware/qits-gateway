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
    // Three routes from two entries: a route is created per CLAIMED PREFIX, not per service, and
    // artifacts claims /v2 as well (see oneConfigEntryCanProduceMoreThanOneRoute). Every other
    // service claims exactly its segment, which QitsServiceTest holds to.
    assertEquals(3, routes.size());
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

  @Test
  void oneConfigEntryCanProduceMoreThanOneRoute() {
    // qits-artifacts serves the OCI registry at the root-level /v2 that docker hardcodes, so its
    // single proxy-hosts entry has to claim two prefixes reaching the same upstream. One entry, not
    // two keys a deployment must hold in sync.
    List<GatewayRoute> routes = RouteTable.buildRoutes(Map.of("artifacts", "qits-artifacts"));
    assertEquals(2, routes.size());
    assertTrue(routes.stream().allMatch(r -> r.name().equals("artifacts")));
    assertTrue(routes.stream().allMatch(r -> r.upstream().equals("qits-artifacts:8080")));

    RouteTable table = RouteTable.of(routes);
    assertEquals("artifacts", matched(table, "/artifacts/api"));
    assertEquals("artifacts", matched(table, "/v2"));
    assertEquals("artifacts", matched(table, "/v2/"));
    assertEquals("artifacts", matched(table, "/v2/qits/build-images/ci-base/manifests/latest"));
  }

  @Test
  void theRegistryRootIsSegmentAwareLikeEveryOtherPrefix() {
    RouteTable table = RouteTable.of(RouteTable.buildRoutes(Map.of("artifacts", "qits-artifacts")));
    assertEquals(null, matched(table, "/v2x"));
    assertEquals(null, matched(table, "/v20/x"));
  }

  @Test
  void theRegistryRootIsRoutedOnlyWhenArtifactsIs() {
    // /v2 is not a standing claim on every gateway: it rides the artifacts entry and appears only
    // when a deployment has actually routed that service.
    RouteTable table =
        RouteTable.of(RouteTable.buildRoutes(Map.of("observability", "qits-observability")));
    assertEquals(null, matched(table, "/v2/"));
  }

  @Test
  void theRegistryRootIsNotAProxyHostsKey() {
    // An extra prefix rides its service's entry. Naming it directly is still the unknown-service
    // startup error it always was, which is what keeps the config surface one key per service.
    assertThrows(
        IllegalArgumentException.class,
        () -> RouteTable.buildRoutes(Map.of("v2", "qits-artifacts")));
  }

  @Test
  void routeOrderIsTotalEvenWhenNamesRepeat() {
    // The comparator used to tie-break by name, which stopped being total once one service could
    // produce several routes. Two equal-length prefixes under one name must still sort
    // deterministically rather than falling back to map iteration order.
    RouteTable one = table(route("artifacts", "/aaaa"), route("artifacts", "/bbbb"));
    RouteTable other = table(route("artifacts", "/bbbb"), route("artifacts", "/aaaa"));
    assertEquals(
        one.routes().stream().map(GatewayRoute::prefix).toList(),
        other.routes().stream().map(GatewayRoute::prefix).toList());
  }
}
