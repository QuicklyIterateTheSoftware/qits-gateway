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
- **A place for edge concerns.** Header hygiene and [authentication](#authentication) live here
  today; TLS termination is still to come.

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
                     /artifacts│  /observability│             /│
                      ┌────────▼──────┐ ┌────────▼──────────┐ ┌─────▼───────────┐
                      │ qits-artifacts│ │qits-observability │ │ qits (app + SPA)│
                      └───────────────┘ └───────────────────┘ └─────────────────┘
```

The set of services the gateway can front is a **named registry** — the `QitsService` enum. A
service's public identity drops the `qits-` prefix, so `qits-artifacts` is reached at `/artifacts/*`
and forwarded to the `qits-artifacts` container. Which services are *live* is a deployment decision
(see [Configuration](#configuration)); everything they do not claim falls through to the qits
monolith.

**Resolution is longest-prefix-wins**, regardless of declaration order: `/artifacts` beats the `/`
catch-all. Matching is **segment-aware** — `/art` never captures `/artifacts/…`, and `/ci` never
captures `/cicd/…`. A path no route claims (and no live service) is answered with **404 by the
gateway itself**; it opens no connection.

Forwarding is **verbatim**: the upstream sees the path unchanged (`/artifacts/blobs` reaches
qits-artifacts as `/artifacts/blobs`), so a service and the qits SPA both get exactly the paths they
serve and nothing breaks apps that emit absolute-root asset URLs.

## Configuration

Everything is MicroProfile config, so any key works as a property, a system property or an
environment variable. The **catch-all** (the qits monolith):

| Key | Default | Meaning |
| --- | --- | --- |
| `qits.gateway.app-host` | — | qits monolith DNS name; unset ⇒ no catch-all (unclaimed paths 404) |
| `qits.gateway.app-port` | `8080` | qits monolith port |

The **service registry** — one entry per live split-out service, keyed by its public segment:

| Key | Default | Meaning |
| --- | --- | --- |
| `qits.gateway.proxy-hosts.<segment>` | — | upstream `host` or `host:port` for that service; the entry both enables the service and names its target |

`<segment>` must be a known service — one of the `QitsService` enum values below; an unknown segment
is rejected at startup. Each is reached at `/<segment>/*` and forwarded verbatim. A service is routed
**only** when it has a `proxy-hosts` entry, so undeployed services simply 404 rather than 502.

| Service (submodule) | Segment | Reached at | Default host |
| --- | --- | --- | --- |
| `qits-artifacts` | `artifacts` | `/artifacts/*` | `qits-artifacts` |
| `qits-observability` | `observability` | `/observability/*` | `qits-observability` |
| `qits-workspaces` | `workspaces` | `/workspaces/*` | `qits-workspaces` |
| `qits-projects` | `projects` | `/projects/*` | `qits-projects` |
| `qits-stt` | `stt` | `/stt/*` | `qits-stt` |
| `qits-ci` | `ci` | `/ci/*` | `qits-ci` |
| `qits-cd` | `cd` | `/cd/*` | `qits-cd` |

(The "default host" is the container's `qits-net` DNS name — what you would normally put in the
`proxy-hosts` value. Add a service to the enum when a new component splits out.) As environment
variables:

```bash
QITS_GATEWAY_APP_HOST=qits
QITS_GATEWAY_PROXY_HOSTS_ARTIFACTS=qits-artifacts
QITS_GATEWAY_PROXY_HOSTS_OBSERVABILITY=qits-observability:9000   # host:port when not on 8080
```

Edge headers:

| Key | Default | Meaning |
| --- | --- | --- |
| `qits.gateway.forwarded.enabled` | `true` | emit `X-Forwarded-For`/`-Proto`/`-Host`/`-Port` |
| `qits.gateway.forwarded.strip-request-headers` | the identity headers below | request headers dropped from every inbound request |

The shipped `application.properties` defaults to today's topology — the `/` catch-all to `qits:8080`
— and carries the whole enum as commented `proxy-hosts` lines, ready to uncomment as each service
goes live. Other settings worth knowing: `quarkus.http.limits.max-body-size` (must be ≥ the largest
upload any fronted component accepts) and `quarkus.http.idle-timeout=1H` (long-lived SSE/HMR/git
exchanges pass through here).

## Security posture

- **Upstreams come from configuration only.** No request component ever selects a host or port, so
  the gateway cannot be steered into an SSRF. This is the same rule qits' in-process proxies follow
  (they resolve origins exclusively from supervisor state).
- **`X-Qits-*` is a reserved prefix, stripped unconditionally.** Every header the gateway asserts
  about a request lives under `X-Qits-`, and the strip rule *is* that prefix — one rule doing both
  jobs, so it is structurally impossible to introduce a trusted header that is not stripped. An
  enumerated list would have the wrong failure mode here: adding a trusted header and forgetting to
  extend the list is a silent, additive mistake that no test naturally catches. Unlike the list
  below, the prefix is **not configurable** and cannot be shrunk away.
- **Client-supplied identity headers are dropped.** A forward-auth proxy's headers use the vendor's
  names rather than ours, so those stay enumerated in
  `qits.gateway.forwarded.strip-request-headers`: `Remote-User`, `Remote-Groups`, `Remote-Name`,
  `Remote-Email`, `X-Auth-Request-*` and `X-Forwarded-User`/`-Groups` are removed from every inbound
  request. This matters when something still fronts the gateway. Extend that list per deployment;
  **never** shrink it below what whatever sits in front of the gateway injects.
- **`X-Forwarded-For` is set, not appended** — the gateway is the outermost hop, so any inbound value
  is client-supplied and worthless.
- **The gateway's own management surface is never proxied.** `/q/*` is served locally even under a
  `/` catch-all route.
- **The gateway is the only thing that authenticates.** It performs the login, decides authorization
  (`qits.auth.required-role`), and asserts the resulting identity downstream as `X-Qits-User` /
  `X-Qits-User-Id`. Every other component consumes that header and authenticates nothing — see
  [Authentication](#authentication).
- **This is a perimeter against the internet, not a boundary on `qits-net`.** Every service sits
  unpublished on the shared network, and so does every workspace container running a coding agent
  over an untrusted checkout. `curl http://qits-projects:8080/api/…` from inside a workspace bypasses
  the gateway entirely. That is accepted for now and is a *known* gap, not a covered one; nothing
  here should be described as if the gateway bounded it.

## Authentication

The gateway authenticates every human request, injects the identity as request headers, and every
other component consumes those headers instead of authenticating anything. One component chooses a
scheme; the rest have no scheme to choose. (The alternative — a shared auth library — makes seven
copies of the problem share a jar without making the problem smaller.)

| Header | Value | Consumed as |
| --- | --- | --- |
| `X-Qits-User` | the principal **name** (`preferred_username`) | `SecurityIdentity.getPrincipal().getName()` |
| `X-Qits-User-Id` | the stable subject id | an identity attribute; nothing reads it yet |

The principal is the name and not the id because upstreams write it into audit columns whose existing
rows hold usernames. **No groups header is emitted**: the one role check the system has happens here,
so no service can make — or appear to make — a role decision of its own.

Authorization is a single global check. `PublicPaths` is the token-free allowlist for callers that
hold no user token by construction (workspace containers doing git/OTLP/MCP, health probes,
`/api/auth/me`); everything else needs an identity.

### Build targets

The unauthenticated build has to stay reachable for testing and impossible to switch on by accident,
so the target is a **build** property, never a runtime config key:

```bash
./mvnw package -Dqits.variant=oauth   # OIDC login; needs QUARKUS_OIDC_* at runtime
./mvnw package -Dqits.variant=local   # EXPLICITLY UNAUTHENTICATED — never internet-expose
```

`quarkus:dev` and `test` default to `oauth` flagless; packaging without the flag is refused. The
selection is baked into the recorded bean set at augmentation, so **no environment variable and no
properties file can open a production gateway** — setting `QITS_AUTH_VARIANT=local` against an
`oauth` build changes nothing at all.

A `local` gateway synthesizes a fixed identity and emits the same `X-Qits-*` headers, so everything
downstream is byte-identical between targets: test and production differ in exactly one component.

### Native image cost

`quarkus-oidc` is the largest extension this repo has taken on, and it stays on the classpath in both
targets — a single-module build cannot conditionally drop a compile dependency, so `@IfBuildProperty`
conditions the beans rather than the jar. The extension itself *is* switched off in the `local`
target, which is what lets that build start with no IdP configured; what it cannot shed is the jar.

Measured (`-Dquarkus.native.container-build=true`, same machine, same day):

| Build | Native binary | vs. before |
| --- | --- | --- |
| before `quarkus-oidc` | 50,146,360 B | — |
| `-Dqits.variant=local` (extension off, jar present) | 52,317,240 B | +2.2 MB, +4.3% |
| `-Dqits.variant=oauth` | 56,286,264 B | +6.1 MB, +12.2% |

Native generation time barely moved: 26.6 s → 27.6 s. The `local` row is the price of not being able
to drop a compile dependency in a single-module build; the gap between the two targets is what OIDC
actually costs when it is switched on.

Judged acceptable: ~12% on a binary that exists to start in ~50 ms, in exchange for deleting the auth
question from six other repositories. If it ever stops being acceptable, the escape hatch works
unchanged: front the gateway with a forward-auth proxy and have it translate `Remote-User` into
`X-Qits-User`. Nothing downstream would notice.

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
./mvnw quarkus:dev -Dqits.gateway.app-host=localhost \
                   -Dqits.gateway.proxy-hosts.artifacts=127.0.0.1:9000
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

Implemented: the `QitsService` registry (a named, enum-backed set of proxyable services),
config-driven longest-prefix / segment-aware routing, verbatim streaming reverse proxy with
WebSocket passthrough, edge-header hygiene, health/readiness, native build, container image.

Planned, per the epic's staging:

- **Workspace addressability** — a general `/ws/{workspaceId}/{service}/*` scheme resolving the
  origin from qits-held state, generalising qits' current daemon web-view proxy.
- **Fronting split-out siblings** — as artifacts, telemetry and the rest become their own processes,
  each is enabled with a `proxy-hosts` entry and nothing changes for callers.
- **Subsuming the edge** — TLS termination and edge authentication in the gateway, so a standard qits
  deployment needs no external reverse proxy at all.
