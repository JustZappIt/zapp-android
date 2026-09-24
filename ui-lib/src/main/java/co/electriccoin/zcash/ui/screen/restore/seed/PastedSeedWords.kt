package co.electriccoin.zcash.ui.screen.restore.seed

import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.design.component.SeedWordTextFieldState
import co.electriccoin.zcash.ui.design.component.TextSelection

// Anything but a letter separates words, so a numbered backup's "1." or "2)" drops out.
private val NON_LETTERS = Regex("\\P{L}+")

internal fun splitPastedSeedWords(text: String): List<String> =
    text.split(NON_LETTERS).filter { it.isNotEmpty() }.map { it.lowercase() }

/**
 * The words a field change pasted, or none when it was typing. Only the text inserted at the old
 * cursor or selection counts, so a paste never absorbs what the field held.
 */
internal fun pastedSeedWords(
    old: SeedWordInnerTextFieldState,
    new: SeedWordInnerTextFieldState
): List<String> = splitPastedSeedWords(insertedText(old, new.value)).takeIf { it.size > 1 }.orEmpty()

/** A whole phrase always starts at the first field; anything past the last field is dropped. */
internal fun List<SeedWordTextFieldState>.withPastedWords(
    index: Int,
    words: List<String>
): List<SeedWordTextFieldState> {
    val start = if (words.size >= size) 0 else index
    return mapIndexed { i, field ->
        words.getOrNull(i - start)?.let {
            field.copy(innerState = SeedWordInnerTextFieldState(it, TextSelection.End))
        } ?: field
    }
}

private fun insertedText(
    old: SeedWordInnerTextFieldState,
    new: String
): String {
    val length = old.value.length
    val (start, end) =
        when (val selection = old.selection) {
            TextSelection.Start -> 0 to 0
            TextSelection.End -> length to length
            is TextSelection.ByTextRange -> selection.range.min to selection.range.max
        }
    val before = old.value.take(start.coerceAtMost(length))
    val after = old.value.drop(end.coerceAtMost(length))
    val kept = new.length >= before.length + after.length && new.startsWith(before) && new.endsWith(after)
    return if (kept) new.substring(before.length, new.length - after.length) else new
}
