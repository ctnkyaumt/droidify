package com.looker.droidify.utility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun testIsLocalHostExamples() {
        // Literal examples requested in brief
        assertTrue(isLocalHost("192.168.1.10"))
        assertTrue(isLocalHost("10.0.0.2"))
        assertTrue(isLocalHost("172.20.0.1"))
        assertFalse(isLocalHost("172.32.0.1"))
        assertFalse(isLocalHost("8.8.8.8"))
        assertTrue(isLocalHost("myrepo.local"))
        assertFalse(isLocalHost("f-droid.org"))

        // Additional IPv4 ranges
        assertTrue(isLocalHost("127.0.0.1"))
        assertTrue(isLocalHost("169.254.1.1"))

        // Hostnames and local domain endings
        assertTrue(isLocalHost("localhost"))
        assertTrue(isLocalHost("myrepo"))
        assertTrue(isLocalHost("server.lan"))
        assertTrue(isLocalHost("router.home.arpa"))

        // IPv6
        assertTrue(isLocalHost("::1"))
        assertTrue(isLocalHost("fe80::1"))
        assertTrue(isLocalHost("fc00::1"))
        assertTrue(isLocalHost("fd12::34"))
        assertFalse(isLocalHost("2001:db8::1"))

        // Full URLs
        assertTrue(isLocalHost("http://192.168.1.10:8080/repo"))
        assertTrue(isLocalHost("https://myrepo.local/fdroid/repo"))
        assertFalse(isLocalHost("https://f-droid.org/repo"))
    }
}
