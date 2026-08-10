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
 * reached at {@code /artifacts/*}. So the enum ties three things together for a service:
 *
 * <ul>
 *   <li>its {@link #segment() public path segment} ({@code artifacts}),
 *   <li>the {@link #pathPrefix() inbound prefix} it claims ({@code /artifacts}), and
 *   <li>its {@link #navigationLabel() display label} and {@link #navigationPosition() place} in the
 *       platform's left navigation ({@code Artifacts}, third) — <b>if</b> it has one.
 * </ul>
 *
 * <p><b>The upstream host is not one of them.</b> A route's target is named by the deployment and
 * derived from nothing here: a platform service answers to {@code qits-platform-artifacts}, an
 * environment service to {@code <env>-qits-<app>} ({@code prod-qits-ci}), and the environment is
 * not knowable at build time. The enum says what a segment <i>is</i>; configuration says where it
 * lives.
 *
 * <p><b>Why display identity lives here too.</b> {@code @qits/ui-components} used to hardcode the
 * navigation as a compile-time list of eight {@code {label, href}} entries in a published npm
 * package: a second declaration of what the platform routes, in a place that could not know, and it
 * lagged — {@code /docs/} was routed for a while with no way to reach it. The gateway is the one
 * process that knows what it routes, so it answers the navigation ({@link NavigationRoute}), and
 * the identity a link needs belongs on the constant that already carries the routing identity. A
 * service is added in one place, still.
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
  ARTIFACTS("Artifacts", 3, "/v2"),
  OBSERVABILITY("Observability", 7),
  WORKSPACES("Workspaces", 5),
  PROJECTS("Projects", 4),
  /**
   * qits-stt — speech to text, and <b>deliberately unlabelled</b>: it is an API a workspace and the
   * other services call, with no SPA of its own behind the segment. A navigation entry for it would
   * be a link to nothing. Do not "complete" the list by giving it one; give it one when it grows a
   * client.
   */
  STT,
  EVENTS("Events", 6),
  CI("CI", 1),
  /** qits-platform-deployments — environment topology plus deployment execution, in one service. */
  PLATFORM_DEPLOYMENTS("Deployments", 2),
  /**
   * qits-docs — the reading surface over the documentation sites qits-artifacts holds.
   *
   * <p>It stores nothing: it resolves a site's newest version and streams bytes from {@code
   * /artifacts/docs}, so the two are one deployment decision rather than two stores to keep in
   * step. Which means this entry routes a <em>view</em>, and a deployment that runs it without
   * qits-artifacts has published no documentation to look at.
   */
  DOCS("Docs", 8);

  /**
   * The {@link #navigationPosition()} of a service that is not in the navigation. Never compared
   * against a labelled service's position: {@link NavigationRoute} drops the unlabelled ones before
   * it sorts, so this is a value that says "unset" rather than "last".
   */
  static final int NOT_IN_NAVIGATION = -1;

  private final String navigationLabel;
  private final int navigationPosition;
  private final List<String> extraPrefixes;

  /**
   * A service with no navigation entry — the plain spelling, so a constant stays as short as what
   * it declares. {@code STT} is written this way and says above why.
   */
  QitsService(String... extraPrefixes) {
    this(null, NOT_IN_NAVIGATION, extraPrefixes);
  }

  /** A service the platform's navigation links to, at a fixed place in the list. */
  QitsService(String navigationLabel, int navigationPosition, String... extraPrefixes) {
    this.navigationLabel = navigationLabel;
    this.navigationPosition = navigationPosition;
    this.extraPrefixes = List.of(extraPrefixes);
  }

  /**
   * The public path segment, with the {@code qits-} prefix dropped — e.g. {@code "artifacts"}.
   *
   * <p>A multi-word name is <b>dashed</b>, not underscored: {@code PLATFORM_DEPLOYMENTS} is reached
   * at {@code /platform-deployments/*}. The underscore is the enum's own spelling and has no place
   * in a URL, so the rule lives in the derivation rather than in a per-constant override — the next
   * multi-word service gets it right without anyone remembering.
   */
  public String segment() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
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
   * The label the platform's left navigation shows for this service, or empty when the service is
   * not in the navigation at all.
   *
   * <p><b>Empty is the switch</b>, not a fallback: a constant with no label produces no link, and
   * nothing derives one from the segment. That is what lets a segment be routable without being
   * navigable — {@code stt} is an API with no client, so it is routed and belongs in no menu.
   *
   * <p>The label is not the segment title-cased, deliberately: {@code CI} is two capitals and
   * {@code platform-deployments} is shown as "Deployments". A user reads the label and a machine
   * reads the segment, and neither has to be derivable from the other.
   */
  public Optional<String> navigationLabel() {
    return Optional.ofNullable(navigationLabel);
  }

  /**
   * Where this service sits in the navigation — ascending, and {@link #NOT_IN_NAVIGATION} when it
   * has no label. An explicit number rather than declaration order, because declaration order here
   * is the registry's (it grew as services split out) and the menu's is the platform's idea of what
   * a user reaches for first. Reordering the menu must not mean reordering the registry.
   *
   * <p>Positions are unique across the labelled services, which {@code QitsServiceTest} asserts:
   * two services sharing one would sort by whatever the stream happened to do, i.e. differently on
   * a different JDK.
   */
  public int navigationPosition() {
    return navigationPosition;
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
