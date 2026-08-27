package co.electriccoin.zcash.ui.design.theme.colors

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The figure struck into a card's face, on the stocks that earn one.
 *
 * One field rather than a flag per ornament, because these are registers and a card belongs to
 * exactly one. That includes the brand mark: a face carrying the Z *and* a design is the brand
 * elbowing into a picture that was finished without it, and it reads as a sticker on someone
 * else's work. Making the choice a type rather than a rule means the ladder cannot get it wrong.
 */
@Immutable
sealed interface CardMotif {
    val ink: Color

    /** The Zapp Z, struck into the corner. What a card wears when it has no design of its own. */
    @Immutable
    data class Mark(
        override val ink: Color,
    ) : CardMotif

    /** The splash Z's twin diagonals, swept across the face. */
    @Immutable
    data class Sweep(
        override val ink: Color,
    ) : CardMotif

    /** The same diagonals, heavier and in threes: three tears raked corner to corner. */
    @Immutable
    data class Claw(
        override val ink: Color,
    ) : CardMotif

    /** Guilloché — the banknote line-work. Ceremony where the sweep was energy. */
    @Immutable
    data class Rosette(
        override val ink: Color,
    ) : CardMotif

    /** Interlocking scales, the way lacquerwork carries a dragon. The rarest figure. */
    @Immutable
    data class Scales(
        override val ink: Color,
    ) : CardMotif
}

