// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.Gas
import xyz.justzappit.evm.types.Nonce
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Eip1559TxTest {
    @Test
    fun `signed tx ecrecovers to the signer address`() {
        val key = EvmKeyDerivation.derive(MNEMONIC, accountIndex = 0)
        val tx = sampleTx(toAddress = "0x000000000000000000000000000000000000dEaD")

        val priv = BigInteger(1, key.privateKey)
        val sig = EcdsaSigner.sign(tx.signingHash(), priv)
        val signed = tx.encodeSigned(sig)

        assertEquals(0x02.toByte(), signed[0])

        val recovered =
            EcdsaSigner.recoverPublicKeyBytes(
                sig.yParity.toInt(),
                sig.r,
                sig.s,
                tx.signingHash(),
            )
        assertNotNull(recovered)
        val pubXY = recovered.copyOfRange(1, recovered.size)
        val recoveredAddress = "0x" + keccak256(pubXY).copyOfRange(12, 32).toHex()
        assertEquals(key.address.lowercaseHex, recoveredAddress.lowercase())
    }

    @Test
    fun `signing payload matches a hand-rolled RLP encoding of a known fixture`() {
        // Catches the failure mode where Rlp.kt drifts from canonical RLP yet still round-trips
        // through our own ecrecover (since the same wrong encoding is used on both sides). The
        // fixture below is a fixed EIP-1559 tx; the expected signing payload is built by hand,
        // byte-for-byte, without going through Rlp.kt — so any divergence here means Rlp.kt is
        // producing bytes that no other EVM client will accept.
        //
        // Inputs:
        //   chainId=84532 (Base Sepolia, two-byte big-endian = 0x014a34)
        //   nonce=2, maxPriorityFee=1_000_000_000 (0x3b9aca00),
        //   maxFee=2_000_000_000 (0x77359400), gasLimit=21000 (0x5208),
        //   to=0x000000000000000000000000000000000000dEaD, value=0, data=empty, accessList=[]
        val tx =
            Eip1559Tx(
                chainId = ChainId.BASE_SEPOLIA,
                nonce = Nonce(bigIntegerValueOf(2)),
                maxPriorityFeePerGas = Wei.ofLong(1_000_000_000L),
                maxFeePerGas = Wei.ofLong(2_000_000_000L),
                gasLimit = Gas(bigIntegerValueOf(21_000L)),
                to = Address.parse("0x000000000000000000000000000000000000dEaD"),
                value = Wei.ZERO,
                data = byteArrayOf(),
            )
        val handBuiltPayload =
            handRollEip1559SigningPayload(
                chainIdHex = "014a34",
                nonceHex = "02",
                tipHex = "3b9aca00",
                maxFeeHex = "77359400",
                gasLimitHex = "5208",
                toHex = "000000000000000000000000000000000000dead",
            )
        assertEquals(handBuiltPayload.toHex(), tx.signingPayload().toHex())
    }

    /**
     * Builds the EIP-1559 signing payload (0x02 || rlp([chainId, nonce, tip, maxFee, gasLimit,
     * to, value=0, data=empty, accessList=[]])) byte-by-byte, using a literal RLP encoder. All
     * inputs are short (≤ 4 bytes) so each field encodes to a 0x80-prefixed short string and
     * fits in the 0xc0..0xf7 short-list form for the outer list.
     */
    private fun handRollEip1559SigningPayload(
        chainIdHex: String,
        nonceHex: String,
        tipHex: String,
        maxFeeHex: String,
        gasLimitHex: String,
        toHex: String,
    ): ByteArray {
        fun rlpInt(hex: String): String {
            // EIP-1559 / RLP integers are encoded with no leading zero bytes.
            val trimmed = hex.trimStart('0').let { if (it.length % 2 == 1) "0$it" else it }
            if (trimmed.isEmpty()) return "80" // empty byte string
            val byteLen = trimmed.length / 2
            return if (byteLen == 1 && trimmed.toInt(16) < 0x80) trimmed else (0x80 + byteLen).toString(16) + trimmed
        }

        fun rlpAddress(hex20: String) = "94" + hex20 // 0x80 + 20 = 0x94

        fun rlpEmpty() = "80"

        fun rlpEmptyList() = "c0"

        val payload = (
            rlpInt(chainIdHex) + rlpInt(nonceHex) + rlpInt(tipHex) + rlpInt(maxFeeHex) +
                rlpInt(gasLimitHex) + rlpAddress(toHex) + rlpEmpty() + rlpEmpty() + rlpEmptyList()
        )
        val payloadLen = payload.length / 2
        val outer =
            if (payloadLen <= 0x37) {
                (0xc0 + payloadLen).toString(16) + payload
            } else {
                val lenHex = payloadLen.toString(16).let { if (it.length % 2 == 1) "0$it" else it }
                val lenOfLen = lenHex.length / 2
                (0xf7 + lenOfLen).toString(16) + lenHex + payload
            }
        return ("02" + outer).hexToBytes()
    }

    @Test
    fun `non-empty data is included in encoding`() {
        val data = "deadbeefcafe".hexToBytes()
        val tx = sampleTx(callData = data)
        val payload = tx.signingPayload().toHex()
        assertTrue(payload.contains("86deadbeefcafe"), "expected data with length prefix in payload, got $payload")
    }

    private fun sampleTx(
        toAddress: String = "0x000000000000000000000000000000000000dEaD",
        callData: ByteArray = byteArrayOf(),
    ) = Eip1559Tx(
        chainId = ChainId.BASE_SEPOLIA,
        nonce = Nonce(bigIntegerValueOf(7)),
        maxPriorityFeePerGas = Wei.ofLong(1_000_000L),
        maxFeePerGas = Wei.ofLong(50_000_000L),
        gasLimit = Gas(bigIntegerValueOf(100_000L)),
        to = Address.parse(toAddress),
        value = Wei.ofLong(123_456_789L),
        data = callData,
    )

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
    }
}
