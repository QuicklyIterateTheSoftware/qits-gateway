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
  void theRegistryVersionProbeIsAnsweredRatherThanRedirected() {
    // Anonymous, and it must be a 200. A registry client sends no Sec-Fetch-Mode, no
    // X-Requested-With and no Accept: text/event-stream, so NonNavigationRequestChecker keeps the
    // redirect default — meaning an unlisted /v2 would 302 into Keycloak's HTML login, which docker
    // reads as "not a v2 registry". PublicPaths is what prevents that, and this is the proof.
    given().redirects().follow(false).when().get("/v2/").then().statusCode(200);
  }

  @Test
  void aStoredCredentialOnAPullStillReachesTheRegistry() {
    // docker sends stored Basic credentials on reads too once a host has any in its config. The
    // registry ignores them (it carries no auth at all), but the gateway must neither let hybrid
    // quarkus-oidc eat a non-Bearer Authorization on a public path nor challenge because of it —
    // otherwise a host that ever logged in anywhere pulls differently than a clean one.
    given()
        .redirects()
        .follow(false)
        .header("Authorization", "Basic cWl0czp0b2tlbg==")
        .when()
        .get("/v2/qits/alpine/manifests/latest")
        .then()
        .statusCode(200)
        .body(containsString("authorization=Basic cWl0czp0b2tlbg=="));
  }

  @Test
  void aPushIsRefusedAtTheGatewayAndNeverReachesTheRegistry() {
    // The registry carries no write guard of its own any more, so THIS refusal is its whole
    // external write protection: /v2 is public for read methods only, and a write falls back to
    // the session policy — a challenge no registry client can answer, which is the point (external
    // push is unwanted entirely; producers dial the registry on qits-net). The body must not carry
    // the stub upstream's echo: the request has to die here, not there.
    given()
        .redirects()
        .follow(false)
        .header("Authorization", "Basic cWl0czp0b2tlbg==")
        .when()
        .post("/v2/qits/alpine/blobs/uploads/")
        .then()
        .statusCode(401)
        .body(not(containsString("authorization=")));

    // A bare anonymous push gets the mechanism's redirect default instead (no Authorization, none
    // of the non-navigation signals) — a 302 into the IdP's HTML login, which no registry client
    // can follow. A different dead end, the same refusal.
    given()
        .redirects()
        .follow(false)
        .when()
        .post("/v2/qits/alpine/blobs/uploads/")
        .then()
        .statusCode(302)
        .body(not(containsString("authorization=")));
  }

  @Test
  void theRegistryPrefixDoesNotBleedAtThePolicyLayer() {
    // End-to-end proof that /v2 did not widen into a neighbouring root: /v2x is not the registry,
    // so it is still challenged like anything else nobody made public.
    given().redirects().follow(false).when().get("/v2x/y").then().statusCode(302);
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
