package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The reserved-prefix predicate, tested without booting an application — the same place and for the
 * same reason as {@link RouteTableTest}: this is the edge-case surface of the header contract, and
 * it must stay provable without a running proxy.
 *
 * <p>Everything the gateway asserts about a request lives under {@link
 * EdgeHeaders#RESERVED_PREFIX}, and the strip rule is that same prefix. If this predicate is wrong,
 * {@code curl -H 'X-Qits-User: admin'} against the public port is a complete authentication bypass
 * — so the cases below are the bypass attempts, not decoration.
 */
class EdgeHeadersTest {

  @Test
  void theHeadersTheGatewayAssertsAreReserved() {
    assertTrue(EdgeHeaders.isReserved("X-Qits-User"));
    assertTrue(EdgeHeaders.isReserved("X-Qits-User-Id"));
  }

  @Test
  void aHeaderNotYetInventedIsAlreadyReserved() {
    // The point of the prefix: adding a trusted header must not require touching a strip list.
    assertTrue(EdgeHeaders.isReserved("X-Qits-Groups"));
    assertTrue(EdgeHeaders.isReserved("X-Qits-Anything-At-All"));
  }

  @Test
  void caseIsNotABypass() {
    // HTTP header names are case-insensitive; a client picks the casing, so the rule cannot.
    assertTrue(EdgeHeaders.isReserved("x-qits-user"));
    assertTrue(EdgeHeaders.isReserved("X-QITS-USER"));
    assertTrue(EdgeHeaders.isReserved("x-QiTs-UsEr"));
  }

  @Test
  void headersOutsideTheNamespaceArePassedThrough() {
    assertFalse(EdgeHeaders.isReserved("Authorization"));
    assertFalse(EdgeHeaders.isReserved("X-Forwarded-For"));
    assertFalse(EdgeHeaders.isReserved("Remote-User"));
    // Near misses: the prefix is `X-Qits-`, and neither of these carries it.
    assertFalse(EdgeHeaders.isReserved("XQits-User"));
    assertFalse(EdgeHeaders.isReserved("X-Qitsy-User"));
    assertFalse(EdgeHeaders.isReserved("Y-X-Qits-User"));
  }

  @Test
  void theBarePrefixCarriesNoValueAndIsNotAHeaderWeAssert() {
    // Nothing is named `X-Qits-`; treat it as an ordinary header rather than pretending otherwise.
    assertFalse(EdgeHeaders.isReserved("X-Qits-"));
    assertFalse(EdgeHeaders.isReserved("X-Qits"));
  }

  @Test
  void aMissingHeaderNameIsNotReserved() {
    assertFalse(EdgeHeaders.isReserved(null));
    assertFalse(EdgeHeaders.isReserved(""));
  }

  @Test
  void noHandshakeHeaderIsOneTheGatewayAsserts() {
    // The upgrade path forwards an allow-list rather than stripping a prefix, so the two rules have
    // to agree: if a reserved name ever appeared on that list, a client could set it on a handshake
    // and the gateway would forward it — the same bypass isReserved exists to prevent, reintroduced
    // through the one door that does not run the prefix strip.
    for (String name : EdgeHeaders.UPGRADE_HEADERS) {
      assertFalse(EdgeHeaders.isReserved(name), name + " must not be forwarded from a client");
    }
  }

  @Test
  void theHandshakeAllowListIsTheHandshakeAndNothingElse() {
    // Lower-case, because the comparison is: an entry with a capital would silently never match and
    // the header would be dropped rather than forwarded.
    for (String name : EdgeHeaders.UPGRADE_HEADERS) {
      assertTrue(
          name.equals(name.toLowerCase(java.util.Locale.ROOT)), name + " must be lower-case");
    }
    // Credentials do not travel past the gateway on a socket. Authentication terminates here and no
    // upstream authenticates by cookie or bearer, so neither belongs on a handshake.
    assertFalse(EdgeHeaders.UPGRADE_HEADERS.contains("cookie"));
    assertFalse(EdgeHeaders.UPGRADE_HEADERS.contains("authorization"));
  }

  @Test
  void theGatewayIsTheOnlyHopWhenNothingFrontsIt() {
    // The direct deployment, unchanged by the edge landing in front of the platform: no inbound
    // chain, so the peer IS the chain and the header reads exactly as it always did.
    assertEquals("127.0.0.1", EdgeHeaders.forwardedFor(null, "127.0.0.1"));
    assertEquals("127.0.0.1", EdgeHeaders.forwardedFor("", "127.0.0.1"));
    assertEquals("127.0.0.1", EdgeHeaders.forwardedFor("   ", "127.0.0.1"));
  }

  @Test
  void anOuterHopsClientIsKeptAndTheGatewaysPeerAddedAfterIt() {
    // THE BUG THIS REPLACED. The gateway used to SET this header, so behind qits-platform-edge
    // every request upstream claimed to come from the edge container and the real client address
    // was gone by the time anything could log it.
    assertEquals("203.0.113.7, 172.18.0.4", EdgeHeaders.forwardedFor("203.0.113.7", "172.18.0.4"));
  }

  @Test
  void theClientStaysFirstHoweverManyProxiesSign() {
    // Oldest first, so the ORIGINAL client is entry one and every later entry is a proxy. Anything
    // that ever reads a client address out of this header must read the first entry, not the last.
    String chain = EdgeHeaders.forwardedFor("203.0.113.7, 198.51.100.4", "172.18.0.4");

    assertEquals("203.0.113.7, 198.51.100.4, 172.18.0.4", chain);
    assertEquals("203.0.113.7", chain.split(",")[0].trim());
  }

  @Test
  void anUnknownPeerLeavesTheChainAsItArrived() {
    // A connection with no remote address must not truncate what an outer hop recorded.
    assertEquals("203.0.113.7", EdgeHeaders.forwardedFor("203.0.113.7", null));
    assertEquals("203.0.113.7", EdgeHeaders.forwardedFor("  203.0.113.7  ", "  "));
  }

  @Test
  void nothingToSayMeansNoHeaderAtAll() {
    // Null is the signal to leave the header off — an empty X-Forwarded-For would be a claim.
    assertNull(EdgeHeaders.forwardedFor(null, null));
    assertNull(EdgeHeaders.forwardedFor("", ""));
  }
}
