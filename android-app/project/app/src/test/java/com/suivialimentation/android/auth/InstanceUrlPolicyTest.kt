package com.suivialimentation.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstanceUrlPolicyTest {
    @Test
    fun acceptsHttpsAndPrivateHttp() {
        assertEquals("https://ha.example.test", InstanceUrlPolicy.normalize("https://ha.example.test/"))
        assertEquals("http://192.168.1.10:8123", InstanceUrlPolicy.normalize("http://192.168.1.10:8123/"))
        assertEquals("http://homeassistant.local:8123", InstanceUrlPolicy.normalize("http://homeassistant.local:8123"))
    }

    @Test
    fun rejectsRemoteCleartextAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) { InstanceUrlPolicy.normalize("http://ha.example.test") }
        assertThrows(IllegalArgumentException::class.java) { InstanceUrlPolicy.normalize("https://user:password@ha.example.test") }
    }
}
