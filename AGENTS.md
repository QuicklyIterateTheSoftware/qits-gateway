# AGENTS.md

Guidance for AI coding agents working in this repository. `CLAUDE.md` is a symlink to this file.

## What this is

`qits-gateway` — the qits front door: a standalone Quarkus 3 (Java 25) reverse proxy that receives
every inbound request for a qits deployment, **authenticates it**, and delegates it to the right
component. Read `README.md` first; it is the contract document (routing model, configuration
reference, the header contract, security posture) and must be updated in the same change that alters
any of them.

Key facts that shape every change here:

- **Separately deployable, parent-less build.** This repo is a git submodule of the qits monorepo
  but is *not* a module of its Maven reactor. Never add a `<parent>` or a dependency on a qits
  module. The clone-alone rule now reads **"clone *and* `git submodule update --init`"**: this repo
  carries `qits-spa-home` at `src/main/webui` and Quinoa packages it. `./mvnw test` still needs
  neither the submodule nor node (Quinoa is off under `%test`); `package` needs both. See README
  "The webui submodule" for the table.
- **Compiles to a GraalVM native binary, locally and without docker.** `.sdkmanrc` names
  `25.0.2-graalce`, so `sdk env` gives you a `native-image` and `./mvnw package -Dnative
  -Dqits.variant=…` produces `target/qits-gateway` in about 40 seconds. Two consequences:
  - **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
    Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
    either way, so the fallback is easy to be in without noticing — recognise it by the image pull.
    Leave it working (it is what a GraalVM-less CI gets), but it is not the declared path.
  - **Every added extension costs image size, build time and reflection surface**, and a missing
    reflection/proxy/`ServiceLoader`/resource registration fails at *runtime in the binary* while
    the JVM suite stays green. Keep the dependency set minimal; prefer raw Vert.x routes over a REST
    layer. If a native build needs configuration to pass, that configuration is part of the change.
  - `docker/Dockerfile` is the **shipping** form — it wraps this binary in an image (and, because
    its builder stage carries its own Mandrel, is the escape hatch on a machine with no GraalVM).
- **Stateless.** No database, no ORM, no session state. Route resolution reads configuration only.
- **Streaming.** Never buffer request or response bodies — SSE, git smart-HTTP, WebSocket upgrades
  and large artifact uploads all pass through here.

## Commands

```bash
git submodule update --init src/main/webui      # the landing SPA; package needs it, test does not
(cd src/main/webui && npm ci)                   # once; needs the platform's npm registry

./mvnw test                    # unit tests + the end-to-end proxy suite (no docker needed)
./mvnw package -Dqits.variant=oauth   # JVM build -> target/quarkus-app/ (the flag is required)
./mvnw package -Dqits.variant=local   # the EXPLICITLY UNAUTHENTICATED build; never internet-expose
./mvnw quarkus:dev             # dev mode on :8000, fronting a qits on localhost:8080
./mvnw package -Dnative -Dqits.variant=oauth   # native binary -> target/qits-gateway (no docker)
./mvnw test -Dtest=RouteTableTest

(cd src/main/webui && npm run build)   # REQUIRED before the image build: the builder stage packages
docker build -t qits/gateway:latest --build-arg QITS_VARIANT=oauth -f docker/Dockerfile .
```

**The image build does not build the SPA, and that is the one place this repo deviates from the
platform's Quinoa recipe** (`docs/project-setup-quinoa-angular.md` in the qits monorepo). `qits-spa-home` depends on
`@qits/angular` and `@qits/ui-components`, which exist only on the platform's own npm registry, and
a `RUN` step in a docker build can reach that registry by no address at all. So the install happens
where it can — a developer's host, or the CI step container on `qits-net` — and the builder stage
packages an already-built `dist/` with Quinoa's install and build commands replaced by `--version`.
An install that "worked" inside the image build would have resolved the `@qits` scope against
npmjs.org, where those names do not exist; it fails loudly instead, and so does a missing bundle.

