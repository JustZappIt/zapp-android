// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReverseSwapClientTest {
    @Test
    fun errorsUseCodesEvenWhenDisplayTextChanges() =
        runTest {
            var requests = 0
            val http =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            """{"code":"rejected","error":"not a stable protocol field"}""",
                            HttpStatusCode.Conflict
                        )
                    }
                )
            try {
                val client = ReverseSwapClient(http, deployment)
                val failure =
                    assertFailsWith<ReverseServiceException> {
                        client.ready(ReverseAuthorization(word, 100, "0x" + "01".repeat(65)))
                    }
                assertEquals(ReverseErrorCode.REJECTED, failure.code)
                assertEquals(409, failure.status)
                assertEquals(1, requests)
            } finally {
                http.close()
            }
        }

    @Test
    fun infoUsesThePinnedMakerPath() =
        runTest {
            val http =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("https://host/maker/v1/info", request.url.toString())
                        respond(
                            """{"apiVersion":1,"maker":"$address","chainId":11155111,"contract":"$address",
                    "token":"$address","zcashNetwork":"testnet","reverseEnabled":true}"""
                        )
                    }
                )
            try {
                assertEquals(11_155_111, ReverseSwapClient(http, deployment).info().chainId)
            } finally {
                http.close()
            }
        }

    private companion object {
        val address = "0x" + "01".repeat(20)
        val word = "0x" + "01".repeat(32)
        val deployment =
            ReverseDeployment(
                "https://host/maker",
                "https://host/relayer",
                "https://rpc",
                11_155_111,
                address,
                address,
                address,
                address,
                address,
                "100000"
            )
    }
}
