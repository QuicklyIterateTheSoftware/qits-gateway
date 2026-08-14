package eu.wohlben.qits.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * The gateway end to end: a real request in, a real forwarded request at a real upstream.
 *
 * <p>Named caller throughout, because the gateway now authenticates: every path here except the
 * health probe is behind the policy, and an anonymous request would be challenged before routing
 * ever ran. {@code @TestSecurity} keeps these tests about <em>routing</em> — who gets in is {@link
 * eu.wohlben.qits.gateway.security.GatewayAuthTest}'s subject.
 */
@QuarkusTest
@TestSecurity(user = "dev")
@WithTestResource(StubUpstream.class)
class GatewayRoutingTest {

  @Test
  void forwardsToTheServiceVerbatim() {
    // /artifacts/… reaches qits-artifacts as /artifacts/… — the public path is not stripped.
    given()
        .when()
        .get("/artifacts/deep/path?q=1")
        .then()
        .statusCode(200)
        .body(containsString("path=/artifacts/deep/path?q=1"));
  }

  @Test
  void theRegistryRootReachesArtifactsVerbatim() {
    // /v2 was the first prefix in the system that is not /<segment>, so nothing else in this suite
    // proves the gateway forwards it at all — let alone unrewritten.
    given().when().get("/v2/").then().statusCode(200).body(containsString("path=/v2/"));
  }

  @Test
  void bothOfTheGitHostsAddressesReachTheOneUpstream() {
    // One proxy-hosts entry, two prefixes: the SPA and API a browser opens at /githost, and the
    // /git address every clone url, workspace remote and qits-ci config read hardcodes. A rename
    // that dropped the extra would leave every clone in the platform 404ing on a landing page.
    given()
        .when()
        .get("/githost/api/repositories")
        .then()
        .statusCode(200)
        .body(containsString("path=/githost/api/repositories"));

    given()
        .when()
        .get("/git/abc-123/info/refs?service=git-upload-pack")
        .then()
        .statusCode(200)
        .body(containsString("path=/git/abc-123/info/refs?service=git-upload-pack"));
  }

  @Test
  void aMultiSlashRegistryPathIsForwardedUnrewritten() {
    // The single most important registry assertion here. An OCI name may contain slashes, and the
    // service splits repository from image on the FIRST one — so a gateway that normalised, merged
    // or stripped any part of this path would break a push in a way only a real client would show.
    given()
        .when()
        .get("/v2/qits/build-images/ci-base/manifests/latest")
        .then()
        .statusCode(200)
        .body(containsString("path=/v2/qits/build-images/ci-base/manifests/latest"));
  }

  @Test
  void aDigestReferenceSurvivesTheColonInThePath() {
    // Worth pinning because a reader will wonder: RouteTable parses host:port with lastIndexOf(':')
    // and a digest puts a colon in the path. Nothing on the way through may touch it.
    String digest = "sha256:" + "0".repeat(64);
    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/qits/alpine/blobs/" + digest)
        .then()
        .statusCode(200)
        .body(containsString("path=/v2/qits/alpine/blobs/" + digest));
  }

  @Test
  void aRegistryWriteIsRefusedHereEvenForANamedCaller() {
    // Not an authentication decision, which is why it is asserted next to the routing: /v2 has a
    // real route and this caller has a real identity, and the write is still answered here. No
    // legitimate writer pushes through the gateway — producers dial qits-artifacts on qits-net, an
    // external push goes to the edge's registry vhost with a Bearer — so the gateway refuses in
    // every build target. LocalVariantTest holds the target that has no identity at all.
    given()
        .body(new byte[] {1, 2, 3})
        .when()
        .post("/v2/qits/alpine/blobs/uploads/")
        .then()
        .statusCode(403)
        .contentType(containsString("application/json"))
        .body(containsString("DENIED"))
        .body(containsString("edge registry vhost"))
        // It died here: the stub upstream never saw it.
        .body(not(containsString("body-bytes")));
  }

