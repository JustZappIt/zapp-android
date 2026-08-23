// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.provider

import android.app.Application
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources

/**
 * The network this build talks to, readable before a wallet exists.
 *
 * [SynchronizerProvider] is the usual source, but it has nothing to say on a device that has not
 * been through onboarding yet — and a gift link still has to be judged against a network there.
 */
interface ZcashNetworkProvider {
    operator fun invoke(): ZcashNetwork
}

class ZcashNetworkProviderImpl(
    private val application: Application,
) : ZcashNetworkProvider {
    override operator fun invoke(): ZcashNetwork = ZcashNetwork.fromResources(application)
}
