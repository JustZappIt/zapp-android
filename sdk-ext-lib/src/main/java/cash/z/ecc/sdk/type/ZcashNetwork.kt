@file:Suppress("ktlint:standard:filename")

package cash.z.ecc.sdk.type

import android.content.Context
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.ext.R

/**
 * @return Zcash network determined from [R.bool.zcash_is_testnet]. The `app` module generates
 * this resource from its `network` product flavor (see `app/build.gradle.kts`), so the
 * runtime network and `BuildConfig.FLAVOR_network` are always in lockstep. When a library
 * module is exercised in isolation (e.g. `sdk-ext-lib` androidTest) the default from
 * `sdk-ext-lib/src/main/res/values/bools.xml` applies and resolves to Mainnet.
 */
fun ZcashNetwork.Companion.fromResources(context: Context) =
    if (context.resources.getBoolean(R.bool.zcash_is_testnet)) {
        ZcashNetwork.Testnet
    } else {
        ZcashNetwork.Mainnet
    }
