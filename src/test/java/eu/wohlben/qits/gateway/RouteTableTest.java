package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The matching and rewriting rules, tested without booting an application — this is where the edge
 * cases live (segment-aware prefixes, longest-match precedence, the empty remainder after a strip).
 */
class RouteTableTest {

  private static GatewayRoute route(String name, String prefix, boolean stripPrefix) {
    return new GatewayRoute(name, prefix, "upstream-" + name, 8080, stripPrefix, Optional.empty());
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
            route("qits", "/", false),
            route("api", "/api", false),
            route("artifacts", "/api/artifacts", false));

    assertEquals("artifacts", matched(table, "/api/artifacts/blobs/abc"));
    assertEquals("api", matched(table, "/api/projects"));
    assertEquals("qits", matched(table, "/index.html"));
  }

  @Test
  void prefixMatchingIsSegmentAware() {
    RouteTable table = table(route("artifacts", "/api/artifacts", false));

    assertEquals("artifacts", matched(table, "/api/artifacts"));
    assertEquals("artifacts", matched(table, "/api/artifacts/"));
    assertEquals("artifacts", matched(table, "/api/artifacts/blobs"));
    // A path that merely starts with the same characters belongs to somebody else.
    assertEquals(null, matched(table, "/api/artifactsx"));
    assertEquals(null, matched(table, "/api/art"));
  }

  @Test
  void unmatchedPathHasNoRouteWhenThereIsNoCatchAll() {
    assertEquals(null, matched(table(route("api", "/api", false)), "/git/repo"));
  }

  @Test
  void prefixesAreNormalised() {
    // Trailing slashes and a missing leading slash all name the same route; `/` is the catch-all.
    assertEquals("/api/artifacts", route("a", "/api/artifacts/", false).prefix());
    assertEquals("/api/artifacts", route("a", "api/artifacts", false).prefix());
    assertTrue(route("a", "/", false).isCatchAll());
    assertTrue(route("a", "", false).isCatchAll());
  }

  @Test
  void rewriteStripsThePrefixOnlyWhenAsked() {
    assertEquals(
        "/api/artifacts/blobs",
        route("a", "/api/artifacts", false).rewrite("/api/artifacts/blobs"));
    assertEquals("/blobs", route("a", "/api/artifacts", true).rewrite("/api/artifacts/blobs"));
  }

  @Test
  void strippingTheWholePathLeavesARootSlash() {
    // "" is not a legal request target — the remainder must keep a leading slash.
    assertEquals("/", route("a", "/api/artifacts", true).rewrite("/api/artifacts"));
  }

  @Test
  void catchAllIsNeverStripped() {
    assertEquals("/anything", route("a", "/", true).rewrite("/anything"));
  }

  @Test
  void anEmptyTableRoutesNothing() {
    assertTrue(table().isEmpty());
    assertEquals(null, matched(table(), "/anything"));
    assertFalse(table(route("api", "/api", false)).isEmpty());
  }
}
