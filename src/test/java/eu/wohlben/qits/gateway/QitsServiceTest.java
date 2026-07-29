package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The registry's derivation rules: public segment, path prefix, default container host, lookup. */
class QitsServiceTest {

  @Test
  void thePublicIdentityDropsTheQitsPrefix() {
    assertEquals("artifacts", QitsService.ARTIFACTS.segment());
    assertEquals("/artifacts", QitsService.ARTIFACTS.pathPrefix());
    // …while the default upstream is the submodule/container name, which keeps the prefix.
    assertEquals("qits-artifacts", QitsService.ARTIFACTS.defaultHost());
  }

  @Test
  void forSegmentResolvesKnownServicesAndIsCaseInsensitive() {
    assertEquals(Optional.of(QitsService.OBSERVABILITY), QitsService.forSegment("observability"));
    assertEquals(Optional.of(QitsService.OBSERVABILITY), QitsService.forSegment("OBSERVABILITY"));
    assertEquals(Optional.of(QitsService.PROJECTS), QitsService.forSegment(" projects "));
  }

  @Test
  void forSegmentIsEmptyForUnknownOrNull() {
    assertTrue(QitsService.forSegment("nope").isEmpty());
    assertTrue(QitsService.forSegment(null).isEmpty());
  }

  @Test
  void theArtifactsServiceAlsoClaimsTheRegistryRoot() {
    // The segment prefix comes first: everything qits itself emits uses that form, and the extra
    // exists only because docker and podman hardcode /v2 at the host root.
    assertEquals(List.of("/artifacts", "/v2"), QitsService.ARTIFACTS.pathPrefixes());
  }

  @Test
  void everyOtherServiceClaimsExactlyItsSegment() {
    // So an extra prefix added later without thought fails here rather than in production. An extra
    // is a concession to a client we do not control, never a convenience alias.
    for (QitsService service : QitsService.values()) {
      if (service == QitsService.ARTIFACTS) {
        continue;
      }
      assertEquals(
          List.of(service.pathPrefix()),
          service.pathPrefixes(),
          service + " should claim only its own segment");
    }
  }

  @Test
  void noExtraPrefixShadowsAnotherServicesSegment() {
    // The structural guard on the mechanism itself: an extra that collided with a sibling's segment
    // would hijack that service's traffic, and longest-prefix-wins would not save it.
    for (QitsService service : QitsService.values()) {
      for (String prefix : service.pathPrefixes()) {
        if (prefix.equals(service.pathPrefix())) {
          continue;
        }
        for (QitsService other : QitsService.values()) {
          assertTrue(
              !prefix.equals(other.pathPrefix())
                  && !prefix.startsWith(other.pathPrefix() + "/")
                  && !other.pathPrefix().startsWith(prefix + "/"),
              prefix + " collides with " + other + "'s segment prefix");
        }
      }
    }
  }

  @Test
  void theRegistryRootIsNotAConfigurableSegment() {
    // This is the test that pins the whole "no phantom service" decision. Making /v2 an enum
    // constant would have been simpler and wrong: it manufactures a service with a meaningless
    // default host (qits-v2), a bogus row in the readiness payload, and a second proxy-hosts key a
    // deployment has to hold in sync with the artifacts one.
    assertTrue(QitsService.forSegment("v2").isEmpty());
  }
}
