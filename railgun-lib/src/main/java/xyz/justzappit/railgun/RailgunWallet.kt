// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex

/** Railgun's wallet SDK in a hidden WebView, one engine and wallet per page; `debug` makes the page inspectable. */
class RailgunWallet(
    context: Context,
    private val debug: Boolean = false,
) {
    private val host = RailgunWebViewHost(context.applicationContext, debug)

    @Volatile
    private var opened: RailgunSession? = null

    val events: SharedFlow<RailgunEvent> get() = host.events

    /** The wallet [open] opened, until its page closes or its renderer dies. */
    val session: RailgunSession? get() = opened?.takeIf { it.isOpen }

    /** Starts the engine on a fresh page and opens [mnemonic]'s wallet; the page closes if any step fails. */
    suspend fun open(
        network: RailgunNetwork,
        encryptionKey: ByteArray,
        mnemonic: CharArray,
        gasAccountKey: ByteArray?,
    ): RailgunSession {
        require(encryptionKey.size == ENCRYPTION_KEY_BYTES) { "the key must be $ENCRYPTION_KEY_BYTES bytes" }
        opened = null
        val page = host.load()
        var session: RailgunSession? = null
        try {
            val started =
                page.request(
                    RailgunMethod.START,
                    StartParams(network, network.rpcUrls, listOf(RailgunEndpoints.POI_NODE_URL), debug),
                    StartParams.serializer(),
                    StartResult.serializer(),
                )
            val wallet =
                page.request(
                    RailgunMethod.OPEN_WALLET,
                    OpenWalletParams(encryptionKey.toHex(), mnemonic.concatToString()),
                    OpenWalletParams.serializer(),
                    OpenWalletResult.serializer(),
                )
            val gasAccount =
                gasAccountKey?.let {
                    page.request(
                        RailgunMethod.SET_GAS_ACCOUNT,
                        SetGasAccountParams("0x${it.toHex()}"),
                        SetGasAccountParams.serializer(),
                        RailgunGasAccount.serializer(),
                    )
                }
            session = RailgunSession(page, network, wallet.address, started.fees, gasAccount?.address)
            opened = session
            return session
        } finally {
            if (session == null) withContext(NonCancellable) { page.close() }
        }
    }

    suspend fun close() {
        opened = null
        host.close()
    }

    /** Closes the page and deletes everything it stored, wallets included. */
    suspend fun wipe() {
        opened = null
        host.wipe()
    }

    private companion object {
        const val ENCRYPTION_KEY_BYTES = 32
    }
}

/** One page's engine and wallet. Every call throws [RailgunException.Disconnected] once the page is gone. */
class RailgunSession internal constructor(
    internal val page: RailgunPage,
    val network: RailgunNetwork,
    val address: RailgunAddress,
    val fees: RailgunFees,
    /** The account that pays gas and sends in place of a broadcaster, on Sepolia. */
    val gasAccountAddress: Address?,
) {
    val isOpen: Boolean get() = page.isOpen

    /** Syncs to the chain's tip, asks the screening nodes about new notes, and returns balances. */
    suspend fun refresh(): RailgunBalances =
        page
            .request(RailgunMethod.REFRESH, EMPTY, BALANCES)
            .mapNotNull { (name, amounts) ->
                RailgunBalanceBucket.entries.firstOrNull { it.wireName == name }?.let { bucket ->
                    bucket to amounts.map { RailgunTokenAmount(it.token, it.amount) }
                }
            }.toMap()
            .let(::RailgunBalances)

    /** Proves [transfer] and has the gas account sign it; nothing is sent. */
    suspend fun sign(transfer: RailgunTransfer): RailgunSignedTransaction =
        when (val to = transfer.to) {
            is RailgunDestination.Private -> {
                signed(
                    RailgunMethod.TRANSFER,
                    TransferParams(to.address, transfer.token, transfer.amount),
                    TransferParams.serializer(),
                )
            }

            is RailgunDestination.Public -> {
                signed(
                    RailgunMethod.UNSHIELD,
                    UnshieldParams(to.address, transfer.token, transfer.amount),
                    UnshieldParams.serializer(),
                )
            }
        }

    suspend fun reverseCost(request: RailgunReverseCostRequest): RailgunReverseCost =
        page.request(
            RailgunMethod.REVERSE_COST,
            request,
            RailgunReverseCostRequest.serializer(),
            RailgunReverseCost.serializer(),
        )

    suspend fun prepareReverse(request: RailgunReverseRequest): RailgunReverseTransaction =
        page
            .request(
                RailgunMethod.PREPARE_REVERSE,
                request,
                RailgunReverseRequest.serializer(),
                RailgunReverseTransaction.serializer(),
            ).also {
                require(it.to == request.relayAdapt && it.value.signum() == 0) { "unexpected funding destination" }
            }

    internal suspend fun <P> signed(
        method: RailgunMethod,
        params: P,
        serializer: SerializationStrategy<P>,
    ): RailgunSignedTransaction {
        val signed = page.request(method, params, serializer, SignedResult.serializer())
        requireHashOf(signed.raw, signed.txHash)
        return RailgunSignedTransaction(signed.raw, signed.txHash, signed.from, signed.nonce)
    }

    internal companion object {
        val EMPTY = JsonObject(emptyMap())
        val BALANCES = MapSerializer(String.serializer(), ListSerializer(WireTokenAmount.serializer()))

        // The hash the app logs must be the hash of what goes out.
        fun requireHashOf(
            raw: String,
            txHash: TxHash
        ) {
            val matches =
                try {
                    keccak256(raw.hexToBytes()).contentEquals(txHash.bytes)
                } catch (ignored: IllegalArgumentException) {
                    false
                }
            if (!matches) throw RailgunException.Protocol("the page's transaction and its hash disagree")
        }
    }
}

internal suspend fun <R> RailgunPage.request(
    method: RailgunMethod,
    params: JsonElement,
    result: DeserializationStrategy<R>,
): R =
    call(method, params).let {
        try {
            RailgunProtocol.json.decodeFromJsonElement(result, it)
        } catch (e: IllegalArgumentException) {
            throw RailgunException.Protocol("the page's result is unreadable", e)
        }
    }

internal suspend fun <P, R> RailgunPage.request(
    method: RailgunMethod,
    params: P,
    paramsSerializer: SerializationStrategy<P>,
    result: DeserializationStrategy<R>,
): R = request(method, RailgunProtocol.json.encodeToJsonElement(paramsSerializer, params), result)
