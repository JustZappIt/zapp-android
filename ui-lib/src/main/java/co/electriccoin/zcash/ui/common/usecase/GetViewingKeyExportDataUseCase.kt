package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
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

sealed interface ViewingKeyExportResult {
    class Available(
        val accountLabel: StringResource,
        val accountIndex: Long,
        val network: ZcashNetwork,
        val availableKeyTypes: Set<ViewingKeyType>,
        val keyType: ViewingKeyType,
        val encodedKey: String,
    ) : ViewingKeyExportResult {
        override fun toString(): String = "ViewingKeyExportResult.Available(REDACTED)"
    }

    data class Unavailable(
        val accountLabel: StringResource,
        val accountIndex: Long,
        val network: ZcashNetwork,
        val availableKeyTypes: Set<ViewingKeyType>,
        val requestedKeyType: ViewingKeyType,
    ) : ViewingKeyExportResult
}

class GetViewingKeyExportDataUseCase(
    private val accountDataSource: AccountDataSource,
    private val versionInfoProvider: GetVersionInfoProvider,
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
    ): ViewingKeyExportResult {
        val account = accountDataSource.getAllAccounts().first { it.sdkAccount.accountUuid == accountId }
        val availableKeyTypes = account.availableViewingKeyTypes()
        val encodedKey =
            when (keyType) {
                ViewingKeyType.UFVK -> account.sdkAccount.ufvk
                ViewingKeyType.UIVK -> account.sdkAccount.uivk
            }

        return if (encodedKey == null) {
            ViewingKeyExportResult.Unavailable(
                accountLabel = account.name,
                accountIndex = account.hdAccountIndex.index,
                network = versionInfoProvider().network,
                availableKeyTypes = availableKeyTypes,
                requestedKeyType = keyType,
            )
        } else {
            ViewingKeyExportResult.Available(
                accountLabel = account.name,
                accountIndex = account.hdAccountIndex.index,
                network = versionInfoProvider().network,
                availableKeyTypes = availableKeyTypes,
                keyType = keyType,
                encodedKey = encodedKey,
            )
        }
    }
}

private fun co.electriccoin.zcash.ui.common.model.WalletAccount.availableViewingKeyTypes(): Set<ViewingKeyType> =
    buildSet {
        if (sdkAccount.ufvk != null) add(ViewingKeyType.UFVK)
        if (sdkAccount.uivk != null) add(ViewingKeyType.UIVK)
    }
