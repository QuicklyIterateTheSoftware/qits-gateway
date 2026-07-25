# qits-gateway

**The qits front door.** One address that receives every inbound request and delegates it to the
right qits component — the qits app, a split-out sibling service, or (later) a workspace container —
so a qits deployment publishes exactly one port no matter how many processes it grows into.

A small, stateless Quarkus 3 (Java 25) application that compiles to a **GraalVM native binary** and
is **deployed separately** from qits itself. It holds a route table and streams bytes; it has no
database, no ORM, no REST layer and no persistent state of its own.

- **Epic:** `docs/epics/qits-gateway/epic.md` in the [qits monorepo](https://github.com/wohlben/qits-backend)
- **Consumed as:** a git submodule of that monorepo (at `qits-gateway/`), built and released on its
  own — it is *not* a Maven module of qits' reactor.

## Why this exists

qits already **is** the thing every request arrives at, but it dispatches them through a distributed,
unnamed entry surface: the JAX-RS `/api` tree, three MCP roots (`/mcp/*`), raw Vert.x routes
(`/git/*`, `/service/*`), and Quinoa's SPA serving — with the canonical list of backend prefixes
duplicated across three unrelated config spots. At the same time the deployment story assumes an
**external** reverse proxy in front of qits (the `forwardauth` auth variant is built entirely on
trusting such a proxy's headers).

qits-gateway makes that front door **explicit, named, and owned by us**:

- **One origin, many components.** As artifacts, telemetry and other pieces split into their own
  processes, external callers keep hitting *one* origin. A split becomes a deployment-topology
  change invisible to clients — no new host:port for the SPA to reach cross-origin, no per-service
  auth story exposed at the edge.
- **No host-port publishing.** Everything the gateway fronts stays unpublished on the shared
  `qits-net` docker network, reachable only by DNS name — the same addressing model qits already
  uses to reach workspace containers.
- **A place for edge concerns.** Header hygiene today (see [Security posture](#security-posture)),
  TLS termination and edge authentication as the epic's Part 4 lands.

### Why a separate deployable, and not code inside qits

The gateway must be up **before** anything is reachable and must survive a restart of what it
fronts — properties a component embedded in the thing it routes to cannot have. Separately deployed,
it also stays honest: it can only use what a real front door has (configuration and the network),
never in-process access to qits' state. Native compilation follows from the same role: a ~50 ms
start and a footprint in tens of megabytes make restarting the front door a non-event, and there is
no long-running JIT-warmable work to give up.

> **Note on the epic.** `docs/epics/qits-gateway/epic.md` argues for an *in-app* hub (routing
> machinery inside the qits service). This repository is the decision to build the front door as its
> own deployable instead; the epic's routing model, addressing scheme and staged parts still apply,
> and the epic text should be updated to match this split.

## The routing model

One catch-all Vert.x route resolves the owning component from the route table and hands the exchange
to `vertx-http-proxy`, which **streams** it — request and response bodies are never buffered, and
WebSocket upgrades are forwarded by default. That is what keeps SSE channels, the git smart-HTTP
protocol, dev-server HMR sockets and large artifact uploads working through the hub.

```
                       ┌─────────────────────────────────────────────┐
   client ──:8080──▶   │  qits-gateway   (the only published port)   │
                       │   route table  ·  edge headers  ·  health   │
                       └───────┬───────────────┬──────────────┬──────┘
                               │  qits-net (DNS names, nothing published)
                 /api/artifacts│        /api/otel│             /│
                      ┌────────▼──────┐ ┌────────▼──────┐ ┌─────▼───────────┐
                      │ qits-artifacts│ │ qits-telemetry│ │ qits (app + SPA)│
                      └───────────────┘ └───────────────┘ └─────────────────┘
```

**Resolution is longest-prefix-wins**, regardless of declaration order: `/api/artifacts` beats
`/api` beats the `/` catch-all. Matching is **segment-aware** — `/api/art` never captures
`/api/artifacts/…`. A path no route claims is answered with **404 by the gateway itself**; it opens
no connection.

Forwarding is **verbatim by default** (no prefix stripping): qits' own routes and its SPA expect the
paths they already serve, and stripping breaks apps that emit absolute-root asset URLs. Routes that
*want* stripping opt in per route, and the removed prefix is announced upstream as
`X-Forwarded-Prefix`.

## Configuration

Everything is MicroProfile config, so any key works as a property, a system property or an
environment variable. The route table:

| Key | Default | Meaning |
| --- | --- | --- |
| `qits.gateway.routes.<name>.path-prefix` | — | inbound prefix this route claims; `/` is the catch-all |
| `qits.gateway.routes.<name>.host` | — | upstream DNS name (configuration only, never request-derived) |
| `qits.gateway.routes.<name>.port` | `8080` | upstream port |
| `qits.gateway.routes.<name>.strip-prefix` | `false` | remove the prefix before forwarding |
| `qits.gateway.routes.<name>.authority` | upstream `host:port` | override the `Host` header sent upstream |
| `qits.gateway.routes.<name>.enabled` | `true` | take a route out of service without deleting it |

`<name>` is arbitrary and appears only in logs and health output. As environment variables:

```bash
QITS_GATEWAY_ROUTES_QITS_PATH_PREFIX=/
QITS_GATEWAY_ROUTES_QITS_HOST=qits
QITS_GATEWAY_ROUTES_ARTIFACTS_PATH_PREFIX=/api/artifacts
QITS_GATEWAY_ROUTES_ARTIFACTS_HOST=qits-artifacts
```

Edge headers:

| Key | Default | Meaning |
| --- | --- | --- |
| `qits.gateway.forwarded.enabled` | `true` | emit `X-Forwarded-For`/`-Proto`/`-Host`/`-Port`/`-Prefix` |
| `qits.gateway.forwarded.strip-request-headers` | the identity headers below | request headers dropped from every inbound request |

The shipped `application.properties` defaults to today's topology — a single `/` route to `qits:8080`
— and carries commented entries for the artifacts/telemetry splits. Other settings worth knowing:
`quarkus.http.limits.max-body-size` (must be ≥ the largest upload any fronted component accepts) and
`quarkus.http.idle-timeout=1H` (long-lived SSE/HMR/git exchanges pass through here).

## Security posture

- **Upstreams come from configuration only.** No request component ever selects a host or port, so
  the gateway cannot be steered into an SSRF. This is the same rule qits' in-process proxies follow
  (they resolve origins exclusively from supervisor state).
- **Client-supplied identity headers are dropped.** qits' `forwardauth` variant believes headers like
  `Remote-User` *unconditionally*, on the contract that whatever fronts it strips client copies.
  Since the gateway is that front door, it honours the contract: `Remote-User`, `Remote-Groups`,
  `Remote-Name`, `Remote-Email`, `X-Auth-Request-*` and `X-Forwarded-User`/`-Groups` are removed from
  every inbound request. Extend that list per deployment; **never** shrink it below what the fronted
  qits build trusts.
- **`X-Forwarded-For` is set, not appended** — the gateway is the outermost hop, so any inbound value
  is client-supplied and worthless.
- **The gateway's own management surface is never proxied.** `/q/*` is served locally even under a
  `/` catch-all route.
- **No authentication of its own, yet.** Today the gateway forwards to components that authenticate
  their own requests (qits' `QitsAuthPolicy`). TLS termination and edge authentication are Part 4 of
  the epic and deliberately not implemented here.

## Endpoints the gateway serves itself

| Path | Purpose |
| --- | --- |
| `/q/health/live` | the process is up |
| `/q/health/ready` | the route table is non-empty — and the response data *is* the route table |

Readiness deliberately does **not** probe upstreams: an upstream being down is a 502 for that path,
not a reason to pull the whole front door and take every other component offline with it.

## Build & run

Requires **JDK 25** (`.sdkmanrc` pins it) — google-java-format, applied automatically by Spotless on
every build, needs JDK 21+. The native build needs a GraalVM/Mandrel `native-image` for JDK 25 on
`GRAALVM_HOME` or `PATH`; qits' workspace image ships one at `/usr/lib/jvm/mandrel-25`, so `-Dnative`
works out of the box inside a workspace container or the devcontainer.

```bash
# Tests (unit + an end-to-end proxy suite against a stub upstream; no docker needed)
./mvnw test

# JVM build, then run
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar

# Dev mode (live reload). Defaults to fronting a qits on localhost:8080 and listens on :8000,
# so it does not collide with the qits it is proxying.
./mvnw quarkus:dev

# Native binary -> target/qits-gateway
./mvnw package -Dnative

# Native container image (self-contained: compiles inside the build)
docker build -t qits/gateway:latest -f docker/Dockerfile .
```

Point it at something without touching a file:

```bash
./mvnw quarkus:dev -Dqits.gateway.routes.qits.host=localhost -Dqits.gateway.routes.qits.port=8080
```

## Deployment

The gateway is the **only published listener**; everything it fronts joins the shared `qits-net`
network with no ports of its own. See `docker-compose.example.yml` for the worked topology:

```bash
docker network create qits-net           # if it does not exist yet
docker compose -f docker-compose.example.yml up
```

## Relationship to the qits monorepo

This repository is a **git submodule** of qits (`qits-gateway/` at its root) so the two travel
together, but it builds and releases on its own — a clone of *this* repo alone is a complete build.
Two things are therefore duplicated here on purpose and must be kept in step when qits moves them:

- the **Quarkus platform version** (`quarkus.platform.version` in `pom.xml`), and
- the **JDK release** (`maven.compiler.release`, `.sdkmanrc`, and the native builder image tag).

Nothing else is shared — the gateway depends on no qits module, which is what lets it start, stop and
be upgraded independently of what it fronts.

## Status & roadmap

Implemented: the route table (config-driven, longest-prefix, segment-aware), streaming reverse proxy
with WebSocket passthrough, per-route prefix stripping, edge-header hygiene, health/readiness,
native build, container image.

Planned, per the epic's staging:

- **Workspace addressability** — a general `/ws/{workspaceId}/{service}/*` scheme resolving the
  origin from qits-held state, generalising qits' current daemon web-view proxy.
- **Fronting split-out siblings** — as artifacts/telemetry become their own processes, they are added
  to the route table and nothing changes for callers.
- **Subsuming the edge** — TLS termination and edge authentication in the gateway, so a standard qits
  deployment needs no external reverse proxy at all.
