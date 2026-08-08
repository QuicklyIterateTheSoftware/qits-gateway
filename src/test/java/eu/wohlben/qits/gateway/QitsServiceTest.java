package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The registry's derivation rules: public segment, path prefix, lookup — plus the display identity
 * the navigation is built from.
 */
class QitsServiceTest {

  @Test
  void thePublicIdentityDropsTheQitsPrefix() {
    assertEquals("artifacts", QitsService.ARTIFACTS.segment());
    assertEquals("/artifacts", QitsService.ARTIFACTS.pathPrefix());
  }

  @Test
  void aMultiWordServiceIsDashedEverywhereItIsRead() {
    // The enum spells the name with an underscore because Java requires it; nothing else may.
    // name().toLowerCase() alone would put /platform_deployments in the URL, which is wrong.
    assertEquals("platform-deployments", QitsService.PLATFORM_DEPLOYMENTS.segment());
    assertEquals("/platform-deployments", QitsService.PLATFORM_DEPLOYMENTS.pathPrefix());
    assertTrue(QitsService.forSegment("platform_deployments").isEmpty());

    // The second multi-word service, which is what makes the rule above a derivation rather than
    // one constant's special case.
    assertEquals("platform-docs", QitsService.PLATFORM_DOCS.segment());
    assertEquals("/platform-docs", QitsService.PLATFORM_DOCS.pathPrefix());
  }

  @Test
  void noSegmentCarriesAnUnderscore() {
    // The derivation rule, asserted across the whole enum so the next multi-word service inherits
    // it rather than rediscovering it.
    for (QitsService service : QitsService.values()) {
      assertTrue(
          service.segment().indexOf('_') < 0, service + " must not have an underscore in its URL");
    }
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
  void exactlyTheseServicesAreInTheNavigation() {
    // Pinned as a set rather than left to whoever adds the next constant: a label is what puts a
    // service in front of every user of the platform, so adding or dropping one should be a diff
    // here and not a side effect of extending the enum.
    Map<QitsService, String> expected =
        Map.of(
            QitsService.CI, "CI",
            QitsService.PLATFORM_DEPLOYMENTS, "Deployments",
            QitsService.ARTIFACTS, "Artifacts",
            QitsService.PROJECTS, "Projects",
            QitsService.WORKSPACES, "Workspaces",
            QitsService.EVENTS, "Events",
            QitsService.OBSERVABILITY, "Observability",
            QitsService.PLATFORM_DOCS, "Docs");

    for (QitsService service : QitsService.values()) {
      assertEquals(
          Optional.ofNullable(expected.get(service)),
          service.navigationLabel(),
          service + "'s navigation label");
    }
  }

  @Test
  void aServiceWithNoClientCarriesNoLabel() {
    // The omission is a decision and is easy to mistake for an oversight — stt is an API with no
    // SPA behind it. The javadoc on the constant says so; this is what stops a well-meaning
    // "completion" of the list from being green.
    assertTrue(QitsService.STT.navigationLabel().isEmpty());
    assertEquals(QitsService.NOT_IN_NAVIGATION, QitsService.STT.navigationPosition());
  }

  @Test
  void noTwoServicesShareALabelOrAPosition() {
    // A duplicate label shows a user two identical entries going to different places; a duplicate
    // position leaves the sort to whatever the stream happened to do, which is a menu that can
    // reorder itself between JDKs. Neither is visible without a test.
    List<String> labels = new ArrayList<>();
    List<Integer> positions = new ArrayList<>();
    for (QitsService service : QitsService.values()) {
      if (service.navigationLabel().isEmpty()) {
        continue;
      }
      labels.add(service.navigationLabel().orElseThrow());
      positions.add(service.navigationPosition());
      assertTrue(
          service.navigationPosition() > QitsService.NOT_IN_NAVIGATION,
          service + " is in the navigation and must have a real position");
    }
    assertEquals(
        labels.size(), Set.copyOf(labels).size(), "duplicate navigation label in " + labels);
    assertEquals(
        positions.size(),
        Set.copyOf(positions).size(),
        "duplicate navigation position " + positions);
  }

  @Test
  void theRegistryRootIsNotAConfigurableSegment() {
    // This is the test that pins the whole "no phantom service" decision. Making /v2 an enum
    // constant would have been simpler and wrong: it manufactures a service with a bogus row in the
    // readiness payload and a second proxy-hosts key a deployment has to hold in sync with the
    // artifacts one.
    assertTrue(QitsService.forSegment("v2").isEmpty());
  }
}
