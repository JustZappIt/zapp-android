// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 The Zapp Contributors

package xyz.justzappit.offramp.identity

import kotlinx.coroutines.CancellationException
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.RpcException
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.types.Wei
import xyz.justzappit.offramp.account.SubmittingAccount
import xyz.justzappit.offramp.config.P2pNetworkConfig
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.reputation.IdentityCheck
import xyz.justzappit.offramp.reputation.ReputationCalls
import xyz.justzappit.offramp.reputation.ReputationReader
import xyz.justzappit.offramp.reputation.ReputationSummary

internal sealed interface IdentitySubmission {
    data class Confirmed(
        val block: String
    ) : IdentitySubmission

    data class Verified(
        val summary: ReputationSummary
    ) : IdentitySubmission

    data class Failed(
        val reason: IdentityFailure
    ) : IdentitySubmission
}

/** Owns the durable boundary between an attestation and its on-chain transaction. */
internal class IdentityAttestationSubmitter(
    private val rpc: BaseRpcClient,
    private val network: P2pNetworkConfig,
    private val reputationReader: ReputationReader,
    private val store: IdentityVerificationStore,
    private val onUnrecognisedRevert: (String) -> Unit,
) {
    suspend fun submit(
        account: SubmittingAccount,
        check: IdentityCheck,
        key: String,
        initial: PendingIdentityVerification,
        currency: CurrencyCode,
    ): IdentitySubmission {
        var pending = initial
        return try {
            val existingHash = pending.transactionHash
            val txHash: TxHash
            if (existingHash != null) {
                txHash = TxHash.fromHex(existingHash)
                account.submitter.restorePendingTransaction(txHash, pending.transactionNonce?.let(::BigInteger))
            } else {
                val calldata =
                    ReputationCalls.submitIdentityAttestationCalldata(
                        check,
                        requireNotNull(pending.attestation).decode(),
                    )
                val failure = simulationFailure(network.reputationManagerAddress, account.address, calldata)
                if (failure != null) return simulationResult(failure, account, check, key, currency)
                txHash =
                    account.submitter.sendTransaction(
                        to = network.reputationManagerAddress,
                        value = Wei.ZERO,
                        data = calldata,
                        beforeBroadcast = { prepared ->
                            pending =
                                pending.copy(
                                    transactionHash = prepared.hash.hex,
                                    transactionNonce = prepared.nonce.toString(),
                                )
                            store.set(key, pending)
                        },
                    )
            }
            val receipt = account.submitter.awaitReceipt(txHash)
            if (receipt.success) {
                store.set(key, pending.copy(receiptBlock = receipt.blockNumber))
                IdentitySubmission.Confirmed(receipt.blockNumber)
            } else {
                // Inclusion consumed the nonce; keep the attestation for a fresh attempt.
                store.set(key, pending.copy(transactionHash = null, transactionNonce = null))
                IdentitySubmission.Failed(IdentityFailure.Rejected)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException.TransportError) {
            // The operation may be included. Keep its hash and nonce even if the send response was lost.
            IdentitySubmission.Failed(classify(e))
        } catch (e: RpcException) {
            // Match the submitter's definite send rejection rule; preparation and polling do not release a hash.
            if (e.method == "eth_sendUserOperation") {
                store.set(key, pending.copy(transactionHash = null, transactionNonce = null))
            }
            IdentitySubmission.Failed(classify(e))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            IdentitySubmission.Failed(classify(e))
        }
    }

    private suspend fun simulationResult(
        failure: IdentityFailure,
        account: SubmittingAccount,
        check: IdentityCheck,
        key: String,
        currency: CurrencyCode,
    ): IdentitySubmission {
        if (failure == IdentityFailure.AlreadyVerified) {
            val read = reputationReader.read(account.address, currency)
            if (check in read.identityVerified) {
                store.set(key, null)
                return IdentitySubmission.Verified(read)
            }
        }
        store.set(key, null)
        return IdentitySubmission.Failed(failure)
    }

    private suspend fun simulationFailure(
        to: Address,
        from: Address,
        calldata: ByteArray,
    ): IdentityFailure? =
        try {
            rpc.ethCall(to = to, data = calldata, from = from)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            // A simulation that cannot run is not proof the send would fail; let the send decide.
            (e as? RpcException.ExecutionReverted)?.let(::classifyRevert)
        }

    @Suppress("ReturnCount")
    private fun classify(e: Exception): IdentityFailure {
        if (e is RpcException.ExecutionReverted) return classifyRevert(e)
        val message = e.message.orEmpty()
        REVERTS.entries.firstOrNull { it.key in message }?.let { return it.value }
        if (SPONSORSHIP_MARKERS.any { message.contains(it, ignoreCase = true) }) {
            return IdentityFailure.SponsorshipUnavailable
        }
        return IdentityFailure.Network
    }

    private fun classifyRevert(e: RpcException.ExecutionReverted): IdentityFailure {
        val selector = e.selector?.hex
        return selector?.let(REVERTS::get) ?: run {
            onUnrecognisedRevert(selector ?: e.solidityErrorString ?: "revert with no data")
            IdentityFailure.Rejected
        }
    }

    private companion object {
        /** The ReputationManager's errors for both checks, by selector; the bundler quotes them as text. */
        val REVERTS: Map<String, IdentityFailure> =
            mapOf(
                "0x66790623" to IdentityFailure.Unavailable, // LivenessSignerNotSet
                "0x54710b53" to IdentityFailure.Expired, // LivenessAttestationExpired
                "0xfa82e304" to IdentityFailure.Rejected, // LivenessNullifierZero
                "0x61746f27" to IdentityFailure.AlreadyClaimed, // LivenessNullifierAlreadySpent
                "0x95182781" to IdentityFailure.AlreadyVerified, // LivenessAlreadyVerified
                "0x9cfb0729" to IdentityFailure.Rejected, // LivenessInvalidSignature
                "0x2477577f" to IdentityFailure.Unavailable, // KycSignerNotSet
                "0xeb1a0215" to IdentityFailure.Expired, // KycAttestationExpired
                "0x311fde50" to IdentityFailure.Rejected, // KycNullifierZero
                "0xbdbb1a03" to IdentityFailure.AlreadyClaimed, // KycNullifierAlreadySpent
                "0x10afbce2" to IdentityFailure.AlreadyVerified, // KycAlreadyVerified
                "0x80686e11" to IdentityFailure.Rejected, // KycInvalidSignature
            )

        val SPONSORSHIP_MARKERS = listOf("paymaster", "sponsor", "AA31", "AA33", "prefund")
    }
}
