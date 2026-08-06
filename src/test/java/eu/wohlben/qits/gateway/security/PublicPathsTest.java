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
 * <p>The nesting mirrors {@link PublicPaths}' two groups. It had a third, {@code OnTheMonolith},
 * covering the monolith-relative spellings the {@code /} catch-all carried; both are gone — qits
 * deploys clean, with no monolith beside these services, so those paths name no upstream and are
 * simply not public any more. The cases below now assert exactly that.
 */
class PublicPathsTest {

  @Nested
  class TheGatewaysOwnSurface {

    @Test
    void healthAndFrameworkPathsArePublic() {
      assertTrue(PublicPaths.isPublic("GET", "/q"));
      assertTrue(PublicPaths.isPublic("GET", "/q/health"));
      assertTrue(PublicPaths.isPublic("GET", "/q/health/ready"));
      assertFalse(PublicPaths.isPublic("GET", "/qq")); // prefix must not bleed past the segment
    }

    @Test
    void aServicesOwnManagementSurfaceIsNotPublicThroughTheGateway() {
      // Each service's /q/* has just moved under its segment. It stays behind the policy: the only
      // callers that MUST reach it (container healthchecks, orchestrator probes) dial the service
      // directly on qits-net and never traverse the gateway, so what is exposed here is swagger-ui,
      // the OpenAPI document and deployment detail — for humans, who have a session.
      assertFalse(PublicPaths.isPublic("GET", "/observability/q/openapi"));
      assertFalse(PublicPaths.isPublic("GET", "/observability/q/swagger-ui"));
      assertFalse(PublicPaths.isPublic("GET", "/ci/q/health/ready"));
      assertFalse(PublicPaths.isPublic("GET", "/workspaces/q"));
    }

    @Test
    void configRelayIsPublicExactlyNotAsPrefix() {
      assertTrue(PublicPaths.isPublic("GET", "/api/config.json"));
      assertFalse(PublicPaths.isPublic("GET", "/api/config.json/extra"));
      assertFalse(PublicPaths.isPublic("GET", "/api/config"));
    }

    @Test
    void authEndpointsArePublic() {
      assertTrue(PublicPaths.isPublic("GET", "/api/auth/me"));
      assertTrue(PublicPaths.isPublic("GET", "/api/auth/logout"));
    }
  }

  @Nested
  class OnAService {

    @Test
    void theGitHostSubtreeIsPublicUnderTheArtifactsSegment() {
      assertTrue(PublicPaths.isPublic("GET", "/artifacts/git/abc-123/info/refs"));
      assertTrue(PublicPaths.isPublic("GET", "/artifacts/git/proj-1/repo/git-receive-pack"));
      assertFalse(
          PublicPaths.isPublic("GET", "/artifacts/git")); // only the subtree, not the bare path
      assertFalse(PublicPaths.isPublic("GET", "/artifacts/gitignore")); // prefix must not bleed
    }

    @Test
    void theRegistryIsPublicForReadsAndIsTheOneNonSegmentPrefixedEntry() {
      // docker's version probe, in both the spellings a client actually sends, plus a pull's two
      // methods — the whole anonymous-pull surface.
      assertTrue(PublicPaths.isPublic("GET", "/v2"));
      assertTrue(PublicPaths.isPublic("GET", "/v2/"));
      assertTrue(PublicPaths.isPublic("GET", "/v2/qits/alpine/manifests/latest"));
      assertTrue(PublicPaths.isPublic("HEAD", "/v2/qits/alpine/manifests/latest"));
      // A multi-slash OCI name is still one path under the same root.
      assertTrue(
          PublicPaths.isPublic(
              "GET", "/v2/qits/build-images/ci-base/blobs/sha256:" + "0".repeat(64)));

      assertFalse(PublicPaths.isPublic("GET", "/v2x")); // prefix must not bleed
      assertFalse(PublicPaths.isPublic("GET", "/v20/x"));

      // The registry has exactly ONE address. A prefixed spelling is not a second one, and must not
      // become public just by looking as though it belongs to artifacts.
      assertFalse(PublicPaths.isPublic("GET", "/artifacts/v2"));
      assertFalse(PublicPaths.isPublic("GET", "/artifacts/v2/qits/alpine/manifests/latest"));
    }

    @Test
    void registryWritesAreNotPublicAndThatIsTheRegistrysWholeExternalWriteProtection() {
      // qits-artifacts carries NO push guard of its own any more (producers on qits-net are
      // trusted; external push is unwanted entirely), so these lines are what stands between the
      // internet and `docker push`. An anonymous write is challenged for a session no registry
      // client can answer — deliberately nonfunctional. Widening /v2 back to all methods without
      // restoring a guard in qits-artifacts opens push to the world; the two move together.
      assertFalse(PublicPaths.isPublic("POST", "/v2/qits/alpine/blobs/uploads/"));
      assertFalse(PublicPaths.isPublic("PATCH", "/v2/qits/alpine/blobs/uploads/session-1"));
      assertFalse(PublicPaths.isPublic("PUT", "/v2/qits/alpine/manifests/latest"));
      assertFalse(PublicPaths.isPublic("DELETE", "/v2/qits/alpine/manifests/latest"));
      // The probe itself is also read-only territory.
      assertFalse(PublicPaths.isPublic("POST", "/v2"));
      assertFalse(PublicPaths.isPublic("POST", "/v2/"));
    }

