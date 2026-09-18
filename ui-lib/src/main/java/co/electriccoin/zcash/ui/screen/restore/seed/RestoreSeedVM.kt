package co.electriccoin.zcash.ui.screen.restore.seed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.SeedTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordTextFieldState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.restore.height.RestoreBDHeight
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RestoreSeedVM(
    private val navigationRouter: NavigationRouter,
    private val validateSeed: ValidateSeedUseCase
) : ViewModel() {
    private val suggestions =
        flow {
            val result = withContext(Dispatchers.IO) { Mnemonics.getCachedWords(Locale.ENGLISH.language) }
            emit(result)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    @Suppress("MagicNumber")
    private val seedWords =
        MutableStateFlow(
            (0..23).map { index ->
                SeedWordTextFieldState(
                    innerState =
                        SeedWordInnerTextFieldState(
                            ""
                        ),
                    onValueChange = { onValueChange(index, it) },
                    isError = false
                )
            }
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val seedValidations =
        combine(seedWords, suggestions) { seedWords, suggestions ->
            seedWords to suggestions.orEmpty()
        }.mapLatest { (seedWords, suggestions) ->
            withContext(Dispatchers.Default) {
                seedWords.map { field ->
                    val trimmed =
                        field.innerState.value
                            .lowercase()
                            .trim()
                    val autocomplete = suggestions.filter { it.startsWith(trimmed) }
                    val validSuggestions =
                        when {
                            trimmed.isBlank() -> suggestions
                            suggestions.contains(trimmed) && autocomplete.size == 1 -> suggestions
                            else -> autocomplete
                        }
                    validSuggestions.isNotEmpty()
                }
            }
        }

    private val validSeed =
        seedWords
            .map { fields ->
                validateSeed(fields.map { it.innerState.value.trim() })
            }

    val state: StateFlow<RestoreSeedState?> =
        combine(seedWords, seedValidations, validSeed) { words, seedValidations, validation ->
            createState(words, seedValidations, validation)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    /**
     * The complete word list that the user can choose from; useful for autocomplete
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val suggestionsState =
        combine(validSeed, suggestions) { seed, suggestions ->
            seed to suggestions
        }.mapLatest { (seed, suggestions) ->
            RestoreSeedSuggestionsState(
                isVisible = seed == null && suggestions != null,
                suggestions = suggestions.orEmpty()
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    private fun createState(
        words: List<SeedWordTextFieldState>,
        seedValidations: List<Boolean>,
        seedPhrase: SeedPhrase?
    ) = RestoreSeedState(
        seed =
            SeedTextFieldState(
                values =
                    words
                        .mapIndexed { index, word ->
                            word.copy(isError = !seedValidations[index])
                        }
            ),
        onBack = ::onBack,
        dialogButton =
            IconButtonState(
                icon = R.drawable.ic_help,
                onClick = ::onInfoButtonClick
            ),
        nextButton =
            ButtonState(
                text = stringRes(R.string.restore_button),
                onClick = ::onNextClicked,
            ).takeIf { seedPhrase != null }
    )

    private fun onBack() {
        navigationRouter.back()
    }

    private fun onInfoButtonClick() {
        navigationRouter.forward(SeedInfo)
    }

    private fun onNextClicked() {
        viewModelScope.launch {
            val seed = validSeed.first() ?: return@launch
            navigationRouter.forward(RestoreBDHeight(seed.joinToString()))
        }
    }

    private fun onValueChange(
        index: Int,
        state: SeedWordInnerTextFieldState
    ) {
        // A single field only ever holds one word, so several words arriving at once is a paste
        // of (part of) a phrase: spread it across the grid instead of cramming it into one box.
        val pasted = splitPastedSeedWords(state.value)
        if (pasted.size > 1) {
            distributeSeedWords(index, pasted)
        } else {
            updateSeedWord(index, state)
        }
    }

    private fun updateSeedWord(
        index: Int,
        newState: SeedWordInnerTextFieldState
    ) {
        seedWords.update {
            val newSeedWords = it.toMutableList()
            newSeedWords[index] = newSeedWords[index].copy(innerState = newState.copy(value = newState.value.trim()))
            newSeedWords.toList()
        }
    }

    private fun distributeSeedWords(
        index: Int,
        words: List<String>
    ) {
        seedWords.update { fields ->
            val values = fields.map { it.innerState.value }
            val newValues = placePastedSeedWords(values, index, words)
            fields.mapIndexed { i, field ->
                if (newValues[i] == field.innerState.value) {
                    field
                } else {
                    field.copy(innerState = SeedWordInnerTextFieldState(newValues[i], TextSelection.End))
                }
            }
        }
    }
}
