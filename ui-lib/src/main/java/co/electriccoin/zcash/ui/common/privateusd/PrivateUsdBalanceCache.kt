// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.privateusd

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.bestEffort
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import co.electriccoin.zcash.ui.common.provider.StoreCorruptedException
import co.electriccoin.zcash.ui.common.repository.RailgunSync
import kotlinx.serialization.Serializable
import xyz.justzappit.evm.types.Address
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunBalanceBucket
import xyz.justzappit.railgun.RailgunNetwork
import xyz.justzappit.railgun.RailgunTokenAmount
import java.math.BigInteger
import kotlin.time.Instant

internal data class CachedBalances(
    val balances: PrivateUsdBalances,
    val updatedAt: Instant,
)

/** The last balance, encrypted and tied to the wallet it belongs to, so it shows before a sync can finish. */
internal class PrivateUsdBalanceCache(
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) {
    private val store = EncryptedJsonStore(encryptedPreferenceProvider, KEY, Cache.serializer())

    /** Null when nothing, or nothing readable, is cached for [address]. */
    suspend fun read(
        address: RailgunAddress,
        network: RailgunNetwork
    ): CachedBalances? =
        try {
            store.get()?.takeIf { it.address == address.value }?.let {
                val updatedAt = Instant.fromEpochMilliseconds(it.updatedAt)
                CachedBalances(PrivateUsdBalances.of(network, it.byBucket()), updatedAt)
            }
        } catch (e: StoreCorruptedException) {
            Twig.warn(e) { "Private USD: the cached balance is unreadable" }
            null
        } catch (e: IllegalArgumentException) {
            Twig.warn(e) { "Private USD: the cached balance has an unreadable amount" }
            null
        }

    suspend fun write(sync: RailgunSync) {
        val buckets =
            sync.balances.byBucket.mapKeys { it.key.name }.mapValues { (_, tokens) ->
                tokens
                    .filter { it.amount.signum() > 0 }
                    .map { CachedAmount(it.token.checksumHex, it.amount.toString()) }
            }
        bestEffort("Private USD: the balance wasn't cached") {
            store.set(Cache(sync.address.value, sync.at.toEpochMilliseconds(), buckets))
        }
    }

    @Serializable
    private data class Cache(
        val address: String,
        val updatedAt: Long,
        val buckets: Map<String, List<CachedAmount>>,
    ) {
        // Throws on an amount or token it can't read; a bucket it doesn't know is left out.
        fun byBucket(): Map<RailgunBalanceBucket, List<RailgunTokenAmount>> =
            buckets
                .mapNotNull { (name, amounts) ->
                    RailgunBalanceBucket.entries.firstOrNull { it.name == name }?.let { bucket ->
                        bucket to amounts.map { RailgunTokenAmount(Address.parse(it.token), it.value()) }
                    }
                }.toMap()
    }

    @Serializable
    private data class CachedAmount(
        val token: String,
        val amount: String,
    ) {
        fun value(): BigInteger = BigInteger(amount).also { require(it.signum() >= 0) { "a negative amount" } }
    }

    private companion object {
        const val KEY = "private_usd_balances_v1"
    }
}
