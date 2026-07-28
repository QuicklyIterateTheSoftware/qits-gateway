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
}
