// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import io.ktor.http.Url
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records devices keep today must read, and write back the same; the stores decode strictly, as [storeJson] does. */
class SwapRecordStorageTest {
    @Test
    fun aForwardRecordFromBeforeDepositsAndSweepsWereKeptStillReads() {
        val record = storeJson.decodeFromString(AtomicSwapRecord.serializer(), FORWARD)

        assertEquals(3, record.index)
        assertEquals(SwapDeposit.Recorded(ZcashTxId.parse(TX_HEX)), record.deposit)
        val refunded = AtomicSwapOutcome.Refunded(ZcashTxId.parse(SWEEP_HEX), RefundCause.MAKER_CANCELLED)
        assertEquals(refunded, record.outcome)
        assertEquals(1_000_000L, record.maxTotalZat)
        assertNull(record.deposit.transaction)
        assertNull(record.sweep)
        assertNull(record.relayerFee)
    }

    @Test
    fun whatAForwardRecordNowKeepsReadsBack() {
        val record =
            storeJson.decodeFromString(AtomicSwapRecord.serializer(), FORWARD).copy(
                deposit = SwapDeposit.Kept(ZcashTransaction(ZcashTxId.parse(TX_HEX), "0400", 4_200_040)),
                sweep = ZcashTransaction(ZcashTxId.parse(SWEEP_HEX), "0500", 4_200_140),
                relayerFee = Usdc6.ofMicros(20_000),
            )
        val json = storeJson.encodeToString(AtomicSwapRecord.serializer(), record)

        assertEquals(record, storeJson.decodeFromString(AtomicSwapRecord.serializer(), json))
        val relayerFee = storeJson.parseToJsonElement(json).jsonObject.getValue("relayerFee")
        assertEquals("20000", relayerFee.jsonPrimitive.content)
    }

    @Test
    fun recordsFromBeforeTheDerivedRailgunWalletPayTheZcashSeedsOwn() {
        val forward = storeJson.decodeFromString(AtomicSwapRecord.serializer(), FORWARD)
        val reverse = storeJson.decodeFromString(ReverseSwapRecord.serializer(), REVERSE)

        assertEquals(RailgunKeySource.ZCASH_SEED, forward.railgunKeys)
        assertEquals(RailgunKeySource.ZCASH_SEED, reverse.railgunKeys)
    }

    @Test
    fun aRecordKeepsTheRailgunKeysItPays() {
        val forward =
            storeJson
                .decodeFromString(AtomicSwapRecord.serializer(), FORWARD)
                .copy(railgunKeys = RailgunKeySource.BIP85)
        val reverse =
            storeJson
                .decodeFromString(ReverseSwapRecord.serializer(), REVERSE)
                .copy(railgunKeys = RailgunKeySource.BIP85)
        val forwardJson = storeJson.encodeToString(AtomicSwapRecord.serializer(), forward)
        val reverseJson = storeJson.encodeToString(ReverseSwapRecord.serializer(), reverse)

        assertEquals(forward, storeJson.decodeFromString(AtomicSwapRecord.serializer(), forwardJson))
        assertEquals(reverse, storeJson.decodeFromString(ReverseSwapRecord.serializer(), reverseJson))
        listOf(forwardJson, reverseJson).forEach { json ->
            val railgunKeys = storeJson.parseToJsonElement(json).jsonObject.getValue("railgunKeys")
            assertEquals("BIP85", railgunKeys.jsonPrimitive.content)
        }
    }

    @Test
    fun aReverseRecordWithASweepStillReadsAndWritesTheSameShape() {
        val record = storeJson.decodeFromString(ReverseSwapRecord.serializer(), REVERSE)

        assertEquals(ReversePhase.RECEIVING, record.phase)
        assertEquals(ZcashTxId.parse("09".repeat(32)), record.receive?.transaction?.txId)
        assertTrue(record.rescuePending)
        val written = storeJson.parseToJsonElement(storeJson.encodeToString(ReverseSwapRecord.serializer(), record))
        assertEquals(storeJson.parseToJsonElement(REVERSE), written)
        assertFalse("transaction" in written.jsonObject.getValue("receive").jsonObject)
    }

