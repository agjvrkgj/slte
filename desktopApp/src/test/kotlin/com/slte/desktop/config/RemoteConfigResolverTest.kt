package com.slte.desktop.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteConfigResolverTest {
    private val allowed = listOf("example.com", "slte.test")

    @Test
    fun `accepts https subdomains in allowlist`() {
        assertTrue(RemoteConfigResolver.isAllowedApiUrl("https://api.slte.test", allowed))
        assertTrue(RemoteConfigResolver.isAllowedApiUrl("https://edge.api.slte.test/v1", allowed))
    }

    @Test
    fun `rejects suffix confusion and insecure urls`() {
        assertFalse(RemoteConfigResolver.isAllowedApiUrl("https://slte.test.evil.invalid", allowed))
        assertFalse(RemoteConfigResolver.isAllowedApiUrl("http://api.slte.test", allowed))
        assertFalse(RemoteConfigResolver.isAllowedApiUrl("https://user@api.slte.test", allowed))
    }

    @Test
    fun `validates direct domains on label boundaries`() {
        assertTrue(RemoteConfigResolver.isAllowedDomain("cdn.slte.test", allowed))
        assertFalse(RemoteConfigResolver.isAllowedDomain("notslte.test", allowed))
        assertFalse(RemoteConfigResolver.isAllowedDomain("localhost", allowed))
    }
}