    @Test
    void theBlobStoreIsPublicUnderTheArtifactsSegment() {
      // Token-free at the session-policy layer — CI uploaders hold no session; writes are guarded
      // by the static-token filter in the service, reads must work as a plain <img> src.
      assertTrue(PublicPaths.isPublic("GET", "/artifacts/api"));
      assertTrue(PublicPaths.isPublic("GET", "/artifacts/api/repositories/ci-screenshots/blobs"));
      assertTrue(
          PublicPaths.isPublic(
              "GET",
              "/artifacts/api/repositories/ci-screenshots/blobs/"
                  + "0000000000000000000000000000000000000000000000000000000000000000"));
      assertFalse(PublicPaths.isPublic("GET", "/artifacts/apiary")); // prefix must not bleed
    }

    @Test
    void onlyTheCiEventIntakeIsPublic() {
      // The intake is token-free at the session-policy layer (the git host's post-receive hook
      // holds no session, and after extraction is another process) and guarded by the static-token
      // filter in the service.
      assertTrue(PublicPaths.isPublic("GET", "/ci/api/events/post-receive"));
      // Run READS are not public: step output is the build log of a possibly private repository,
      // and repo ids are handed to containers/clone urls, so anonymous reads would leak them.
      assertFalse(PublicPaths.isPublic("GET", "/ci/api/runs"));
      assertFalse(PublicPaths.isPublic("GET", "/ci/api/runs/run-1"));
      assertFalse(PublicPaths.isPublic("GET", "/ci/api"));
      assertFalse(
          PublicPaths.isPublic("GET", "/ci/api/events")); // only the subtree, not the bare path
      assertFalse(PublicPaths.isPublic("GET", "/cinema/api/events/x")); // prefix must not bleed
    }

    @Test
    void nothingOfCdIsPublic() {
      // Deliberate, and different from ci: qits-ci's build-succeeded notifier dials qits-cd
      // directly on qits-net and never traverses the gateway, so even the intake has no
      // session-free front-door spelling — and correspondingly no token guard in the service.
      // Allowlisting it here without restoring that guard would open the intake to the internet;
      // this test is what makes that a conscious pair of changes.
      assertFalse(PublicPaths.isPublic("GET", "/cd/api/events/build-succeeded"));
      assertFalse(PublicPaths.isPublic("GET", "/cd/api/environments"));
      assertFalse(PublicPaths.isPublic("GET", "/cd/api/deployments"));
      assertFalse(PublicPaths.isPublic("GET", "/cd/api"));
    }

    @Test
    void nothingOfPlatformDeploymentsIsPublic() {
      // qits-platform-deployments supersedes qits-cd and inherits its posture exactly: the intake
      // is an intra-network call from qits-ci, and the reads are a browser's, so the whole segment
      // stays behind the session policy. Same pairing rule as above — a front-door intake spelling
      // and a write guard in the service arrive together or not at all.
      assertFalse(PublicPaths.isPublic("GET", "/platform-deployments/api/events/build-succeeded"));
      assertFalse(PublicPaths.isPublic("GET", "/platform-deployments/api/environments"));
      assertFalse(PublicPaths.isPublic("GET", "/platform-deployments/api/deployments"));
      assertFalse(PublicPaths.isPublic("GET", "/platform-deployments/api"));
    }

    @Test
    void otlpIngestIsPublicUnderTheObservabilitySegment() {
      assertTrue(PublicPaths.isPublic("GET", "/observability/api/otel/v1/traces"));
      assertTrue(PublicPaths.isPublic("GET", "/observability/api/otel/v1/logs"));
      assertTrue(PublicPaths.isPublic("GET", "/observability/api/otel/v1/metrics"));
      // Telemetry READS sit under the same rest path and are not public.
      assertFalse(PublicPaths.isPublic("GET", "/observability/api/telemetry/logs"));
      assertFalse(PublicPaths.isPublic("GET", "/observability/api/otel")); // subtree only
    }

    @Test
    void eachMcpServerIsPublicAtExactlyItsOwnPath() {
      // One path per service, not the old /mcp/<server> family: observability's server was renamed
      // off `repository`, which is what collapsed the family and let the allowlist tighten.
      assertTrue(PublicPaths.isPublic("GET", "/observability/mcp"));
      assertTrue(PublicPaths.isPublic("GET", "/projects/mcp"));
      // Deliberately NOT the subtree. quarkus-mcp-server-http mounts the legacy SSE transport at
      // <root>/sse; the daemon dials these with streamable HTTP (the root itself), so nothing we
      // ship needs it. If an SSE-transport client ever appears, this assertion is where it lands.
      assertFalse(PublicPaths.isPublic("GET", "/observability/mcp/sse"));
      assertFalse(PublicPaths.isPublic("GET", "/projects/mcp/repository"));
      assertFalse(PublicPaths.isPublic("GET", "/projects/mcpx")); // prefix must not bleed
    }

