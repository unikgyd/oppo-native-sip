package dev.mysip

import org.junit.Assert.*
import org.junit.Test

class SipConfigTest {
    @Test fun newAccountHasNoInventedSettingsAndDoesNotConnect() {
        val config = SipConfig()
        assertEquals("", config.host); assertEquals(0, config.port)
        assertEquals("", config.user); assertEquals("", config.password)
        assertEquals("", config.transport); assertFalse(config.enabled)
        assertNotNull(config.validate())
    }
    @Test fun protocolMustBeSelectedExplicitlyBeforeSaving() {
        val config = SipConfig(host = "pbx.test", port = 5060, user = "extension", password = "test-password")
        assertNotNull(config.validate())
        assertNull(config.copy(transport = "UDP").validate())
    }
}
