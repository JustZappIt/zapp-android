// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.room

import co.electriccoin.zcash.ui.design.util.StringResource

/**
 * Effects emitted by [ChatRoomVM] that require View-side capabilities the VM
 * cannot reach on its own — activity-result launchers, runtime permission
 * checks, the GPS lookup via Play Services, and toasts.
 */
sealed interface ChatRoomEffect {
    data object PickMedia : ChatRoomEffect

    data object PickFile : ChatRoomEffect

    data object TakePhoto : ChatRoomEffect

    data object ShareLocation : ChatRoomEffect

    data class ShowToast(
        val message: StringResource
    ) : ChatRoomEffect
}
