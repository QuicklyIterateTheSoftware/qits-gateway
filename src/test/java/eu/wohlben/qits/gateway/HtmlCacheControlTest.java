package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The content-type boundary of the no-cache rule — the media type, not its parameters or case. */
class HtmlCacheControlTest {

  @Test
  void theMediaTypeDecides() {
    assertTrue(HtmlCacheControl.isHtml("text/html"));
    assertTrue(HtmlCacheControl.isHtml("text/html; charset=utf-8"));
    assertTrue(HtmlCacheControl.isHtml("text/html ; charset=utf-8"));
    assertTrue(HtmlCacheControl.isHtml("TEXT/HTML"));
  }

  @Test
  void everythingElseIsLeftAlone() {
    // A response with no body, or a handler that never said, is not a document.
    assertFalse(HtmlCacheControl.isHtml(null));
    assertFalse(HtmlCacheControl.isHtml("application/json"));
    assertFalse(HtmlCacheControl.isHtml("application/javascript"));
    assertFalse(HtmlCacheControl.isHtml("text/plain; charset=utf-8"));
    // A prefix is not a match: the comparison is the whole media type.
    assertFalse(HtmlCacheControl.isHtml("text/htmlx"));
  }
}
