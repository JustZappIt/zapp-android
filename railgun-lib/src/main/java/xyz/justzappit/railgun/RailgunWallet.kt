// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.railgun

import android.content.Context
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigInteger

/**
 * Railgun's wallet SDK, run in a hidden WebView. One engine and one wallet per WebView: after a
 * [RailgunEvent.Disconnected], [start] and [openWallet] again. `debug` exposes the WebView to
 * chrome://inspect and forwards the SDK's logs as [RailgunEvent.Log].
 */
@Suppress("TooManyFunctions")
class RailgunWallet(
    context: Context,
    private val debug: Boolean = false,
) {
    private val host = RailgunWebViewHost(context.applicationContext, debug)

    val events: SharedFlow<RailgunEvent> get() = host.events

    suspend fun start(
        network: RailgunNetwork,
        rpcUrls: List<String>,
        poiNodeUrls: List<String>,
    ) {
        host.call(
            "start",
            buildJsonObject {
                put("network", network.wireName)
                putJsonArray("rpcUrls") { rpcUrls.forEach(::add) }
                putJsonArray("poiNodeUrls") { poiNodeUrls.forEach(::add) }
                put("debug", debug)
            }
        )
    }

    /**
     * Opens the Railgun wallet [mnemonic] derives at index 0, the one Railway opens from the same
     * words, and returns its 0zk address. [encryptionKey] (32 bytes) encrypts it in the WebView's
     * storage; [creationBlock] skips the notes before it, so pass it only for a wallet known to be
     * newer.
     */
    suspend fun openWallet(
        encryptionKey: ByteArray,
        mnemonic: String,
        creationBlock: Long? = null,
    ): String {
        require(encryptionKey.size == ENCRYPTION_KEY_BYTES) { "the encryption key must be 32 bytes" }
        val result =
            host.call(
                "openWallet",
                buildJsonObject {
                    put("encryptionKey", encryptionKey.toHex())
                    put("mnemonic", mnemonic)
                    creationBlock?.let { put("creationBlock", it) }
                }
            )
        val address = result.jsonObject.getValue("address")
        return address.jsonPrimitive.content
    }

    /** Syncs to the chain's tip, asks the screening nodes about new notes, and returns balances. */
    suspend fun refresh(): RailgunBalances =
        RailgunProtocol.decodeBalances(host.call("refresh", JsonObject(emptyMap())))

    /**
     * A funded account that pays gas and sends the transactions below itself, standing in for a
     * Railgun broadcaster while testnets have none. The page refuses one outside Sepolia: sending
     * from it ties its public address to every transaction.
     */
    suspend fun setGasAccount(privateKey: ByteArray): RailgunGasAccount =
        RailgunProtocol.decodeGasAccount(
            host.call("setGasAccount", buildJsonObject { put("privateKey", "0x${privateKey.toHex()}") })
        )

    suspend fun gasAccount(): RailgunGasAccount =
        RailgunProtocol.decodeGasAccount(host.call("gasAccount", JsonObject(emptyMap())))

    /** Wraps [amount] wei of the gas account's ETH and shields it into the open wallet. */
    suspend fun shieldBaseToken(amount: BigInteger): RailgunSent =
        RailgunProtocol.decodeSent(host.call("shield", buildJsonObject { put("amount", amount.toString()) }))

    /** Sends [amount] of [token] privately to the 0zk address [to]; proves in the WebView first. */
    suspend fun transfer(
        to: String,
        token: String,
        amount: BigInteger,
    ): RailgunSent = RailgunProtocol.decodeSent(host.call("transfer", transferParams(to, token, amount)))

    /** Withdraws [amount] of [token] to the public address [to]. */
    suspend fun unshield(
        to: String,
        token: String,
        amount: BigInteger,
    ): RailgunSent = RailgunProtocol.decodeSent(host.call("unshield", transferParams(to, token, amount)))

    suspend fun reverseCost(request: RailgunReverseCostRequest): RailgunReverseCost =
        Json.decodeFromJsonElement(
            RailgunReverseCost.serializer(),
            host.call(
                "reverseCost",
                Json.encodeToJsonElement(RailgunReverseCostRequest.serializer(), request).jsonObject
            )
        )

    suspend fun prepareReverse(request: RailgunReverseRequest): RailgunReverseTransaction =
        Json.decodeFromJsonElement(
            RailgunReverseTransaction.serializer(),
            host.call(
                "prepareReverse",
                Json.encodeToJsonElement(RailgunReverseRequest.serializer(), request).jsonObject
            )
        )

    suspend fun close() = host.close()

    private fun transferParams(
        to: String,
        token: String,
        amount: BigInteger,
    ) = buildJsonObject {
        put("to", to)
        put("token", token)
        put("amount", amount.toString())
    }

    private companion object {
        const val ENCRYPTION_KEY_BYTES = 32

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
