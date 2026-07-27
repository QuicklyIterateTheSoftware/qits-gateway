package eu.wohlben.qits.gateway.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.gateway.StubUpstream;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * The front door as a front door: who gets in, who is turned away, and what an upstream is told
 * about whoever got in.
 *
 * <p>The login itself is quarkus-oidc's and is not re-tested here — what this repo owns is the
 * policy, the token-free allowlist, and the header assertion. Callers are therefore named with
 * {@code @TestSecurity} rather than driven through a code flow against a stubbed IdP: the
 * identity's <em>provenance</em> is the extension's business, its <em>consequences</em> are ours.
 */
@QuarkusTest
@WithTestResource(StubUpstream.class)
class GatewayAuthTest {

  @Test
  void anUnauthenticatedNavigationIsSentToTheLoginAndNeverReachesAnUpstream() {
    // The whole point of terminating here: the upstream is not consulted about whether to let
    // someone in, because it cannot be — it has no authentication of its own by design.
    //
    // The exact status matters. A 404 would mean the request was turned away for the wrong reason
    // and this test would prove nothing; theGatewayAssertsThePrincipalToTheUpstream answers the
    // same path with 200, which is what makes this a statement about the policy rather than about
    // the route table.
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(302)
        .header("location", containsString("/protocol/openid-connect/auth"));
  }

  @Test
  void anUnauthenticatedBackgroundTransportIsRefusedRatherThanRedirected() {
    // NonNavigationRequestChecker's reason for existing, and it matters more here than it did in
    // the monolith: every SSE channel and every websocket in the deployment passes through this one
    // process. A 302 at an EventSource is followed into the IdP's HTML and then dies — and worse,
    // it mints a q_auth state cookie that clobbers the one an in-flight document code flow needs,
    // which is the reload loop this replaced. 499 mints no cookie.
    given()
        .header("Accept", "text/event-stream")
        .redirects()
        .follow(false)
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(499);

    given()
        .header("Sec-Fetch-Mode", "cors")
        .redirects()
        .follow(false)
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(499);
  }

  @Test
  @TestSecurity(user = "alice")
  void theGatewayAssertsThePrincipalToTheUpstream() {
    given()
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=alice"));
  }

  @Test
  @TestSecurity(user = "alice")
  void aSpoofedHeaderLosesToTheAuthenticatedOne() {
    // The bypass this whole design lives or dies on. Strip and inject are both in EdgeHeaders and
    // in that order, so a forged X-Qits-User cannot survive to be believed by an upstream that
    // believes it unconditionally.
    given()
        .header("X-Qits-User", "admin")
        .header("x-qits-user-id", "spoofed")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=alice"))
        .body(not(containsString("admin")))
        .body(not(containsString("spoofed")));
  }

  @Test
  void tokenFreePathsAreNotChallenged() {
    // Callers on this list hold no user token by construction — health probes here; container
    // clients (git, OTLP, MCP) in PublicPathsTest. Demanding an identity of them would break them
    // with no security gained.
    given().when().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
  }

  @Test
  void authMeIsPublicAndReportsNobodyWhenNobodyIsLoggedIn() {
    given()
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(200)
        .body("variant", is("oauth"))
        .body("username", nullValue());
  }

  @Test
  @TestSecurity(user = "alice")
  void authMeNamesTheLoggedInUser() {
    given()
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(200)
        .body("variant", is("oauth"))
        .body("username", is("alice"));
  }
}
