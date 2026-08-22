package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The stock a gift card is printed on.
 *
 * Fixed colours rather than [ZappColors] tokens, and deliberately so: a gift card is an object the
 * sender hands to someone, and the same card has to be the same object in both themes. A bone card
 * that turned obsidian at dusk would be a different card.
 *
 * Every face sits clearly lighter than the page it lies on — on a near-black background a card that
 * only just clears its surroundings reads as a panel, not as something with thickness. [sheen] is
 * the light catching the top edge, which is what sells that thickness once a shadow is under it.
 */
@Immutable
data class ZappGiftCardStock(
    val face: Color,
    val edge: Color,
    val sheen: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
)

/**
 * The four stocks a card can be printed on. Which one a card gets is a product decision made where
 * the card is built — this only says what each looks like.
 *
 * Hex colour literals, as in [ZappColors] — which carries the same finding in the detekt baseline.
 */
@Suppress("MagicNumber")
object ZappGiftCardStocks {
    val Obsidian =
        ZappGiftCardStock(
            face = Color(0xFF1E1A16),
            edge = Color(0xFF3A342D),
            sheen = Color(0x14FFFFFF),
            ink = Color(0xFFF6F2EA),
            inkMuted = Color(0xFFA59C90),
            inkFaint = Color(0xFF7A7167),
        )

    val Amber =
        ZappGiftCardStock(
            face = Color(0xFF412B14),
            edge = Color(0xFF8A5C22),
            sheen = Color(0x1FFFB26B),
            ink = Color(0xFFFDF6EC),
            inkMuted = Color(0xFFFFB26B),
            inkFaint = Color(0xFFB07F44),
        )

    val Bone =
        ZappGiftCardStock(
            face = Color(0xFFEAE4D9),
            edge = Color(0xFFD2CBBD),
            sheen = Color(0xB3FFFFFF),
            ink = Color(0xFF15120D),
            inkMuted = Color(0xFF6B645A),
            inkFaint = Color(0xFF9A9288),
        )

    /**
     * Collected. Grey where the others are warm — the colour has drained out of it — but still
     * clearly lighter than the page, because a card you cannot pick out of the background is a
     * hole in the stack rather than a settled card.
     */
    val Spent =
        ZappGiftCardStock(
            face = Color(0xFF2E2C29),
            edge = Color(0xFF474440),
            sheen = Color(0x14FFFFFF),
            ink = Color(0xFFAAA59D),
            inkMuted = Color(0xFF847F77),
            inkFaint = Color(0xFF666159),
        )

    /** The mark on a card that is still out there, whatever stock it is printed on. */
    val LiveMark = Color(0xFFFF9417)
}
