package com.slte.desktop.core

import com.slte.desktop.model.ProxyMode
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MihomoControllerTest {
    @Test
    fun `forces loopback ports disables tun and injects direct rule`() {
        val raw = """
            mixed-port: 7890
            allow-lan: true
            tun:
              enable: true
            dns:
              fake-ip-filter: []
            proxies:
              - name: demo
                type: ss
                server: 127.0.0.1
                port: 443
                cipher: aes-128-gcm
                password: test
            proxy-groups:
              - name: PROXY
                type: select
                proxies: [demo]
            rules:
              - MATCH,PROXY
        """.trimIndent()

        val prepared = MihomoController.prepareConfig(
            raw = raw,
            mixedPort = 48123,
            controllerPort = 49123,
            secret = "test-secret",
            directDomains = listOf("api.slte.test"),
            mode = ProxyMode.Rule,
        )
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any?>>(prepared)

        assertEquals(48123, root["mixed-port"])
        assertEquals("127.0.0.1", root["bind-address"])
        assertEquals("127.0.0.1:49123", root["external-controller"])
        assertFalse(root["allow-lan"] as Boolean)
        assertFalse((root["tun"] as Map<*, *>)["enable"] as Boolean)
        assertTrue((root["rules"] as List<*>).first() == "DOMAIN-SUFFIX,api.slte.test,DIRECT")
        assertTrue("+.api.slte.test" in (root["dns"] as Map<*, *>)["fake-ip-filter"] as List<*>)
    }
}
