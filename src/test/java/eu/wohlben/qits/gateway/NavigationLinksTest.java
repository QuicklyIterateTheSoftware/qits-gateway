package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.gateway.NavigationRoute.Link;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The navigation derivation, tested without booting an application — the rules with edge cases in
 * them (the {@code /v2} dedupe, unlabelled services, ordering, the always-present Home entry) live
 * on framework-free values exactly so they can be tested here.
 */
class NavigationLinksTest {

  /** The routes a service actually produces, so a case cannot invent a shape the table cannot. */
  private static List<GatewayRoute> routesFor(QitsService... services) {
    return java.util.Arrays.stream(services)
        .flatMap(
            service ->
                service.pathPrefixes().stream()
                    .map(prefix -> GatewayRoute.forService(service, prefix, "upstream", 8080)))
        .toList();
  }

  private static List<String> labels(List<Link> links) {
    return links.stream().map(Link::label).toList();
  }

  private static List<String> hrefs(List<Link> links) {
    return links.stream().map(Link::href).toList();
  }

  @Test
  void anEmptyRouteTableIsHomeAlone() {
    // A gateway that routes nothing still serves its own landing page — the bundle is in the
    // binary, not in the route table — so the menu is never empty. (It reports NOT READY, which is
    // where an unconfigured gateway is supposed to be visible; this document does not have to
    // double as the alarm.)
    assertEquals(List.of(new Link("Home", "/")), NavigationRoute.links(List.of()));
  }

  @Test
  void homeIsPrependedAndComesFirst() {
    List<Link> links = NavigationRoute.links(routesFor(QitsService.PLATFORM_DOCS, QitsService.CI));

    assertEquals(new Link("Home", "/"), links.getFirst());
    assertEquals(List.of("Home", "CI", "Docs"), labels(links));
  }

  @Test
  void onlyRoutedServicesAppear() {
    // THE REASON THE DERIVATION READS THE ROUTE TABLE and not QitsService.values(). A service the
    // deployment did not configure is not reachable, and a link to it is a link to the landing page
    // wearing another service's address.
    List<String> labels = labels(NavigationRoute.links(routesFor(QitsService.CI)));

    assertEquals(List.of("Home", "CI"), labels);
    assertFalse(labels.contains("Projects"));
  }

  @Test
  void anUnlabelledServiceIsRoutedButNotNavigable() {
    // stt has no SPA behind it and cd is superseded by platform-deployments. Both are routable, and
    // neither has a page to send a user to.
    assertEquals(
        List.of("Home"), labels(NavigationRoute.links(routesFor(QitsService.STT, QitsService.CD))));

    // …and the one that supersedes cd is the one that carries the label, on the same table.
    assertEquals(
        List.of("Home", "Deployments"),
        labels(
            NavigationRoute.links(
                routesFor(QitsService.CD, QitsService.PLATFORM_DEPLOYMENTS, QitsService.STT))));
  }

  @Test
  void theRegistryRootIsNeverALinkAndArtifactsAppearsOnce() {
    // qits-artifacts produces TWO routes from one proxy-hosts entry. /v2 is the address docker
    // hardcodes for the OCI Distribution API — a protocol root, not a page — so it must never reach
    // a menu, and the service it belongs to must not appear twice for having claimed it.
    List<Link> links = NavigationRoute.links(routesFor(QitsService.ARTIFACTS));

    assertEquals(List.of("Home", "Artifacts"), labels(links));
    assertEquals(List.of("/", "/artifacts/"), hrefs(links));
    assertFalse(hrefs(links).contains("/v2/"));
    assertFalse(hrefs(links).contains("/v2"));
  }

  @Test
  void theOrderIsTheEnumsAndNotTheRouteTables() {
    // The route table is sorted longest-prefix-first, which is a MATCHING concern: read off it, the
    // menu would start with /platform-deployments because that segment is the longest string. The
    // order a user sees is QitsService.navigationPosition(), whatever order the routes arrive in.
    List<Link> links =
        NavigationRoute.links(
            routesFor(
                QitsService.PLATFORM_DOCS,
                QitsService.OBSERVABILITY,
                QitsService.EVENTS,
                QitsService.WORKSPACES,
                QitsService.PROJECTS,
                QitsService.ARTIFACTS,
                QitsService.PLATFORM_DEPLOYMENTS,
                QitsService.CI));

    assertEquals(
        List.of(
            "Home",
            "CI",
            "Deployments",
            "Artifacts",
            "Projects",
            "Workspaces",
            "Events",
            "Observability",
            "Docs"),
        labels(links));
  }

  @Test
  void everyHrefEndsInASlash() {
    // The consuming library normalises both sides when it decides which entry is current, but it
    // renders the anchor verbatim — so the trailing slash is what a user sees and copies. Home is
    // "/" and is already one.
    List<Link> links =
        NavigationRoute.links(
            routesFor(QitsService.CI, QitsService.PLATFORM_DEPLOYMENTS, QitsService.PLATFORM_DOCS));

    assertEquals(List.of("/", "/ci/", "/platform-deployments/", "/platform-docs/"), hrefs(links));
    for (Link link : links) {
      assertTrue(link.href().startsWith("/"), link + " must be root-relative");
      assertTrue(link.href().endsWith("/"), link + " must end in a slash");
    }
  }
}