The `--build-arg` has no default, deliberately: an image is a shipped gateway, and the
`require-auth-variant` enforcer rule exists so a shipped gateway always said out loud whether it
authenticates.

**`-Dqits.variant` is a packaging flag, not a test flag.** `verify -Dqits.variant=local` does not run
"the suite against the local target" — it re-augments *every* `@QuarkusTest` into the open target, and
`GatewayAuthTest` (which asserts the oauth challenge) then fails against a build that has no
challenge to make. `local` is covered by `LocalVariantTest`'s `@TestProfile`, which flips the same two
build properties for one class. Use `-Dqits.variant=oauth` for a full `verify`; the enforcer is bound
to `prepare-package` precisely so everything before it runs flagless.

Spotless (google-java-format) runs automatically at `process-sources`, so formatting is never a
review topic — but it needs JDK 21+; `.sdkmanrc`'s `25.0.2-graalce` covers both that and the native
build.

## Layout

```
src/main/java/eu/wohlben/qits/gateway/
  QitsService.java            the registry: enum of proxyable services; segment derivation (the
                              upstream host is the deployment's to name, never derived here),
                              plus the extra root-level prefixes a service may claim (/v2), plus
                              the DISPLAY identity (navigation label + position; no label ⇒ no
                              menu entry). The ONLY place a service is declared — route order, not
                              a config list, is what makes a proxied segment beat the landing SPA
  GatewayConfig.java          @ConfigMapping — the entire configuration surface
  GatewayRoute.java           one resolved route; prefix matching (framework-free)
  RouteTable.java             config -> routes (segment validation, host:port parse); longest-prefix.
                              Also looks every known segment up BY NAME, because @ConfigMapping Map
                              discovery drops a multi-word key set from an env var — read
                              resolveProxyHosts before simplifying it back to the mapped map
  GatewayRouter.java          the catch-all Vert.x route (order 20_000 — read its ROUTE_ORDER
                              javadoc before moving it); one HttpProxy per route
  EdgeHeaders.java            the only rewrites: header hygiene + X-Forwarded-* (verbatim otherwise)
  RouteTableHealthCheck.java  readiness = a non-empty route table
  AssertedIdentity.java       the identity hand-off from the route handler to EdgeHeaders
  ConfigJsonRoute.java        GET /api/config.json, as a raw route (there is no REST layer)
  NavigationRoute.java        GET|HEAD /main-navigation — the platform's left navigation, derived
                              from the ROUTE TABLE (not from QitsService.values()), so a service
                              appears in the menu exactly when this gateway routes it
  security/
    QitsAuthPolicy.java       the one authorization decision (global HttpSecurityPolicy)
    PublicPaths.java          the token-free allowlist — grouped by who serves the path
    AuthMeRoute.java          GET /api/auth/me, as a raw route (there is no REST layer)
    NonNavigationRequestChecker.java   499 instead of 302 for SSE/websocket/XHR (oauth only)
    LocalAuthMechanism.java   the `local` build target's fixed identity (local only)
    LocalIdentityProvider.java

src/main/webui/                 the qits-spa-home submodule — Quinoa's default ui-dir. Not this
                                repo's code; do not edit it from here, push it in its own repo and
                                move the gitlink.
```

Routing is **verbatim** (no path rewriting): a service is reached at `/<segment>/*` and the upstream
sees that path unchanged. Services are the closed `QitsService` set; a service is live only when a
`qits.gateway.proxy-hosts.<segment>` entry names its host. Add a service by extending the enum, not
by inventing a config key.

A service may also claim **extra, root-level prefixes** (`QitsService.pathPrefixes()`), which is how
qits-artifacts gets `/v2` — the OCI registry root that docker and podman hardcode and that has no
segment-prefixed spelling. One `proxy-hosts` entry then produces several routes. Two consequences a
change here must respect: `GatewayRoute.name()` is **no longer unique**, so `GatewayRouter` keys its
proxy map on the record rather than the name, and `RouteTable`'s comparator tie-breaks on `prefix()`
rather than `name()` so the ordering stays total. An extra prefix is a concession to a client whose
address we do not control — never a convenience alias, and `QitsServiceTest` asserts no extra can
shadow another service's segment.

