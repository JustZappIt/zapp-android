// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import xyz.justzappit.evm.deposits.DepositsDomain
import xyz.justzappit.evm.deposits.ReceiveAuthorization
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.Usdc6

/**
 * Signs deposits and rescues as zecSwap's `docs/deposits.md` does, each into a record to keep before it's posted. The
 * note is built from the wallet's seed to its own Railgun keys and opened with its viewing key ([DepositNotes]), so
 * what lands is the wallet's own; the nonce commits to it, the fee and the relayer's deposits account, so the relayer
 * can delay a deposit but never redirect it.
 */
internal class DepositSigning(
    private val pin: DepositsPin,
    private val chain: DepositsChainReader,
    private val keys: DepositKeyring,
    private val notes: DepositNotes,
    private val now: () -> Long,
) {
    private val deposits = DepositsDomain(pin.chainId, pin.contract)

    /**
     * A shield of [value], address [index]'s whole balance, under [terms]: its fee within the cap and under the value,
     * valid for an hour (or what the relayer takes, less a minute) and past [after], so each signing of an address has
     * an id of its own, and under the token's own domain, checked against its `DOMAIN_SEPARATOR()` first.
     */
    suspend fun shield(
        index: Int,
        value: BigInteger,
        terms: DepositTerms,
        afterRefusal: Boolean,
        after: Long = 0,
    ): DepositRecord {
        val fee =
            DepositFees.capped(terms, value)
                ?: throw DepositsException(DepositProblem.FEE_OVER_CAP, "the relayer's fee is over the cap")
        val token = chain.tokenDomain()
        val createdAt = now()
        val validBefore = maxOf(createdAt + terms.validity, after + 1)
        val note = notes.shield(index, validBefore)
        val signed =
            keys.withKey(index) { key ->
                val nonce = deposits.depositNonce(note, fee, terms.relayer)
                val authorization = ReceiveAuthorization(key.address, pin.contract, value, 0, validBefore, nonce)
                Signed(key.address, key.sign(token.receiveWithAuthorization(authorization)).hex, nonce)
            }
        val request =
            ShieldDeposit(
                chainId = pin.chainId,
                contract = pin.contract,
                from = signed.from,
                value = Usdc6(value),
                validAfter = 0,
                validBefore = validBefore,
                note = note.wire(),
                fee = Usdc6(fee),
                signature = signed.signature,
            )
        requireFits(request, ShieldDeposit.serializer())
        return DepositRecord(
            id = "shield-$index-$validBefore",
            kind = DepositKind.SHIELD,
            index = index,
            state = DepositState.SIGNED,
            createdAt = createdAt,
            value = request.value,
            terms = SignedDepositTerms.of(terms),
            shield = request,
            nonce = signed.nonce?.let { "0x" + it.toHex() },
            afterRefusal = afterRefusal,
        )
    }

    /**
     * A rescue of what address [index]'s vault holds, [held], to a new note, paying [fee], the terms' fee alone, with
     * the contract's [nonce] for the address and a deadline as far off as a shield's validity.
     */
    suspend fun rescue(
        index: Int,
        held: BigInteger,
        fee: BigInteger,
        nonce: Long,
        terms: DepositTerms,
    ): DepositRecord {
        val note = notes.rescue(index, nonce)
        val createdAt = now()
        val deadline = createdAt + terms.validity
        val signed =
            keys.withKey(index) { key ->
                val digest = deposits.rescue(key.address, note, terms.relayer, fee, nonce, deadline)
                Signed(key.address, key.sign(digest).hex)
            }
        val request =
            RescueDeposit(
                chainId = pin.chainId,
                contract = pin.contract,
                from = signed.from,
                note = note.wire(),
                fee = Usdc6(fee),
                nonce = nonce,
                deadline = deadline,
                signature = signed.signature,
            )
        requireFits(request, RescueDeposit.serializer())
        return DepositRecord(
            id = "rescue-$index-$nonce-$deadline",
            kind = DepositKind.RESCUE,
            index = index,
            state = DepositState.SIGNED,
            createdAt = createdAt,
            value = Usdc6(held),
            terms = SignedDepositTerms.of(terms),
            rescue = request,
        )
    }

    // What a key signed, taken out of the block that lent it: the address, the signature, and a shield's nonce.
    private class Signed(
        val from: Address,
        val signature: String,
        val nonce: ByteArray? = null,
    )
}
