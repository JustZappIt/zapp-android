// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.design.util.StringResource

enum class ViewingKeyType {
    UFVK,
    UIVK,
}

data class ViewingKeyExportAccount(
    val accountId: AccountUuid,
    val label: StringResource,
    val accountIndex: Long,
    val isSelected: Boolean,
    val availableKeyTypes: Set<ViewingKeyType>,
)

class ViewingKeyExportData(
    val keyType: ViewingKeyType,
    val encodedKey: String,
) {
    override fun toString(): String = "ViewingKeyExportData(REDACTED)"
}

class GetViewingKeyExportDataUseCase(
    private val accountDataSource: AccountDataSource,
) {
    suspend fun getAccounts(): List<ViewingKeyExportAccount> =
        accountDataSource.getAllAccounts().map { account ->
            ViewingKeyExportAccount(
                accountId = account.sdkAccount.accountUuid,
                label = account.name,
                accountIndex = account.hdAccountIndex.index,
                isSelected = account.isSelected,
                availableKeyTypes = account.availableViewingKeyTypes(),
            )
        }

    suspend operator fun invoke(
        accountId: AccountUuid,
        keyType: ViewingKeyType,
    ): ViewingKeyExportData? {
        val account =
            accountDataSource.getAllAccounts().firstOrNull { it.sdkAccount.accountUuid == accountId }
                ?: return null
        val encodedKey =
            when (keyType) {
                ViewingKeyType.UFVK -> account.sdkAccount.ufvk
                ViewingKeyType.UIVK -> account.sdkAccount.uivk
            }
        return encodedKey?.let { ViewingKeyExportData(keyType = keyType, encodedKey = it) }
    }
}

private fun co.electriccoin.zcash.ui.common.model.WalletAccount.availableViewingKeyTypes(): Set<ViewingKeyType> =
    buildSet {
        if (sdkAccount.ufvk != null) add(ViewingKeyType.UFVK)
        if (sdkAccount.uivk != null) add(ViewingKeyType.UIVK)
    }