    @Test
    fun forwardRecordsInEveryStateWriteBackExactlyAsTheyWereKept() {
        for (json in FORWARD_STATES) assertRoundTrip(AtomicSwapRecord.serializer(), json)
    }

    @Test
    fun aForwardRecordsDepositAndEndReadAsTheirTypedStates() {
        val states = FORWARD_STATES.map(::forward)
        val (early, started, recorded) = states
        val (kept, paid) = states.drop(3)

        assertEquals(SwapDeposit.NotStarted, early.deposit)
        assertNull(early.end)
        assertEquals(SwapDeposit.Started, started.deposit)
        assertEquals(SwapDeposit.Recorded(ZcashTxId.parse(TX_HEX)), recorded.deposit)
        assertEquals(SwapDeposit.Kept(ZcashTransaction(ZcashTxId.parse(TX_HEX), "0400", 4_200_040)), kept.deposit)
        assertEquals(SwapEnd(AtomicSwapOutcome.Paid, 1_790_003_000), paid.end)
        assertEquals(TxHash.fromHex(PAYOUT_TX), paid.payoutTx)
        assertEquals(Usdc6.ofMicros(977_550), paid.receives)
        assertEquals(Usdc6.ofMicros(1_000_000), paid.quote.amount)
        assertEquals(SWAP_ID, paid.swapId.hex)
    }

    @Test
    fun reverseRecordsInEveryStateWriteBackExactlyAsTheyWereKept() {
        for (json in REVERSE_STATES) assertRoundTrip(ReverseSwapRecord.serializer(), json)
    }

    @Test
    fun aReverseRecordsIdsAddressesAndAmountsReadTyped() {
        val record = storeJson.decodeFromString(ReverseSwapRecord.serializer(), REVERSE_STATES.last())

        assertEquals(TESTNET, record.deployment)
        assertEquals(Address.parse(USER), record.quote.user)
        assertEquals(TxHash.fromHex(FUNDING_TX), record.funding?.txId)
        assertEquals(Usdc6.ofMicros(1_002_506), record.cost?.debit)
        assertNull(record.cost?.broadcasterFee)
        assertEquals(Usdc6.ofMicros(20_000), record.payout?.fee)
        assertEquals(SwapId.parse(SWAP_ID), record.refundLock?.swapId)
        assertEquals(ReversePhase.REFUNDED, record.phase)
    }

    @Test
    fun keptReverseRecordsReadAsWhereTheConversionStands() {
        val states = REVERSE_STATES.map { storeJson.decodeFromString(ReverseSwapRecord.serializer(), it) }
        val (previewed, accepted) = states
        val (settling, refunded) = states.drop(3)
        val sweeping = storeJson.decodeFromString(ReverseSwapRecord.serializer(), REVERSE)

        fun underWay(
            phase: ReversePhase,
            awaiting: ReverseApproval? = null,
            cancellable: Boolean = true,
        ) = ReverseSwapStatus.UnderWay(phase, awaiting, cancellable)

        assertEquals(ReverseSwapStatus.Previewed, previewed.status)
        assertEquals(underWay(ReversePhase.ACCEPTING), accepted.status)
        val funding = accepted.copy(phase = ReversePhase.AWAITING_FUNDING)
        assertEquals(underWay(ReversePhase.AWAITING_FUNDING, ReverseApproval.FUNDING), funding.status)
        val ready = settling.copy(phase = ReversePhase.AWAITING_READY)
        assertEquals(underWay(ReversePhase.AWAITING_READY, ReverseApproval.SETTLEMENT), ready.status)
        val cancelled = settling.copy(phase = ReversePhase.REFUND_WAIT, cancelRequested = true)
        assertEquals(underWay(ReversePhase.REFUND_WAIT, cancellable = false), cancelled.status)
        assertEquals(underWay(ReversePhase.RECEIVING, cancellable = false), sweeping.status)
        assertEquals(ReverseSwapStatus.Over(ReverseSwapResult.REFUNDED), refunded.status)
        assertEquals(Usdc6.ofMicros(1_002_506), refunded.debit)
        assertEquals(Usdc6.ofMicros(1_000_000), previewed.copy(cost = null).debit)
    }

