package eu.wohlben.qits.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
