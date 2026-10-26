package dev.mysip

import org.junit.Assert.*
import org.junit.Test

class SipTargetTest {
    @Test fun copiedPhoneFormattingDoesNotBlockDialing() {
        assertEquals("sip:+8613800000000@pbx.test", SipTarget.uri("+86 (138) 0000-0000", "pbx.test"))
        assertEquals("sip:1002@pbx.test", SipTarget.uri("tel:1002", "pbx.test"))
        assertEquals("sip:1002@pbx.test;transport=tcp", SipTarget.uri("sip:1002@pbx.test;transport=tcp", "other.test"))
    }
    @Test fun plainPhoneAndInternationalNumbersUseGatewayDomain() {
        assertEquals("sip:+8613800000000@pbx.test", SipTarget.uri("tel:+8613800000000", "pbx.test"))
        assertEquals("sip:1002@pbx.test", SipTarget.uri("1002", "pbx.test"))
    }
    @Test fun literalHashCannotBecomeUriFragment() {
        assertEquals("sip:*123%23@pbx.test", SipTarget.uri("*123#", "pbx.test"))
    }
    @Test fun explicitSipAddressPreservesDestination() {
        assertEquals("sip:2002@other.test;transport=tcp", SipTarget.uri("sip:2002@other.test;transport=tcp", "pbx.test"))
        assertEquals("sips:alice@other.test", SipTarget.uri("sips:alice@other.test", "pbx.test"))
    }
    @Test fun rejectsHeaderInjectionAndAmbiguousAddresses() {
        listOf("", "sip:missing-domain", "1001\r\nContact: evil", "sip:1001@test?Subject=bad", "<sip:1001@test>", "1001;transport=tcp").forEach { value ->
            try { SipTarget.uri(value, "pbx.test"); fail("Accepted invalid target") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun callerNumberCanMatchSystemContacts() {
        assertEquals("+8613800000000", SipTarget.display("sip:+8613800000000@pbx.test;user=phone"))
        assertEquals("1001", SipTarget.display("sips:1001@pbx.test"))
    }
    @Test fun telecomEscapedAtSignStillProducesDialableSipAddress() {
        val address = java.net.URI("sip:5550100%40pbx.test")
        val raw = SipTarget.fromTelecom(address.scheme, address.schemeSpecificPart)
        assertEquals("sip:5550100@pbx.test", SipTarget.uri(raw, "other.test"))
    }
}
