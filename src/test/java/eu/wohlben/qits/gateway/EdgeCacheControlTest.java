package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The two boundaries of the rewrite: which header, and which filenames are exempt. */
class EdgeCacheControlTest {

  @Test
  void onlyTheKnownStaticDefaultIsEligible() {
    assertTrue(EdgeCacheControl.isStaticDefault("public, immutable, max-age=86400"));
    assertTrue(EdgeCacheControl.isStaticDefault("Public, Immutable, Max-Age=86400"));
    assertTrue(EdgeCacheControl.isStaticDefault(" public, immutable, max-age=86400 "));
    // A header a handler chose is a decision, and the edge does not overrule decisions.
    assertFalse(EdgeCacheControl.isStaticDefault(null));
    assertFalse(EdgeCacheControl.isStaticDefault("no-store"));
    assertFalse(EdgeCacheControl.isStaticDefault("no-cache"));
    assertFalse(EdgeCacheControl.isStaticDefault("public, max-age=86400"));
    assertFalse(EdgeCacheControl.isStaticDefault("public, immutable, max-age=3600"));
  }

  @Test
  void angularsHashedNamesAreRecognised() {
    assertTrue(EdgeCacheControl.isContentHashed("/projects/main-4RS6EA47.js"));
    assertTrue(EdgeCacheControl.isContentHashed("/projects/styles-AB12CD34.css"));
    assertTrue(EdgeCacheControl.isContentHashed("/deep/path/chunk-XK9Q2M1P.js"));
    assertTrue(EdgeCacheControl.isContentHashed("/media/logo-ABCD1234.svg"));
  }

  @Test
  void everythingElseRevalidates() {
    assertFalse(EdgeCacheControl.isContentHashed(null));
    assertFalse(EdgeCacheControl.isContentHashed("/projects/"));
    assertFalse(EdgeCacheControl.isContentHashed("/projects/index.html"));
    assertFalse(EdgeCacheControl.isContentHashed("/projects/main.js"));
    assertFalse(EdgeCacheControl.isContentHashed("/projects/favicon.ico"));
    // Eight lowercase letters are a word, not a hash — the segment must be uppercase-alphanumeric.
    assertFalse(EdgeCacheControl.isContentHashed("/projects/user-settings.js"));
    // Eight uppercase letters could be a word too; the digit requirement is what rules them out.
    assertFalse(EdgeCacheControl.isContentHashed("/assets/logo-DOWNLOAD.png"));
    // Seven or nine characters are not the shape Angular emits.
    assertFalse(EdgeCacheControl.isContentHashed("/projects/main-4RS6EA4.js"));
    assertFalse(EdgeCacheControl.isContentHashed("/projects/main-4RS6EA471.js"));
  }
}
