package co.electriccoin.zcash.ui.design.util

/**
 * Returns a copy of this string with its middle replaced by `…` if it is longer than
 * `prefix + suffix + 1` characters. Short inputs are returned verbatim. Used for addresses,
 * tx hashes, and any other long identifier we display in a single line.
 */
fun String.ellipsizeMiddle(prefix: Int, suffix: Int): String {
    if (length <= prefix + suffix + 1) return this
    return take(prefix) + "…" + takeLast(suffix)
}
