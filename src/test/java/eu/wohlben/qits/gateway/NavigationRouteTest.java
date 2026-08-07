package eu.wohlben.qits.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /main-navigation} against a real route table: {@link StubUpstream} routes {@code
 * artifacts}, {@code observability} and {@code projects} and nothing else, so what this asserts is
 * the derivation running end to end on a gateway's actual configuration rather than on a list.
 *
 * <p>Named caller like {@code GatewayRoutingTest}, for the same reason: this is a test about the
 * document, not about who may read it. That the path is reachable with no session at all — the
 * chrome renders before there is anything to authenticate — is {@code PublicPathsTest}'s subject.
 */
@QuarkusTest
@TestSecurity(user = "dev")
@WithTestResource(StubUpstream.class)
class NavigationRouteTest {

  @Test
  void theNavigationIsExactlyTheRoutedServices() {
    // Home first and always; then the three configured services in the enum's order (artifacts 3,
    // projects 4, observability 7) rather than the route table's longest-prefix one. Everything
    // else in the registry is unconfigured here, so it has no link — which is the whole point.
    given()
        .when()
        .get("/main-navigation")
        .then()
        .statusCode(200)
        .contentType(containsString("application/json"))
        .body("links.label", contains("Home", "Artifacts", "Projects", "Observability"))
        .body("links.href", contains("/", "/artifacts/", "/projects/", "/observability/"));
  }

  @Test
  void theRegistryRootIsNotOfferedAsAPage() {
    // artifacts is routed here and produces a second route for /v2, the OCI Distribution root that
    // docker hardcodes. It reaches the upstream (GatewayRoutingTest) and it is not a page.
    given()
        .when()
        .get("/main-navigation")
        .then()
        .statusCode(200)
        .body(not(containsString("/v2")))
        .body("links.href", everyItem(not(is("/v2/"))));
  }

  @Test
  void anUnroutedServiceGetsNoLink() {
    // `stt` is unconfigured here AND unlabelled, `ci` is labelled but unconfigured. Neither is
    // reachable through this gateway, so neither may be offered.
    given()
        .when()
        .get("/main-navigation")
        .then()
        .statusCode(200)
        .body(not(containsString("/stt/")))
        .body(not(containsString("\"CI\"")));
  }

  @Test
  void theDocumentIsNeverCached() {
    // The route table is a deployment fact: a browser holding yesterday's copy renders a menu
    // missing the service it was just told to go and use.
    given()
        .when()
        .get("/main-navigation")
        .then()
        .statusCode(200)
        .header("Cache-Control", is("no-store"));
  }

  @Test
  void headIsAnsweredByThisRouteAndNotByWhatIsLayeredBehindIt() {
    // Same shape of hole ConfigJsonRoute's javadoc records: router.get() matches GET only, and a
    // HEAD that fell past this handler would be answered by the SPA fallback with a web page's
    // headers. Quinoa is off in the suite, so what this can prove is that HEAD is handled at all,
    // with the same status and the same content type as the GET.
    given()
        .when()
        .head("/main-navigation")
        .then()
        .statusCode(200)
        .contentType(containsString("application/json"))
        .header("Cache-Control", is("no-store"));
  }
}
