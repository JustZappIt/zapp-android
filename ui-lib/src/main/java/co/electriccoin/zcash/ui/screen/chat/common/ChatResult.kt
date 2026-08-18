// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.common

sealed class ChatResult<out T> {
    data class Success<out T>(
        val value: T
    ) : ChatResult<T>()

    data class Failure(
        val error: ChatError
    ) : ChatResult<Nothing>()
}
