package eu.wohlben.qits.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The managed case: the browser cannot read {@code OTEL_*}, so this process relays them, parsing
 * the {@code k=v,k=v} attribute list the supervising qits injects. Capture is absent here, which is
 * half of the point — the two sections gate independently.
 */
@QuarkusTest
@TestProfile(ConfigJsonRelayTest.TelemetryOnlyProfile.class)
class ConfigJsonRelayTest {

  public static class TelemetryOnlyProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "otel.exporter.otlp.endpoint", "http://qits-observability:8080/observability/api/otel",
          "otel.service.name", "qits-dev",
          "otel.resource.attributes",
              "qits.workspace.id=ws-1,qits.repository.id=repo-1,qits.command.id=cmd-1");
    }
  }

  @Test
  void configRelaysParsedIdentityWhileCaptureStaysNull() {
    given()
        .when()
        .get("/api/config.json")
        .then()
        .statusCode(200)
        .body("telemetry.serviceName", is("qits-dev"))
        .body("telemetry.resourceAttributes.'qits.workspace.id'", is("ws-1"))
        .body("telemetry.resourceAttributes.'qits.repository.id'", is("repo-1"))
        .body("telemetry.resourceAttributes.'qits.command.id'", is("cmd-1"))
        .body("capture", nullValue());
  }
}