/**
 * The stock a gift card is printed on.
 *
 * Fixed colours rather than [ZappColors] tokens, and deliberately so: a gift card is an object the
 * sender hands to someone, and the same card has to be the same object in both themes. A clay card
 * that turned cinnabar at dusk would be a different card.
 *
 * No stock has a white or near-white face. A card is a gift, and across much of the world a white
 * card is the one handed over at a funeral — so white is spent on ink, on a hairline of sheen and
 * on the strokes of a figure, never on the field itself.
 *
 * Every face sits clearly lighter than the page it lies on — on a near-black background a card that
 * only just clears its surroundings reads as a panel, not as something with thickness. [sheen] is
 * the light catching the top edge, which is what sells that thickness once a shadow is under it.
 *
 * **Colour is spent inward, not on the rim.** A ladder that says "this card is better" by
 * repainting its border nine times is one idea repeated nine times, and every rung ends up looking
 * like the same card in a different marker. So the lower half of the ladder leaves [edge] a quiet
 * shade of its own face and spends its colour where a reader is already looking: [figureInk], the
 * denomination itself, and then [ring], the rule set in from the edge that also frames the reverse.
 * A red card here is a normal card whose figure happens to be red — which is a far better red card
 * than a red rectangle.
 *
 * [edge] turns to metal only in the top stretch, where the stock genuinely *is* a metal and the rim
 * is the honest place to say so. And once a card has a design of its own, the branding comes off:
 * no Z, no wordmark. The top of a ladder should feel like an object, not like an advertisement.
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
    /** Foil. Reserved for the metals at the top; below them the rim stays a shade of the face. */
    val edgeWidth: Dp = HAIRLINE,
    /** Which figure the face carries, if any. See [CardMotif]. */
    val motif: CardMotif? = null,
    /**
     * The denomination struck in colour rather than plain [ink] — the ladder's main lever, and the
     * one that costs nothing and shows most. Null leaves the figure in ink, which is what the two
     * bare stocks want: a coloured figure on the cheapest card reads as decoration, not as value.
     */
    val figureInk: Color? = null,
    /**
     * A rule set in from the edge, the way a banknote frames its own field — and the same colour
     * the reverse frames itself in, so the two faces of a card are recognisably one card.
     */
    val ring: Color? = null,
    /**
     * Whether the card signs itself "Zapp" along its bottom edge.
     *
     * False from [Tiger] up. A card carrying a claw or a dragon is already saying something, and a
     * wordmark under it turns the thing into a branded item — which is exactly what the expensive
     * end of a ladder should not feel like. The cheap cards keep it, where it reads as a maker's
     * mark rather than as advertising.
     */
    val showsWordmark: Boolean = true,
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
    /** Clay. Warm dark paper, and the everyday card: a coffee, a round, a thank-you. */
    val Clay =
        ZappGiftCardStock(
            face = Color(0xFF2A231C),
            core = Color(0xFF15110D),
            edge = Color(0xFF453B31),
            sheen = Color(0x17FFFFFF),
            ink = Color(0xFFEDE6DB),
            inkMuted = Color(0xFFA79C8D),
            inkFaint = Color(0xFF7B7365),
        )

    /**
     * Slate. Still paper, but a cooler and deeper sheet, so the first rung up is a change of
     * temperature rather than of material — which is the only lever the bare stocks have.
     */
    val Slate =
        ZappGiftCardStock(
            face = Color(0xFF1D2226),
            core = Color(0xFF0E1113),
            edge = Color(0xFF343C42),
            sheen = Color(0x1AFFFFFF),
            ink = Color(0xFFE7ECEF),
            inkMuted = Color(0xFF9AA3A9),
            inkFaint = Color(0xFF6E767B),
        )

    /**
     * Cinnabar. A plain charcoal card whose figure is struck in red — the first stock to spend any
     * colour at all, and it spends it entirely on the number. Red is the colour a gift arrives in
     * across most of the world, and putting it in the ink rather than round the rim is what keeps
     * the card a card instead of a red rectangle.
     */
    val Cinnabar =
        ZappGiftCardStock(
            face = Color(0xFF201E1C),
            core = Color(0xFF100F0E),
            edge = Color(0xFF373430),
            sheen = Color(0x1AFFFFFF),
            ink = Color(0xFFEDEAE5),
            inkMuted = Color(0xFFA09A92),
            inkFaint = Color(0xFF746F68),
            figureInk = Color(0xFFF06055),
        )

    /**
     * Vermilion. [Cinnabar]'s red, hotter, on a darker card — and the rung where the ring arrives,
     * so the red now frames the field as well as filling the figure. Still nothing on the rim.
     */
    val Vermilion =
        ZappGiftCardStock(
            face = Color(0xFF1A1817),
            core = Color(0xFF0C0B0B),
            edge = Color(0xFF302D2B),
            sheen = Color(0x1FFFFFFF),
            ink = Color(0xFFF2EEE9),
            inkMuted = Color(0xFFA8A19A),
            inkFaint = Color(0xFF787269),
            figureInk = Color(0xFFFF6A57),
            ring = Color(0x2EFF5A45),
        )

    /**
     * Copper. The first stock to carry the mark, and the last one whose rim stays quiet: the metal
     * is in the ink and the ring, not round the edge. A card that says copper without being trimmed
     * in it is a better copper card than one that is.
     */
    val Copper =
        ZappGiftCardStock(
            face = Color(0xFF1F1B17),
            core = Color(0xFF0F0D0B),
            edge = Color(0xFF39332C),
            sheen = Color(0x1AFFE8D0),
            ink = Color(0xFFF2EBE2),
            inkMuted = Color(0xFFD08A50),
            inkFaint = Color(0xFF8A6038),
            motif = CardMotif.Mark(Color(0x1AD08A50)),
            figureInk = Color(0xFFE09A5A),
            ring = Color(0x24C98550),
        )

    /**
     * Amber foil. The brand colour, and the rung where the rim finally becomes metal — the brand's
     * own stock is the right place to start spending it. The mark gives way to the splash sweep:
     * the Z stops being stamped on the card and starts being drawn across it.
     */
    val Amber =
        ZappGiftCardStock(
            face = Color(0xFF261B0C),
            core = Color(0xFF130D05),
            edge = Color(0xFFFF9417),
            sheen = Color(0x24FFB26B),
            ink = Color(0xFFFDF6EC),
            inkMuted = Color(0xFFC9A47A),
            inkFaint = Color(0xFF8E7550),
            edgeWidth = ZappGiftCardStock.FOIL,
            motif = CardMotif.Sweep(Color(0x24FFB26B)),
            figureInk = Color(0xFFFFB26B),
            ring = Color(0x24FF9417),
        )

    /**
     * Tiger. The black card. Three heavy tears raked corner to corner on the splash Z's own
     * diagonal — the brand's angle, put on the animal rather than on the logo. The rim goes dark
     * on purpose: a heavy black rule around a black card, with every scrap of colour saved for the
     * claws, and no Z anywhere near them.
     */
    val Tiger =
        ZappGiftCardStock(
            face = Color(0xFF0C0A08),
            core = Color(0xFF040302),
            edge = Color(0xFF3A2E1E),
            sheen = Color(0x2EFFA85C),
            ink = Color(0xFFFDF4E9),
            inkMuted = Color(0xFFE2A165),
            inkFaint = Color(0xFF8C6A44),
            edgeWidth = ZappGiftCardStock.FOIL,
            motif = CardMotif.Claw(Color(0x38FF9417)),
            figureInk = Color(0xFFFFB26B),
            ring = Color(0x2EF07C1E),
            showsWordmark = false,
        )

    /**
     * Signature. Gold foil on near-black, and the card that stops shouting: the claw gives way to
     * engraving, so what was feral becomes formal. The card you hand someone once.
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
            motif = CardMotif.Rosette(Color(0x1FE0B056)),
            figureInk = Color(0xFFE0B056),
            ring = Color(0x2BE0B056),
            showsWordmark = false,
        )

    /**
     * Dragon. Red scales under gold, and the top of the ladder. Same metal as [Signature] — there
     * is no richer one to reach for — so the distinction is the figure: the engraved rosette gives
     * way to a field of scales, and those scales are red rather than another pass of the gold.
     *
     * The two colours run at different depths, which is the whole reason it works: the gold is the
     * rim, the ring and the figure, so it stays the card's furniture, and the red is only ever the
     * hide underneath it. Ground pushed nearly to black so the scales have something to be seen
     * against — on the oxblood they were first drawn over, red on red went quiet. Unsigned and
     * unmarked, which is most of why it reads as the best card in the deck.
     */
    val Dragon =
        ZappGiftCardStock(
            face = Color(0xFF14090A),
            core = Color(0xFF080405),
            edge = Color(0xFFE8C06A),
            sheen = Color(0x33F0D392),
            ink = Color(0xFFFBF2E2),
            inkMuted = Color(0xFFE8C06A),
            inkFaint = Color(0xFF9C8248),
            edgeWidth = ZappGiftCardStock.FOIL,
            motif = CardMotif.Scales(Color(0x40F0483A)),
            figureInk = Color(0xFFF2D289),
            ring = Color(0x33E8C06A),
            showsWordmark = false,
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
