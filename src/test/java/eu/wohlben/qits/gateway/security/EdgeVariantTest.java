package eu.wohlben.qits.gateway.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.gateway.StubUpstream;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code edge} build target: the identity is the one {@code qits-platform-edge} already
 * established, carried on {@code X-Qits-User}, {@code X-Qits-User-Id} and {@code X-Qits-Roles}, and
 * re-asserted to the upstream from there.
 *
 * <p>What this class is really for is the hand-off. The edge validates a session cookie against
 * qits-idp and injects three headers; this process must turn them into a real {@link
 * io.quarkus.security.identity.SecurityIdentity} — principal, subject id and roles — and put the
 * same three back on the wire for a service to read. Anything lost in the middle is lost silently:
 * a dropped role turns {@code qits.auth.required-role} into a no-op, and a dropped username turns
 * every downstream audit row anonymous.
 *
 * <p><b>Why "spoofing" is not a case this target can fail.</b> {@code aSpoofedHeaderStillLoses} in
 * {@code LocalVariantTest} has no counterpart here, and the absence is the design rather than a
 * gap: in this target every inbound {@code X-Qits-*} <em>is</em> the identity. That is sound
 * because the edge is the only network path in — it strips the whole reserved prefix off client
 * traffic before it decides anything, then injects what the session says — and because anything
 * else that can reach this port is already on {@code qits-net}, where it can dial any service
 * directly and assert whatever it likes without a gateway in the way. The trusted-plane wart is
 * named in user-authentication-plan.md; this variant does not widen it, it consumes the same
 * forward-auth contract five services already consume, one hop earlier. What would be a real defect
 * is a build shipped `edge` with nothing in front of it, which is a deployment question the
 * enforcer message and the README carry.
 *
 * <p>{@code qits.auth.variant} is a <b>build</b> property, so this needs a {@link TestProfile}, the
 * same as {@code LocalVariantTest}: Quarkus re-augments per profile, which is what flipping an
 * {@code @IfBuildProperty} takes. Role enforcement needs a second profile and lives in {@link
 * EdgeVariantRoleTest}, because a required role cannot be set for one test method of a class.
 */
@QuarkusTest
@TestProfile(EdgeVariantTest.EdgeTarget.class)
@WithTestResource(StubUpstream.class)
class EdgeVariantTest {

  public static class EdgeTarget implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.auth.variant",
          "edge",
          // What `-Dqits.variant=edge` does through the matching Maven profile — both are build
          // properties and both have to be here, or this exercises a target that is not the one
          // that ships. There is no auth server in this topology (the edge terminates the session),
          // and quarkus-oidc treats a missing auth-server-url as fatal rather than as "not needed".
          "quarkus.oidc.enabled",
          "false");
    }
  }

  @Test
  void theForwardedIdentityBecomesTheIdentityAndIsAssertedUpstream() {
    // The whole hand-off in one request: username, subject id and the role set all survive into
    // what the upstream reads. The roles arrive joined the way the edge sends them — comma
    // separated, no spaces needed — because a role string never contains a comma.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-User-Id", "9f1c-4b2e")
        .header("X-Qits-Roles", "qits-platform:admin,qits:admin")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=alice"))
        .body(containsString("x-qits-user-id=9f1c-4b2e"))
        .body(containsString("x-qits-roles=qits-platform:admin,qits:admin"));
  }

  @Test
  void aRaggedRoleListLosesItsBlanksRatherThanBecomingARoleNamedEmpty() {
    // A naive join upstream leaves a trailing comma, and a hand-written value has spaces. Neither
    // may become a role: an empty-string role would match nothing and read like a bug forever, and
    // " qits:admin" is not the role the idp stored.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", " qits:admin , ,qits-platform:admin,")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-roles=qits:admin,qits-platform:admin"));
  }

  @Test
  void aHeaderlessRequestIsAnonymousAndStillProxied() {
    // The edge's session gate switched off (the rollout flag), or an internal dial on qits-net that
    // never crossed the edge. Both are ordinary states, and neither may be refused: this target's
    // policy permits anonymous because the edge is what refuses unauthenticated browser traffic.
    // The upstream is told nothing rather than told a name it cannot trust.
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=-"))
        .body(containsString("x-qits-user-id=-"))
        .body(containsString("x-qits-roles=-"));
  }

  @Test
  void aBlankUserHeaderIsNoIdentityAtAll() {
    // A header present but empty is what a misconfigured injector produces. It must read as
    // anonymous rather than authenticate a user named "", which would be a real principal
    // downstream and would satisfy every "is somebody logged in" check in the platform.
    given()
        .header("X-Qits-User", "   ")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=-"))
        // The roles go with the identity that is not there. Forwarding them alone would hand a
        // service a role set belonging to nobody.
        .body(containsString("x-qits-roles=-"));
  }

  @Test
  void anIdentityWithNoRolesAssertsNoRoleHeader() {
    // The common case while nothing assigns roles: a name and an id, and no role header at all.
    // Absent rather than empty, so a reader never has to tell "no roles" from "the header exists
    // and is blank".
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-User-Id", "9f1c-4b2e")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=alice"))
        .body(containsString("x-qits-roles=-"));
  }

  @Test
  void aRegistryWriteIsRefusedHereToo() {
    // RegistryWriteBlock is a route, not an authorization decision, precisely so it holds in a
    // target that authenticates nobody itself. A push carrying a perfectly good forwarded identity
    // is still refused, and the body must not carry the stub upstream's echo.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits-platform:admin")
        .body(new byte[] {1, 2, 3})
        .when()
        .post("/v2/qits/plausibility/blobs/uploads/")
        .then()
        .statusCode(403)
        .contentType(containsString("application/json"))
        .body(containsString("DENIED"))
        .body(not(containsString("body-bytes")));
  }

  @Test
  void authMeReportsTheEdgeTargetAndTheForwardedUser() {
    // spa-home's user chip reads this. It has to answer from the same header-derived identity as
    // everything else, or the header would show one user while the audit rows record another.
    given()
        .header("X-Qits-User", "alice")
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(200)
        .body("variant", is("edge"))
        .body("username", is("alice"));
  }

  @Test
  void authMeReportsNobodyWhenTheEdgeAssertedNobody() {
    given()
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(200)
        .body("variant", is("edge"))
        .body("username", nullValue());
  }
}
