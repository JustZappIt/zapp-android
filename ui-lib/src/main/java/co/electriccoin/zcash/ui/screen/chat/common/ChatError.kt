// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.common

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

sealed class ChatError {
    object ExportSeedPhraseFailed : ChatError()
}

fun ChatError.toStringResource(): StringResource =
    when (this) {
        ChatError.ExportSeedPhraseFailed -> stringRes(R.string.chat_identity_setup_error_export_seed_failed)
    }
