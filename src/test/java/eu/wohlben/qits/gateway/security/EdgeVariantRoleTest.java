package eu.wohlben.qits.gateway.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import eu.wohlben.qits.gateway.StubUpstream;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.auth.required-role} in the {@code edge} target — {@link RequiredRoleTest}'s subject
 * with the identity coming off the wire instead of out of a login.
 *
 * <p>This is the half of the target that is easy to lose. Permitting anonymous requests is the
 * headline of the {@code edge} policy, and a check written as "this target permits" rather than
 * "this target permits <em>anonymous</em>" would silently stop enforcing the one authorization
 * decision the system has, on every authenticated request, with every test still green. The two
 * sentences of that rule are asserted here one test each.
 *
 * <p>A class of its own rather than two more methods in {@link EdgeVariantTest}, for the mechanical
 * reason {@code RequiredRoleTest} is one: a required role is configuration, {@code @TestProfile} is
 * per class, and Quarkus re-augments per profile.
 */
@QuarkusTest
@TestProfile(EdgeVariantRoleTest.EdgeTargetWithRole.class)
@WithTestResource(StubUpstream.class)
class EdgeVariantRoleTest {

  public static class EdgeTargetWithRole implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.auth.variant",
          "edge",
          "quarkus.oidc.enabled",
          "false",
          "qits.auth.required-role",
          "qits:admin");
    }
  }

  @Test
  void aForwardedRoleSetCarryingTheRequiredRoleIsProxied() {
    // The roles the edge forwarded are the ones the policy checks — they are not decoration on the
    // way through. One of several is enough, as everywhere else.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits-platform:admin,qits:admin")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=alice"));
  }

  @Test
  void aForwardedIdentityWithoutTheRoleIsForbiddenRatherThanChallenged() {
    // 403, not a challenge: the edge already said who this is, so re-authenticating cannot help —
    // and this process owns no login to send anyone to anyway. The 403 also IS the proof the
    // request was never proxied; the stub upstream would have answered 200.
    given()
        .redirects()
        .follow(false)
        .header("X-Qits-User", "mallory")
        .header("X-Qits-Roles", "qits:reader")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(403);
  }

  @Test
  void aForwardedIdentityWithNoRolesAtAllIsForbidden() {
    // The identity is present, so the check applies; there is simply nothing to match.
    given()
        .redirects()
        .follow(false)
        .header("X-Qits-User", "mallory")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(403);
  }

  @Test
  void aHeaderlessRequestIsStillPermittedEvenWithARoleRequired() {
    // The second sentence of the rule, and the one worth writing down: a required role does NOT
    // turn the anonymous permit off. There is no identity to check a role against, and the callers
    // that arrive headerless — the edge with its session gate off, an internal dial on qits-net —
    // are exactly the ones this target must not refuse. Refusing them would take the platform down
    // in the rollout window, which is the failure this ordering exists to avoid.
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=-"));
  }
}
