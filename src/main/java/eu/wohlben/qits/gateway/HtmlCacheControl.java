package eu.wohlben.qits.gateway;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import java.util.Locale;

/**
 * Every HTML document leaving the gateway carries {@code Cache-Control: no-cache}, whatever the
 * handler that produced it said.
 *
 * <p><b>Why.</b> A SPA's {@code index.html} is the one file whose freshness decides which version
 * of the application a browser runs — it is the file that names the hashed bundles. Quarkus'
 * static-resource default is {@code public, immutable, max-age=86400}, on the gateway's own landing
 * page and on every service the gateway fronts, so a browser kept yesterday's document for a day
 * and ran yesterday's application against today's platform. {@code no-cache} keeps conditional
 * revalidation (a 304 costs one round trip); the hashed assets the document names keep whatever
 * their producer said — content-addressed names make {@code immutable} correct <i>there</i>.
 *
 * <p><b>Why at the edge and not in ten services.</b> The gateway is the one process every document
 * passes through, and the default is wrong the same way in every upstream. One rule here replaces a
 * per-service configuration nobody would keep complete.
 *
 * <p><b>Why a headers-end hook and not a proxy interceptor.</b> The landing SPA is served by this
 * process (Quinoa static resources and the deep-link fallback), so a {@code ProxyInterceptor} would
 * cover the proxied documents and miss the gateway's own. The hook is installed before any handler
 * runs and fires just before the response's headers leave, which is the one point both kinds of
 * response pass.
 *
 * <p>The gateway's own machine routes ({@code /api/config.json}, {@code /api/auth/me}, {@code
 * /main-navigation}) answer JSON with {@code no-store} and are untouched by this rule — the
 * content-type check is the boundary, and {@link #isHtml} is where its edge cases live.
 */
final class HtmlCacheControl {

  private HtmlCacheControl() {}

  /**
   * Installed as the first route on the main router: registers the hook, then yields. It must be
   * ordered before Quinoa's static handler (1060) so the hook exists before any document handler
   * writes; see {@link GatewayRouter#ROUTE_ORDER}'s table.
   */
  static void install(RoutingContext rc) {
    rc.addHeadersEndHandler(
        ignored -> {
          if (isHtml(rc.response().headers().get(HttpHeaders.CONTENT_TYPE))) {
            rc.response().headers().set(HttpHeaders.CACHE_CONTROL, "no-cache");
          }
        });
    rc.next();
  }

  /**
   * Whether a {@code Content-Type} names an HTML document. The media type is what is compared —
   * parameters ({@code ; charset=utf-8}) are ignored, case is ignored, and {@code text/htmlx} does
   * not match. {@code null} (no body, or a handler that never said) is not a document.
   */
  static boolean isHtml(String contentType) {
    if (contentType == null) {
      return false;
    }
    int semicolon = contentType.indexOf(';');
    String mediaType = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();
    return mediaType.toLowerCase(Locale.ROOT).equals("text/html");
  }
}