    @Test
    fun theTestnetDeploymentWritesExactlyWhatReverseRecordsKept() {
        assertEquals(TESTNET_JSON, storeJson.encodeToString(SwapDeployment.serializer(), TESTNET))
        assertEquals(TESTNET, storeJson.decodeFromString(SwapDeployment.serializer(), TESTNET_JSON))
        val local = TESTNET.copy(makerUrl = Url("http://127.0.0.1:8787"), escrowConfirmations = 12)
        val localJson = storeJson.encodeToString(SwapDeployment.serializer(), local)
        assertTrue(""""makerUrl":"http://127.0.0.1:8787"""" in localJson && """"confirmations":12""" in localJson)
        assertEquals(local, storeJson.decodeFromString(SwapDeployment.serializer(), localJson))
    }

    @Test
    fun aSwapIdIsItsContractWordWrittenLowercase() {
        val id = storeJson.decodeFromString(SwapId.serializer(), "\"0x${"AB".repeat(32)}\"")

        assertEquals("\"0x${"ab".repeat(32)}\"", storeJson.encodeToString(SwapId.serializer(), id))
        assertEquals(SwapId.of(ByteArray(32) { 0xab.toByte() }), id)
        for (bad in listOf("\"0x5c\"", "\"${"ab".repeat(32)}\"", "\"0x${"zz".repeat(32)}\"")) {
            assertFailsWith<IllegalArgumentException> { storeJson.decodeFromString(SwapId.serializer(), bad) }
        }
    }

    @Test
    fun amountsAreBaseUnitsWrittenInDecimal() {
        val quote = storeJson.decodeFromString(SwapQuote.serializer(), QUOTE)

        assertEquals(Usdc6.ofMicros(1_000_000), quote.amount)
        assertEquals(QUOTE, storeJson.encodeToString(SwapQuote.serializer(), quote))
        val cost = ReverseFundingCost(Usdc6.ofMicros(1_002_506), Usdc6.ofMicros(2_506), Usdc6.ofMicros(1))
        assertEquals(
            """{"debit":"1002506","railgunFee":"2506","broadcasterFee":"1"}""",
            storeJson.encodeToString(ReverseFundingCost.serializer(), cost),
        )
        assertFailsWith<IllegalArgumentException> {
            storeJson.decodeFromString(SwapQuote.serializer(), QUOTE.replace("\"1000000\"", "\"1.5\""))
        }
    }

    @Test
    fun anAddressKeptInMixedCaseReadsTheSameAndIsWrittenLowercase() {
        val checksummed = QUOTE.replace(MAKER, "0x2bac02B5032e9092493814c705F156B49E288922")
        val quote = storeJson.decodeFromString(SwapQuote.serializer(), checksummed)

        assertEquals(storeJson.decodeFromString(SwapQuote.serializer(), QUOTE), quote)
        assertEquals(QUOTE, storeJson.encodeToString(SwapQuote.serializer(), quote))
    }

    @Test
    fun aReverseRecordsSharesNoteAndAccountReadTypedAndWriteBackAsKept() {
        val record = storeJson.decodeFromString(ReverseSwapRecord.serializer(), REVERSE_STATES.last())

        assertEquals(SwapShare.parse(hex(64, 1)), record.userShare)
        assertEquals(SwapShare.parse(hex(64, 2)), record.quote.terms.makerShare)
        assertEquals(NoteCommitment.parse(hex(32, 6)), record.quote.refundNote)
        assertEquals(JointAccountId.parse(ACCOUNT), record.account)
        assertEquals(REVERSE_STATES.last(), storeJson.encodeToString(ReverseSwapRecord.serializer(), record))
    }

