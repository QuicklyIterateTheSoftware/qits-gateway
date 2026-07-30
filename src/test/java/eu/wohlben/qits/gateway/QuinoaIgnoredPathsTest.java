package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The one thing that keeps the landing SPA from eating the route table.
 *
 * <p>Quinoa's SPA-routing handler sits at Vert.x route order 40_000 and {@link GatewayRouter}'s
 * catch-all at {@code Integer.MAX_VALUE - 1000}, so on a GET the SPA fallback runs <b>first</b>:
 * without {@code quarkus.quinoa.ignored-path-prefixes}, every {@code /artifacts/…}, {@code /v2/…}
 * and {@code /ci/…} would be answered with {@code index.html} and {@code 200 text/html} instead of
 * being proxied. Listing a prefix suppresses the reroute only — it registers and unregisters
 * nothing — so the request falls through to the gateway and is forwarded exactly as before.
 *
 * <p>That list is a static config value and {@link QitsService} is a closed enum. The closedness is
 * what makes a static list <em>safe</em>; being static is what lets it <em>drift</em>. So this test
 * derives what the value must be and compares — add a service to the enum, or give one a new extra
 * root-level prefix, and this fails here rather than in production, where the symptom would be a
 * new service quietly serving a web page to its own API clients.
 *
 * <p><b>Why it reads the file rather than the config.</b> {@code quarkus.quinoa.*} is build-time
 * configuration and Quinoa is switched off for the whole suite ({@code %test.quarkus.quinoa=false}
 * — the suite runs with no node, no network and, on a fresh clone, no submodule), so there is no
 * booted application that could be asked what the shipped value is. {@code
 * src/test/resources/application.properties} would shadow the shipped file on the classpath, which
 * leaves the path — surefire runs with the project basedir as its working directory.
 */
class QuinoaIgnoredPathsTest {

  private static final Path SHIPPED_CONFIG = Path.of("src/main/resources/application.properties");

  private static final String KEY = "quarkus.quinoa.ignored-path-prefixes";

  /**
   * The gateway's own machine surface. {@code /api} carries {@link ConfigJsonRoute} and {@code
   * AuthMeRoute}; {@code /q} is {@code quarkus.http.non-application-root-path}. Quinoa would have
   * derived {@code /q} on its own, but setting the key at all replaces that derivation instead of
   * extending it, so it has to be spelled again — which is exactly the kind of quiet subtraction
   * this test is here to catch.
   */
  private static final Set<String> GATEWAYS_OWN = Set.of("/api", "/q");

  @Test
  void everyProxiedPrefixIsIgnoredBySpaRouting() {
    Set<String> expected = new LinkedHashSet<>(GATEWAYS_OWN);
    for (QitsService service : QitsService.values()) {
      expected.addAll(service.pathPrefixes());
    }

    assertEquals(
        expected,
        configured(),
        """
        %s must list the gateway's own prefixes plus EVERY prefix QitsService claims.
        A missing entry means that prefix is answered with the landing page's index.html on a GET \
        instead of being proxied; a stale extra entry means a path the gateway no longer routes \
        404s where it should serve the SPA."""
            .formatted(KEY));
  }

  @Test
  void theRegistryRootIsListedEvenThoughItIsNotASegment() {
    // Spelled out separately because it is the entry a reader would assume is a typo: /v2 is not a
    // service segment, it is qits-artifacts' extra root-level prefix (the OCI Distribution API root
    // docker and podman hardcode). A docker pull is the most machine-like caller the front door
    // has, and index.html is not a manifest.
    assertTrue(configured().contains("/v2"), KEY + " must list the registry root /v2");
  }

  private static Set<String> configured() {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(SHIPPED_CONFIG)) {
      properties.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read " + SHIPPED_CONFIG.toAbsolutePath(), e);
    }
    String value = properties.getProperty(KEY);
    if (value == null || value.isBlank()) {
      throw new AssertionError(
          KEY
              + " is unset in "
              + SHIPPED_CONFIG
              + ". Unset does not mean 'ignore nothing' — it means Quinoa derives the list from"
              + " quarkus.http.non-application-root-path alone, and every proxied segment starts"
              + " being served as the landing page.");
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }
}
