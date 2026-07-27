package eu.wohlben.qits.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The other half of the gate: {@code QITS_CAPTURE_ENDPOINT} injected but no OTLP endpoint. The
 * capture section relays verbatim while telemetry stays dark — a real deployment shape, and the
 * reason the document has two nullable sections rather than one "is this managed" flag.
 *
 * <p>The relayed URL is the deployment's, never composed here: the gateway does not know which
 * address is reachable from the browser that will use it.
 */
@QuarkusTest
@TestProfile(ConfigJsonCaptureTest.CaptureOnlyProfile.class)
class ConfigJsonCaptureTest {

  public static class CaptureOnlyProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.capture.endpoint", "http://qits-workspaces:8080/workspaces/api/capture",
          "otel.resource.attributes", "qits.workspace.id=work,qits.repository.id=repo");
    }
  }

  @Test
  void captureRelaysEndpointAndIdentityWhileTelemetryStaysNull() {
    given()
        .when()
        .get("/api/config.json")
        .then()
        .statusCode(200)
        .body("capture.ingestUrl", equalTo("http://qits-workspaces:8080/workspaces/api/capture"))
        .body("capture.resourceAttributes.'qits.repository.id'", equalTo("repo"))
        .body("capture.resourceAttributes.'qits.workspace.id'", equalTo("work"))
        .body("telemetry", nullValue());
  }
}