  @Test
  void aBodyLargerThanTheQuarkusDefaultWireLimitStreamsThrough() {
    // The gateway analogue of the artifacts suite's oversized-upload test, and the only automated
    // guard against quarkus.http.limits.max-body-size being lowered back below what a layer needs.
    // A large upload through here would otherwise 413 at the front door, bodiless, before the
    // service could answer with its own error envelope.
    //
    // It used to be a registry blob upload, which is the exchange that first needed the limit
    // raised. That path is refused at the front door now (see the test above), so the same body
    // rides the blob store's own write address instead — the limit is the gateway's and is not per
    // route.
    byte[] body = new byte[12 * 1024 * 1024];
    given()
        .body(body)
        .when()
        .post("/artifacts/api/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(200)
        .body(containsString("body-bytes=" + body.length));
  }

  @Test
  void describesTheOriginalClientToTheUpstream() {
    // The direct deployment: nothing fronts the gateway, so no chain arrives and the gateway's own
    // peer is the whole of it. This is the case that must not change now that the header is
    // appended rather than set.
    given()
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-forwarded-proto=http"))
        .body(containsString("x-forwarded-for=127.0.0.1"))
        .body(not(containsString("x-forwarded-host=-")));
  }

  @Test
  void behindTheEdgeTheRealClientSurvivesInsteadOfBeingOverwritten() {
    // qits-platform-edge binds the host port and forwards here, so the gateway is no longer the
    // outermost hop. It used to SET this header on the grounds that it was — which behind the edge
    // meant every upstream saw the edge container's address and the real client was unrecoverable.
    // Appended now: the client stays FIRST and this hop signs after it.
    given()
        .header("X-Forwarded-For", "203.0.113.7")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-forwarded-for=203.0.113.7, 127.0.0.1"));
  }

  @Test
  void aChainThatAlreadyNamesSeveralProxiesIsExtendedNotRestarted() {
    given()
        .header("X-Forwarded-For", "203.0.113.7, 198.51.100.4")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-forwarded-for=203.0.113.7, 198.51.100.4, 127.0.0.1"));
  }

  @Test
  void whatTheOuterHopSawOfTheAddressIsLeftAlone() {
    // -Host and -Proto describe the address the client actually typed, and only the outermost hop
    // saw it. The edge terminates TLS, so overwriting -Proto here would tell every upstream the
    // exchange was plain http; it forwards the original Host, so overwriting -Host would replace a
    // public name with whatever this hop was dialled by.
    given()
        .header("X-Forwarded-Proto", "https")
        .header("X-Forwarded-Host", "qits.example")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-forwarded-proto=https"))
        .body(containsString("x-forwarded-host=qits.example"));
  }

  @Test
  void dropsClientSuppliedIdentityHeaders() {
    // The forward-auth trust contract: qits believes Remote-User unconditionally, so a client must
    // never be able to set it through the front door.
    given()
        .header("Remote-User", "attacker")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("remote-user=-"));
  }

