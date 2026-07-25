# AGENTS.md

Guidance for AI coding agents working in this repository. `CLAUDE.md` is a symlink to this file.

## What this is

`qits-gateway` — the qits front door: a standalone Quarkus 3 (Java 25) reverse proxy that receives
every inbound request for a qits deployment and delegates it to the right component. Read
`README.md` first; it is the contract document (routing model, configuration reference, security
posture) and must be updated in the same change that alters any of them.

Key facts that shape every change here:

- **Separately deployable, parent-less build.** This repo is a git submodule of the qits monorepo
  but is *not* a module of its Maven reactor. A clone of this repo alone must build. Never add a
  `<parent>` or a dependency on a qits module.
- **Compiles to a GraalVM native binary.** Every added extension costs image size, build time and
  reflection surface. Keep the dependency set minimal; prefer raw Vert.x routes over a REST layer.
- **Stateless.** No database, no ORM, no session state. Route resolution reads configuration only.
- **Streaming.** Never buffer request or response bodies — SSE, git smart-HTTP, WebSocket upgrades
  and large artifact uploads all pass through here.

## Commands

```bash
./mvnw test                    # unit tests + the end-to-end proxy suite (no docker needed)
./mvnw package                 # JVM build -> target/quarkus-app/
./mvnw quarkus:dev             # dev mode on :8000, fronting a qits on localhost:8080
./mvnw package -Dnative        # native binary -> target/qits-gateway
./mvnw test -Dtest=RouteTableTest

docker build -t qits/gateway:latest -f docker/Dockerfile .
```

Spotless (google-java-format) runs automatically at `process-sources`, so formatting is never a
review topic — but it needs JDK 21+; build on the JDK 25 that `.sdkmanrc` pins.

## Layout

```
src/main/java/eu/wohlben/qits/gateway/
  GatewayConfig.java          @ConfigMapping — the entire configuration surface
  GatewayRoute.java           one resolved route; matching + path rewriting (framework-free)
  RouteTable.java             longest-prefix resolution over the configured routes
  GatewayRouter.java          the catch-all Vert.x route; one HttpProxy per route
  EdgeHeaders.java            the only rewrites: header hygiene, prefix strip, X-Forwarded-*
  RouteTableHealthCheck.java  readiness = a non-empty route table
```

## Conventions

- **Security invariant:** upstream host/port come from configuration *only*, never from any part of
  a request. Any change that lets a request influence the target is an SSRF and must not land.
- **Header hygiene is a contract**, not a nicety: qits' `forwardauth` variant believes identity
  headers unconditionally. `qits.gateway.forwarded.strip-request-headers` may be extended, never
  shrunk below the identity headers a fronted qits build trusts.
- Put edge-case logic (matching, rewriting) on the framework-free value types so it stays unit
  testable without booting the application; `RouteTableTest` is where those cases belong.
  End-to-end behaviour goes in `GatewayRoutingTest`, which proxies to a real stub upstream.
- Add a regression test with every bug fix. Tests are JUnit `*Test.java`.
- Keep the Quarkus platform version and the JDK release in step with the qits monorepo when it moves
  them (see the README's "Relationship to the qits monorepo").
