// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.div
import xyz.justzappit.evm.math.times
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.evm.util.toHex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EoaSignerTest {
    private val sentRequests = mutableListOf<JsonObject>()
    private val sentRawTxs = mutableListOf<String>()

    private val client =
        HttpClient(
            MockEngine { request ->
                val bodyBytes = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                val payload = Json.parseToJsonElement(bodyBytes.decodeToString()) as JsonObject
                sentRequests += payload
                val response =
                    when (val method = payload["method"]!!.jsonPrimitive.content) {
                        "eth_getTransactionCount" -> {
                            """{"jsonrpc":"2.0","id":1,"result":"0x05"}"""
                        }

                        "eth_maxPriorityFeePerGas" -> {
                            """{"jsonrpc":"2.0","id":1,"result":"0x3b9aca00"}"""
                        }

                        "eth_getBlockByNumber" -> {
                            """{"jsonrpc":"2.0","id":1,"result":{"number":"0x100","timestamp":"0x68000000","baseFeePerGas":"0x77359400"}}"""
                        }

                        "eth_estimateGas" -> {
                            """{"jsonrpc":"2.0","id":1,"result":"0x5208"}"""
                        }

                        "eth_sendRawTransaction" -> {
                            val raw = payload["params"]!!.toString().substringAfter('"').substringBefore('"')
                            sentRawTxs += raw
                            """{"jsonrpc":"2.0","id":1,"result":"$RETURNED_TX_HASH_HEX"}"""
                        }

                        else -> {
                            error("Unexpected method: $method")
                        }
                    }
                respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(ContentNegotiation) { json() }
        }

    private val rpc = BaseRpcClient(client, "http://mock/rpc")

    @AfterTest
    fun shutdown() {
        client.close()
    }

    @Test
    fun `sendTransaction signs broadcasts and the broadcast raw tx ecrecovers to the EOA`() =
        runTest {
            val account = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
            val signer = EoaSigner(rpc, chainId = ChainId.BASE_SEPOLIA, account = account)

            val txHash =
                signer.sendTransaction(
                    to = Address.parse("0x000000000000000000000000000000000000dEaD"),
                    value = Wei.ofLong(1_000),
                )
            assertEquals(TxHash.fromHex(RETURNED_TX_HASH_HEX), txHash)
            assertEquals(1, sentRawTxs.size)

            val signedTxHex = sentRawTxs.first().removePrefix("0x")
            val signedTxBytes = signedTxHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            assertEquals(0x02.toByte(), signedTxBytes[0])

            // Parse out (yParity, r, s) from the tail of the signed payload, then reconstruct the
            // unsigned signing-payload to recompute the hash, then ecrecover.
            val unsigned =
                unsignedSigningPayload(
                    chainId = ChainId.BASE_SEPOLIA,
                    nonce = bigIntegerValueOf(5),
                    tip = BigInteger("1000000000"),
                    baseFee = BigInteger("2000000000"),
                    gasLimit =
                        bigIntegerValueOf(21_000) *
                            bigIntegerValueOf(120) /
                            bigIntegerValueOf(100),
                    to = Address.parse("0x000000000000000000000000000000000000dEaD"),
                    value = bigIntegerValueOf(1_000),
                    data = byteArrayOf(),
                )
            val signingHash = keccak256(unsigned)

            val sig = parseSignatureFromSignedTx(signedTxBytes)
            val recovered = EcdsaSigner.recoverPublicKeyBytes(sig.yParity.toInt(), sig.r, sig.s, signingHash)!!
            val pubXY = recovered.copyOfRange(1, recovered.size)
            val recoveredAddress = "0x" + keccak256(pubXY).copyOfRange(12, 32).toHex()

            assertEquals(account.address.lowercaseHex, recoveredAddress.lowercase())
        }

    private fun parseSignatureFromSignedTx(signedBytes: ByteArray): EcdsaSignature {
        val rlp = signedBytes.drop(1).toByteArray()
        val items = decodeRlpList(rlp)
        val yParity = items[items.size - 3].toBigInt().toInt().toByte()
        val r = items[items.size - 2].toBigInt()
        val s = items[items.size - 1].toBigInt()
        return EcdsaSignature(r = r, s = s, yParity = yParity)
    }

    private fun unsignedSigningPayload(
        chainId: ChainId,
        nonce: BigInteger,
        tip: BigInteger,
        baseFee: BigInteger,
        gasLimit: BigInteger,
        to: Address,
        value: BigInteger,
        data: ByteArray,
    ): ByteArray {
        val maxFee = baseFee.multiply(bigIntegerValueOf(2)).add(tip)
        val tx =
            Eip1559Tx(
                chainId = chainId,
                nonce =
                    xyz.justzappit.evm.types
                        .Nonce(nonce),
                maxPriorityFeePerGas = Wei(tip),
                maxFeePerGas = Wei(maxFee),
                gasLimit =
                    xyz.justzappit.evm.types
                        .Gas(gasLimit),
                to = to,
                value = Wei(value),
                data = data,
            )
        return tx.signingPayload()
    }

    private fun ByteArray.toBigInt(): BigInteger =
        if (isEmpty()) bigIntegerZero else BigInteger(1, this)

    private fun decodeRlpList(bytes: ByteArray): List<ByteArray> {
        var i = 0
        val firstByte = bytes[0].toInt() and 0xff
        val payloadStart: Int
        val payloadEnd: Int
        when {
            firstByte in 0xc0..0xf7 -> {
                payloadStart = 1
                payloadEnd = 1 + (firstByte - 0xc0)
            }

            firstByte in 0xf8..0xff -> {
                val lenOfLen = firstByte - 0xf7
                val lenBytes = bytes.copyOfRange(1, 1 + lenOfLen)
                val len = BigInteger(1, lenBytes).toInt()
                payloadStart = 1 + lenOfLen
                payloadEnd = payloadStart + len
            }

            else -> {
                error("Top-level item is not a list (first byte=0x${firstByte.toString(16)})")
            }
        }
        val out = mutableListOf<ByteArray>()
        i = payloadStart
        while (i < payloadEnd) {
            val b = bytes[i].toInt() and 0xff
            when {
                b < 0x80 -> {
                    out += byteArrayOf(bytes[i])
                    i += 1
                }

                b in 0x80..0xb7 -> {
                    val len = b - 0x80
                    out += bytes.copyOfRange(i + 1, i + 1 + len)
                    i += 1 + len
                }

                b in 0xb8..0xbf -> {
                    val lenOfLen = b - 0xb7
                    val len = BigInteger(1, bytes.copyOfRange(i + 1, i + 1 + lenOfLen)).toInt()
                    out += bytes.copyOfRange(i + 1 + lenOfLen, i + 1 + lenOfLen + len)
                    i += 1 + lenOfLen + len
                }

                b in 0xc0..0xf7 -> {
                    val len = b - 0xc0
                    // Skip nested list — represent as empty bytes for our purposes
                    out += byteArrayOf()
                    i += 1 + len
                }

                else -> {
                    val lenOfLen = b - 0xf7
                    val len = BigInteger(1, bytes.copyOfRange(i + 1, i + 1 + lenOfLen)).toInt()
                    out += byteArrayOf()
                    i += 1 + lenOfLen + len
                }
            }
        }
        return out
    }

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"

        // Synthetic but valid 32-byte tx hash used by the mock RPC.
        private const val RETURNED_TX_HASH_HEX =
            "0xfeedfacefeedfacefeedfacefeedfacefeedfacefeedfacefeedfacefeedface"
    }
}
