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
#     (cd src/main/webui && pnpm install --frozen-lockfile && pnpm build)
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
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
# its own — from nodejs.org and registry.npmjs.org, both of which a docker build can reach. Pinned,
# so the image build is reproducible, and pinned to what the pipeline's own step container carries
# (node 24.18.0) and to the `packageManager` field of src/main/webui (pnpm 10.33.0). It lives here
# rather than in application.properties on purpose: a local or CI `mvnw` build must use the node on
# PATH, so that no build silently downloads a toolchain.
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
# THIS DOES NOT FAIL QUIETLY. Quinoa checks for quarkus.quinoa.build-dir immediately after the build
# command returns, so a context whose dist/ is missing or stale-empty stops the image build with
# "Quinoa build directory not found: …/dist/qits-spa-home/browser" naming the key. A forgotten
# `pnpm build` in the step is a red build, not a gateway that ships without its landing page.
RUN ./mvnw -B -ntp package -Dnative -DskipTests \
      -Dqits.variant=${QITS_VARIANT} -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=24.18.0 \
      -Dquarkus.quinoa.package-manager-install.pnpm-version=10.33.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime -------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-gateway` (no -runner suffix), so the process
# is identifiable in `ps`/`docker top`.
COPY --from=build --chown=1001:root /src/target/qits-gateway /work/qits-gateway
RUN chmod +x /work/qits-gateway

EXPOSE 8080
USER 1001

# The route table is configuration: pass it as env (QITS_GATEWAY_ROUTES_<NAME>_HOST=…) or mount an
# application.properties next to the binary. See README.md.
ENTRYPOINT ["/work/qits-gateway", "-Dquarkus.http.host=0.0.0.0"]
