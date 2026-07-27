package eu.wohlben.qits.gateway.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import eu.wohlben.qits.gateway.StubUpstream;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.auth.required-role} — the system's only authorization decision, made here and nowhere
 * else.
 *
 * <p>Worth its own class because of what it implies elsewhere: since the gateway checks the role
 * and emits <b>no</b> groups header, an upstream cannot repeat the check even if it wanted to.
 * Anyone later tempted to add a per-resource role decision to a service should find this test and
 * the absence of any role in what the services receive.
 */
@QuarkusTest
@TestProfile(RequiredRoleTest.RoleRequired.class)
@WithTestResource(StubUpstream.class)
class RequiredRoleTest {

  public static class RoleRequired implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.auth.required-role", "qits-user");
    }
  }

  @Test
  @TestSecurity(user = "alice", roles = "qits-user")
  void aUserWithTheRoleIsProxied() {
    given()
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=alice"));
  }

  @Test
  @TestSecurity(user = "mallory", roles = "some-other-role")
  void anAuthenticatedUserWithoutTheRoleIsForbiddenRatherThanChallenged() {
    // 403, not a challenge: they are logged in, they are just not allowed. Re-authenticating would
    // not help, and a challenge here is how you get a redirect loop. The 403 also *is* the proof
    // the request was never proxied — anything the stub upstream answered would have been a 200.
    given().redirects().follow(false).when().get("/artifacts/x").then().statusCode(403);
  }
}
