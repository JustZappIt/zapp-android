// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.deposits

import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerOne
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.math.plus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DepositHashesTest {
    @Test
    fun `the type hashes are the Deposits contract's and USDC's`() {
        // DEPOSIT_TYPEHASH() of the mainnet Deposits contract and RECEIVE_WITH_AUTHORIZATION_TYPEHASH() of USDC, as
        // the deployed contracts answer them; the rescue's is `cast keccak` of its type.
        assertEquals(
            "0x04d51e6505ce99d72e9a54e7878f2248b9ef749e1d53e60068d6a9edfe1a93ae",
            DepositsDomain.DEPOSIT_TYPEHASH.hex()
        )
        assertEquals(
            "0xf63e4f48c3dedcff7ed0b276bca13ef2b5390d4562b609bfbe1fafafb79e7bd1",
            DepositsDomain.RESCUE_TYPEHASH.hex()
        )
        assertEquals(
            "0xd099cc98ef71107a616c4f0f941f04c322d8e254fe26b3c6668db87aae413de8",
            TokenDomain.RECEIVE_WITH_AUTHORIZATION_TYPEHASH.hex()
        )
    }

    @Test
    fun `the deposit nonce is the contract's known answer`() {
        assertEquals(NONCE, VECTOR_DEPOSITS.depositNonce(VECTOR_NOTE, FEE, address(0x77)).hex())
    }

    @Test
    fun `the token's domain separator is the known answer`() {
        assertEquals(
            "0x71fb9e856a21ce6c4c42af22617f8c5f42bc79024fa4c2acf2f845f51140175b",
            VECTOR_TOKEN.separator().hex()
        )
    }

    @Test
    fun `the receive authorization digest is the known answer`() {
        assertEquals(
            "0x7b8010094423feaf967f85141de01415ea756128fbbcc9b5f548d4afa05404f9",
            VECTOR_TOKEN.receiveWithAuthorization(vectorAuthorization()).hex()
        )
    }

    @Test
    fun `the note commitment is the contract's noteCommitment`() {
        assertEquals(COMMITMENT, VECTOR_NOTE.commitment.hex())
    }

    @Test
    fun `the rescue digest is the known answer`() {
        assertEquals(
            "0xa417fb0cbab5c5f12b9ec4c5d409c4148463a48675c333823dc3ce034837fc2c",
            VECTOR_DEPOSITS
                .rescue(
                    from = SCALAR_ONE_ADDRESS,
                    note = VECTOR_NOTE,
                    relayer = address(0x77),
                    fee = FEE,
                    nonce = 0,
                    deadline = VALID_BEFORE,
                ).hex()
        )
    }

    @Test
    fun `the separators of USDC and tUSD are the ones the tokens report`() {
        // DOMAIN_SEPARATOR() of each, read with `cast call` on 2026-10-23, from their own name() and version().
        assertEquals(
            "0x06c37168a7db5138defc7866392bb87a741f9b3d104deb5094588ce041cae335",
            TokenDomain(
                "USD Coin",
                "2",
                ChainId.ETHEREUM_MAINNET,
                Address.parse("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")
            ).separator().hex()
        )
        assertEquals(
            "0x213081332edff57e3793dd54b7845a3c6ca4fcab305d4bef25eaa7bd476b286b",
            TokenDomain(
                "ZecSwap Test USD",
                "2",
                ChainId.ETHEREUM_SEPOLIA,
                Address.parse("0x64c4a5cb09fe0b706d4cf24dc0a45fff275d727c")
            ).separator().hex()
        )
    }

    @Test
    fun `the nonces are the ones the deployed Deposits contracts compute`() {
        // depositNonce(…) of the known-answer note and fee, for each network's relayer deposits account, and
        // noteCommitment(…), read with `cast call` from both deployments on 2026-10-23.
        val sepolia =
            DepositsDomain(ChainId.ETHEREUM_SEPOLIA, Address.parse("0x58324e9a866c9690aca08169d3c67630e84c9019"))
        val mainnet =
            DepositsDomain(ChainId.ETHEREUM_MAINNET, Address.parse("0xebc7508e20bd4467491ee7e7589d5c7d4c5e8cb6"))
        assertEquals(
            "0xbe2970b10d01883236e5590c158e6a38e96e52fca56d6460261a1f61db0d4747",
            sepolia.depositNonce(VECTOR_NOTE, FEE, Address.parse("0xedc4c88a3f6ae8130a2c18babbabd8100cc1a446")).hex()
        )
        assertEquals(
            "0xee6882889244d3493a3dee85bd32983cf143abdd71c48bcfbaa030df7d7f0f4a",
            mainnet.depositNonce(VECTOR_NOTE, FEE, Address.parse("0x6ae40b34c32d3feaa0eef53d7e4724160b7a2135")).hex()
        )
    }

    @Test
    fun `the nonce commits to the chain, the contract, every word of the note, the fee and the relayer`() {
        val nonce = VECTOR_DEPOSITS.depositNonce(VECTOR_NOTE, FEE, address(0x77))
        val others =
            listOf(
                VECTOR_DEPOSITS.copy(chainId = ChainId.ETHEREUM_MAINNET).depositNonce(VECTOR_NOTE, FEE, address(0x77)),
                VECTOR_DEPOSITS.copy(contract = address(0x12)).depositNonce(VECTOR_NOTE, FEE, address(0x77)),
                VECTOR_DEPOSITS.depositNonce(VECTOR_NOTE, FEE + bigIntegerOne, address(0x77)),
                VECTOR_DEPOSITS.depositNonce(VECTOR_NOTE, FEE, address(0x78)),
            ) +
                (0 until NOTE_WORDS).map { changed ->
                    VECTOR_DEPOSITS.depositNonce(noteWith(changed), FEE, address(0x77))
                }
        others.forEach { assertNotEquals(nonce.hex(), it.hex()) }
        assertEquals(others.size, others.map { it.hex() }.toSet().size)
    }

    @Test
    fun `the commitment commits to every word of the note, in order`() {
        val commitments = (0 until NOTE_WORDS).map { noteWith(it).commitment.hex() }
        commitments.forEach { assertNotEquals(COMMITMENT, it) }
        val swapped =
            DepositNote(repeated(0x22), listOf(repeated(0x44), repeated(0x33), repeated(0x55)), repeated(0x66))
        assertNotEquals(COMMITMENT, swapped.commitment.hex())
    }

    @Test
    fun `an authorization's digest commits to each of its fields and to the token's domain`() {
        val digest = VECTOR_TOKEN.receiveWithAuthorization(vectorAuthorization()).hex()
        val others =
            listOf(
                VECTOR_TOKEN.copy(name = "USD Coin"),
                VECTOR_TOKEN.copy(version = "1"),
                VECTOR_TOKEN.copy(chainId = ChainId.ETHEREUM_MAINNET),
                VECTOR_TOKEN.copy(token = address(0x89)),
            ).map { it.receiveWithAuthorization(vectorAuthorization()).hex() } +
                listOf(
                    vectorAuthorization(from = address(0x01)),
                    vectorAuthorization(to = address(0x12)),
                    vectorAuthorization(value = VALUE + bigIntegerOne),
                    vectorAuthorization(validAfter = 1),
                    vectorAuthorization(validBefore = VALID_BEFORE + 1),
                    vectorAuthorization(nonce = repeated(0x01)),
                ).map { VECTOR_TOKEN.receiveWithAuthorization(it).hex() }
        others.forEach { assertNotEquals(digest, it) }
        assertEquals(others.size, others.toSet().size)
    }

    @Test
    fun `a rescue's digest commits to each of its fields and to the contract's domain`() {
        fun rescue(
            deposits: DepositsDomain = VECTOR_DEPOSITS,
            from: Address = SCALAR_ONE_ADDRESS,
            note: DepositNote = VECTOR_NOTE,
            relayer: Address = address(0x77),
            fee: BigInteger = FEE,
            nonce: Long = 0,
            deadline: Long = VALID_BEFORE,
        ) = deposits.rescue(from, note, relayer, fee, nonce, deadline).hex()

        val digest = rescue()
        val others =
            listOf(
                rescue(deposits = VECTOR_DEPOSITS.copy(chainId = ChainId.ETHEREUM_MAINNET)),
                rescue(deposits = VECTOR_DEPOSITS.copy(contract = address(0x12))),
                rescue(from = address(0x01)),
                rescue(note = noteWith(0)),
                rescue(relayer = address(0x78)),
                rescue(fee = FEE + bigIntegerOne),
                rescue(nonce = 1),
                rescue(deadline = VALID_BEFORE + 1),
            )
        others.forEach { assertNotEquals(digest, it) }
        assertEquals(others.size, others.toSet().size)
        // A rescue is signed under the Deposits contract's own domain, never the token's.
        assertNotEquals(VECTOR_TOKEN.separator().hex(), VECTOR_DEPOSITS.separator().hex())
    }

    @Test
    fun `values outside their Solidity types are refused`() {
        val twoTo128 = bigIntegerOne.shiftLeft(128)
        val twoTo256 = bigIntegerOne.shiftLeft(256)
        val negative = bigIntegerValueOf(-1)
        assertFailsWith<IllegalArgumentException> { VECTOR_DEPOSITS.depositNonce(VECTOR_NOTE, twoTo128, address(0x77)) }
        assertFailsWith<IllegalArgumentException> { VECTOR_DEPOSITS.depositNonce(VECTOR_NOTE, negative, address(0x77)) }
        assertFailsWith<IllegalArgumentException> {
            VECTOR_DEPOSITS.rescue(SCALAR_ONE_ADDRESS, VECTOR_NOTE, address(0x77), twoTo128, 0, VALID_BEFORE)
        }
        assertFailsWith<IllegalArgumentException> {
            VECTOR_DEPOSITS.rescue(SCALAR_ONE_ADDRESS, VECTOR_NOTE, address(0x77), FEE, -1, VALID_BEFORE)
        }
        assertFailsWith<IllegalArgumentException> {
            VECTOR_DEPOSITS.rescue(SCALAR_ONE_ADDRESS, VECTOR_NOTE, address(0x77), FEE, 0, -1)
        }
        assertFailsWith<IllegalArgumentException> { vectorAuthorization(value = twoTo256) }
        assertFailsWith<IllegalArgumentException> { vectorAuthorization(value = negative) }
        assertFailsWith<IllegalArgumentException> { vectorAuthorization(validBefore = -1) }
        assertFailsWith<IllegalArgumentException> { vectorAuthorization(nonce = ByteArray(31)) }
        // The largest of each still hashes.
        VECTOR_DEPOSITS.depositNonce(VECTOR_NOTE, twoTo128 - bigIntegerOne, address(0x77))
        VECTOR_TOKEN.receiveWithAuthorization(vectorAuthorization(value = twoTo256 - bigIntegerOne))
    }

    @Test
    fun `a note is five words of 32 bytes`() {
        assertFailsWith<IllegalArgumentException> {
            DepositNote(repeated(0x22), listOf(repeated(0x33), repeated(0x44)), repeated(0x66))
        }
        assertFailsWith<IllegalArgumentException> {
            DepositNote(repeated(0x22), List(4) { repeated(0x33) }, repeated(0x66))
        }
        assertFailsWith<IllegalArgumentException> {
            DepositNote(ByteArray(31), listOf(repeated(0x33), repeated(0x44), repeated(0x55)), repeated(0x66))
        }
        assertFailsWith<IllegalArgumentException> {
            DepositNote(repeated(0x22), listOf(repeated(0x33), ByteArray(33), repeated(0x55)), repeated(0x66))
        }
    }

    @Test
    fun `a note keeps its own copy of its words and never prints them`() {
        val npk = repeated(0x22)
        val bundle = listOf(repeated(0x33), repeated(0x44), repeated(0x55))
        val note = DepositNote(npk, bundle, repeated(0x66))
        npk.fill(0)
        bundle.first().fill(0)
        note.npk.fill(0)
        note.encryptedBundle.forEach { it.fill(0) }

        assertEquals(COMMITMENT, note.commitment.hex())
        assertContentEquals(repeated(0x22), note.npk)
        assertContentEquals(repeated(0x33), note.encryptedBundle.first())
        assertContentEquals(repeated(0x66), note.shieldKey)
        assertEquals(VECTOR_NOTE, note)
        assertEquals(VECTOR_NOTE.hashCode(), note.hashCode())
        assertNotEquals(VECTOR_NOTE, noteWith(4))
        assertEquals("DepositNote", note.toString())
        assertTrue("22" !in note.toString())
    }
}

