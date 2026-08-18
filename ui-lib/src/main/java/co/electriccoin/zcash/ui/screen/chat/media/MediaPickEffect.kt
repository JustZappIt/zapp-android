// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.media

/**
 * Media-attachment effects emitted by a chat VM that require View-side launchers
 * (activity-result APIs and runtime permission checks the VM can't reach directly).
 */
sealed interface MediaPickEffect {
    data object PickMedia : MediaPickEffect

    data object PickFile : MediaPickEffect

    data object TakePhoto : MediaPickEffect
}