    @Test
    void theDaemonControlSocketIsPublicUnderTheWorkspacesSegment() {
      assertTrue(PublicPaths.isPublic("GET", "/workspaces/daemon/42"));
      assertFalse(PublicPaths.isPublic("GET", "/workspaces/daemon")); // only the subtree
    }

    @Test
    void captureIsPublicExactlyNotAsPrefix() {
      assertTrue(PublicPaths.isPublic("GET", "/workspaces/api/capture"));
      assertFalse(PublicPaths.isPublic("GET", "/workspaces/api/captures"));
      assertFalse(PublicPaths.isPublic("GET", "/workspaces/api/capture/extra"));
    }

    @Test
    void theRestOfEachServicesSurfaceIsProtected() {
      assertFalse(PublicPaths.isPublic("GET", "/projects/api/projects"));
      assertFalse(PublicPaths.isPublic("GET", "/projects/api/repositories/r1/remote-login"));
      assertFalse(PublicPaths.isPublic("GET", "/workspaces/api/workspaces/1/events"));
      assertFalse(PublicPaths.isPublic("GET", "/workspaces/service/1/d1/")); // dev-server proxy
      assertFalse(PublicPaths.isPublic("GET", "/stt/api/transcriptions"));
      // The bare rest-path root of a service is public only where the whole API is (artifacts).
      assertFalse(PublicPaths.isPublic("GET", "/observability/api"));
      assertFalse(PublicPaths.isPublic("GET", "/projects/api"));
    }
  }

  /**
   * The monolith-relative spellings, which used to be public because the {@code /} catch-all served
   * them. Asserted PROTECTED rather than simply deleted: dropping the old cases would have left
   * nothing saying what happened, and these paths are exactly the ones a stale container or an old
   * bookmark would still dial. There is no upstream behind them now, so the gateway challenges them
   * — and if someone re-adds a catch-all, this is what fails.
   */
  @Nested
  class TheRetiredMonolithSpellings {

    @Test
    void containerFacingPathsAreNoLongerPublic() {
      assertFalse(PublicPaths.isPublic("GET", "/git/abc-123/info/refs"));
      assertFalse(PublicPaths.isPublic("GET", "/mcp"));
      assertFalse(PublicPaths.isPublic("GET", "/mcp/repository"));
      assertFalse(PublicPaths.isPublic("GET", "/api/workspace-daemon/w1"));
      assertFalse(PublicPaths.isPublic("GET", "/api/otel/v1/traces"));
      assertFalse(PublicPaths.isPublic("GET", "/api/capture"));
    }

    @Test
    void theOldArtifactAndCiSpellingsAreNoLongerPublic() {
      assertFalse(PublicPaths.isPublic("GET", "/api/artifacts"));
      assertFalse(PublicPaths.isPublic("GET", "/api/artifacts/repositories/ci-screenshots/blobs"));
      assertFalse(PublicPaths.isPublic("GET", "/api/ci/events/post-receive"));
    }

    /** The segment-prefixed forms are the live ones, and stay public. */
    @Test
    void theSegmentPrefixedFormsStillAre() {
      assertTrue(PublicPaths.isPublic("GET", "/artifacts/git/abc-123/info/refs"));
      assertTrue(PublicPaths.isPublic("GET", "/artifacts/api/repositories/ci-screenshots/blobs"));
      assertTrue(PublicPaths.isPublic("GET", "/observability/api/otel/v1/traces"));
      assertTrue(PublicPaths.isPublic("GET", "/observability/mcp"));
      assertTrue(PublicPaths.isPublic("GET", "/projects/mcp"));
      assertTrue(PublicPaths.isPublic("GET", "/workspaces/daemon/w1"));
      assertTrue(PublicPaths.isPublic("GET", "/workspaces/api/capture"));
      assertTrue(PublicPaths.isPublic("GET", "/ci/api/events/post-receive"));
    }
  }

  @Test
  void uiSurfaceIsProtected() {
    assertFalse(PublicPaths.isPublic("GET", "/"));
    assertFalse(PublicPaths.isPublic("GET", "/index.html"));
    assertFalse(PublicPaths.isPublic("GET", "/api/projects"));
    assertFalse(PublicPaths.isPublic("GET", "/api/repositories/r1/workspaces/w1/events"));
    assertFalse(PublicPaths.isPublic("GET", "/api/terminal/commands/c1"));
    // The retired agent-session hook endpoint is no longer public (agent-activity tracking moved it
    // to the workspace-daemon's in-container loopback webhook).
    assertFalse(PublicPaths.isPublic("GET", "/api/commands/abc-123/agent-session"));
    assertFalse(PublicPaths.isPublic("GET", "/service/w1/d1/"));
  }
}
