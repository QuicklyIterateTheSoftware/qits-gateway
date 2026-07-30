package eu.wohlben.qits.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The unconfigured (standalone, not-managed) case: both relay sections report dark.
 *
 * <p>The 200 is itself an assertion about routing. Nothing claims {@code /} any more — the monolith
 * catch-all is gone — so if {@link ConfigJsonRoute} did not claim this path ahead of {@code
 * GatewayRouter}, the answer would be the gateway's own 404. That it is served here at all is the
 * point: {@code /api/config.json} belongs to the gateway itself, not to any segment.
 *
 * <p>No {@code @TestSecurity}: the path is on {@code PublicPaths}, because {@code @qits/angular}
 * fetches it pre-bootstrap with no session. Reaching a 200 anonymously is part of the contract.
 */
@QuarkusTest
class ConfigJsonRouteTest {

  @Test
  void configReportsBothSectionsDarkWhenNothingIsInjected() {
    given()
        .when()
        .get("/api/config.json")
        .then()
        .statusCode(200)
        .contentType("application/json")
        .body("telemetry", nullValue())
        .body("capture", nullValue());
  }

  @Test
  void theDocumentIsNeverCached() {
    // Pre-bootstrap identity: a cached copy would outlive the deployment that injected it.
    given()
        .when()
        .get("/api/config.json")
        .then()
        .statusCode(200)
        .header("Cache-Control", is("no-store"));
  }

  @Test
  void headIsAnsweredByThisRouteAndNotByTheLandingBundlesStub() {
    // The regression this exists for is invisible from inside the suite, so it is worth spelling
    // out: the landing SPA ships a public/api/config.json stub for a standalone `ng serve`, and it
    // lands in the packaged bundle as a static resource on THIS path. Quinoa is off here, so the
    // stub does not exist and this only asserts that HEAD is handled at all — but on the packaged
    // image, a HEAD that fell past this route was answered by the stub with
    // `Cache-Control: public, immutable, max-age=86400`, i.e. an invitation to keep the wrong
    // identity document for a day. `router.get()` matched GET only; the route now names both.
    given()
        .when()
        .head("/api/config.json")
        .then()
        .statusCode(200)
        .header("Cache-Control", is("no-store"));
  }
}
