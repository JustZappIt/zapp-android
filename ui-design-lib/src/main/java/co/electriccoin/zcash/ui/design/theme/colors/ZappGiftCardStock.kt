package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 *
 * The stocks form a ladder rather than a palette. As a denomination climbs, the card picks up a
 * heavier edge, then a watermark, then engraving — so the difference between a small gift and a
 * large one is something you see before you read the figure.
 */
@Immutable
data class ZappGiftCardStock(
    val face: Color,
    /** The card seen edge-on. A card with no visible thickness is a rectangle. */
    val core: Color,
    val edge: Color,
    val sheen: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    /** Foil, on the stocks that earn it. A heavier rule is the cheapest signal of a richer card. */
    val edgeWidth: Dp = HAIRLINE,
    /** The Zapp mark, struck into the face. Null on the plainer stocks. */
    val watermark: Color? = null,
    /** Guilloché — the banknote line-work. Reserved for the top of the ladder. */
    val engraving: Color? = null,
) {
    companion object {
        val HAIRLINE = 1.dp
        val FOIL = 2.dp
    }
}

/**
 * The stocks a card can be printed on, cheapest first. Which one a card gets is decided where the
 * card is built — this only says what each looks like.
 *
 * Hex colour literals, as in [ZappColors] — which carries the same finding in the detekt baseline.
 */
@Suppress("MagicNumber")
object ZappGiftCardStocks {
    /** Paper. The everyday card: a coffee, a round, a thank-you. */
    val Paper =
        ZappGiftCardStock(
            face = Color(0xFFEAE4D9),
            core = Color(0xFFBFB6A5),
            edge = Color(0xFFD2CBBD),
            sheen = Color(0xB3FFFFFF),
            ink = Color(0xFF15120D),
            inkMuted = Color(0xFF6B645A),
            inkFaint = Color(0xFF9A9288),
        )

    /** Graphite. Cooler than everything above it, so the step off paper is a change of material. */
    val Graphite =
        ZappGiftCardStock(
            face = Color(0xFF20211F),
            core = Color(0xFF121311),
            edge = Color(0xFF3B3D3A),
            sheen = Color(0x14FFFFFF),
            ink = Color(0xFFECEDEA),
            inkMuted = Color(0xFF9EA09C),
            inkFaint = Color(0xFF74766F),
        )

    /** Obsidian. Warm black, and the first stock to carry the mark. */
    val Obsidian =
        ZappGiftCardStock(
            face = Color(0xFF1E1A16),
            core = Color(0xFF0E0C0A),
            edge = Color(0xFF3A342D),
            sheen = Color(0x14FFFFFF),
            ink = Color(0xFFF6F2EA),
            inkMuted = Color(0xFFA59C90),
            inkFaint = Color(0xFF7A7167),
            watermark = Color(0x0DF6F2EA),
        )

    /** Amber foil. The brand colour, spent on the edge where it reads as metal rather than paint. */
    val Amber =
        ZappGiftCardStock(
            face = Color(0xFF3E2A12),
            core = Color(0xFF23170A),
            edge = Color(0xFFFF9417),
            sheen = Color(0x1FFFB26B),
            ink = Color(0xFFFDF6EC),
            inkMuted = Color(0xFFFFB26B),
            inkFaint = Color(0xFFB07F44),
            edgeWidth = ZappGiftCardStock.FOIL,
            watermark = Color(0x1AFF9417),
        )

    /** Signature. Gold foil on near-black, engraved. The card you hand someone once. */
    val Signature =
        ZappGiftCardStock(
            face = Color(0xFF12100C),
            core = Color(0xFF060504),
            edge = Color(0xFFE0B056),
            sheen = Color(0x33E0B056),
            ink = Color(0xFFF7EFDF),
            inkMuted = Color(0xFFE0B056),
            inkFaint = Color(0xFF9C7B3C),
            edgeWidth = ZappGiftCardStock.FOIL,
            watermark = Color(0x14E0B056),
            engraving = Color(0x1FE0B056),
        )

    /**
     * Collected. Grey where the others are warm — the colour has drained out of it — but still
     * clearly lighter than the page, because a card you cannot pick out of the background is a
     * hole in the stack rather than a settled card.
     */
    val Spent =
        ZappGiftCardStock(
            face = Color(0xFF2E2C29),
            core = Color(0xFF1A1917),
            edge = Color(0xFF474440),
            sheen = Color(0x14FFFFFF),
            ink = Color(0xFFAAA59D),
            inkMuted = Color(0xFF847F77),
            inkFaint = Color(0xFF666159),
        )

    /** The mark on a card that is still out there, whatever stock it is printed on. */
    val LiveMark = Color(0xFFFF9417)
}
