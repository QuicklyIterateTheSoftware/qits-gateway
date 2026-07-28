package eu.wohlben.qits.gateway;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Map;

/**
 * The gateway's whole configuration surface: the split-out service registry and the edge-header
 * policy.
 *
 * <p><b>The service registry.</b> The gateway proxies to a fixed, named set of components — the
 * {@link QitsService} enum. A service is <i>routed</i> only when {@code
 * qits.gateway.proxy-hosts.<segment>} names its upstream host: the entry is both the on-switch and
 * the target. The key is the public segment ({@code artifacts}, never {@code qits-artifacts}); a
 * key that is not a known service is rejected at startup. The value is a bare host ({@code
 * qits-artifacts}) or {@code host:port} ({@code qits-artifacts:9000}); with no port the gateway
 * assumes {@code 8080}.
 *
 * <p><b>There is no catch-all, deliberately.</b> A path no service claims is answered 404 by the
 * gateway itself. It used to fall through to the qits monolith via {@code qits.gateway.app-host};
 * qits is deployed clean now — these services and nothing else, with no monolith running beside
 * them and no shared access between the two — so there is no upstream that could serve "everything
 * else", and an unrouted path is a configuration gap worth seeing rather than silently forwarding.
 *
 * <p>Every upstream is resolved from configuration ONLY: the gateway never derives a host or port
 * from anything in a request, which is the SSRF guard qits' own {@code ServiceProxyRoute} keeps.
 * Since config sources include environment variables, a deployment declares the whole table without
 * a file: {@code QITS_GATEWAY_PROXY_HOSTS_ARTIFACTS=qits-artifacts}.
 */
@ConfigMapping(prefix = "qits.gateway")
public interface GatewayConfig {

  /**
   * The enabled services, keyed by public {@link QitsService#segment() segment}: {@code
   * qits.gateway.proxy-hosts.<segment> = host} (or {@code host:port}). Only listed services are
   * routed; a key that is not a known service is a startup error.
   */
  Map<String, String> proxyHosts();

  /** Edge-header handling — what the gateway tells upstreams about the original client. */
  Forwarded forwarded();

  interface Forwarded {

    /** Emit {@code X-Forwarded-For} / {@code -Proto} / {@code -Host} / {@code -Port} upstream. */
    @WithDefault("true")
    boolean enabled();

    /**
     * <b>Compatibility</b> request headers the gateway DROPS from every inbound request before
     * forwarding — the header names a forward-auth proxy vendor chose (Authelia's {@code Remote-*},
     * oauth2-proxy's {@code X-Auth-Request-*}), which matter when something still fronts the
     * gateway.
     *
     * <p>The gateway's <i>own</i> identity headers are NOT on this list and never need to be: they
     * live under {@link EdgeHeaders#RESERVED_PREFIX} and are stripped by prefix, structurally,
     * ahead of this list. That is deliberate — an enumerated list is exactly the wrong shape for
     * headers we keep adding to.
     *
     * <p>Add to this list, never shrink it below the identity headers whatever sits in front of the
     * gateway injects.
     */
    @WithDefault(
        "Remote-User,Remote-Groups,Remote-Name,Remote-Email,"
            + "X-Auth-Request-User,X-Auth-Request-Groups,X-Auth-Request-Email,"
            + "X-Forwarded-User,X-Forwarded-Groups")
    List<String> stripRequestHeaders();
  }
}
