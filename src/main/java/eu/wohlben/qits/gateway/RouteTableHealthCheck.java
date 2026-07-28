package eu.wohlben.qits.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness = "this gateway can route something". A gateway with an empty route table is up but
 * useless (every request 404s), which is exactly the misconfiguration an orchestrator should catch
 * before sending it traffic.
 *
 * <p>Upstreams are deliberately NOT probed: an upstream being down is a 502 for that path, not a
 * reason to pull the whole front door — the other components stay reachable. The routes are
 * reported as data so {@code /q/health/ready} doubles as the route-table dump.
 */
@Readiness
@ApplicationScoped
public class RouteTableHealthCheck implements HealthCheck {

  @Inject RouteTable routeTable;

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder response =
        HealthCheckResponse.named("gateway-routes").status(!routeTable.isEmpty());
    routeTable.routes().forEach(r -> response.withData(r.prefix(), r.upstream()));
    return response.build();
  }
}
