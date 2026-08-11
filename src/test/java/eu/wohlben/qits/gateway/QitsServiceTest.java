package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
  void theGitHostTakesItsRepositoryNameAndKeepsTheProtocolAddress() {
    // This constant used to be GIT, at /git, and was the one exception to the derivation rule. The
    // SPA is what ended the exception: qits-githost serves a page, so it takes the segment its
    // repository name gives it, like every sibling.
    assertEquals("githost", QitsService.GITHOST.segment());
    assertEquals("/githost", QitsService.GITHOST.pathPrefix());

    // Which is also the proxy-hosts key, since RouteTable composes it from the segment:
    // qits.gateway.proxy-hosts.githost, i.e. QITS_GATEWAY_PROXY_HOSTS_GITHOST. The old spelling
    // names no service any more, and a deployment still holding it fails startup rather than
    // routing nothing quietly.
    assertEquals(Optional.of(QitsService.GITHOST), QitsService.forSegment("githost"));
    assertTrue(QitsService.forSegment("git").isEmpty());
  }

  @Test
  void theGitProtocolStillArrivesAtTheGitHost() {
    // /git is an extra prefix, for the reason extras exist: a clone url, a workspace remote and
    // qits-ci's config reads all hardcode it, and git cannot be told a new address. The segment
    // comes first — everything qits itself emits uses that form.
    assertEquals(List.of("/githost", "/git"), QitsService.GITHOST.pathPrefixes());
  }

  @Test
  void thePlatformMirrorIsNamedForItsSegment() {
    // MIRROR rather than PLATFORM_MIRROR: the service already claims /mirror/q as its
    // non-application root, so the segment has to be `mirror`, and a constant is named for the
    // segment so segment() needs no per-constant override. Same rule PLATFORM_DEPLOYMENTS follows
    // in the other direction.
    assertEquals("mirror", QitsService.MIRROR.segment());
    assertEquals("/mirror", QitsService.MIRROR.pathPrefix());
    assertEquals(Optional.of(QitsService.MIRROR), QitsService.forSegment("mirror"));

    // Its npm, maven and OCI wires are NOT extras here. They are qits-artifacts' addresses today
    // (/artifacts/npm, /artifacts/maven, /v2), two services cannot hold one prefix behind one
    // gateway, and moving the clients over is a separate work package.
    assertEquals(List.of("/mirror"), QitsService.MIRROR.pathPrefixes());
    assertEquals(Optional.of(QitsService.ARTIFACTS), QitsService.forSegment("artifacts"));
  }

  @Test
  void aMultiWordServiceIsDashedEverywhereItIsRead() {
    // The enum spells the name with an underscore because Java requires it; nothing else may.
    // name().toLowerCase() alone would put /platform_deployments in the URL, which is wrong.
    assertEquals("platform-deployments", QitsService.PLATFORM_DEPLOYMENTS.segment());
    assertEquals("/platform-deployments", QitsService.PLATFORM_DEPLOYMENTS.pathPrefix());
    assertTrue(QitsService.forSegment("platform_deployments").isEmpty());

    // PLATFORM_DEPLOYMENTS is the only multi-word service left — DOCS was the second until the
    // byte-plane split made it an environment service at /docs. What keeps the rule a derivation
    // rather than one constant's special case is noSegmentCarriesAnUnderscore below, which asserts
    // it across the whole enum.
    assertEquals("docs", QitsService.DOCS.segment());
    assertEquals("/docs", QitsService.DOCS.pathPrefix());
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
    // is a concession to a client we do not control, never a convenience alias. There are exactly
    // two: docker's /v2 on artifacts, and git's /git on the git host.
    for (QitsService service : QitsService.values()) {
      if (service == QitsService.ARTIFACTS || service == QitsService.GITHOST) {
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
            QitsService.DOCS, "Docs",
            QitsService.GITHOST, "Githost",
            QitsService.MIRROR, "Mirror");

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

    // stt is the only one left. The git host used to be here for a second reason — git is a
    // protocol, not a page — and it now serves a page at /githost, so it has somewhere to link to.
    assertEquals(1, unlabelled(), "stt should be the only unlabelled service");
  }

  private static long unlabelled() {
    return Arrays.stream(QitsService.values())
        .filter(service -> service.navigationLabel().isEmpty())
        .count();
  }

  @Test
  void theNavigationPositionsAreTheOrderTheMenuIsRead() {
    // The menu a user sees, pinned end to end. Position is explicit rather than declaration order
    // precisely so this list can be reordered without touching the registry — which means the only
    // place the intended order is written down is here.
    assertEquals(
        List.of(
            "CI",
            "Deployments",
            "Artifacts",
            "Projects",
            "Workspaces",
            "Events",
            "Observability",
            "Docs",
            "Githost",
            "Mirror"),
        Arrays.stream(QitsService.values())
            .filter(service -> service.navigationLabel().isPresent())
            .sorted(java.util.Comparator.comparingInt(QitsService::navigationPosition))
            .map(service -> service.navigationLabel().orElseThrow())
            .toList());
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
