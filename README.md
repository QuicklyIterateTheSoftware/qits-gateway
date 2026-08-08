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

The enum carries a service's **display** identity as well as its routing identity — a navigation
label and a place in the list, or neither. That is what lets the gateway answer
[`/main-navigation`](#main-navigation) instead of every SPA shipping its own copy of the menu, and a
service is still declared in exactly one place.

**Resolution is longest-prefix-wins**, regardless of declaration order, so adding a service never
depends on where its line lands in a properties file. Matching is **segment-aware** — `/art` never captures `/artifacts/…`, and `/ci` never
captures `/cicd/…`. A path no route claims names **no upstream**: the gateway opens no connection
for it and answers it itself — with the [landing SPA](#the-landing-spa) on a browser-shaped request,
and with a 404 otherwise.

Forwarding is **verbatim**: the upstream sees the path unchanged (`/artifacts/blobs` reaches
qits-artifacts as `/artifacts/blobs`), so a service and the qits SPA both get exactly the paths they
serve and nothing breaks apps that emit absolute-root asset URLs.

The one response-side exception is `Cache-Control`, and only one value of it: Quarkus'
static-resource default, `public, immutable, max-age=86400`. That blanket is correct exactly where
the filename is content-hashed (a new build names a new file) and wrong everywhere else — the
`index.html` that names the bundles, favicons, logos, i18n files — where it made a browser keep
yesterday's file for a day. `EdgeCacheControl` rewrites the default to `Cache-Control: no-cache`
(revalidation stays; a 304 costs one round trip) unless the path ends in Angular's hash shape
(`main-4RS6EA47.js`). A header a handler *chose* is a decision and passes through untouched —
`no-store` on the machine routes is never weakened, the git protocol's own caching is never
touched, and an upstream that deliberately marks a response cacheable stays marked.

## The landing SPA

The front door serves the platform's landing page out of its own binary. `qits-spa-home` — an
Angular 21 application that consumes `@qits/ui-components` and `@qits/angular` from qits' own npm
registry — is a **git submodule at `src/main/webui`**, which is [Quinoa](https://docs.quarkiverse.io/quarkus-quinoa/)'s
default ui-dir, so the location is a convention rather than a setting. Quinoa turns the built bundle
into build-time generated static resources: no second container, no nginx, no runtime file system.

Every other qits service mounts its client under its own segment (`/projects/`, `/ci/`, …) because
the gateway routes it there. The gateway has **no segment**, and this is the platform's landing
page, so it is mounted at `/` — Quinoa's default `ui-root-path`.

### How it coexists with the route table

The two are layered, and **the route table decides precedence** — the proxy is wedged between the
SPA's two halves rather than sitting behind both:

| Order | Route | Claims |
| --- | --- | --- |
| 0 | `EdgeCacheControl` | nothing — installs a headers-end hook and yields |
| 100 | `ConfigJsonRoute`, `AuthMeRoute`, `NavigationRoute` | `/api/config.json`, `/api/auth/me`, `/main-navigation` |
| 1060 | Quinoa's generated static resources | the bundle's own files (`/`, `/index.html`, `/main-*.js`, …) |
| 10 000 | `RouteConstants.ROUTE_ORDER_DEFAULT` | where an unordered route would land |
| **20 000** | **`GatewayRouter`** | **the route table; no match ⇒ `next()`** |
| 40 000 | Quinoa's SPA fallback | every **GET/HEAD/OPTIONS** not in `ignored-path-prefixes` → `index.html` |

Both orders on the Quinoa/Quarkus side are read off the jars this project builds against, not
assumed: 1060 is Quarkus' `GeneratedStaticResourcesProcessor`, 40 000 is Quinoa's
`QuinoaRecorder.QUINOA_SPA_ROUTE_ORDER`. `GatewayRouter.ROUTE_ORDER` carries the table and the
reasoning; anything in `(1060, 40000)` would do, and a value at or past 40 000 silently restores the
old arrangement.

So a proxied segment beats the landing page **because a route claims it**, and the SPA gets only
what no route claimed. `quarkus.quinoa.ignored-path-prefixes` is back down to
`/api,/q,/main-navigation` — the gateway's own machine surface, near enough the same list every
other qits service carries and for the same reason: those paths are served by this process, the
route table has no say over them, and something must still stop the SPA from answering a mistyped
one with a web page. The third entry is defence in depth rather than what makes its route work
(order 100 already wins the exact path); what it covers is the near miss, `/main-navigation/`, which
would otherwise answer `200 text/html` to a client parsing JSON.

Consequences worth stating plainly:

- **A service is added in one place.** Extend `QitsService`, give it a `proxy-hosts` entry, and it
  is routed. There is no second list to keep in step. That list used to exist — it named every
  platform segment, because the SPA fallback ran *first* and each segment had to be spelled to hold
  it off — and a segment missing from it was answered with `index.html` and `200 text/html` instead
  of being proxied. That failure mode is gone with the ordering that caused it.
- **An unconfigured service now answers 200, not 404.** A path whose service has no `proxy-hosts`
  entry is claimed by no route, so it falls through to the landing page — `/projects/x` on a gateway
  that does not route `projects` is the SPA. That is the quietest of the possible failures, and it
  is why `RouteTableHealthCheck` reporting **NOT READY** on an empty table matters more than it did:
  readiness, not a response code, is what makes an unconfigured gateway visible.
- **There is no way to configure a `/` catch-all**, so nothing can be put in front of the landing
  page by configuration. `GatewayRoute` rejects an empty prefix rather than normalising it into one,
  and `proxy-hosts` keys are `QitsService` segments — the single-upstream topology is gone from the
  configuration surface, not merely unused.
- **`/api/config.json` is answered by the gateway, not by the bundle.** qits-spa-home ships a
  `public/api/config.json` stub (`{"telemetry":null,"capture":null}`) so a standalone `ng serve` has
  the shape `@qits/angular` expects, and it lands in the bundle as a static resource. It never wins:
  `ConfigJsonRoute` is registered at order 100, the static resources at 1060. The collision is easy
  to *miss* rather than easy to hit, because a dark gateway relays exactly the stub's bytes — the
  way to tell them apart is the header (`Cache-Control: no-store` is ours, `public, immutable,
  max-age=86400` is the stub's). That is how the one real bite was found: `router.get()` matches GET
  only, so **HEAD** used to fall past the route and be answered by the stub with a day-long cache
  hint. `ConfigJsonRoute` now names both methods on one route.

### Building it

**This repository does not build the SPA from scratch in every context, and the split is deliberate.**
`qits-spa-home` depends on `@qits/angular` and `@qits/ui-components`, which exist **only** on the
platform's own npm registry (qits-artifacts). Who can reach that registry decides where the install
may happen:

| Context | Reaches the platform registry | What it does |
| --- | --- | --- |
| a developer on the deployment host | yes (`localhost:8081`, via the submodule's committed `.npmrc` and lockfile) | Quinoa runs the real `npm ci` and `npm run build`, with the node on `PATH` |
| the CI step container | yes (on `qits-net`) | runs the install and the build itself, before the image build |
| a `RUN` step in a docker build | **no** — not the qits-net alias, not `localhost:8081`, not `host.docker.internal` | packages the bundle the step already built; runs no install |

The image build is therefore the deviation, and it is confined to one `RUN` in `docker/Dockerfile`:
it provisions its own pinned node (npm bundled; the Mandrel builder image ships none) and replaces Quinoa's
install and build commands with `--version`, the null command. **It does not fail quietly:** the
`RUN` first asserts the bundle is in the context — before the multi-minute native compile — and
Quinoa's own `build-dir` check would stop it after. A forgotten `npm run build` is a red build, never a
gateway that ships without its landing page.

That same `RUN` copies the bundle onto itself before invoking maven, which looks superstitious and
is not: Quinoa **moves** `build-dir` into `target/quinoa/build`, and overlayfs cannot rename a
directory that still lives in a lower image layer. It answers `EXDEV`, the JDK falls back to its
cross-file-store path, and that path refuses a non-empty directory — the build dies with a
`DirectoryNotEmptyException` naming the source and explaining nothing. Every other Quinoa build in
the platform is immune by accident, because its builder stage *creates* `dist/` itself. Ours arrives
by `COPY`, so it has to be re-materialised in the layer that is about to move it — which is also why
that `cp` cannot be a tidier `RUN` of its own.

The platform's general recipe for this — every other service does have its builder run the client's
real install — is `docs/project-setup-quinoa-angular.md` in the qits monorepo. This repo follows it
except where the `@qits` scope forces the split above.

## Configuration

Everything is MicroProfile config, so any key works as a property, a system property or an
environment variable.

**There is still no catch-all upstream.** No proxy route claims "everything else". A path no service
claims names no upstream, and the gateway opens no connection for it. It used to fall through to the
qits monolith (`qits.gateway.app-host`), so the split could run beside it and take paths over one at
a time; qits is deployed clean now — these services and nothing else, sharing no database, volume or
session with a monolith — so there is no upstream entitled to "everything else", and both that key
and the route it built are gone.

What did change is what an unclaimed path *looks like*: it is now the landing page rather than a
404. That is **static serving, not proxying** — no host is selected, no socket is opened, and the
route table is untouched by it.

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

| Service (submodule) | Segment | Reached at | Default host | Navigation ᶜ |
| --- | --- | --- | --- | --- |
| `qits-artifacts` | `artifacts` | `/artifacts/*`, `/v2/*` ᵃ | `qits-artifacts` | Artifacts (3) |
| `qits-observability` | `observability` | `/observability/*` | `qits-observability` | Observability (7) |
| `qits-workspaces` | `workspaces` | `/workspaces/*` | `qits-workspaces` | Workspaces (5) |
| `qits-projects` | `projects` | `/projects/*` | `qits-projects` | Projects (4) |
| `qits-stt` | `stt` | `/stt/*` | `qits-stt` | — |
| `qits-events` | `events` | `/events/*` | `qits-events` | Events (6) |
| `qits-ci` | `ci` | `/ci/*` | `qits-ci` | CI (1) |
| `qits-cd` | `cd` | `/cd/*` | `qits-cd` | — |
| `qits-platform-deployments` | `platform-deployments` | `/platform-deployments/*` | `qits-platform-deployments` | Deployments (2) |
| `qits-platform-docs` | `platform-docs` | `/platform-docs/*` | `qits-platform-docs` | Docs (8) |

ᵃ `/v2/*` is the OCI registry root, claimed by the artifacts entry rather than by a key of its own —
see "The routing model". It is the only prefix in the system that is not a service segment.

ᶜ The label and place this service takes in [`/main-navigation`](#main-navigation) when it is routed;
`—` means it is routable but never shown. `stt` is an API with no SPA behind it, and `cd` is
superseded by `platform-deployments`, which carries the *Deployments* entry — so a platform old
enough to still route `cd` shows one link rather than two meaning the same thing.

`qits-platform-deployments` supersedes `qits-cd` — it owns environment topology and deployment
execution in one service. The `cd` entry stays in the registry because a platform deployed before
that cutover still names it; nothing new configures it.

`qits-platform-docs` serves the documentation sites qits-artifacts holds and stores nothing of its
own — it resolves a site's newest version and streams the bytes through. So it is a *view*, and
routing it on a deployment without qits-artifacts gives you a reader for documentation nobody has
published.

A **multi-word segment is dashed**, in the URL and in the container name alike: the underscore in
`PLATFORM_DEPLOYMENTS` is Java's spelling of an enum constant and appears nowhere else.

(The "default host" is the container's `qits-net` DNS name — what you would normally put in the
`proxy-hosts` value. Add a service to the enum when a new component splits out.) As environment
variables:

```bash
QITS_GATEWAY_APP_HOST=qits
QITS_GATEWAY_PROXY_HOSTS_ARTIFACTS=qits-artifacts
QITS_GATEWAY_PROXY_HOSTS_OBSERVABILITY=qits-observability:9000   # host:port when not on 8080
QITS_GATEWAY_PROXY_HOSTS_PLATFORM_DEPLOYMENTS=qits-platform-deployments   # dashed segment
```

The last line works because the gateway **looks each known service up by name** rather than relying
only on config-mapping discovery, and that is not a detail to remove. A `Map` member of a
`@ConfigMapping` is filled by matching visible property names against `qits.gateway.proxy-hosts.*`,
and an environment variable carries no separators — so the wildcard standing for the map key
consumes one word, and `…_PLATFORM_DEPLOYMENTS` matches nothing. The entry would land in no map: no
route, no error, and a gateway that reports ready while serving the landing page where a service
should be. An exact-name lookup has no such ambiguity, so `RouteTable` asks for every segment in the
closed enum. Discovery keeps its one remaining job — an unknown key is only visible there, and is
what still fails startup.

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
- **The gateway's own management surface is never proxied.** `/q/*` is served locally whatever the
  route table says, and `GatewayRouter` passes it to `next()` explicitly rather than matching it.
  `/q` is also in `quarkus.quinoa.ignored-path-prefixes`, which is what keeps a mistyped management
  path a 404 instead of the landing page.
- **Serving the landing page is static serving, not proxying, and the SSRF rule is untouched.** The
  bundle is baked into the binary at build time and answered from memory: nothing about a request
  selects a host, a port or a file path, and `RouteTable` still resolves upstreams from
  configuration alone. What the SPA changed is which *unclaimed* paths get a 200 instead of a 404 —
  a question about this process' own output, not about where bytes are sent.
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

- the gateway's own surface (`/q/*`, `/api/auth/*`, `/api/config.json`, `/main-navigation`) —
  permanent;
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

**The landing page is not public either, and that is the decision rather than an oversight.** `/` is
not in `PublicPaths`, so an `oauth` gateway challenges an anonymous visitor *before* serving a byte
of the SPA: the front door asks who you are and the landing page is what you see once it knows. That
also means the bundle's own assets (`/main-*.js`, `/styles-*.css`) sit behind the session, which is
the same posture and needs no separate rule — they are fetched by a browser that has just completed
the code flow. A `local` gateway has no challenge to make and serves the page openly, like
everything else in that target. If a deployment ever wants an anonymous landing page, that is one
entry in `PublicPaths.gatewaysOwn` plus the asset prefixes, taken deliberately.

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
| `/main-navigation` | the platform's left navigation, derived from the route table (see below) |
| `/` and every unclaimed GET | the [landing SPA](#the-landing-spa), with client-route fallback |

All are served locally and never proxied — they are registered ahead of the proxy (order 100) or
skipped by it, and no route table can claim them.

`/api/config.json` is **web-component configuration, not telemetry configuration**, so it lives with
the thing that serves the web components rather than with qits-observability, which used to own it.
The browser cannot read environment variables and this process can: a deployment injects
`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_SERVICE_NAME` and
`QITS_CAPTURE_ENDPOINT`, and the document relays them as two independently nullable sections —
`telemetry` (dark without an OTLP endpoint) and `capture` (no capture button without a capture
endpoint). It is the one gateway path that **cannot** be renamed: `@qits/angular` fetches the
base-relative `api/config.json` before the application bootstraps, so there is no running app to
tell about a new address. It takes no segment prefix because the gateway has none.

### `/main-navigation`

The platform's left navigation, as JSON, answered `GET` and `HEAD`:

```json
{"links":[{"label":"Home","href":"/"},{"label":"CI","href":"/ci/"}]}
```

**It is derived from the route table, not from the `QitsService` enum**, and that is the entire
reason it lives here. `@qits/ui-components` used to hardcode the same list at compile time and ship
it as an npm package: a second declaration of what the platform serves, held by something that
cannot know, updated by a release. It lagged exactly the way a copy does — `/platform-docs/` was
routed for a while with no entry pointing at it. The gateway is the one process that knows what it
routes, so a service appears in the menu **precisely when it is proxied**, with nothing to release.

The rules, all of them:

- **`Home` is prepended unconditionally.** The landing SPA is this process' own static output, not a
  `QitsService`, so it is in no route table — and it is never missing, because it is compiled into
  the binary.
- **One link per service, not per route.** `qits-artifacts` produces two routes from its single
  `proxy-hosts` entry, and **`/v2` never appears**: it is the address docker hardcodes for the OCI
  Distribution API, a protocol root rather than a page.
- **A service with no label is not in the navigation** — `stt` (an API with no SPA behind it) and
  `cd` (superseded by `platform-deployments`, which carries the *Deployments* entry). Both omissions
  are decisions rather than gaps; the enum says so on each constant and `QitsServiceTest` holds it.
- **Order is `QitsService.navigationPosition()`**, never the route table's — that one is sorted
  longest-prefix-first, which is a matching concern and would put the menu in an order nobody chose.
- **`href` carries its trailing slash** (`/ci/`). The consuming library normalises both sides when it
  decides which entry is current, but it renders the anchor verbatim, so the slash is what a user
  sees and copies.

A gateway routing the whole registry above answers, in this order:

```json
{"links":[{"label":"Home","href":"/"},{"label":"CI","href":"/ci/"},
          {"label":"Deployments","href":"/platform-deployments/"},
          {"label":"Artifacts","href":"/artifacts/"},{"label":"Projects","href":"/projects/"},
          {"label":"Workspaces","href":"/workspaces/"},{"label":"Events","href":"/events/"},
          {"label":"Observability","href":"/observability/"},{"label":"Docs","href":"/platform-docs/"}]}
```

An **object** with a `links` array, not a bare array, so this document can grow a second field
without every SPA in the platform needing a release to keep parsing it. `Cache-Control: no-store`:
the route table is a deployment fact, and a browser holding yesterday's copy renders a menu missing
the service it was just told to go and use. It is public (`PublicPaths`) for the same reason
`/api/config.json` is — the chrome renders before there is anything to authenticate.

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

### The webui submodule

**The clone-alone rule now reads "clone *and* `git submodule update --init`".** That is a real
change to what this repo needs, so it is written down rather than left to a build error:

```bash
git submodule update --init src/main/webui
(cd src/main/webui && git switch main)             # the platform's submodule convention
(cd src/main/webui && npm ci)                   # once; needs the platform's npm registry
```

What each command needs after that:

| Command | Submodule | node on `PATH` | Network |
| --- | --- | --- | --- |
| `./mvnw test` | no | no | no |
| `./mvnw verify -Dqits.variant=oauth` | yes | yes | yes ᵇ |
| `./mvnw package …` | yes | yes | yes, same |
| `docker build …` | yes, plus a built `dist/` | no (the stage brings its own) | public internet only |

ᵇ Quinoa runs `npm ci` (`quarkus.quinoa.ci=true` — a build of this repo must never rewrite a
submodule's lockfile), and `npm ci` deletes and reinstalls `node_modules` on every run — served
from npm's local cache when it can be, from the registry when it cannot, so "reachable registry"
is the honest requirement. The **suite** needs neither node nor the submodule in any case: Quinoa is off
under `%test`, which is also why what the SPA is actually served as is proven on the packaged image
and not by a `@QuarkusTest`.

An **uninitialised** gitlink is an empty directory, and that is the one case Quinoa treats as a
misconfiguration rather than "no client": `package` stops with `No package.json found in Web UI
directory`. A `src/main/webui` that is missing entirely would instead disable Quinoa with a warning.

One wart to know: Quinoa **moves** `dist/qits-spa-home/browser` into `target/quinoa/build` rather
than copying it, so a `./mvnw package` leaves the submodule's `dist/` emptied. Harmless here because
the next build regenerates it — but it is why the image build, which does *not* regenerate it, first
re-materialises the bundle in its own layer.

`quarkus:dev` changes too: Quinoa detects Angular, starts `npm start` as a dev service on :4200 and
proxies to it, so dev mode live-reloads the SPA as well as the gateway — and needs `node_modules` to
do it.

### Commands

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
# its own Mandrel builder, which is also the escape hatch on a machine with no GraalVM. The bundle
# is NOT built by the stage — build it first, see "The landing SPA".
(cd src/main/webui && npm run build)
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

This repository is a **git submodule** of qits (`services/qits-gateway/`) so the two travel together,
but it builds and releases on its own — a clone of *this* repo (plus `git submodule update --init`)
is a complete build. Three things are duplicated here on purpose and must be kept in step when qits
moves them:

- the **Quarkus platform version** (`quarkus.platform.version` in `pom.xml`),
- the **JDK release** (`maven.compiler.release`, `.sdkmanrc`, and the native builder image tag), and
- the **Quinoa version** (`quinoa.version` in `pom.xml`) — Quinoa is in no BOM, and the platform
  pins one version for every service that serves a client.

It also carries a submodule *of its own*: `src/main/webui` is `qits-spa-home`, which the monorepo
holds a second time at `frontends/qits-spa-home`. Both entries follow the platform convention
(`--name <bare repo name>`, `ignore = all`, `update = merge`, `branch = main`), and the two gitlinks
move independently — the monorepo's says which commit the workspace checks out, the gateway's says
which commit the front door ships.

Nothing else is shared — the gateway depends on no qits module, which is what lets it start, stop and
be upgraded independently of what it fronts.

## Status & roadmap

Implemented: the `QitsService` registry (a named, enum-backed set of proxyable services),
config-driven longest-prefix / segment-aware routing, verbatim streaming reverse proxy with
WebSocket passthrough, edge-header hygiene, health/readiness, the landing SPA served from the binary
via Quinoa, `/main-navigation` derived from the route table, native build, container image.

Planned, per the epic's staging:

- **Workspace addressability** — a general `/ws/{workspaceId}/{service}/*` scheme resolving the
  origin from qits-held state, generalising qits' current daemon web-view proxy.
- **Fronting split-out siblings** — as artifacts, telemetry and the rest become their own processes,
  each is enabled with a `proxy-hosts` entry and nothing changes for callers.
- **Subsuming the edge** — TLS termination and edge authentication in the gateway, so a standard qits
  deployment needs no external reverse proxy at all.
