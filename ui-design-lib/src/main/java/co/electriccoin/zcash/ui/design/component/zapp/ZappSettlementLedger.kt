package co.electriccoin.zcash.ui.design.component.zapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.theme.ZappTheme

data class ZappSettlementLedgerRow(
    val label: String,
    val value: String,
    val isDanger: Boolean = false,
)

@Composable
fun ZappSettlementLedger(
    rows: List<ZappSettlementLedgerRow>,
    modifier: Modifier = Modifier,
    notice: String? = null,
    noticeIsDanger: Boolean = false,
) {
    val c = ZappTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(c.surface, RectangleShape)
                .border(BorderStroke(1.dp, c.border), RectangleShape),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (noticeIsDanger) c.danger else c.accent, RectangleShape),
        )
        Column(
            modifier = Modifier.weight(1f).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = row.label,
                        style = ZappTheme.typography.caption.copy(color = c.textMuted),
                    )
                    BasicText(
                        text = row.value,
                        style =
                            ZappTheme.typography.body.copy(
                                color = if (row.isDanger) c.danger else c.text,
                                fontWeight = FontWeight.Medium,
                            ),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            notice?.let {
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (noticeIsDanger) c.dangerSoft else c.accentSoft, RectangleShape)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicText(
                        text = it,
                        style =
                            ZappTheme.typography.caption.copy(
                                color = if (noticeIsDanger) c.danger else c.accentText,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                }
            }
        }
    }
}
