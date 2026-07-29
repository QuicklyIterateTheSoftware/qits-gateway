package eu.wohlben.qits.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * The gateway end to end: a real request in, a real forwarded request at a real upstream.
 *
 * <p>Named caller throughout, because the gateway now authenticates: every path here except the
 * health probe is behind the policy, and an anonymous request would be challenged before routing
 * ever ran. {@code @TestSecurity} keeps these tests about <em>routing</em> — who gets in is {@link
 * eu.wohlben.qits.gateway.security.GatewayAuthTest}'s subject.
 */
@QuarkusTest
@TestSecurity(user = "dev")
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
  void theRegistryRootReachesArtifactsVerbatim() {
    // /v2 is the first prefix in the system that is not /<segment>, so nothing else in this suite
    // proves the gateway forwards it at all — let alone unrewritten.
    given().when().get("/v2/").then().statusCode(200).body(containsString("path=/v2/"));
  }

  @Test
  void aMultiSlashRegistryPathIsForwardedUnrewritten() {
    // The single most important registry assertion here. An OCI name may contain slashes, and the
    // service splits repository from image on the FIRST one — so a gateway that normalised, merged
    // or stripped any part of this path would break a push in a way only a real client would show.
    given()
        .when()
        .get("/v2/qits/build-images/ci-base/manifests/latest")
        .then()
        .statusCode(200)
        .body(containsString("path=/v2/qits/build-images/ci-base/manifests/latest"));
  }

  @Test
  void aDigestReferenceSurvivesTheColonInThePath() {
    // Worth pinning because a reader will wonder: RouteTable parses host:port with lastIndexOf(':')
    // and a digest puts a colon in the path. Nothing on the way through may touch it.
    String digest = "sha256:" + "0".repeat(64);
    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/qits/alpine/blobs/" + digest)
        .then()
        .statusCode(200)
        .body(containsString("path=/v2/qits/alpine/blobs/" + digest));
  }

  @Test
  void aBodyLargerThanTheQuarkusDefaultWireLimitStreamsThrough() {
    // The gateway analogue of the artifacts suite's oversized-upload test, and the only automated
    // guard against quarkus.http.limits.max-body-size being lowered back below what a layer needs.
    // A push through here would otherwise 413 at the front door, bodiless, before the registry
    // could answer with the spec's error envelope.
    byte[] body = new byte[12 * 1024 * 1024];
    given()
        .body(body)
        .when()
        .post("/v2/qits/alpine/blobs/uploads/x")
        .then()
        .statusCode(200)
        .body(containsString("body-bytes=" + body.length));
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
  void clientSuppliedReservedHeadersAreReplacedNotForwarded() {
    // The gateway's own namespace: an upstream believes X-Qits-User unconditionally, so a client
    // setting it through the front door would be a complete authentication bypass. Stripped by
    // prefix, which is why the casing a client picks cannot matter — and only then replaced with
    // what this request actually authenticated as.
    given()
        .header("X-Qits-User", "attacker")
        .header("x-qits-user-id", "00000000-0000-0000-0000-000000000000")
        .header("X-QITS-GROUPS", "admin")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=dev"))
        .body(not(containsString("attacker")))
        .body(not(containsString("admin")))
        .body(not(containsString("00000000")));
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
