# qits-gateway as a GraalVM-native container IMAGE — the same shape as qits' workspace-daemon: a
# Mandrel builder stage compiles the binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative -Dqits.variant=…` produces
# target/qits-gateway locally with no container involved. What this Dockerfile adds is the image
# around it — and, incidentally, a way for a machine with no GraalVM (a CI runner, a laptop on a
# plain OpenJDK) to still get a binary, since the builder stage brings its own native-image.
#
# Native matters here more than anywhere else in qits: the gateway is the process every single
# request passes through, it must be up before anything is reachable, and it holds no state that
# would benefit from a JIT warm-up. Native gives it a ~50ms start and a footprint measured in tens
# of megabytes, so restarting the front door is a non-event.
#
# Build from the repo root:
#
#     git submodule update --init src/main/webui
#     (cd src/main/webui && npm ci && npm run build)
#     docker build -t qits/gateway:latest --build-arg QITS_VARIANT=oauth -f docker/Dockerfile .
#
# The first two lines are not optional and are not a convenience: this image carries the landing SPA
# (src/main/webui = the qits-spa-home submodule), and the builder stage below packages a bundle that
# was ALREADY BUILT rather than building one. See the Quinoa flags on the mvnw line for why the
# build cannot happen inside this stage.
#
# The build arg is REQUIRED and has no default, for the same reason `-Dqits.variant` has none: an
# image is a shipped gateway, and a shipped gateway has to have said out loud whether it
# authenticates. Omit it and the require-auth-variant enforcer rule stops the build with the list of
# valid targets. (Before the enforcer existed this stage ran flagless; it would now fail.)
#
# --- stage `build`: native-compile ---------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25 (same constraint as the qits monorepo's native stages).
#
# `localhost:8082/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the first build
# fetches the image from upstream and caches it, every later build is served from local disk. The
# tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves
# it.
FROM localhost:8082/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole tree, not a file list. `COPY pom.xml .` + `COPY src/ src/` only worked as long as the
# repo had exactly those two things the build reads: the day it gains another top-level entry the
# build needs — a second module, a config dir, a resource tree the native stage wants — the file
# list silently omits it and this Dockerfile has to be edited in lockstep. Nothing enforces that,
# and the failure lands far from the cause (a missing file inside a native-image run, or worse, a
# green build of the wrong thing). Copying everything makes new top-level content free.
# `.dockerignore` is what keeps this cheap — without it this line drags in target/, ~86 MB of
# build output that would also risk shadowing what this stage compiles.
COPY . .

# oauth | local — see the header. No ENV default: an unset value fails the enforcer rather than
# silently picking a target.
ARG QITS_VARIANT
# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# --- the Quinoa flags, and why they are HERE and not in application.properties -------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs
# its own — from nodejs.org and registry.npmjs.org, both of which a docker build can reach. It lives
# here rather than in application.properties on purpose: a local or CI `mvnw` build must use the
# node on PATH, so that no build silently downloads a toolchain.
#
# 22.22.0 is the PLATFORM pin (docs/project-setup-quinoa-angular.md in the qits monorepo) — the node
# every other qits service's builder stage installs, and the version a developer's host carries.
# Note it is deliberately NOT the step container's node: node-docker-base is node:24-alpine, and the
# bundle this image consumes was built there. The two do not have to agree, because this stage does
# not build the bundle — the commands below are neutered to `--version`, so the only thing node does
# here is exist and prove its package manager runs. Matching the platform keeps the pin to one
# number across the repos instead of tracking a base image nobody here chose. npm is node's own
# bundled one; src/main/webui pins no package manager and needs none.
#
# The two command overrides are the load-bearing part, and the reason this repo deviates from the
# platform's Quinoa recipe (docs/project-setup-quinoa-angular.md, which has the builder run the
# client's real install and build):
#
#   src/main/webui is qits-spa-home, and it depends on @qits/angular and @qits/ui-components. Those
#   two packages exist ONLY on the platform's own npm registry (qits-artifacts). A RUN step in a
#   docker build reaches the public internet — Maven Central, npmjs.org, nodejs.org — and can reach
#   the platform registry by NO address: not the qits-net alias, not localhost:8081, not
#   host.docker.internal. So the install cannot happen here, and an install that "worked" here would
#   have resolved the @qits scope against npmjs.org, where those names do not exist and never will.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as src/main/webui/dist/ (see .dockerignore). `--version` is the null
# command: it proves the package manager is really there and does nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a gateway that ships without its landing page.
#
# --- and why the bundle is copied onto itself first ----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename
# a directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in
# with `DirectoryNotEmptyException: …/dist/qits-spa-home/browser`, which names the source and
# explains nothing. Every other Quinoa build in the platform is immune by accident: their builder
# stage *creates* dist itself, so it is in the upper layer already. Ours arrives by COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
RUN d=src/main/webui/dist/qits-spa-home/browser; \
    test -f "$d/index.html" || { \
      echo "No built landing SPA at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp package -Dnative -DskipTests \
      -Dqits.variant=${QITS_VARIANT} -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime -------------------------------------------------------------------------------------
# `localhost:8082/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8082/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-gateway` (no -runner suffix), so the process
# is identifiable in `ps`/`docker top`.
COPY --from=build --chown=1001:root /src/target/qits-gateway /work/qits-gateway
RUN chmod +x /work/qits-gateway

EXPOSE 8080
USER 1001

# The route table is configuration: pass it as env (QITS_GATEWAY_ROUTES_<NAME>_HOST=…) or mount an
# application.properties next to the binary. See README.md.
#
# networkaddress.cache.negative.ttl=0 because under swarm a service's DNS name appears only once its
# task passes its healthcheck, and the JDK caches a FAILED lookup for 10s by default — so the front
# door would keep answering 502 for up to ten seconds after an upstream became resolvable. It has to
# be this JDK property: quarkus.vertx.resolver.cache-negative-time-to-live is inert while
# quarkus.vertx.use-async-dns stays false (the default), which sets vertx.disableDnsResolver=true
# and puts every lookup through the JDK's blocking resolver. GraalVM native reads the property at
# runtime, so the entrypoint is where it belongs.
ENTRYPOINT ["/work/qits-gateway", "-Dquarkus.http.host=0.0.0.0", "-Dnetworkaddress.cache.negative.ttl=0"]
