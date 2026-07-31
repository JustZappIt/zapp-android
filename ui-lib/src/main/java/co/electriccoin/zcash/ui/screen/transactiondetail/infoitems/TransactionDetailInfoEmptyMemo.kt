package co.electriccoin.zcash.ui.screen.transactiondetail.infoitems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.theme.ZcashTheme

@Composable
fun TransactionDetailInfoEmptyMemo(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        color = ZappTheme.colors.surface,
        border = BorderStroke(1.dp, ZappTheme.colors.border)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_transaction_detail_empty_message),
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                modifier = Modifier,
                text = stringResource(R.string.transaction_detail_memo_empty),
                style = ZappTheme.typography.body,
                color = ZappTheme.colors.textMuted,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            TransactionDetailInfoEmptyMemo(
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
