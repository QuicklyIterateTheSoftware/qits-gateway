package eu.wohlben.qits.gateway.security;

/**
 * The token-free surface: paths whose callers cannot hold a user token — workspace containers (git
 * clone/push, OTLP export, MCP), the cross-origin fixture SPA's capture POST, health probes — plus
 * {@code /api/auth/*} (the "who am I" endpoint and the OIDC-intercepted logout path must work for
 * anonymous browsers).
 *
 * <p>Moved here from the monolith unchanged, and the reason it survives the move is worth stating:
 * "authentication moves to the edge" reads like it subsumes this list, and it does not. Workspace
 * containers reach their targets directly on {@code qits-net}; wherever the gateway <em>is</em> in
 * their path, it must not demand an identity they have no way to hold.
 *
 * <p><b>These are monolith-relative paths, which is what the {@code /} catch-all still carries.</b>
 * They are correct for every request that falls through to the monolith and for nothing else. When
 * a service starts serving one of these under its own segment — {@code /observability/otel/v1/…}
 * rather than {@code /api/otel/v1/…} — this list has to grow the segment-prefixed form with it.
 * That is the same unsettled question as the segment/path mismatch in {@code
 * migration-api-map.md}'s ⚠ section; it is not guessed at here.
 */
public final class PublicPaths {

  private PublicPaths() {}

  /** Expects a normalized path (dot-segments collapsed) — see {@link QitsAuthPolicy}. */
  public static boolean isPublic(String path) {
    return path.equals("/q")
        || path.startsWith("/q/") // health/readiness probes (compose healthcheck, orchestrators)
        || path.startsWith("/git/") // container clone/push against the git host
        || path.equals("/mcp")
        || path.startsWith("/mcp/") // the coding agent's MCP servers, called in-container
        || path.startsWith(
            "/api/workspace-daemon/") // in-container workspace-daemon's dial-home control socket
        || path.startsWith("/api/otel/") // OTLP ingest from containers and fixture SPAs
        || path.equals("/api/capture") // cross-origin capture ingest (own CORS route)
        || path.startsWith(
            "/api/artifacts/") // blob store: CI uploaders (writes token-guarded) + <img> serves
        || path.equals("/api/artifacts")
        // ci pipelines: ONLY the git host's event intake is token-free (its caller holds no
        // session, and after extraction is another process); it is guarded by the static
        // qits.ci.token. Run reads are NOT public — step output is build logs of a possibly
        // private repository, so /api/ci/repositories/… and /api/ci/runs/… stay behind the policy.
        || path.startsWith("/api/ci/events/")
        || path.equals("/api/config.json") // the SPA's telemetry/capture relay, fetched pre-boot
        || path.startsWith("/api/auth/"); // /api/auth/me + the oauth variant's logout path
  }
}
