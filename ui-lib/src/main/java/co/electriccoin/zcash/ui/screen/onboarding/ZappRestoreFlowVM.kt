package co.electriccoin.zcash.ui.screen.onboarding

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.IsKeepScreenOnDuringRestoreProvider
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.SeedTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordTextFieldState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.time.toKotlinInstant

enum class BirthdayMode { HEIGHT, DATE }

@Suppress("TooManyFunctions")
class ZappRestoreFlowVM(
    private val application: Application,
    private val validateSeed: ValidateSeedUseCase,
    private val restoreWallet: RestoreWalletUseCase,
    private val isKeepScreenOnDuringRestoreProvider: IsKeepScreenOnDuringRestoreProvider,
) : ViewModel() {
    // ── Seed words ──────────────────────────────────────────────

    private val seedWords =
        MutableStateFlow(
            (0..SEED_WORD_LAST_INDEX).map { index ->
                SeedWordTextFieldState(
                    innerState = SeedWordInnerTextFieldState(""),
                    onValueChange = { onSeedWordChange(index, it) },
                    isError = false,
                )
            }
        )

    private val bip39Suggestions =
        flow {
            val result = withContext(Dispatchers.IO) { Mnemonics.getCachedWords(Locale.ENGLISH.language) }
            emit(result)
        }.stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val seedValidations =
        combine(seedWords, bip39Suggestions) { words, suggestions ->
            words to suggestions.orEmpty()
        }.mapLatest { (words, suggestions) ->
            withContext(Dispatchers.Default) {
                words.map { field ->
                    val trimmed =
                        field.innerState.value
                            .lowercase()
                            .trim()
                    if (trimmed.isBlank()) true else suggestions.any { it.startsWith(trimmed) }
                }
            }
        }

    val validSeed: StateFlow<SeedPhrase?> =
        seedWords
            .map { fields -> validateSeed(fields.map { it.innerState.value.trim() }) }
            .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null)

    val seedFieldState: StateFlow<SeedTextFieldState> =
        combine(seedWords, seedValidations) { words, validations ->
            SeedTextFieldState(
                values =
                    words.mapIndexed { index, word ->
                        word.copy(isError = !validations[index])
                    }
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SeedTextFieldState(values = seedWords.value),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val suggestionsVisible: StateFlow<Boolean> =
        combine(validSeed, bip39Suggestions) { seed, suggestions ->
            seed == null && suggestions != null
        }.mapLatest { it }
            .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = false)

    val suggestionsList: StateFlow<List<String>> =
        bip39Suggestions
            .map { it.orEmpty() }
            .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    private fun onSeedWordChange(index: Int, newState: SeedWordInnerTextFieldState) {
        seedWords.update { list ->
            list.toMutableList().also {
                it[index] = it[index].copy(innerState = newState.copy(value = newState.value.trim()))
            }
        }
    }

    // ── Birthday height & mode ──────────────────────────────────

    private val _birthdayText = MutableStateFlow("")
    val birthdayText: StateFlow<String> = _birthdayText.asStateFlow()

    fun onBirthdayChange(value: String) {
        _birthdayText.update { value.filter { it.isDigit() } }
        // Clear the "below sapling" error as soon as the user edits.
        _birthdayError.update { null }
    }

    private val _birthdayMode = MutableStateFlow(BirthdayMode.HEIGHT)
    val birthdayMode: StateFlow<BirthdayMode> = _birthdayMode.asStateFlow()

    @Suppress("MagicNumber")
    private val _selectedYearMonth = MutableStateFlow(YearMonth.of(2018, 10))
    val selectedYearMonth: StateFlow<YearMonth> = _selectedYearMonth.asStateFlow()

    private val _isEstimating = MutableStateFlow(false)
    val isEstimating: StateFlow<Boolean> = _isEstimating.asStateFlow()

    private val _birthdayError = MutableStateFlow<StringResource?>(null)
    val birthdayError: StateFlow<StringResource?> = _birthdayError.asStateFlow()

    fun onBirthdayModeChange(mode: BirthdayMode) {
        _birthdayMode.update { mode }
        _birthdayError.update { null }
    }

    fun onYearMonthChange(yearMonth: YearMonth) {
        _selectedYearMonth.update { yearMonth }
        _birthdayError.update { null }
    }

    /**
     * Estimate a block height from the selected year/month and write it back to
     * [birthdayText], then switch to HEIGHT mode so the user sees the populated value
     * on the same screen and taps Restore manually. Failure surfaces a localized error
     * on the birthday screen — no auto-advance.
     */
    fun estimateFromDate() {
        if (_isEstimating.value) return
        _isEstimating.update { true }
        _birthdayError.update { null }
        viewModelScope.launch {
            runCatching {
                val instant =
                    _selectedYearMonth.value
                        .atDay(1)
                        .atStartOfDay()
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toKotlinInstant()
                SdkSynchronizer.estimateBirthdayHeight(
                    context = application,
                    date = instant,
                    network = VersionInfo.NETWORK,
                )
            }.onSuccess { bday ->
                _birthdayText.update { bday.value.toString() }
                _birthdayMode.update { BirthdayMode.HEIGHT }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Twig.warn(e) { "ZappRestoreFlowVM: estimateBirthdayHeight failed" }
                _birthdayError.update { stringRes(R.string.restore_flow_error_estimation_failed) }
            }
            _isEstimating.update { false }
        }
    }

    // ── Keep screen on ──────────────────────────────────────────

    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    fun onKeepScreenOnToggle() {
        _keepScreenOn.update { !it }
    }

    fun persistKeepScreenOn() {
        viewModelScope.launch {
            isKeepScreenOnDuringRestoreProvider.store(_keepScreenOn.value)
        }
    }

    // ── Restore action ──────────────────────────────────────────

    private val _restoreError = MutableStateFlow<StringResource?>(null)
    val restoreError: StateFlow<StringResource?> = _restoreError.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    fun startRestore() {
        val seed = validSeed.value
        val saplingHeight = VersionInfo.NETWORK.saplingActivationHeight.value
        val userBirthday = _birthdayText.value.toLongOrNull()
        val effectiveBirthday = computeEffectiveBirthday(userBirthday, saplingHeight)
        if (seed == null || effectiveBirthday == null || _isRestoring.value) return

        _isRestoring.update { true }
        _restoreError.update { null }

        viewModelScope.launch {
            runCatching {
                restoreWallet(
                    seedPhrase = seed,
                    birthday = BlockHeight.new(effectiveBirthday),
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Twig.warn(e) { "ZappRestoreFlowVM: restoreWallet failed" }
                _restoreError.update { stringRes(R.string.restore_flow_error_wallet_failed) }
                _isRestoring.update { false }
            }
        }
    }

    /**
     * Resolves the user-typed birthday height against the network's sapling activation.
     * Returns null (and surfaces a birthday error) when the user typed a value below
     * sapling activation. A blank/missing value falls back to the activation height —
     * a full-chain scan, but a safe lower bound the SDK will accept.
     */
    private fun computeEffectiveBirthday(userBirthday: Long?, saplingHeight: Long): Long? {
        if (userBirthday != null && userBirthday < saplingHeight) {
            _birthdayError.update {
                stringRes(R.string.restore_flow_error_birthday_too_low, saplingHeight.toString())
            }
            return null
        }
        return userBirthday ?: saplingHeight
    }

    /**
     * Called by the composable when the wallet is confirmed restored. Lets the
     * VM drop the [_isRestoring] flag (the auto-advance moved past the loading
     * screen, but the flag would otherwise stay true until VM destruction).
     */
    fun markRestoreCompleted() {
        _isRestoring.update { false }
    }

    fun retryRestore() {
        _isRestoring.update { false }
        startRestore()
    }

    private companion object {
        private const val SEED_WORD_LAST_INDEX = 23
    }
}