    @Test
    fun malformedIdsSharesAndAccountsNeverRead() {
        val badTxId = FORWARD_STATES[2].replace(TX_HEX, "ab".repeat(31))
        val badShare = REVERSE_STATES.last().replace(""""userShare":"${hex(64, 1)}"""", """"userShare":"0x01"""")
        val badAccount = REVERSE_STATES.last().replace(ACCOUNT, "0a0b")

        assertFailsWith<IllegalArgumentException> { forward(badTxId) }
        assertFailsWith<IllegalArgumentException> {
            storeJson.decodeFromString(ReverseSwapRecord.serializer(), badShare)
        }
        assertFailsWith<IllegalArgumentException> {
            storeJson.decodeFromString(ReverseSwapRecord.serializer(), badAccount)
        }
        assertFailsWith<IllegalArgumentException> { ZcashTxId.parse("0x" + "ab".repeat(31)) }
    }

    @Test
    fun aTransactionIdIsKeptLowercaseAsWalletsShowIt() {
        assertEquals("ab".repeat(32), ZcashTxId.parse("AB".repeat(32)).hex)
    }

    @Test
    fun theZcashNetworkAndConfirmationsAtTheirDefaultsAreNotWritten() {
        assertEquals(TESTNET_JSON, storeJson.encodeToString(SwapDeployment.serializer(), TESTNET))
        val mainnet = TESTNET.copy(zcashNetwork = SwapZcashNetwork.MAINNET, zcashConfirmations = 10)
        val json = storeJson.encodeToString(SwapDeployment.serializer(), mainnet)
        assertTrue(""""zcashNetwork":"mainnet"""" in json && """"zcashConfirmations":10""" in json)
        assertEquals(mainnet, storeJson.decodeFromString(SwapDeployment.serializer(), json))
    }

    private fun forward(json: String) = storeJson.decodeFromString(AtomicSwapRecord.serializer(), json)

    private fun <T> assertRoundTrip(
        serializer: KSerializer<T>,
        json: String
    ) {
        val decoded = storeJson.decodeFromString(serializer, json)
        assertEquals(json, storeJson.encodeToString(serializer, decoded))
    }

    private companion object {
        val storeJson =
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
            }

        fun hex(
            bytes: Int,
            value: Int
        ) = "0x" + value.toString(16).padStart(2, '0').repeat(bytes)

        const val MAKER = "0x2bac02b5032e9092493814c705f156b49e288922"
        const val CONTRACT = "0xbd9a37f47a988aefc4d80395727f41feb698e225"
        const val TOKEN = "0x5764d0044bef5aa839e0ddafe2073421101b9ed8"
        const val USER = "0x4444444444444444444444444444444444444444"
        val SWAP_ID = hex(32, 0x5c)
        val TX_HEX = "ab".repeat(32)
        val SWEEP_HEX = "cd".repeat(32)
        val PAYOUT_TX = hex(32, 0xa1)
        val FUNDING_TX = hex(32, 0x08)
        val ACCOUNT = "0a0b".repeat(8)

        val QUOTE =
            """{"quoteId":"${hex(32, 0x22)}","maker":"$MAKER","makerShare":"${hex(64, 0x0a)}",""" +
                """"makerProof":"${hex(64, 0x06)}","chainId":11155111,"contract":"$CONTRACT","token":"$TOKEN",""" +
                """"amount":"1000000","depositZat":202021,"expiresAt":1790000300}"""

        private val FORWARD_HEAD =
            """{"index":7,"quote":$QUOTE,"swapId":"$SWAP_ID","zcashHeight":4200000,"acceptedAt":1790000000,""" +
                """"receives":"977550""""

        /** A swap as the store keeps it: accepted, depositing, deposited by an earlier build and this one, and paid. */
        val FORWARD_STATES =
            listOf(
                "$FORWARD_HEAD}",
                """$FORWARD_HEAD,"depositAttempted":true,"maxTotalZat":212021}""",
                """$FORWARD_HEAD,"depositAttempted":true,"depositTxId":"$TX_HEX","maxTotalZat":212021}""",
                """$FORWARD_HEAD,"depositAttempted":true,"depositTxId":"$TX_HEX","maxTotalZat":212021,""" +
                    """"deposit":{"txId":"$TX_HEX","raw":"0400","expiryHeight":4200040},"relayerFee":"20000",""" +
                    """"railgunKeys":"BIP85"}""",
                """$FORWARD_HEAD,"depositAttempted":true,"depositTxId":"$TX_HEX","outcome":{"type":"paid"},""" +
                    """"finishedAt":1790003000,"payoutTx":"$PAYOUT_TX","maxTotalZat":212021,""" +
                    """"deposit":{"txId":"$TX_HEX","raw":"0400","expiryHeight":4200040},"relayerFee":"20000",""" +
                    """"railgunKeys":"BIP85"}""",
                """$FORWARD_HEAD,"depositAttempted":true,"depositTxId":"$TX_HEX",""" +
                    """"outcome":{"type":"refunded","sweepTxId":"$SWEEP_HEX","cause":"NOT_CLAIMED_IN_TIME"},""" +
                    """"finishedAt":1790009000,"maxTotalZat":212021,""" +
                    """"deposit":{"txId":"$TX_HEX","raw":"0400","expiryHeight":4200040},""" +
                    """"sweep":{"txId":"$SWEEP_HEX","raw":"0500","expiryHeight":4200140},"relayerFee":"20000"}""",
                """$FORWARD_HEAD,"outcome":{"type":"nothing_sent","cause":"QUOTE_EXPIRED"},"finishedAt":1790000001}""",
            )

        val FORWARD =
            """
            {"index":3,"quote":{"quoteId":"${hex(32, 0x22)}","maker":"0x2bac02B5032e9092493814c705F156B49E288922",
             "makerShare":"${hex(64, 0x0a)}","makerProof":"${hex(64, 0x06)}","chainId":11155111,
             "contract":"0xbd9a37f47a988aefc4d80395727f41feb698e225",
             "token":"0x5764D0044bef5AA839E0dDafE2073421101B9Ed8",
             "amount":"1000000","depositZat":202021,"expiresAt":1790000300},
             "swapId":"${hex(32, 0x5c)}","zcashHeight":4200000,"acceptedAt":1790000000,
             "receives":"977550","depositAttempted":true,"depositTxId":"${"ab".repeat(32)}",
             "outcome":{"type":"refunded","sweepTxId":"${"cd".repeat(32)}","cause":"MAKER_CANCELLED"},
             "finishedAt":1790003000,"maxTotalZat":1000000}
            """.trimIndent()

        val TESTNET =
            SwapDeployment(
                makerUrl = Url("https://zecswap-testnet.pepeman931.workers.dev/maker"),
                relayerUrl = Url("https://zecswap-testnet.pepeman931.workers.dev/relayer"),
                rpcUrl = Url("https://ethereum-sepolia-rpc.publicnode.com"),
                chainId = ChainId(11_155_111),
                contract = Address.parse(CONTRACT),
                token = Address.parse("0x5764D0044bef5AA839E0dDafE2073421101B9Ed8"),
                railgunProxy = Address.parse("0xeCFCf3b4eC647c4Ca6D49108b311b7a7C9543fea"),
                maker = Address.parse(MAKER),
                relayer = Address.parse("0xd9633572041886fa7584a2e12f36c8c7f1126412"),
                maxRelayerFee = Usdc6.ofMicros(100_000),
            )

        /** The hosted testnet as every reverse record so far keeps it. */
        val TESTNET_JSON =
            """{"makerUrl":"https://zecswap-testnet.pepeman931.workers.dev/maker",""" +
                """"relayerUrl":"https://zecswap-testnet.pepeman931.workers.dev/relayer",""" +
                """"rpcUrl":"https://ethereum-sepolia-rpc.publicnode.com","chainId":11155111,""" +
                """"contract":"$CONTRACT","token":"$TOKEN","railgun":"0xecfcf3b4ec647c4ca6d49108b311b7a7c9543fea",""" +
                """"maker":"$MAKER","relayer":"0xd9633572041886fa7584a2e12f36c8c7f1126412","maxRefundFee":"100000"}"""

        private val REVERSE_HEAD =
            """{"index":4,"deployment":$TESTNET_JSON,"quote":{"terms":{"quoteId":"${hex(32, 1)}","maker":"$MAKER",""" +
                """"makerShare":"${hex(64, 2)}","makerProof":"${hex(64, 7)}",""" +
                """"chainId":11155111,"contract":"$CONTRACT",""" +
                """"token":"$TOKEN","amount":"1000000","depositZat":100000,"expiresAt":1790000300},"user":"$USER",""" +
                """"refundNote":"${hex(32, 6)}","fundingDeadline":1790000400,"readyDeadline":1790002000,""" +
                """"refundAfter":1790004000},"swapId":"$SWAP_ID","userShare":"${hex(64, 1)}",""" +
                """"acceptance":{"userShare":"${hex(64, 1)}","userProof":"${hex(64, 3)}",""" +
                """"viewingKeys":"${hex(64, 4)}"},""" +
                """"birthday":3900000"""

        private const val COST = """{"debit":"1002506","railgunFee":"2506"}"""
        private val FUNDING = """{"raw":"0x02f8","txId":"$FUNDING_TX","cost":$COST}"""
        private val AUTHORIZATION = """{"swapId":"$SWAP_ID","deadline":1790001000,"signature":"${hex(65, 9)}"}"""
        private val PAYOUT =
            """{"swapId":"$SWAP_ID","note":{"npk":"${hex(32, 0x11)}","encryptedBundle":["${hex(32, 0x12)}",""" +
                """"${hex(32, 0x13)}","${hex(32, 0x14)}"],"shieldKey":"${hex(32, 0x15)}"},"fee":"20000",""" +
                """"signature":"${hex(65, 0x16)}"}"""

        /**
         * A conversion as the store keeps it: previewed, accepted, sent, settling, and refunded, by the build that
         * started keeping the Railgun keys and when the user went ahead, and by the one before it.
         */
        val REVERSE_STATES =
            listOf(
                """$REVERSE_HEAD,"phase":"QUOTED","cost":$COST,"railgunKeys":"BIP85"}""",
                """$REVERSE_HEAD,"cost":$COST,"railgunKeys":"BIP85","acceptedAt":1790000100}""",
                """$REVERSE_HEAD,"account":"$ACCOUNT","phase":"SENDING_USDC","cost":$COST,"funding":$FUNDING}""",
                """$REVERSE_HEAD,"account":"$ACCOUNT","phase":"SETTLING","cost":$COST,"funding":$FUNDING,""" +
                    """"ready":$AUTHORIZATION,"receiveEstimate":{"availableZat":100000,"feeZat":10000},""" +
                    """"railgunKeys":"BIP85","acceptedAt":1790000100}""",
                """$REVERSE_HEAD,"account":"$ACCOUNT","phase":"REFUNDED","cost":$COST,"funding":$FUNDING,""" +
                    """"cancelRequested":true,"refundLock":$AUTHORIZATION,"payout":$PAYOUT,""" +
                    """"railgunKeys":"BIP85","acceptedAt":1790000100}""",
            )

        val REVERSE =
            """
            {"index":4,"deployment":{"makerUrl":"https://maker","relayerUrl":"https://relayer","rpcUrl":"https://rpc",
             "chainId":11155111,"contract":"${hex(20, 4)}","token":"${hex(20, 3)}","railgun":"${hex(20, 6)}",
             "maker":"${hex(20, 2)}","relayer":"${hex(20, 5)}","maxRefundFee":"100000"},
             "quote":{"terms":{"quoteId":"${hex(32, 1)}","maker":"${hex(20, 2)}","makerShare":"${hex(64, 2)}",
              "makerProof":"${hex(64, 7)}","chainId":11155111,"contract":"${hex(20, 4)}","token":"${hex(20, 3)}",
              "amount":"1000000","depositZat":100000,"expiresAt":2000},
             "user":"${hex(20, 1)}","refundNote":"${hex(32, 6)}","fundingDeadline":2000,"readyDeadline":4000,
             "refundAfter":6000},
             "swapId":"${hex(32, 8)}","userShare":"${hex(64, 1)}",
             "acceptance":{"userShare":"${hex(64, 1)}","userProof":"${hex(64, 0)}","viewingKeys":"${hex(64, 0)}"},
             "birthday":100,"account":"${"0a".repeat(16)}","phase":"RECEIVING",
             "cost":{"debit":"1002506","railgunFee":"2506"},
             "funding":{"raw":"0x1234","txId":"${hex(32, 8)}","cost":{"debit":"1002506","railgunFee":"2506"}},
             "rescuePending":true,
             "receive":{"txId":"${"09".repeat(32)}","raw":"abcd","expiryHeight":300,"receivedZat":90000,"feeZat":10000},
             "receiveEstimate":{"availableZat":100000,"feeZat":10000},"receiveConfirmations":1}
            """.trimIndent()
    }
}