/** `0x11…` repeated, as zecSwap's known answers write their inputs. */
internal fun repeated(byte: Int): ByteArray = ByteArray(32) { byte.toByte() }

internal fun address(byte: Int): Address = Address.fromBytes(ByteArray(20) { byte.toByte() })

internal fun ByteArray.hex(): String = "0x" + toHex()

/** The known answers' Deposits deployment: chain 11155111, contract `0x11…`. */
internal val VECTOR_DEPOSITS = DepositsDomain(ChainId.ETHEREUM_SEPOLIA, address(0x11))

internal val VECTOR_TOKEN = TokenDomain("ZecSwap Test USD", "2", ChainId.ETHEREUM_SEPOLIA, address(0x88))

/** `npk` `0x22…`, the bundle `0x33…`, `0x44…`, `0x55…`, and `shieldKey` `0x66…`. */
internal val VECTOR_NOTE =
    DepositNote(repeated(0x22), listOf(repeated(0x33), repeated(0x44), repeated(0x55)), repeated(0x66))

/** The address of the key whose scalar is 1. */
internal val SCALAR_ONE_ADDRESS = Address.parse("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf")

internal const val NONCE = "0x6917e0f85282cee681821f5eaf494c05f60a95699f31bf72e99f45365d6f8544"
internal const val COMMITMENT = "0x4df47be579bd966f5104be761d3cc5704c776403467845b092d22901e91d24b0"
internal const val VALID_BEFORE = 1_790_000_000L
internal val FEE: BigInteger = bigIntegerValueOf(150_000)
internal val VALUE: BigInteger = bigIntegerValueOf(25_000_000)
private const val NOTE_WORDS = 5

/** The known answers' authorization: 25 tUSD from the scalar-1 key to `0x11…`, valid before 1790000000. */
internal fun vectorAuthorization(
    from: Address = SCALAR_ONE_ADDRESS,
    to: Address = address(0x11),
    value: BigInteger = VALUE,
    validAfter: Long = 0,
    validBefore: Long = VALID_BEFORE,
    nonce: ByteArray = NONCE.hexToBytes(),
) = ReceiveAuthorization(from, to, value, validAfter, validBefore, nonce)

/** The known-answer note with its word [changed] (0 the `npk`, 1 to 3 the bundle, 4 the `shieldKey`) set to `0x99…`. */
private fun noteWith(changed: Int): DepositNote {
    val words =
        listOf(0x22, 0x33, 0x44, 0x55, 0x66).mapIndexed { i, byte -> repeated(if (i == changed) 0x99 else byte) }
    return DepositNote(words[0], words.subList(1, 4), words[4])
}
