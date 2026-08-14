package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The refusal rule itself, framework-free — the end-to-end halves are in {@code GatewayRoutingTest}
 * (a named caller is refused all the same) and {@code LocalVariantTest} (the target with no
 * authentication at all, which is the one this rule exists for).
 */
class RegistryWriteBlockTest {

  @Test
  void everyWriteUnderTheRegistryRootIsRefused() {
    assertTrue(RegistryWriteBlock.refuses("POST", "/v2/qits/alpine/blobs/uploads/"));
    assertTrue(RegistryWriteBlock.refuses("PATCH", "/v2/qits/alpine/blobs/uploads/session-1"));
    assertTrue(RegistryWriteBlock.refuses("PUT", "/v2/qits/alpine/manifests/latest"));
    assertTrue(RegistryWriteBlock.refuses("DELETE", "/v2/qits/alpine/manifests/latest"));
    assertTrue(RegistryWriteBlock.refuses("POST", "/v2"));
    assertTrue(RegistryWriteBlock.refuses("POST", "/v2/"));
  }

  @Test
  void aMethodNobodyListedIsRefusedToo() {
    // "not a read" rather than a list of writes: the registry protocol is not ours to enumerate, so
    // an unfamiliar method must not be the one that gets carried.
    assertTrue(RegistryWriteBlock.refuses("OPTIONS", "/v2/qits/alpine/manifests/latest"));
    assertTrue(RegistryWriteBlock.refuses("POSTX", "/v2/qits/alpine/manifests/latest"));
    assertTrue(RegistryWriteBlock.refuses("get", "/v2/")); // the method name is a token, not a word
  }

  @Test
  void readsAreUntouched() {
    // The whole pull surface, which routes exactly as it did before this rule existed.
    assertFalse(RegistryWriteBlock.refuses("GET", "/v2"));
    assertFalse(RegistryWriteBlock.refuses("GET", "/v2/"));
    assertFalse(
        RegistryWriteBlock.refuses("GET", "/v2/qits/build-images/ci-base/manifests/latest"));
    assertFalse(RegistryWriteBlock.refuses("HEAD", "/v2/qits/alpine/manifests/latest"));
    assertFalse(
        RegistryWriteBlock.refuses("GET", "/v2/qits/alpine/blobs/sha256:" + "0".repeat(64)));
  }

  @Test
  void nothingOutsideTheRegistryRootIsAffected() {
    // A write to any other service is that service's business — this rule is about one prefix.
    assertFalse(RegistryWriteBlock.refuses("POST", "/artifacts/api/repositories/r1/blobs"));
    assertFalse(RegistryWriteBlock.refuses("POST", "/git/abc-123/git-receive-pack"));
    assertFalse(RegistryWriteBlock.refuses("POST", "/workspaces/api/capture"));
    // And the prefix must not bleed, in the direction that matters: a neighbouring root is not the
    // registry and must keep working.
    assertFalse(RegistryWriteBlock.refuses("POST", "/v2x"));
    assertFalse(RegistryWriteBlock.refuses("POST", "/v20/x"));
    assertFalse(RegistryWriteBlock.refuses("POST", "/artifacts/v2/qits/alpine/manifests/latest"));
  }

  @Test
  void theRefusalNamesTheDoorThatDoesAcceptAPush() {
    // A docker client prints the OCI envelope's message, so the message is the only place a person
    // pushing at the wrong address is told the right one.
    assertTrue(RegistryWriteBlock.REFUSAL.contains("\"code\":\"DENIED\""));
    assertTrue(RegistryWriteBlock.REFUSAL.contains("edge registry vhost"));
  }
}
