package eu.wohlben.qits.gateway;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The registry of qits components the gateway can proxy to — the canonical, named list the platform
 * wants declared once instead of re-spelled in config every time a service splits out.
 *
 * <p>Each constant is a service the qits monorepo carries as a {@code services/qits-*} submodule.
 * The <b>public</b> identity of a service drops the {@code qits-} prefix: {@code qits-artifacts} is
 * reached at {@code /artifacts/*} and, by default, forwarded to the {@code qits-artifacts}
 * container on the shared docker network. So the enum ties three things together for a service:
 *
 * <ul>
 *   <li>its {@link #segment() public path segment} ({@code artifacts}),
 *   <li>the {@link #pathPrefix() inbound prefix} it claims ({@code /artifacts}), and
 *   <li>its {@link #defaultHost() default upstream container name} ({@code qits-artifacts}).
 * </ul>
 *
 * <p>Which services are actually <i>routed</i> is a deployment decision: a service becomes live
 * only when a {@code qits.gateway.proxy-hosts.<segment>} entry names its host (see {@link
 * GatewayConfig}). The enum is the closed set of segments that entry may use — a {@code
 * proxy-hosts} key that is not a known service is a configuration error, caught at startup.
 *
 * <p>Deliberately framework-free (no Quarkus or Vert.x types) so the derivation rules stay unit
 * testable without booting an application.
 */
public enum QitsService {
  ARTIFACTS,
  OTEL,
  WORKSPACES,
  STT,
  CI,
  CD,
  REPOSITORIES;

  /** The public path segment, with the {@code qits-} prefix dropped — e.g. {@code "artifacts"}. */
  public String segment() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The inbound path prefix this service claims — e.g. {@code "/artifacts"}. */
  public String pathPrefix() {
    return "/" + segment();
  }

  /**
   * The default upstream host: the service's DNS name on the shared {@code qits-net} docker
   * network, which is the submodule name — e.g. {@code "qits-artifacts"}. A deployment may point
   * the route elsewhere via {@code qits.gateway.proxy-hosts.<segment>}.
   */
  public String defaultHost() {
    return "qits-" + segment();
  }

  /** Resolve a configured segment to its service, or empty if it names no known service. */
  public static Optional<QitsService> forSegment(String segment) {
    if (segment == null) {
      return Optional.empty();
    }
    String needle = segment.trim().toLowerCase(Locale.ROOT);
    return Arrays.stream(values()).filter(s -> s.segment().equals(needle)).findFirst();
  }

  /** The known segments, comma-separated — for the "unknown service" configuration error. */
  static String knownSegments() {
    return Arrays.stream(values()).map(QitsService::segment).collect(Collectors.joining(", "));
  }
}
