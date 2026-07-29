package eu.wohlben.qits.gateway;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p>A service may claim <b>more</b> than its segment prefix; see {@link #pathPrefixes()}. That is
 * a concession to protocol clients that hardcode an address, not a second addressing scheme.
 *
 * <p>Deliberately framework-free (no Quarkus or Vert.x types) so the derivation rules stay unit
 * testable without booting an application.
 */
public enum QitsService {
  /**
   * qits-artifacts, which additionally claims {@code /v2} — the OCI Distribution API root. Docker
   * and podman resolve image references against {@code <host>/v2/…} and accept no path prefix, so
   * the registry has no {@code /artifacts/…} spelling for the gateway to route instead. It is the
   * first and so far only prefix in the system that is not a service segment.
   */
  ARTIFACTS("/v2"),
  OBSERVABILITY,
  WORKSPACES,
  PROJECTS,
  STT,
  CI,
  CD;

  private final List<String> extraPrefixes;

  QitsService(String... extraPrefixes) {
    this.extraPrefixes = List.of(extraPrefixes);
  }

  /** The public path segment, with the {@code qits-} prefix dropped — e.g. {@code "artifacts"}. */
  public String segment() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The inbound path prefix this service claims — e.g. {@code "/artifacts"}. */
  public String pathPrefix() {
    return "/" + segment();
  }

  /**
   * Every inbound prefix this service claims: its {@link #pathPrefix() segment prefix} first, then
   * any extras, in declaration order. Almost every service returns exactly one element.
   *
   * <p>An extra prefix exists for exactly one situation — a protocol whose client hardcodes an
   * address we do not get to choose. It is <b>not</b> an alias mechanism and not somewhere to hang
   * a convenience URL: everything qits itself emits uses the {@code /<segment>/*} form, and an
   * extra has to be forced on us from outside. It must also not collide with any other service's
   * segment, which {@code QitsServiceTest} asserts across the whole enum so a future extra cannot
   * quietly shadow a sibling.
   *
   * <p>Note what an extra deliberately is <b>not</b>: a second {@code proxy-hosts} key. {@link
   * #forSegment} resolves segments only, so {@code qits.gateway.proxy-hosts.v2} is still the
   * "unknown qits service" startup error it always was. The extra rides on the service's single
   * entry, which is what keeps a deployment from having to hold two keys in sync and keeps the
   * startup log and the readiness payload free of a component that does not exist.
   */
  public List<String> pathPrefixes() {
    return Stream.concat(Stream.of(pathPrefix()), extraPrefixes.stream()).toList();
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