**There is no catch-all UPSTREAM, and do not reintroduce one.** `/` used to route to the qits
monolith via `qits.gateway.app-host`; qits deploys clean now, with no monolith beside these services
and no shared state between them, so no *upstream* is entitled to every unclaimed path.
`GatewayRoute` rejects an empty prefix outright rather than normalising it back into one, because a
config value that lost its content would otherwise turn a service route into a catch-all silently.

**The doctrine was amended, on purpose, and here is exactly what changed.** It used to read
"nothing is entitled to every unclaimed path", and the landing SPA now is. What that sentence was
protecting is unchanged, and the difference is worth being precise about, because "we made an
exception once" is how the monolith's catch-all comes back:

- The old rule was about **proxying**. A catch-all upstream meant a path nobody owned got forwarded
  to a process that could not answer it, turning our 404 into someone else's 502 and hiding a
  configuration mistake behind a network hop. That is still forbidden: `RouteTable` still resolves
  upstreams from configuration alone, still opens no connection for an unmatched path, and there is
  still no key that would name a default target.
- What serves `/` now is **this process' own static output** — a bundle compiled into the binary at
  augmentation. No host is selected, no port is parsed, no socket is opened, and no part of a
  request influences what is read. The SSRF invariant is not weakened because nothing about it is
  involved.
- The failure mode the old rule feared *does* have a new shape: an unclaimed path answering
  `200 text/html` is worse than a 404 for a **machine** client. The guard is **route order**, not a
  list. `GatewayRouter` registers at 20 000, between Quinoa's static resources (1060) and Quinoa's
  SPA fallback (40 000), so a path the route table claims is proxied and only what no route claimed
  reaches the SPA. `quarkus.quinoa.ignored-path-prefixes` is therefore down to
  `/api,/q,/main-navigation` — the gateway's own machine surface, which no route table covers.
- **This is the inverted arrangement, and the inversion is the point.** The SPA fallback used to run
  *first*, which forced `ignored-path-prefixes` to re-state every platform segment — a hand-kept
  copy of `QitsService` that silently swallowed any segment missing from it. Adding a service now
  takes one place (the enum plus a `proxy-hosts` entry) and no list.
- Read `GatewayRouter.ROUTE_ORDER` before touching any of it: it carries the measured order table
  and its two bounds. Below 1060 the SPA's own assets stop being served; at or past 40 000 the old
  swallowing behaviour comes back silently. Both Quinoa numbers are read off the jars (Quinoa 2.8.2,
  Quarkus 3.34.6) and are not API — re-check them when the Quinoa pin moves.
- What an unmatched path costs is that it is a **200**, not a 404: `GatewayRouter` yields rather than
  answering, so a service with no `proxy-hosts` entry serves the landing page. `RouteTableHealthCheck`
  reporting NOT READY is what makes that visible; do not "fix" it by answering 404 in the router,
  which would take the landing page away from every unclaimed path.

## Conventions

- **Security invariant:** upstream host/port come from configuration *only*, never from any part of
  a request. Any change that lets a request influence the target is an SSRF and must not land.
- **Header hygiene is a contract**, not a nicety. `X-Qits-*` is the gateway's reserved namespace:
  everything it asserts about a request lives there, and `EdgeHeaders` strips that prefix
  unconditionally from every inbound request. Name a new trusted header `X-Qits-…` and it is already
  stripped; name it anything else and you have opened a bypass. The prefix is not configurable.
  `qits.gateway.forwarded.strip-request-headers` is the separate compatibility list for a
  forward-auth proxy's own header names — it may be extended, never shrunk below what a proxy
  fronting the gateway injects.
