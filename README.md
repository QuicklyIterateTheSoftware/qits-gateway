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

A service may claim **one additional, root-level prefix** when a protocol client hardcodes an
address the gateway does not get to choose. There is exactly one today: `qits-artifacts` also claims
`/v2`, the OCI Distribution API root, because docker and podman resolve an image reference against
`<host>/v2/` and accept no path prefix — so the registry has no `/artifacts/…` spelling to route
instead. It rides the **artifacts** `proxy-hosts` entry: there is no `…_V2` key (naming one is still
an "unknown qits service" startup error), no second host for a deployment to hold in sync, and no
phantom component in the startup log or the readiness payload. It is not an alias mechanism —
`/artifacts/v2` is not a second address for it, and does not exist.

The set of services the gateway can front is a **named registry** — the `QitsService` enum. A
service's public identity drops the `qits-` prefix, so `qits-artifacts` is reached at `/artifacts/*`
and forwarded to the `qits-artifacts` container. Which services are *live* is a deployment decision
(see [Configuration](#configuration)); everything they do not claim is a 404 from the gateway
itself.

**Resolution is longest-prefix-wins**, regardless of declaration order, so adding a service never
depends on where its line lands in a properties file. Matching is **segment-aware** — `/art` never captures `/artifacts/…`, and `/ci` never
captures `/cicd/…`. A path no route claims (and no live service) is answered with **404 by the
gateway itself**; it opens no connection.

Forwarding is **verbatim**: the upstream sees the path unchanged (`/artifacts/blobs` reaches
qits-artifacts as `/artifacts/blobs`), so a service and the qits SPA both get exactly the paths they
serve and nothing breaks apps that emit absolute-root asset URLs.

## Configuration

Everything is MicroProfile config, so any key works as a property, a system property or an
environment variable.

**There is no catch-all.** A path no service claims is answered 404 by the gateway itself. It used
to fall through to the qits monolith (`qits.gateway.app-host`), so the split could run beside it and
take paths over one at a time; qits is deployed clean now — these services and nothing else, sharing
no database, volume or session with a monolith — so there is no upstream entitled to "everything
else", and both that key and the route it built are gone.

A consequence worth knowing: a gateway with no `proxy-hosts` entries routes nothing and reports
**not ready** (`/q/health/ready`). That is the intended signal, and it is why nothing is enabled by
default — see the registry below.

The **service registry** — one entry per live split-out service, keyed by its public segment:

| Key | Default | Meaning |
| --- | --- | --- |
| `qits.gateway.proxy-hosts.<segment>` | — | upstream `host` or `host:port` for that service; the entry both enables the service and names its target |

`<segment>` must be a known service — one of the `QitsService` enum values below; an unknown segment
is rejected at startup. Each is reached at `/<segment>/*` and forwarded verbatim. A service is routed
**only** when it has a `proxy-hosts` entry, so undeployed services simply 404 rather than 502.

| Service (submodule) | Segment | Reached at | Default host |
| --- | --- | --- | --- |
| `qits-artifacts` | `artifacts` | `/artifacts/*`, `/v2/*` ᵃ | `qits-artifacts` |
| `qits-observability` | `observability` | `/observability/*` | `qits-observability` |
| `qits-workspaces` | `workspaces` | `/workspaces/*` | `qits-workspaces` |
| `qits-projects` | `projects` | `/projects/*` | `qits-projects` |
| `qits-stt` | `stt` | `/stt/*` | `qits-stt` |
| `qits-ci` | `ci` | `/ci/*` | `qits-ci` |
| `qits-cd` | `cd` | `/cd/*` | `qits-cd` |

ᵃ `/v2/*` is the OCI registry root, claimed by the artifacts entry rather than by a key of its own —
see "The routing model". It is the only prefix in the system that is not a service segment.

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

The shipped `application.properties` enables **nothing**: it carries the whole enum as commented
`proxy-hosts` lines and leaves naming the table to the deployment. That is not laziness — a `Map`
entry cannot be unset by a later config source, only overridden, so an entry shipped here is one no
deployment and no test could take away, and "run five of the six" would become inexpressible. Other
settings worth knowing: `quarkus.http.limits.max-body-size` (must be ≥ the largest
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
- **`Authorization` is forwarded verbatim on an ordinary request.** It is neither reserved nor on the
  strip list. Less load-bearing than it used to be — the OCI registry no longer carries a write
  guard, so no push credential depends on surviving this hop — but a docker with stored credentials
  sends a Basic header on *pulls* too, and it must neither be eaten nor turned into a challenge on a
  public path. Note the deliberate asymmetry with the WebSocket rule below, which drops it: a
  handshake is a protocol negotiation, not a request an upstream answers.
- **`X-Forwarded-For` is set, not appended** — the gateway is the outermost hop, so any inbound value
  is client-supplied and worthless.
- **A WebSocket handshake forwards an allow-list, not everything-minus-the-prefix.** Only the RFC
  6455 handshake headers (`Upgrade`, `Connection`, `Sec-WebSocket-*`, `Host`) plus `Origin` survive;
  the gateway then asserts `X-Qits-*` and `X-Forwarded-*` on top. Everything else the client sent is
  dropped, **including `Cookie` and `Authorization`** — authentication terminates here and no
  upstream authenticates by either, so neither has any business travelling further.

  This needs its own rule because `vertx-http-proxy` short-circuits an upgrade *before* installing
  its interceptor chain, so the ordinary strip-and-inject never ran on a handshake at all. Until it
  was fixed, a client-supplied `X-Qits-User` reached the upstream unchanged — a complete
  authentication bypass through the one door the prefix strip did not cover — and a genuinely
  authenticated socket arrived anonymous. Both halves are in `EdgeHeaders`, one method each, and
  `GatewaySocketRoutingTest` is the regression.
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

The allowlist is grouped by **who serves the path**, because that is what decides when an entry
expires:

- the gateway's own surface (`/q/*`, `/api/auth/*`, `/api/config.json`) — permanent;
- the segment-prefixed forms a split-out service serves (`/artifacts/git/*`,
  `/observability/api/otel/*`, `/ci/api/events/*`, `/workspaces/daemon/*`, `/projects/mcp`, …) —
  permanent, and identical to the address the service serves on `qits-net` because forwarding is
  verbatim;
- **`/v2/*`** — the OCI registry, and doubly exceptional: not segment-prefixed (the client
  hardcodes the root), and the one entry that is public for **read methods only** (`GET`/`HEAD`).
  Pulls are anonymous by design: image names are meant to be *shared*, and are guessable on
  purpose, which is why `/v2/_catalog` stays unimplemented and the posture stays private-network
  rather than capability-URL the way the git host is. Writes fall back to the session policy, and
  that refusal is the registry's **whole** external write protection: qits-artifacts carries no
  push guard of its own (producers dial it on `qits-net`, where callers are trusted), so an
  internet `docker push` must die here — on a challenge no registry client can answer, which is
  the point, because external push is unwanted entirely. Widening `/v2` back to all methods
  without restoring a guard in qits-artifacts opens push to the world; `PublicPathsTest` and
  `GatewayAuthTest` both hold that line;
There used to be a third group: the monolith-relative forms (`/git/*`, `/api/otel/*`, `/mcp/*`, …),
which were public because the `/` catch-all's upstream served them. They went with the catch-all.
Those paths now name no upstream, so they are neither routed nor public — `PublicPathsTest` asserts
they are *protected*, rather than simply dropping the cases, so a re-added catch-all fails a test
instead of silently reopening an anonymous surface.

A **service's** `/q/*` is deliberately not public. Each service's non-application root has moved
under its segment (`/observability/q/openapi`), but nothing that must reach it comes through the
gateway — container healthchecks and orchestrator probes dial the service directly on `qits-net`.
What is left at the front door is swagger-ui, the OpenAPI document and deployment detail, which are
for humans with a session. The gateway's own `/q/*` is public precisely because it is the only
published listener and its probe has no other address.

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

Measured with `-Dquarkus.native.container-build=true` (same machine, same day) — pinned to the
container builder so the three rows share one native-image version, not because a container is
needed to build:

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
| `/api/auth/me` | which auth target this build carries, and who is logged in |
| `/api/config.json` | the web components' identity relay (see below) |

All four are served locally and never proxied, even under a `/` catch-all.

`/api/config.json` is **web-component configuration, not telemetry configuration**, so it lives with
the thing that serves the web components rather than with qits-observability, which used to own it.
The browser cannot read environment variables and this process can: a deployment injects
`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_SERVICE_NAME` and
`QITS_CAPTURE_ENDPOINT`, and the document relays them as two independently nullable sections —
`telemetry` (dark without an OTLP endpoint) and `capture` (no capture button without a capture
endpoint). It is the one gateway path that **cannot** be renamed: `@qits/angular` fetches the
base-relative `api/config.json` before the application bootstraps, so there is no running app to
tell about a new address. It takes no segment prefix because the gateway has none.

Readiness deliberately does **not** probe upstreams: an upstream being down is a 502 for that path,
not a reason to pull the whole front door and take every other component offline with it.

## Build & run

`.sdkmanrc` pins **`25.0.2-graalce`** — a JDK 25 (Spotless' google-java-format needs 21+) that also
carries `native-image`, so `sdk env` is the whole toolchain and `-Dnative` compiles in-process with
**no container involved**. Nothing else has to be installed and `GRAALVM_HOME` should stay unset.

> **If `native-image` is missing, the build does not fail.** Quarkus logs `Cannot find the
> native-image in the GRAALVM_HOME, JAVA_HOME and System PATH. Attempting to fall back to container
> build`, pulls a 1.8 GB Mandrel image and compiles under docker. Green either way — which is why
> the toolchain is declared rather than assumed. The fallback still works and is what a CI runner
> without a GraalVM gets; recognise it by the image pull when a ~40 s build starts downloading a
> container.

Packaging additionally requires naming an [auth target](#build-targets) — `-Dqits.variant=` is
enforced from `prepare-package` on, so `test` and `quarkus:dev` run flagless but nothing that
produces an artifact does.

```bash
# Tests (unit + an end-to-end proxy suite against a stub upstream; no docker needed)
./mvnw test

# JVM build, then run
./mvnw package -Dqits.variant=oauth
java -jar target/quarkus-app/quarkus-run.jar

# Dev mode (live reload). Defaults to fronting a qits on localhost:8080 and listens on :8000,
# so it does not collide with the qits it is proxying.
./mvnw quarkus:dev

# Native binary -> target/qits-gateway (~40 s, no docker)
./mvnw package -Dnative -Dqits.variant=oauth
./target/qits-gateway

# Native container image. This is how the binary is SHIPPED, not how it is built: the stage brings
# its own Mandrel builder, which is also the escape hatch on a machine with no GraalVM.
docker build -t qits/gateway:latest --build-arg QITS_VARIANT=oauth -f docker/Dockerfile .
```

Point it at something without touching a file:

```bash
./mvnw quarkus:dev -Dqits.gateway.proxy-hosts.artifacts=127.0.0.1:9000 \
                   -Dqits.gateway.proxy-hosts.projects=localhost:8090
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
