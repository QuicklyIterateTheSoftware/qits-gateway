package eu.wohlben.qits.gateway.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit over the framework-free path matcher — the public/protected split in one place.
 *
 * <p>Moved from the monolith with its cases intact. They are worth keeping literal: every "prefix
 * must not bleed" case below is a path that would silently become public if someone reached for
 * {@code startsWith} where the original used {@code equals}.
 */
class PublicPathsTest {

  @Test
  void healthAndFrameworkPathsArePublic() {
    assertTrue(PublicPaths.isPublic("/q"));
    assertTrue(PublicPaths.isPublic("/q/health"));
    assertTrue(PublicPaths.isPublic("/q/health/ready"));
    assertFalse(PublicPaths.isPublic("/qq")); // prefix must not bleed past the segment
  }

  @Test
  void containerFacingPathsArePublic() {
    assertTrue(PublicPaths.isPublic("/git/abc-123/info/refs"));
    assertTrue(PublicPaths.isPublic("/mcp"));
    assertTrue(PublicPaths.isPublic("/mcp/repository"));
    assertTrue(PublicPaths.isPublic("/mcp/actions"));
    assertTrue(PublicPaths.isPublic("/api/otel/v1/traces"));
    assertTrue(PublicPaths.isPublic("/api/otel/v1/logs"));
  }

  @Test
  void captureIsPublicExactlyNotAsPrefix() {
    assertTrue(PublicPaths.isPublic("/api/capture"));
    assertFalse(PublicPaths.isPublic("/api/captures"));
    assertFalse(PublicPaths.isPublic("/api/capture/extra"));
  }

  @Test
  void artifactsBlobStoreIsPublic() {
    // Token-free at the session-policy layer — CI uploaders hold no session; writes are guarded by
    // the static-token filter in `service`, reads (serves) must work as a plain <img> src.
    assertTrue(PublicPaths.isPublic("/api/artifacts"));
    assertTrue(PublicPaths.isPublic("/api/artifacts/repositories/ci-screenshots/blobs"));
    assertTrue(
        PublicPaths.isPublic(
            "/api/artifacts/repositories/ci-screenshots/blobs/"
                + "0000000000000000000000000000000000000000000000000000000000000000"));
    assertFalse(PublicPaths.isPublic("/api/artifactories")); // prefix must not bleed
  }

  @Test
  void onlyTheCiEventIntakeIsPublic() {
    // The intake is token-free at the session-policy layer (the git host's post-receive hook holds
    // no session, and after extraction is another process) and guarded by the static-token filter
    // in `service`.
    assertTrue(PublicPaths.isPublic("/api/ci/events/post-receive"));
    // Run READS are not public: step output is the build log of a possibly private repository, and
    // repo ids are handed to containers/clone urls, so anonymous reads would leak them.
    assertFalse(PublicPaths.isPublic("/api/ci/repositories/r1/runs"));
    assertFalse(PublicPaths.isPublic("/api/ci/runs/run-1"));
    assertFalse(PublicPaths.isPublic("/api/ci"));
    assertFalse(PublicPaths.isPublic("/api/ci/events")); // only the subtree, not the bare path
    assertFalse(PublicPaths.isPublic("/api/cinema")); // prefix must not bleed
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
    assertFalse(PublicPaths.isPublic("/git")); // only the subtree is public, not the bare path
  }
}
