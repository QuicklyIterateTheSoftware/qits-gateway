package eu.wohlben.qits.gateway.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit over the framework-free path matcher — the public/protected split in one place.
 *
 * <p>Cases are worth keeping literal: every "prefix must not bleed" case below is a path that would
 * silently become public if someone reached for {@code startsWith} where the original used {@code
 * equals}. An entry with no test is an entry that can be silently deleted later, so every line of
 * {@link PublicPaths} is named here at least once.
 *
 * <p>The nesting mirrors {@link PublicPaths}' three groups, and {@link OnTheMonolith} exists to be
 * deleted in one piece together with the method it covers, when {@code qits.gateway.app-host} goes.
 */
class PublicPathsTest {

  @Nested
  class TheGatewaysOwnSurface {

    @Test
    void healthAndFrameworkPathsArePublic() {
      assertTrue(PublicPaths.isPublic("/q"));
      assertTrue(PublicPaths.isPublic("/q/health"));
      assertTrue(PublicPaths.isPublic("/q/health/ready"));
      assertFalse(PublicPaths.isPublic("/qq")); // prefix must not bleed past the segment
    }

    @Test
    void aServicesOwnManagementSurfaceIsNotPublicThroughTheGateway() {
      // Each service's /q/* has just moved under its segment. It stays behind the policy: the only
      // callers that MUST reach it (container healthchecks, orchestrator probes) dial the service
      // directly on qits-net and never traverse the gateway, so what is exposed here is swagger-ui,
      // the OpenAPI document and deployment detail — for humans, who have a session.
      assertFalse(PublicPaths.isPublic("/observability/q/openapi"));
      assertFalse(PublicPaths.isPublic("/observability/q/swagger-ui"));
      assertFalse(PublicPaths.isPublic("/ci/q/health/ready"));
      assertFalse(PublicPaths.isPublic("/workspaces/q"));
    }

    @Test
    void configRelayIsPublicExactlyNotAsPrefix() {
      assertTrue(PublicPaths.isPublic("/api/config.json"));
      assertFalse(PublicPaths.isPublic("/api/config.json/extra"));
      assertFalse(PublicPaths.isPublic("/api/config"));
    }

    @Test
    void authEndpointsArePublic() {
      assertTrue(PublicPaths.isPublic("/api/auth/me"));
      assertTrue(PublicPaths.isPublic("/api/auth/logout"));
    }
  }

  @Nested
  class OnAService {

    @Test
    void theGitHostSubtreeIsPublicUnderTheArtifactsSegment() {
      assertTrue(PublicPaths.isPublic("/artifacts/git/abc-123/info/refs"));
      assertTrue(PublicPaths.isPublic("/artifacts/git/proj-1/repo/git-receive-pack"));
      assertFalse(PublicPaths.isPublic("/artifacts/git")); // only the subtree, not the bare path
      assertFalse(PublicPaths.isPublic("/artifacts/gitignore")); // prefix must not bleed
    }

    @Test
    void theBlobStoreIsPublicUnderTheArtifactsSegment() {
      // Token-free at the session-policy layer — CI uploaders hold no session; writes are guarded
      // by the static-token filter in the service, reads must work as a plain <img> src.
      assertTrue(PublicPaths.isPublic("/artifacts/api"));
      assertTrue(PublicPaths.isPublic("/artifacts/api/repositories/ci-screenshots/blobs"));
      assertTrue(
          PublicPaths.isPublic(
              "/artifacts/api/repositories/ci-screenshots/blobs/"
                  + "0000000000000000000000000000000000000000000000000000000000000000"));
      assertFalse(PublicPaths.isPublic("/artifacts/apiary")); // prefix must not bleed
    }

    @Test
    void onlyTheCiEventIntakeIsPublic() {
      // The intake is token-free at the session-policy layer (the git host's post-receive hook
      // holds no session, and after extraction is another process) and guarded by the static-token
      // filter in the service.
      assertTrue(PublicPaths.isPublic("/ci/api/events/post-receive"));
      // Run READS are not public: step output is the build log of a possibly private repository,
      // and repo ids are handed to containers/clone urls, so anonymous reads would leak them.
      assertFalse(PublicPaths.isPublic("/ci/api/runs"));
      assertFalse(PublicPaths.isPublic("/ci/api/runs/run-1"));
      assertFalse(PublicPaths.isPublic("/ci/api"));
      assertFalse(PublicPaths.isPublic("/ci/api/events")); // only the subtree, not the bare path
      assertFalse(PublicPaths.isPublic("/cinema/api/events/x")); // prefix must not bleed
    }

    @Test
    void otlpIngestIsPublicUnderTheObservabilitySegment() {
      assertTrue(PublicPaths.isPublic("/observability/api/otel/v1/traces"));
      assertTrue(PublicPaths.isPublic("/observability/api/otel/v1/logs"));
      assertTrue(PublicPaths.isPublic("/observability/api/otel/v1/metrics"));
      // Telemetry READS sit under the same rest path and are not public.
      assertFalse(PublicPaths.isPublic("/observability/api/telemetry/logs"));
      assertFalse(PublicPaths.isPublic("/observability/api/otel")); // subtree only
    }