  @Test
  void clientSuppliedReservedHeadersAreReplacedNotForwarded() {
    // The gateway's own namespace: an upstream believes X-Qits-User unconditionally, so a client
    // setting it through the front door would be a complete authentication bypass. Stripped by
    // prefix, which is why the casing a client picks cannot matter — and only then replaced with
    // what this request actually authenticated as.
    given()
        .header("X-Qits-User", "attacker")
        .header("x-qits-user-id", "00000000-0000-0000-0000-000000000000")
        .header("X-QITS-GROUPS", "admin")
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("x-qits-user=dev"))
        .body(not(containsString("attacker")))
        .body(not(containsString("admin")))
        .body(not(containsString("00000000")));
  }

  @Test
  void unroutedPathsOpenNoConnectionAndAreNotAnsweredByTheGateway() {
    // What this asserts, precisely: the GATEWAY opens no connection for a path no route claims, and
    // does not answer it either — it yields. What answers depends on what is layered BEHIND it, so
    // the 404 below is Vert.x's, not this application's, and the distinction is the test.
    //
    // Quinoa is off for the whole suite (%test.quarkus.quinoa=false — no node, no network, and on
    // a fresh clone no submodule), so nothing is layered behind the proxy here and the yield lands
    // on the container's own 404. That is the MACHINE answer, and it is the half of the contract
    // this suite can prove: a gateway that serves no SPA still 404s a path it does not route,
    // rather than the next() leaking into some other handler.
    //
    // In a packaged gateway the SPA fallback is behind this route (order 40_000 against this
    // handler's 20_000), so the same request is re-routed to index.html and answers 200. That half
    // is proven on the image — see the README's probe table — not here.
    given().when().get("/nothing/here").then().statusCode(404);
  }

  @Test
  void aConfiguredPrefixIsProxiedWithoutBeingNamedInTheSpaIgnoreList() {
    // THE INVARIANT THE ORDERING CHANGE BOUGHT, and the reason this test is worth its own name.
    //
    // quarkus.quinoa.ignored-path-prefixes is /api,/q — the gateway's own machine surface and
    // nothing else. /artifacts appears in it nowhere, yet it proxies, because GatewayRouter runs at
    // 20_000 and the SPA fallback at 40_000: the ROUTE TABLE decides, and the SPA gets only what no
    // route claimed. Before, the list had to re-state every platform segment and a service missing
    // from it was silently answered with index.html instead of being routed.
    //
    // Quinoa is off here so this cannot observe the SPA losing the race; what it pins is the other
    // half, which is the half a regression would break: nothing about being absent from that list
    // stops a configured prefix from reaching its upstream.
    given()
        .when()
        .get("/artifacts/x")
        .then()
        .statusCode(200)
        .body(containsString("path=/artifacts/x"));
  }

  @Test
  void aDeepPathUnderAConfiguredPrefixIsProxiedVerbatim() {
    // The nested case, spelled out because it is the one the ordering has to get right: a deep link
    // under a routed segment must longest-prefix-match its service and be forwarded UNCHANGED, not
    // mistaken for a client-side route and rewritten to index.html. /observability is configured to
    // the stub and named in no ignore list, and the upstream sees every segment of the path.
    given()
        .when()
        .get("/observability/runs/123/detail?tab=logs")
        .then()
        .statusCode(200)
        .body(containsString("path=/observability/runs/123/detail?tab=logs"));
  }

  @Test
  void theGatewaysOwnApiSurfaceIsServedLocallyAndNeverProxied() {
    // /api is one of the two prefixes still in the ignore list, and it is local: ConfigJsonRoute
    // registers at order 100, far ahead of the proxy at 20_000. No service claims /api, so without
    // that local route the path would fall through — this pins that it does not.
    given()
        .when()
        .get("/api/config.json")
        .then()
        .statusCode(200)
        .contentType(containsString("application/json"));
  }

  @Test
  void aSegmentThatIsNotAConfiguredServiceIsNotRouted() {
    // `stt` is a known service but has no proxy-hosts entry in the test config, so it is not live —
    // only services the deployment actually enabled are routed.
    given().when().get("/stt/x").then().statusCode(404);
  }

  @Test
  void theGatewaysOwnManagementSurfaceIsNeverProxied() {
    given().when().get("/q/health/ready").then().statusCode(200).body("status", is("UP"));
  }

  @Test
  void anHtmlDocumentLeavesWithNoCacheWhateverTheUpstreamSaid() {
    // The stub answers this path as Quarkus serves a SPA by default: text/html with the day-long
    // immutable header. A browser holding yesterday's index.html for a day runs yesterday's
    // application, so the edge rewrites the static default to no-cache.
    given()
        .when()
        .get("/artifacts/spa/deep/link")
        .then()
        .statusCode(200)
        .contentType(containsString("text/html"))
        .header("Cache-Control", is("no-cache"));
  }

  @Test
  void aHashNamedFileKeepsTheImmutableDefault() {
    // A content-hashed name is the one place immutable is correct — a new build names a new file —
    // so the upstream's header passes through untouched.
    given()
        .when()
        .get("/artifacts/spa/main-4RS6EA47.js")
        .then()
        .statusCode(200)
        .header("Cache-Control", is("public, immutable, max-age=86400"));
  }

  @Test
  void anUnhashedAssetCarryingTheStaticDefaultRevalidates() {
    // The favicon's name never changes, so the day-long default would pin yesterday's file exactly
    // like it pinned the document. Not being HTML earns no exemption — only a hashed name does.
    given()
        .when()
        .get("/artifacts/spa/favicon.ico")
        .then()
        .statusCode(200)
        .header("Cache-Control", is("no-cache"));
  }
}
