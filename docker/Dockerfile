# qits-gateway as a GraalVM-native container image — the same shape as qits' workspace-daemon: a
# Mandrel builder stage compiles the binary, a minimal runtime stage carries only it.
#
# Native matters here more than anywhere else in qits: the gateway is the process every single
# request passes through, it must be up before anything is reachable, and it holds no state that
# would benefit from a JIT warm-up. Native gives it a ~50ms start and a footprint measured in tens
# of megabytes, so restarting the front door is a non-event.
#
# Build from the repo root:
#
#     docker build -t qits/gateway:latest -f docker/Dockerfile .
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

COPY pom.xml .
COPY src/ src/
# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
RUN ./mvnw -B -ntp package -Dnative -DskipTests -Dquarkus.native.native-image-xmx=4g

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
