package eu.wohlben.qits.gateway;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The Quarkus static-resource default, {@code public, immutable, max-age=86400}, leaves the gateway
 * only on files whose <b>name</b> is content-hashed. Everything else that carries it — the {@code
 * index.html} that names the bundles, favicons, logos, i18n files — goes out as {@code
 * Cache-Control: no-cache} instead.
 *
 * <p><b>Why.</b> Every service serves its SPA with that blanket default, and it is right exactly
 * where the name changes with the content: a hash-named bundle can be kept forever because a new
 * build names a new file. On everything else the same header made a browser keep yesterday's file
 * for a day — most visibly the SPA document itself, which decided which version of the application
 * a returning browser ran. {@code no-cache} keeps conditional revalidation; a 304 costs one round
 * trip.
 *
 * <p><b>Why only the known default is rewritten.</b> A header a handler chose is a decision, and
 * the edge does not overrule decisions: the gateway's own machine routes say {@code no-store}
 * (which a blanket rewrite would <i>weaken</i>), the git smart-HTTP protocol sets its own caching,
 * and an upstream that deliberately marks a response cacheable stays marked. The default is the one
 * value that is known to be nobody's decision, so it is the one value the edge may correct. The
 * string is Quarkus' (read off the platform's pin, not assumed) — re-check it when the Quarkus pin
 * moves.
 *
 * <p><b>What "content-hashed" means</b> is {@link #isContentHashed}: Angular's output shape, a
 * {@code -}-separated final segment of eight uppercase-alphanumeric characters before the
 * extension, at least one of them a digit. Both misses are the safe direction — a hash that happens
 * to be all letters (rare) merely revalidates — while the digit requirement keeps an all-caps word
 * in a filename from being kept for a day.
 *
 * <p>Installed as the first route on the main router (order 0, before Quinoa's static handler at
 * 1060 — see {@link GatewayRouter#ROUTE_ORDER}'s table), so the headers-end hook exists whichever
 * handler answers: the proxy, Quinoa's static resources, or the SPA fallback.
 */
final class EdgeCacheControl {

  /** Exactly what Quarkus puts on a static resource when nobody said anything. */
  static final String STATIC_DEFAULT = "public, immutable, max-age=86400";

  /**
   * A content-hashed filename, as Angular emits them: {@code main-4RS6EA47.js}. The hash segment is
   * eight uppercase base-36 characters; the digit requirement is what separates a hash from an
   * all-caps word.
   */
  private static final Pattern CONTENT_HASHED =
      Pattern.compile(".*-(?=[A-Z0-9]*[0-9])[A-Z0-9]{8}\\.[A-Za-z0-9]+$");

  private EdgeCacheControl() {}

  /** Registers the hook, then yields. */
  static void install(RoutingContext rc) {
    rc.addHeadersEndHandler(
        ignored -> {
          String cacheControl = rc.response().headers().get(HttpHeaders.CACHE_CONTROL);
          if (isStaticDefault(cacheControl) && !isContentHashed(rc.request().path())) {
            rc.response().headers().set(HttpHeaders.CACHE_CONTROL, "no-cache");
          }
        });
    rc.next();
  }

  /** Whether the header is the untouched Quarkus default — tolerant of case, nothing else. */
  static boolean isStaticDefault(String cacheControl) {
    return cacheControl != null
        && cacheControl.trim().toLowerCase(Locale.ROOT).equals(STATIC_DEFAULT);
  }

  /**
   * Whether a request path names a content-hashed file. The path is what is checked, not the
   * response: the name is the cache key a browser holds, and the name changing with the content is
   * the entire justification for {@code immutable}.
   */
  static boolean isContentHashed(String path) {
    return path != null && CONTENT_HASHED.matcher(path).matches();
  }
}
