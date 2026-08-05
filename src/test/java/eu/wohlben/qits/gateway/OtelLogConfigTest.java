package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The drift guard on this process' OTel log export — configuration only.
 *
 * <p><b>The behaviour is proven elsewhere, on purpose.</b> qits-events' {@code OtelLogBridgeTest}
 * takes an unchanged {@code org.jboss.logging.Logger} call all the way to a decoded {@code
 * ExportLogsServiceRequest}, and its {@code PackagedLogBridgeIT} repeats that against the packaged
 * artifact, where the handler's build-time initialisation is a different question. This repo
 * inherits that evidence rather than re-running it: the extension is the same {@code
 * quarkus-opentelemetry}, the Quarkus pin is the same, and the four keys below are the whole of
 * what a service configures. Ten copies of that protocol suite would prove the same thing ten
 * times, slowly.
 *
 * <p>What is NOT inherited is this repository's own configuration, which nothing else can see. The
 * failure this exists for is silent: a Quarkus upgrade that flips a default, a merge that drops a
 * line, a property renamed under it, and the gateway simply stops shipping logs — with a green
 * build, a healthy process and no error anywhere. Reading the assembled config back is the cheapest
 * thing that turns that into a red test.
 *
 * <p>The endpoint and protocol are pinned beside the four log keys because they are the same
 * failure: an exporter aimed at the gRPC default, or at localhost, exports nothing here whatever
 * the log keys say. The endpoint is asserted EXPANDED — {@code qits.observability.url} is the one
 * key a deployment moves, and this is what it resolves to when it does not.
 */
@QuarkusTest
class OtelLogConfigTest {

  @Inject Config config;

  private String value(String key) {
    return config.getValue(key, String.class);
  }

  @Test
  void logExportIsEnabledThroughTheHandlerAndTheSharedExporter() {
    assertEquals("true", value("quarkus.otel.logs.enabled"));
    // The JBoss Log Manager handler: without it the other keys describe a pipe nothing enters.
    assertEquals("true", value("quarkus.otel.logs.handler.enabled"));
    // `cdi` routes records at the exporter configured below, not at a second, separate one.
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));
  }

  @Test
  void theOutboundFloorIsInfoAndNotQuarkusAllDefault() {
    // The one deliberate narrowing: Quarkus exports every record the log manager creates. Losing
    // this line does not break export, it multiplies it — which is why it is asserted rather than
    // left to the default it is not.
    assertEquals("INFO", value("quarkus.otel.logs.level"));
  }

  @Test
  void theExporterAimsAtTheQitsReceiverOverHttpProtobuf() {
    // The receiver is an HTTP resource; the SDK default is gRPC to localhost:4317.
    assertEquals("http/protobuf", value("quarkus.otel.exporter.otlp.protocol"));
    // The exporter appends /v1/logs to this base.
    assertEquals(
        "http://qits-observability:8080/observability/api/otel",
        value("quarkus.otel.exporter.otlp.endpoint"));
  }
}
