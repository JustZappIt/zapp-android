// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import xyz.justzappit.railgun.RailgunRequests.Route
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class RailgunBoundaryTest {
    @Test
    fun thePageReachesItsAssetsAndItsHostsOnly() {
        assertEquals(Route.OWN_ASSET, route(RailgunRequests.PAGE_URL))
        RailgunEndpoints.hosts.forEach { assertEquals(Route.ALLOWED, route("https://$it/any/path")) }
        assertEquals(Route.ALLOWED, route(RailgunEndpoints.SEPOLIA_RPC_URL))
        assertEquals(Route.ALLOWED, route(RailgunEndpoints.POI_NODE_URL))
        assertEquals(Route.ALLOWED, route("blob:https://railgun.appassets.androidplatform.net/0f1e"))
        assertEquals(Route.ALLOWED, route("data:application/wasm;base64,AGFzbQ=="))
    }

    @Test
    fun everythingElseIsBlocked() {
        listOf(
            "https://example.com/",
            "http://ethereum-sepolia-rpc.publicnode.com/",
            "https://ethereum-sepolia-rpc.publicnode.com:8443/",
            "https://evil.ipfs-lb.com/",
            "https://appassets.androidplatform.net/assets/railgun/index.html",
            "file:///data/data/xyz.justzappit.zapp/shared_prefs/prefs.xml",
            "content://xyz.justzappit.zapp.provider/x",
        ).forEach { assertEquals(Route.BLOCKED, route(it), it) }
    }

    @Test
    fun theCspListsTheSameHosts() {
        val csp = csp(File(ASSET).readText())

        val hosts = RailgunEndpoints.hosts.map { "https://$it" }.sorted()
        assertEquals(listOf("'self'") + hosts, csp.getValue("connect-src"))
        assertEquals(listOf("'none'"), csp.getValue("default-src"))
        assertEquals(listOf("'none'"), csp.getValue("base-uri"))
        assertEquals(listOf("'none'"), csp.getValue("form-action"))
    }

    @Test
    fun theBuiltPageIsItsSource() {
        assertEquals(File(SOURCE).readText(), File(ASSET).readText())
    }

    private fun route(url: String): Route = URI(url).let { RailgunRequests.route(it.scheme, it.host, it.port) }

    private fun csp(html: String): Map<String, List<String>> =
        Regex("""http-equiv="Content-Security-Policy" content="([^"]*)"""")
            .find(html)
            .let { checkNotNull(it).groupValues[1] }
            .split(';')
            .map { it.trim().split(' ') }
            .associate { it.first() to it.drop(1).let { values -> listOf(values.first()) + values.drop(1).sorted() } }

    private companion object {
        const val ASSET = "src/main/assets/railgun/index.html"
        const val SOURCE = "web/src/index.html"
    }
}
