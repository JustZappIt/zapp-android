// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwapClientsTest {
    @Test
    fun errorsUseCodesEvenWhenDisplayTextChanges() =
        runTest {
            val refusal = """{"code":"rejected","error":"not a stable protocol field"}"""
            val services = Services { respond(refusal, HttpStatusCode.Conflict) }

            val failure =
                assertFailsWith<AtomicSwapHttpException.Refused> {
                    services.relayer.ready(SwapAuthorization(SWAP_ID, 100, "0x" + "01".repeat(65)), TERMS)
                }

            assertEquals(SwapErrorCode.REJECTED, failure.code)
            assertEquals(409, failure.status)
            assertEquals(AtomicSwapService.RELAYER, failure.service)
            assertEquals(1, services.paths.size)
            services.close()
        }

    @Test
    fun aRefusalWithoutACodeStillSaysWhyAndWithWhatStatus() =
        runTest {
            var answer = """{"error":"quote expired"}"""
            val services = Services { respond(answer, HttpStatusCode.NotFound) }

            val coded =
                assertFailsWith<AtomicSwapHttpException.Refused> {
                    services.maker.accept(QUOTE_ID, SWAP_ID, ACCEPTANCE)
                }
            assertEquals(404, coded.status)
            assertNull(coded.code)
            assertTrue("quote expired" in coded.message.orEmpty())

            answer = "<html>bad gateway</html>"
            val notJson = assertFailsWith<AtomicSwapHttpException.Refused> { services.maker.info() }
            assertEquals(404, notJson.status)
            assertNull(notJson.code)
            services.close()
        }

    @Test
    fun aCodeThisBuildDoesNotKnowStillLeavesTheReasonReadable() =
        runTest {
            val services =
                Services { respond("""{"code":"rateLimited","error":"slow down"}""", HttpStatusCode.TooManyRequests) }

            val failure = assertFailsWith<AtomicSwapHttpException.Refused> { services.relayer.terms() }

            assertNull(failure.code)
            assertEquals(429, failure.status)
            assertTrue("slow down" in failure.message.orEmpty())
            assertTrue("rateLimited" !in failure.message.orEmpty(), "the reason, not the raw body")
            services.close()
        }

    @Test
    fun aMakerOnAZcashNetworkThisBuildDoesNotKnowIsUnreadable() =
        runTest {
            val services =
                Services {
                    respond(
                        """{"apiVersion":1,"maker":"$ADDRESS","chainId":11155111,"contract":"$ADDRESS",
                        "token":"$ADDRESS","zcashNetwork":"regtest","reverseEnabled":true}"""
                    )
                }

            assertFailsWith<AtomicSwapHttpException.Unreadable> { services.maker.info() }
            services.close()
        }

    @Test
    fun infoUsesThePinnedMakerPath() =
        runTest {
            val services =
                Services(maker = Url("https://host/maker")) {
                    respond(
                        """{"apiVersion":1,"maker":"$ADDRESS","chainId":11155111,"contract":"$ADDRESS",
                        "token":"$ADDRESS","zcashNetwork":"testnet","reverseEnabled":true}"""
                    )
                }

            val info = services.maker.info()
            assertEquals(ChainId.ETHEREUM_SEPOLIA, info.chainId)
            assertEquals(SwapZcashNetwork.TESTNET, info.zcashNetwork)
            assertEquals(listOf("https://host/maker/v1/info"), services.urls)
            services.close()
        }

    @Test
    fun unreadableAnswersAndUnreachableServicesAreTypedFailures() =
        runTest {
            var answer = "<html>bad gateway</html>"
            var status = HttpStatusCode.OK
            val services =
                Services { request ->
                    if (request.url.encodedPath.endsWith("/v1/info")) throw IOException("connection refused")
                    respond(answer, status)
                }

            val unreachable = assertFailsWith<AtomicSwapHttpException.Unreachable> { services.maker.info() }
            assertEquals(AtomicSwapService.MAKER, unreachable.service)
            val unreadable = assertFailsWith<AtomicSwapHttpException.Unreadable> { services.relayer.terms() }
            assertEquals(AtomicSwapService.RELAYER, unreadable.service)
            status = HttpStatusCode.BadGateway
            assertEquals(502, assertFailsWith<AtomicSwapHttpException.Refused> { services.relayer.terms() }.status)
            answer = """{"relayer":"0x01","chainId":11155111,"contract":"$ADDRESS","fee":"1"}"""
            status = HttpStatusCode.OK
            assertFailsWith<AtomicSwapHttpException.Unreadable> { services.relayer.terms() }
            answer = """{"relayer":"$ADDRESS","chainId":11155111,"contract":"$ADDRESS","fee":"-1"}"""
            assertFailsWith<AtomicSwapHttpException.Unreadable> { services.relayer.terms() }
            answer = """{"transactions":["0xpay"]}"""
            assertFailsWith<AtomicSwapHttpException.Unreadable> { services.relayer.refund(REVEAL, TERMS) }
            services.close()
        }

    @Test
    fun requestsGoOutAsZecSwapsSerdeWritesThem() =
        runTest {
            val services = Services { respond("""{"transactions":[]}""") }
            val funding =
                """{"swapId":"${SWAP_ID.hex}","chainId":11155111,"to":"$ADDRESS","data":"0x12345678","value":"0"}"""

            services.relayer.fundReverse(
                ReverseFundingRequest(SWAP_ID, ChainId.ETHEREUM_SEPOLIA, Address.parse(ADDRESS), "0x12345678", "0")
            )
            services.relayer.lockClaim(AUTHORIZATION, TERMS)
            services.relayer.claim(REVEAL, TERMS)
            services.relayer.payout(PAYOUT, TERMS)
            services.relayer.ready(AUTHORIZATION, TERMS)
            services.relayer.lockRefund(AUTHORIZATION, TERMS)
            services.relayer.refund(REVEAL, TERMS)
            services.relayer.refundPayout(PAYOUT, TERMS)
            services.relayer.rescue(SwapRescue(SWAP_ID, PAYOUT.note, PAYOUT.fee, 0, 1000, SIGNATURE), TERMS)

            assertEquals(
                listOf(
                    "/relayer/v1/reverse/fund" to funding,
                    "/relayer/v1/lock-claim" to ZecSwapWireSamples.AUTHORIZATION,
                    "/relayer/v1/claim" to ZecSwapWireSamples.REVEAL,
                    "/relayer/v1/payout" to ZecSwapWireSamples.PAYOUT,
                    "/relayer/v1/reverse/ready" to ZecSwapWireSamples.AUTHORIZATION,
                    "/relayer/v1/reverse/lock-refund" to ZecSwapWireSamples.AUTHORIZATION,
                    "/relayer/v1/reverse/refund" to ZecSwapWireSamples.REVEAL,
                    "/relayer/v1/reverse/refund-payout" to ZecSwapWireSamples.PAYOUT,
                    "/relayer/v1/reverse/rescue" to ZecSwapWireSamples.RESCUE,
                ),
                services.paths.zip(services.bodies),
            )
            services.close()
        }

    @Test
    fun relayerTermsDiscoverSponsorshipWithoutBreakingOlderServices() =
        runTest {
            var sponsorship = ""
            val services =
                Services {
                    respond(
                        """{"relayer":"$ADDRESS","chainId":11155111,"contract":"$ADDRESS","fee":"20000"$sponsorship}"""
                    )
                }
            assertNull(services.relayer.terms().reverseFunding)
            sponsorship =
                ""","reverseFunding":{"relayAdapt":"$ADDRESS","token":"$ADDRESS","maker":"$ADDRESS",""" +
                """"maxGasLimit":4000000,"maxGasPriceWei":"20000000000","maxCalldataBytes":65536,"fee":"250000"}"""
            val funding = services.relayer.terms().reverseFunding
            assertEquals("20000000000", funding?.maxGasPriceWei)
            assertEquals(Usdc6.ofMicros(250_000), funding?.fee)
            services.close()
        }

    @Test
    fun timedOutFundingIsNotRetriedByTheHttpClient() =
        runTest {
            val services = Services { throw IOException("response lost") }
            val request =
                ReverseFundingRequest(SWAP_ID, ChainId.ETHEREUM_SEPOLIA, Address.parse(ADDRESS), "0x12345678", "0")
            assertFailsWith<AtomicSwapHttpException.Unreachable> { services.relayer.fundReverse(request) }
            assertEquals(listOf("/relayer/v1/reverse/fund"), services.paths)
            services.close()
        }

    @Test
    fun quotesAskForBaseUnitsForALowercaseAddress() =
        runTest {
            val services = Services { respond("{}") }
            val user = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5")

            assertFailsWith<AtomicSwapHttpException.Unreadable> {
                services.maker.quote(Usdc6.ofMicros(110_000), user, NoteCommitment.of(ByteArray(32) { 5 }))
            }
            assertFailsWith<AtomicSwapHttpException.Unreadable> {
                services.maker.quoteReverse(Usdc6.ofMicros(1_000_000), user, NoteCommitment.of(ByteArray(32) { 6 }))
            }

            assertEquals(
                listOf(
                    """{"units":110000,"payout":"${user.lowercaseHex}","payoutNote":"0x${"05".repeat(32)}"}""",
                    """{"units":1000000,"user":"${user.lowercaseHex}","refundNote":"0x${"06".repeat(32)}"}""",
                ),
                services.bodies,
            )
            assertEquals(listOf("/maker/v1/quote", "/maker/v1/reverse/quote"), services.paths)
            services.close()
        }

    @Test
    fun anAmountTheMakerCantQuoteIsNeverAskedFor() =
        runTest {
            val services = Services { respond("{}") }

            for (amount in listOf(Usdc6.ZERO, Usdc6.ofMicros(1L shl 32))) {
                assertFailsWith<IllegalArgumentException> {
                    services.maker.quote(amount, Address.ZERO, NoteCommitment.of(ByteArray(32)))
                }
            }
            assertTrue(services.paths.isEmpty())
            services.close()
        }

    @Test
    fun anAcceptAnsweredWithAnotherSwapComesBackAsIt() =
        runTest {
            val other = SwapId.of(ByteArray(32) { 7 })
            val services = Services { respond("""{"swapId":"${other.hex}","t0":1790003600,"t1":1790007200}""") }

            val accepted = services.maker.acceptReverse(QUOTE_ID, SWAP_ID, ACCEPTANCE)
            assertEquals(SwapAccepted(other, 1_790_003_600, 1_790_007_200), accepted)
            assertEquals(listOf("/maker/v1/reverse/quote/$QUOTE_ID/accept"), services.paths)
            assertEquals(
                """{"userShare":"${ACCEPTANCE.userShare}","userProof":"${ACCEPTANCE.userProof}",""" +
                    """"viewingKeys":"${ACCEPTANCE.viewingKeys}"}""",
                services.bodies.single(),
            )
            services.close()
        }

    private class Services(
        maker: Url = Url("https://host/maker"),
        relayer: Url = Url("https://host/relayer/"),
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val urls = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        private val http =
            HttpClient(
                MockEngine { request ->
                    urls += request.url.toString()
                    paths += request.url.encodedPath
                    (request.body as? TextContent)?.let { bodies += it.text }
                    answer(request)
                }
            )
        val maker = MakerClient(http, maker)
        val relayer = RelayerClient(http, relayer)

        fun close() = http.close()
    }

    private companion object {
        val ADDRESS = "0x" + "01".repeat(20)
        val WORD = "0x" + "02".repeat(32)
        val SIGNATURE = "0x" + "01".repeat(65)
        val QUOTE_ID = "0x" + "22".repeat(32)
        val SWAP_ID = SwapId.of(ByteArray(32) { 1 })
        val ACCEPTANCE = SwapAcceptance("0x" + "0c".repeat(64), "0x" + "04".repeat(64), "0x" + "05".repeat(64))
        val AUTHORIZATION = SwapAuthorization(SWAP_ID, 100, SIGNATURE)
        val PAYOUT = SwapPayout(SWAP_ID, SwapNote(WORD, List(3) { WORD }, WORD), Usdc6.ofMicros(20_000), SIGNATURE)
        val TERMS = ZecSwapVectors.PAYS_RAILGUN
        val REVEAL = SwapReveal(SWAP_ID, WORD, PAYOUT)
    }
}