- Put edge-case logic (prefix matching, segment validation, host:port parsing, the reserved-header
  predicate) on the framework-free value types so it stays unit testable without booting the
  application; `RouteTableTest` and `EdgeHeadersTest` are where those cases belong.
  End-to-end behaviour goes in `GatewayRoutingTest`, which proxies to a real stub upstream.
- **Authentication terminates here and nowhere else.** Every other component trusts `X-Qits-User`
  unconditionally, so the strip-then-inject order in `EdgeHeaders` is load-bearing: both halves live
  in one method precisely so a later edit cannot separate them. Never move the injection into
  `GatewayRouter` "for clarity" — that is how the forged header wins.
- **A WebSocket upgrade is a second entry point, and it must stay inside `EdgeHeaders`.**
  `vertx-http-proxy` short-circuits an upgrade before installing its interceptor chain, so
  `handleProxyRequest` never runs on a handshake — for a while that meant every socket forwarded the
  client's `X-Qits-*` verbatim and injected nothing. `EdgeHeaders.applyToUpgrade` is that path;
  `GatewayRouter` decides *which* entry point applies and performs neither. It forwards an
  allow-list (`UPGRADE_HEADERS`) rather than stripping a prefix, because a handshake is a protocol
  negotiation and not a request an upstream answers — so nothing beyond the handshake and what the
  gateway itself asserts belongs on it, `Cookie` and `Authorization` included. A reserved name can
  never appear on that list, and `EdgeHeadersTest` asserts exactly that.
- **`SameOriginUpgradeCheck` does not exist** in the Quarkus this repo builds against, whatever older
  comments in this repo and its siblings claimed. If you are chasing why a socket fails through the
  gateway, it is not that.
- **A WebSocket upgrade only works on the first Quarkus start in a JVM.** After a `@QuarkusTest`
  restart the upgrade silently degrades to a plain proxied GET, so the handshake fails with nothing
  logged anywhere. It reproduces with all of the gateway's own header work removed, so it is the
  test harness rather than this code, and a deployed gateway starts once. That is the entire reason
  `GatewaySocketRoutingTest` runs in its own surefire execution — do not fold it back into the main
  one to tidy the pom.
- **The auth target is a build property, never a runtime key.** `-Dqits.variant=oauth|local` is read
  at augmentation and baked into the bean set by `@IfBuildProperty`, which is what makes it
  impossible for an environment variable to open a production gateway. Any change that lets
  `local` be selected at runtime defeats the whole design and must not land.
- The suite must keep running **without docker and without network**: no Keycloak Dev Services, no
  live IdP. `src/test/resources/application.properties` pins a static, never-contacted provider —
  the tests assert that the gateway challenges and where it points, not that a code flow completes.
  `%test.quarkus.quinoa=false` is the same rule applied to the SPA: a `@QuarkusTest` must never
  shell out to a package manager, so **no test in this repo serves or even builds the landing page**
  and none can. What that costs is stated rather than papered over: SPA serving, the `index.html`
  fallback and `/api/config.json` winning over the bundle's own stub are proven on the **packaged
  image** and on the deployment, not by the suite. Do not "fix" the gap by enabling Quinoa in tests.

  With Quinoa off, nothing is layered behind `GatewayRouter`, so its `next()` on an unmatched path
  lands on Vert.x's own 404 — which is why the unmatched-path tests still assert 404 and are worth
  keeping: they prove a gateway with no SPA still gives a machine the machine answer. The half the
  suite *can* hold is that a configured prefix proxies while appearing in no ignore list
  (`GatewayRoutingTest`) and that a deep path resolves to its service (`RouteTableTest`).
- Add a regression test with every bug fix. Tests are JUnit `*Test.java`.
- Keep the Quarkus platform version, the JDK release and the **Quinoa version** in step with the
  qits monorepo when it moves them (see the README's "Relationship to the qits monorepo"). Quinoa
  is in no BOM and the platform pins one version for every service that serves a client; the rule
  there is that a Quinoa release must have been built against a Quarkus no newer than ours.
