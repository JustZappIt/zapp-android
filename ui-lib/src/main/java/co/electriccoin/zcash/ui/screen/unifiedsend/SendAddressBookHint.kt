package co.electriccoin.zcash.ui.screen.unifiedsend

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ProvideZappTheme
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme

@Composable
internal fun SendAddressBookHint(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = ZappTheme.colors.accent,
                contentColor = ZappTheme.colors.onAccent
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_send_address_book_hint),
                colorFilter = ColorFilter.tint(ZappTheme.colors.onAccent),
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.send_address_book_hint),
                style = ZappTheme.typography.caption,
                fontWeight = FontWeight.Medium,
                color = ZappTheme.colors.onAccent
            )
        }
    }
}

@PreviewScreens
@Composable
private fun SendAddressBookHintPreview() =
    ZcashTheme {
        ProvideZappTheme {
            Box(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                SendAddressBookHint(Modifier.fillMaxWidth())
            }
        }
    }
