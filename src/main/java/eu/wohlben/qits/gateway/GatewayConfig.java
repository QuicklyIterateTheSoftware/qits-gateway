package eu.wohlben.qits.gateway;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The gateway's whole configuration surface: a named route table plus the edge-header policy.
 *
 * <p>Routes are declared as {@code qits.gateway.routes.<name>.*} — the name is arbitrary and only
 * shows up in logs and the health check, so a deployment can call them {@code qits}, {@code
 * artifacts}, {@code telemetry}, … Every route is resolved from configuration ONLY: the gateway
 * never derives an upstream host or port from anything in the request, which is the same SSRF guard
 * qits' own {@code ServiceProxyRoute} keeps (it resolves origins exclusively from supervisor
 * state).
 *
 * <p>Since config sources include environment variables, a route is fully declarable without a
 * file: {@code QITS_GATEWAY_ROUTES_ARTIFACTS_PATH_PREFIX=/api/artifacts}, {@code
 * QITS_GATEWAY_ROUTES_ARTIFACTS_HOST=qits-artifacts}, …
 */
@ConfigMapping(prefix = "qits.gateway")
public interface GatewayConfig {

  /** The route table, keyed by an arbitrary route name. */
  Map<String, Route> routes();

  /** Edge-header handling — what the gateway tells upstreams about the original client. */
  Forwarded forwarded();

  interface Route {

    /**
     * The inbound path prefix this route claims, e.g. {@code /api/artifacts}. {@code /} is the
     * catch-all (typically the qits app itself). Matching is longest-prefix and segment-aware:
     * {@code /api/art} does NOT match {@code /api/artifacts/…}.
     */
    String pathPrefix();

    /**
     * Upstream hostname — a DNS name on the shared docker network, never a client-supplied value.
     */
    String host();

    /** Upstream port. */
    @WithDefault("8080")
    int port();

    /**
     * Strip {@link #pathPrefix()} before forwarding. Off by default: qits' own routes (and its SPA)
     * expect the full path, and prefix-stripping breaks apps that emit absolute-root asset URLs.
     * When on, the stripped prefix is announced upstream as {@code X-Forwarded-Prefix}.
     */
    @WithDefault("false")
    boolean stripPrefix();

    /**
     * Override the {@code Host}/{@code :authority} header sent upstream. Unset ⇒ the upstream's own
     * {@code host:port} (vertx-http-proxy's default). Needed for upstreams that validate Host — dev
     * servers reject anything that isn't localhost or allow-listed, which is why qits' daemon proxy
     * rewrites it to {@code localhost}.
     */
    Optional<String> authority();

    /** Take the route out of service without deleting its configuration. */
    @WithDefault("true")
    boolean enabled();
  }

  interface Forwarded {

    /**
     * Emit {@code X-Forwarded-For} / {@code -Proto} / {@code -Host} / {@code -Port} (and {@code
     * -Prefix} for stripped routes) toward upstreams.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Request headers the gateway DROPS from every inbound request before forwarding. This is the
     * gateway's half of the forward-auth trust contract: qits' {@code forwardauth} variant believes
     * its identity headers unconditionally, so whoever sits in front of it MUST strip
     * client-supplied copies. Defaults cover the Authelia and oauth2-proxy header names qits
     * supports. Add to this list, never shrink it below the identity headers your qits build
     * trusts.
     */
    @WithDefault(
        "Remote-User,Remote-Groups,Remote-Name,Remote-Email,"
            + "X-Auth-Request-User,X-Auth-Request-Groups,X-Auth-Request-Email,"
            + "X-Forwarded-User,X-Forwarded-Groups")
    List<String> stripRequestHeaders();
  }
}
