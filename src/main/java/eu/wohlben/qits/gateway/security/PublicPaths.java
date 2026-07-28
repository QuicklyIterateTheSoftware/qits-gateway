package eu.wohlben.qits.gateway.security;

/**
 * The token-free surface: paths whose callers cannot hold a user token — workspace containers (git
 * clone/push, OTLP export, MCP), the cross-origin fixture SPA's capture POST, health probes — plus
 * the gateway's own {@code /api/auth/*} and {@code /api/config.json}, which anonymous browsers have
 * to be able to fetch before there is a session to speak of.
 *
 * <p>Moved here from the monolith, and the reason it survives the move is worth stating:
 * "authentication moves to the edge" reads like it subsumes this list, and it does not. Workspace
 * containers reach their targets directly on {@code qits-net}; wherever the gateway <em>is</em> in
 * their path, it must not demand an identity they have no way to hold.
 *
 * <h2>Two groups, and why they are separate methods</h2>
 *
 * <ul>
 *   <li>{@link #gatewaysOwn} — served by this process.
 *   <li>{@link #onAService} — served by a split-out service under its own {@code /<segment>/…}
 *       prefix.
 * </ul>
 *
 * <p>There was a third, {@code onTheMonolith}: the monolith-relative spellings ({@code /git/},
 * {@code /api/otel/}, {@code /mcp/}) that the {@code /} catch-all carried. It is gone with the
 * catch-all itself. qits is deployed clean — these services and nothing else, no monolith beside
 * them and no shared access — so those paths name an upstream that does not exist, and the gateway
 * now 404s them rather than allowing them through to nothing.
 *
 * <p>The grouping survives because it still says something: a path is public either because this
 * process serves it to a caller with no session yet, or because a service serves it to a caller
 * that cannot hold a token at all. Adding an entry means deciding which, and that is the question
 * worth being forced to answer.
 */
public final class PublicPaths {

  private PublicPaths() {}

  /** Expects a normalized path (dot-segments collapsed) — see {@link QitsAuthPolicy}. */
  public static boolean isPublic(String path) {
    return gatewaysOwn(path) || onAService(path);
  }

  /**
   * Paths this process serves itself. All three are fetched by a browser that has no session yet,
   * or by an orchestrator that has no browser.
   */
  private static boolean gatewaysOwn(String path) {
    // The GATEWAY's health surface, and only the gateway's. It is the single published listener of
    // a deployment, so a compose healthcheck or an orchestrator probe has no other address for it
    // and cannot hold a token.
    //
    // A SERVICE's /q/* is deliberately NOT here, even though each service's non-application root
    // has just moved under its segment (/observability/q/openapi, /ci/q/health, …). Nothing that
    // must reach those traverses the gateway: every service sits on qits-net and its container
    // healthcheck dials it directly by DNS name. What is left of a service's /q/* at the front door
    // is swagger-ui and the OpenAPI document — read by humans, who have a session — plus a health
    // endpoint whose body is deployment detail (the gateway's own readiness response literally *is*
    // its route table). Anonymous internet access to that is a leak with no caller asking for it.
    return path.equals("/q")
        || path.startsWith("/q/")
        // GET /api/auth/me + the oauth variant's logout path, which quarkus-oidc intercepts inside
        // the authentication mechanism. A browser with no session must be able to ask who it is.
        || path.startsWith("/api/auth/")
        // The web components' pre-bootstrap config fetch (@qits/angular reads the base-relative
        // `api/config.json` before the app exists). Served here by ConfigJsonRoute — it has no
        // segment because the gateway has none.
        || path.equals("/api/config.json");
  }

  /**
   * The segment-prefixed forms, per {@code migration-path-conventions.md}. Each is served by one
   * split-out service and reaches it verbatim: the gateway does not rewrite, so the address below
   * is also the address the service itself must serve — including for the service-to-service calls
   * on {@code qits-net} that never pass through here at all.
   */
  private static boolean onAService(String path) {
    return
    // qits-artifacts is the git host: container clone/push, and qits-ci's own fetches.
    path.startsWith("/artifacts/git/")
        // qits-artifacts' blob store is the whole of that service's JSON API. Token-free at the
        // session layer: CI uploaders hold no session (writes are guarded by the static-token
        // filter in the service), and reads have to work as a plain <img> src.
        || path.equals("/artifacts/api")
        || path.startsWith("/artifacts/api/")
        // ci pipelines: ONLY the git host's event intake is token-free (its caller is the
        // post-receive hook, which holds no session, and after extraction is another process); it
        // is guarded by the static qits.ci.token. Run reads are NOT public — step output is build
        // logs of a possibly private repository, so /ci/api/runs/… stays behind the policy.
        || path.startsWith("/ci/api/events/")
        // OTLP ingest from workspace containers and fixture SPAs. The exporters append
        // /v1/<signal> to a literal endpoint, so the subtree is the unit.
        || path.startsWith("/observability/api/otel/")
        // The coding agent's MCP servers, called in-container. EXACT paths, not a subtree: these
        // used to be a wildcard family (/mcp/<server>) because every service hung its server off a
        // shared /mcp root and two of them both called it `repository`. Renaming observability's
        // server is what collapsed that family into one path per service, and the allowlist should
        // record the tightening rather than keep the old shape.
        //
        // Consequence, deliberate: quarkus-mcp-server-http also mounts the legacy SSE transport at
        // <root>/sse under the same root path, and that is NOT public. The daemon dials these with
        // McpServers.httpMcp — streamable HTTP, which is the root path itself — so nothing we ship
        // needs the subtree. An SSE-transport client through the gateway would be challenged; add
        // the sse path here, explicitly, if one ever appears.
        || path.equals("/observability/mcp")
        || path.equals("/projects/mcp")
        // The in-container workspace-daemon's dial-home control socket. A websocket, and the one
        // socket on this list; being public here is necessary because a daemon holds no user token.
        //
        // This used to say the open question was how a socket survives the gateway with
        // SameOriginUpgradeCheck still seeing a real Origin/Host. That was wrong twice over. There
        // is no SameOriginUpgradeCheck in Quarkus 3.34's websockets-next — the class does not
        // exist — and the real defect was here: vertx-http-proxy skips its interceptor chain
        // entirely on an upgrade, so EdgeHeaders never ran on a handshake. That is fixed
        // (EdgeHeaders.applyToUpgrade), and this socket is unaffected either way: the daemon dials
        // qits-workspaces directly on qits-net and never traverses the gateway at all.
        || path.startsWith("/workspaces/daemon/")
        // Cross-origin capture ingest from a fixture SPA (its own CORS route in the service).
        || path.equals("/workspaces/api/capture");
  }
}