    @Test
    void eachMcpServerIsPublicAtExactlyItsOwnPath() {
      // One path per service, not the old /mcp/<server> family: observability's server was renamed
      // off `repository`, which is what collapsed the family and let the allowlist tighten.
      assertTrue(PublicPaths.isPublic("/observability/mcp"));
      assertTrue(PublicPaths.isPublic("/projects/mcp"));
      // Deliberately NOT the subtree. quarkus-mcp-server-http mounts the legacy SSE transport at
      // <root>/sse; the daemon dials these with streamable HTTP (the root itself), so nothing we
      // ship needs it. If an SSE-transport client ever appears, this assertion is where it lands.
      assertFalse(PublicPaths.isPublic("/observability/mcp/sse"));
      assertFalse(PublicPaths.isPublic("/projects/mcp/repository"));
      assertFalse(PublicPaths.isPublic("/projects/mcpx")); // prefix must not bleed
    }

    @Test
    void theDaemonControlSocketIsPublicUnderTheWorkspacesSegment() {
      assertTrue(PublicPaths.isPublic("/workspaces/daemon/42"));
      assertFalse(PublicPaths.isPublic("/workspaces/daemon")); // only the subtree
    }

    @Test
    void captureIsPublicExactlyNotAsPrefix() {
      assertTrue(PublicPaths.isPublic("/workspaces/api/capture"));
      assertFalse(PublicPaths.isPublic("/workspaces/api/captures"));
      assertFalse(PublicPaths.isPublic("/workspaces/api/capture/extra"));
    }

    @Test
    void theRestOfEachServicesSurfaceIsProtected() {
      assertFalse(PublicPaths.isPublic("/projects/api/projects"));
      assertFalse(PublicPaths.isPublic("/projects/api/repositories/r1/remote-login"));
      assertFalse(PublicPaths.isPublic("/workspaces/api/workspaces/1/events"));
      assertFalse(PublicPaths.isPublic("/workspaces/service/1/d1/")); // dev-server proxy
      assertFalse(PublicPaths.isPublic("/stt/api/transcriptions"));
      // The bare rest-path root of a service is public only where the whole API is (artifacts).
      assertFalse(PublicPaths.isPublic("/observability/api"));
      assertFalse(PublicPaths.isPublic("/projects/api"));
    }
  }

  /**
   * Transitional, and covered so it cannot be dropped by accident before its time: these are the
   * spellings the {@code /} catch-all still carries. Delete this class together with {@code
   * PublicPaths.onTheMonolith} when {@code qits.gateway.app-host} goes.
   */
  @Nested
  class OnTheMonolith {

    @Test
    void containerFacingPathsArePublic() {
      assertTrue(PublicPaths.isPublic("/git/abc-123/info/refs"));
      assertTrue(PublicPaths.isPublic("/mcp"));
      assertTrue(PublicPaths.isPublic("/mcp/repository"));
      assertTrue(PublicPaths.isPublic("/mcp/actions"));
      assertTrue(PublicPaths.isPublic("/api/workspace-daemon/w1"));
      assertTrue(PublicPaths.isPublic("/api/otel/v1/traces"));
      assertTrue(PublicPaths.isPublic("/api/otel/v1/logs"));
      assertFalse(PublicPaths.isPublic("/git")); // only the subtree is public, not the bare path
    }

    @Test
    void captureIsPublicExactlyNotAsPrefix() {
      assertTrue(PublicPaths.isPublic("/api/capture"));
      assertFalse(PublicPaths.isPublic("/api/captures"));
      assertFalse(PublicPaths.isPublic("/api/capture/extra"));
    }

    @Test
    void artifactsBlobStoreIsPublic() {
      assertTrue(PublicPaths.isPublic("/api/artifacts"));
      assertTrue(PublicPaths.isPublic("/api/artifacts/repositories/ci-screenshots/blobs"));
      assertFalse(PublicPaths.isPublic("/api/artifactories")); // prefix must not bleed
    }

    @Test
    void onlyTheCiEventIntakeIsPublic() {
      assertTrue(PublicPaths.isPublic("/api/ci/events/post-receive"));
      assertFalse(PublicPaths.isPublic("/api/ci/repositories/r1/runs"));
      assertFalse(PublicPaths.isPublic("/api/ci/runs/run-1"));
      assertFalse(PublicPaths.isPublic("/api/ci"));
      assertFalse(PublicPaths.isPublic("/api/ci/events")); // only the subtree, not the bare path
      assertFalse(PublicPaths.isPublic("/api/cinema")); // prefix must not bleed
    }
  }

  @Test
  void uiSurfaceIsProtected() {
    assertFalse(PublicPaths.isPublic("/"));
    assertFalse(PublicPaths.isPublic("/index.html"));
    assertFalse(PublicPaths.isPublic("/api/projects"));
    assertFalse(PublicPaths.isPublic("/api/repositories/r1/workspaces/w1/events"));
    assertFalse(PublicPaths.isPublic("/api/terminal/commands/c1"));
    // The retired agent-session hook endpoint is no longer public (agent-activity tracking moved it
    // to the workspace-daemon's in-container loopback webhook).
    assertFalse(PublicPaths.isPublic("/api/commands/abc-123/agent-session"));
    assertFalse(PublicPaths.isPublic("/service/w1/d1/"));
  }
}
