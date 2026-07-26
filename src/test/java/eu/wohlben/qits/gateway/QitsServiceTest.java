package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
