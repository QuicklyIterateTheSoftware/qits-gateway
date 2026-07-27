package eu.wohlben.qits.gateway.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.gateway.StubUpstream;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code local} build target: explicitly unauthenticated, every request the fixed local user.
 *
 * <p>What this test is really for is the claim in migration-auth-plan.md §5 that <em>test and
 * production differ in one component</em> — a local gateway synthesizes an identity and emits the
 * same {@code X-Qits-*} headers as an authenticated one, so everything downstream is byte-identical
 * between targets. If that stopped being true, every service's assumptions would hold only in
 * production, which is the worst possible place to find out.
 *
 * <p>{@code qits.auth.variant} is a <b>build</b> property, so this needs a {@link TestProfile}:
 * Quarkus re-augments per profile, which is exactly what flipping an {@code @IfBuildProperty}
 * takes. That it cannot be flipped any other way is the security property, not an inconvenience.
 */
@QuarkusTest
@TestProfile(LocalVariantTest.LocalTarget.class)
@WithTestResource(StubUpstream.class)
class LocalVariantTest {

  public static class LocalTarget implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.auth.variant",
          "local",
          "qits.auth.local.user",
          "localdev",
          // What `-Dqits.variant=local` does through the matching Maven profile. Both are build
          // properties, and both have to be here or this exercises a target that is not the one
          // that ships: a local gateway has no IdP, and quarkus-oidc treats a missing
          // auth-server-url as fatal rather than as "not needed".
          "quarkus.oidc.enabled",
          "false");
    }
  }

  @Test
  void everyRequestIsTheFixedLocalUser() {
    given()
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=localdev"));
  }

  @Test
  void theDownstreamHeaderSetIsTheSameAsAnAuthenticatedOne() {
    // Same headers, same shape — a service cannot tell which target fronted it, which is the whole
    // reason this target is safe to test against.
    given()
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=localdev"))
        .body(containsString("x-qits-user-id=localdev"));
  }

  @Test
  void aSpoofedHeaderStillLoses() {
    // The open target is open about *authentication*, not about header hygiene. Nothing a client
    // sends is ever believed, in either target.
    given()
        .header("X-Qits-User", "admin")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=localdev"))
        .body(not(containsString("admin")));
  }

  @Test
  void authMeReportsTheOpenTarget() {
    // The SPA renders no sign-out link for this target — there is no session to end.
    given()
        .when()
        .get("/api/auth/me")
        .then()
        .statusCode(200)
        .body("variant", is("local"))
        .body("username", is("localdev"));
  }
}
