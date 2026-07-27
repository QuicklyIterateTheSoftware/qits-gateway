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
  void forwardsToTheServiceVerbatim() {
    // /artifacts/… reaches qits-artifacts as /artifacts/… — the public path is not stripped.
    given()
        .when()
        .get("/artifacts/deep/path?q=1")
        .then()
        .statusCode(200)
        .body(containsString("path=/artifacts/deep/path?q=1"));
  }

  @Test
  void describesTheOriginalClientToTheUpstream() {
    given()
        .when()
        .get("/artifacts/x")
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
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("remote-user=-"));
  }

  @Test
  void clientSuppliedReservedHeadersNeverReachAnUpstream() {
    // The gateway's own namespace: an upstream believes X-Qits-User unconditionally, so a client
    // setting it through the front door would be a complete authentication bypass. Stripped by
    // prefix, which is why the casing a client picks cannot matter.
    given()
        .header("X-Qits-User", "attacker")
        .header("x-qits-user-id", "00000000-0000-0000-0000-000000000000")
        .header("X-QITS-GROUPS", "admin")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=-"))
        .body(containsString("x-qits-user-id=-"))
        .body(not(containsString("attacker")))
        .body(not(containsString("admin")));
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
  void aSegmentThatIsNotAConfiguredServiceIsNotRouted() {
    // `stt` is a known service but has no proxy-hosts entry in the test config, so it is not live —
    // only services the deployment actually enabled are routed.
    given().when().get("/stt/x").then().statusCode(404);
  }

  @Test
  void theGatewaysOwnManagementSurfaceIsNeverProxied() {
    given().when().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
  }
}
