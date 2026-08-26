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
 * The stocks form a ladder rather than a palette, and it runs in three stretches. At the bottom
 * the cards differ by material alone — bone, then linen, then graphite — because a cheap card
 * earns no ornament. The middle picks up the mark, then metal at the edge, then a coloured
 * figure, then the splash sweep, then a printed ring. At the top the register changes: the sweep
 * gives way to engraving, and the last two are the same formal card in rarer metal.
 *
 * Each rung adds one thing the rung below does not have. Where two rungs share a lever set they
 * are deliberately a matched pair — gold and platinum at the top, the three papers at the bottom —
 * and the material is the distinction.
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
    /**
     * Guilloché — the banknote line-work.
     *
     * Never set alongside [spark]. The two are different registers, and a card wearing both is
     * saying two things at once: the sweep is energy and the line-work is ceremony. The ladder
     * runs the sweep through its middle and then hands over to engraving at the top, so climbing
     * past the changeover reads as a card that has stopped shouting and started being formal.
     */
    val engraving: Color? = null,
    /**
     * The denomination struck in colour rather than plain [ink]. Null leaves the figure in ink —
     * which is what most of the ladder wants, because a coloured figure on a cheap stock reads as
     * decoration rather than as value.
     */
    val figureInk: Color? = null,
    /**
     * The splash Z's twin diagonals, swept across the face. The brand's own mark rather than a
     * generic sheen. Mutually exclusive with [engraving] — see the note there.
     */
    val spark: Color? = null,
    /**
     * A rule set in from the edge, the way a banknote frames its own field. Cheap to print and
     * loud at a glance, so it does the work where colour alone would have to: the black card,
     * and the two engraved stocks that want a frame around the line-work.
     */
    val ring: Color? = null,
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

    /**
     * Linen. Still paper, but a heavier sheet — warmer and a shade deeper than [Paper], so the
     * first rung up is a better version of the same thing rather than a different material.
     */
    val Linen =
        ZappGiftCardStock(
            face = Color(0xFFDED5C4),
            core = Color(0xFFB3A88F),
            edge = Color(0xFFC8BDA6),
            sheen = Color(0xA6FFFFFF),
            ink = Color(0xFF17140E),
            inkMuted = Color(0xFF6A6255),
            inkFaint = Color(0xFF979083),
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

    /**
     * Copper. The first stock with metal at its edge — a cheaper metal than the brand's own, so
     * arriving at [Amber] still reads as an arrival.
     */
    val Copper =
        ZappGiftCardStock(
            face = Color(0xFF2A1E15),
            core = Color(0xFF150E09),
            edge = Color(0xFFB87333),
            sheen = Color(0x1FD08A50),
            ink = Color(0xFFF4EAE0),
            inkMuted = Color(0xFFD08A50),
            inkFaint = Color(0xFF8A6038),
            edgeWidth = ZappGiftCardStock.FOIL,
            watermark = Color(0x14B87333),
            figureInk = Color(0xFFE09A5A),
        )

    /**
     * Amber foil. The brand colour, spent on the edge where it reads as metal rather than paint,
     * and the rung where the splash sweep arrives — the brand's own stock is the right place for
     * the brand's own mark.
     */
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
            figureInk = Color(0xFFFFB26B),
            spark = Color(0x22FFB26B),
        )

    /**
     * Onyx. The black card, and the only stock that drops the ladder's warmth without replacing it
     * with a metal. Everything on it is white on near-nothing: no colour to spend means the ring
     * and the sweep have to carry it, which is the point — this is the last rung before the top,
     * and restraint reads richer here than another shade of gold would.
     */
    val Onyx =
        ZappGiftCardStock(
            face = Color(0xFF08080A),
            core = Color(0xFF030304),
            edge = Color(0xFFE8E4DC),
            sheen = Color(0x2EFFFFFF),
            ink = Color(0xFFFFFFFF),
            inkMuted = Color(0xFFB6B2AC),
            inkFaint = Color(0xFF6E6B67),
            edgeWidth = ZappGiftCardStock.FOIL,
            watermark = Color(0x1FE8E4DC),
            figureInk = Color(0xFFFFFFFF),
            spark = Color(0x26E8E4DC),
            ring = Color(0x2EE8E4DC),
        )

    /**
     * Signature. Gold foil on near-black. The rung where the register changes: the sweep gives way
     * to engraving and a frame, so the card stops shouting and starts being formal. The card you
     * hand someone once.
     */
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
            figureInk = Color(0xFFE0B056),
            ring = Color(0x2BE0B056),
        )

    /**
     * Aurora. [Signature]'s treatment in a rarer metal — same frame, same engraving, cold where
     * the gold was warm. At the top the ornament is settled and the material is the distinction,
     * which is how the rungs below it read too: the three papers differ the same way.
     */
    val Aurora =
        ZappGiftCardStock(
            face = Color(0xFF0B0D12),
            core = Color(0xFF050609),
            edge = Color(0xFFC8D4E8),
            sheen = Color(0x33C8D4E8),
            ink = Color(0xFFF2F5FA),
            inkMuted = Color(0xFFC8D4E8),
            inkFaint = Color(0xFF7E8AA0),
            edgeWidth = ZappGiftCardStock.FOIL,
            watermark = Color(0x18C8D4E8),
            engraving = Color(0x22C8D4E8),
            figureInk = Color(0xFFDCE6F7),
            ring = Color(0x33C8D4E8),
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
