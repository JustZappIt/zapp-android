// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanPublicKeyUseCase
import co.electriccoin.zcash.ui.screen.scan.ImageToQrCodeResult
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ChatScanPublicKeyVM(
    private val args: ChatScanPublicKeyArgs,
    private val navigateToScanPublicKey: NavigateToScanPublicKeyUseCase,
) : ViewModel() {
    val state = MutableStateFlow(ScanValidationState.NONE)

    private val mutex = Mutex()
    private var hasBeenScannedSuccessfully = false

    fun onScanned(result: String) =
        viewModelScope.launch {
            mutex.withLock {
                if (hasBeenScannedSuccessfully) return@withLock
                val normalized = result.trim().removePrefix("0x")
                if (isValidPublicKey(normalized)) {
                    hasBeenScannedSuccessfully = true
                    state.update { ScanValidationState.VALID }
                    navigateToScanPublicKey.onScanned(normalized.lowercase(), args)
                } else {
                    state.update { ScanValidationState.INVALID }
                }
            }
        }

    fun onImageScanned(result: ImageToQrCodeResult) =
        viewModelScope.launch {
            mutex.withLock {
                if (hasBeenScannedSuccessfully) return@withLock
                when (result) {
                    is ImageToQrCodeResult.SingleCode -> {
                        onScanned(result.text)
                    }

                    ImageToQrCodeResult.MultipleCodes -> {
                        state.update { ScanValidationState.SEVERAL_CODES_FOUND }
                    }

                    ImageToQrCodeResult.NoCode -> {
                        state.update { ScanValidationState.INVALID_IMAGE }
                    }
                }
            }
        }

    fun onBack() = viewModelScope.launch { navigateToScanPublicKey.onScanCancelled(args) }

    private fun isValidPublicKey(raw: String): Boolean =
        raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
