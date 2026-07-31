package co.electriccoin.zcash.ui.screen.feedback

import androidx.annotation.DrawableRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.TextFieldState

data class FeedbackState(
    val onBack: () -> Unit,
    val emojiState: FeedbackEmojiState,
    val feedback: TextFieldState,
    val sendButton: ButtonState
)

data class FeedbackEmojiState(
    val selection: FeedbackEmoji,
    val onSelected: (FeedbackEmoji) -> Unit,
)

enum class FeedbackEmoji(
    @field:DrawableRes val res: Int,
    val order: Int,
    val encoding: String
) {
    FIRST(
        res = R.drawable.ic_emoji_1,
        order = 1,
        encoding = "😠"
    ),
    SECOND(
        res = R.drawable.ic_emoji_2,
        order = 2,
        encoding = "😒"
    ),
    THIRD(
        res = R.drawable.ic_emoji_3,
        order = 3,
        encoding = "😊"
    ),
    FOURTH(
        res = R.drawable.ic_emoji_4,
        order = 4,
        encoding = "😄"
    ),
    FIFTH(
        res = R.drawable.ic_emoji_5,
        order = 5,
        encoding = "😍"
    )
}
