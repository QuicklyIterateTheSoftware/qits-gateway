package eu.wohlben.qits.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The gateway end to end: a real request in, a real forwarded request at a real upstream. */
@QuarkusTest
@WithTestResource(StubUpstream.class)
class GatewayRoutingTest {

  @Test
  void forwardsVerbatimByDefault() {
    given()
        .when()
        .get("/verbatim/deep/path?q=1")
        .then()
        .statusCode(200)
        .body(containsString("path=/verbatim/deep/path?q=1"));
  }

  @Test
  void stripsThePrefixWhenTheRouteAsksForIt() {
    given()
        .when()
        .get("/stub/hello?q=1")
        .then()
        .statusCode(200)
        .body(containsString("path=/hello?q=1"))
        // …and tells the upstream what was removed, so it can still build outside-visible URLs.
        .body(containsString("x-forwarded-prefix=/stub"));
  }

  @Test
  void describesTheOriginalClientToTheUpstream() {
    given()
        .when()
        .get("/verbatim/x")
        .then()
        .statusCode(200)
        .body(containsString("x-forwarded-proto=http"))
        .body(containsString("x-forwarded-for=127.0.0.1"))
        .body(not(containsString("x-forwarded-host=-")));
  }

  @Test
  void dropsClientSuppliedIdentityHeaders() {
    // The forward-auth trust contract: qits believes Remote-User unconditionally, so a client must
    // never be able to set it through the front door.
    given()
        .header("Remote-User", "attacker")
        .when()
        .get("/verbatim/x")
        .then()
        .statusCode(200)
        .body(containsString("remote-user=-"));
  }

  @Test
  void unroutedPathsAreAnsweredLocallyWithoutConnectingAnywhere() {
    given()
        .when()
        .get("/nothing/here")
        .then()
        .statusCode(404)
        .body(containsString("No qits component is routed here."));
  }

  @Test
  void theGatewaysOwnManagementSurfaceIsNeverProxied() {
    given().when().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
  }
}
