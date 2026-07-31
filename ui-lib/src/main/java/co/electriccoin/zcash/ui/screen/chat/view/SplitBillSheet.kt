// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ZecFiatRate
import co.electriccoin.zcash.ui.design.component.ZashiModalBottomSheet
import co.electriccoin.zcash.ui.design.component.zapp.ZappButton
import co.electriccoin.zcash.ui.design.component.zapp.ZappInputField
import co.electriccoin.zcash.ui.design.theme.ZappTheme
import co.electriccoin.zcash.ui.design.util.StringResource.Companion.NUMBER_FORMAT_LOCALE
import co.electriccoin.zcash.ui.screen.chat.room.ChatRoomSplitSheetState
import co.electriccoin.zcash.ui.screen.chat.room.SplitParticipant
import co.electriccoin.zcash.ui.screen.chat.room.SplitShareInput
import co.electriccoin.zcash.ui.screen.request.ext.toBigDecimalLocalized
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat

private const val CURRENCY_ZEC = 0
private const val CURRENCY_FIAT = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitBillSheet(state: ChatRoomSplitSheetState) {
    val c = ZappTheme.colors
    val fiatRate = state.fiatRate
    val canToggleFiat = state.fiatRate != null

    var currency by remember { mutableIntStateOf(if (canToggleFiat) CURRENCY_FIAT else CURRENCY_ZEC) }
    var totalText by remember { mutableStateOf(TextFieldValue("")) }
    var memoText by remember { mutableStateOf(TextFieldValue("")) }
    val shareOverrides = remember { mutableStateMapOf<String, TextFieldValue>() }

    LaunchedEffect(canToggleFiat) {
        if (SplitBillLogic.shouldDefaultToFiat(canToggleFiat, currency, totalText, shareOverrides)) {
            currency = CURRENCY_FIAT
        }
    }

    val isFiat = currency == CURRENCY_FIAT && canToggleFiat
    val unit = SplitBillLogic.unit(isFiat, fiatRate, stringResource(R.string.chat_split_zec_suffix))
    val total = totalText.text.toBigDecimalLocalized()
    val onToggleCurrency: (() -> Unit)? =
        if (canToggleFiat) {
            {
                val next = if (currency == CURRENCY_FIAT) CURRENCY_ZEC else CURRENCY_FIAT
                convertedTotal(next, total, fiatRate)?.let { totalText = TextFieldValue(it) }
                shareOverrides.clear()
                currency = next
            }
        } else {
            null
        }
    val divisor = if (state.isGroup) state.participants.size + 1 else 1
    val equalShare = total?.let { if (divisor > 0) it.divide(BigDecimal(divisor), MathContext.DECIMAL64) else it }
    val shares = computeShares(state.participants, shareOverrides, equalShare, isFiat, fiatRate)
    val canSend = total != null && total > BigDecimal.ZERO && shares.isNotEmpty()

    ZashiModalBottomSheet(
        onDismissRequest = state.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        scrimColor = c.overlay,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(
                text =
                    stringResource(
                        if (state.isGroup) R.string.chat_split_title_group else R.string.chat_split_title_direct,
                    ),
                style = ZappTheme.typography.sectionTitle.copy(color = c.text, fontWeight = FontWeight.Black),
            )

            SplitTotalField(
                totalText = totalText,
                unit = unit,
                equivalent = equivalentOf(total, isFiat, fiatRate),
                onChange = { totalText = it },
                onToggleCurrency = onToggleCurrency,
            )

            if (state.isGroup) {
                SplitParticipants(
                    participants = state.participants,
                    overrides = shareOverrides,
                    equalShare = equalShare,
                    isFiat = isFiat,
                    unit = unit,
                    divisor = divisor,
                )
            }

            ZappInputField(
                value = memoText,
                onValueChange = { memoText = it },
                placeholder = stringResource(R.string.chat_split_memo_placeholder),
            )

            ZappButton(
                text = stringResource(R.string.chat_split_send_button),
                enabled = canSend,
                modifier = Modifier.fillMaxWidth(),
                onClick = { state.onSend(memoText.text.trim(), shares) },
            )
        }
    }
}

@Composable
private fun SplitTotalField(
    totalText: TextFieldValue,
    unit: String,
    equivalent: String?,
    onChange: (TextFieldValue) -> Unit,
    onToggleCurrency: (() -> Unit)?,
) {
    BasicText(
        text = stringResource(R.string.chat_split_total_label),
        style = ZappTheme.typography.chip.copy(color = ZappTheme.colors.textMuted),
    )
    ZappInputField(
        value = totalText,
        onValueChange = onChange,
        placeholder = stringResource(R.string.chat_split_total_placeholder),
        trailingIcon = { UnitLabel(unit, onToggle = onToggleCurrency) },
        keyboardType = KeyboardType.Decimal,
    )
    if (equivalent != null) {
        BasicText(
            text = stringResource(R.string.chat_split_equivalent_fmt, equivalent),
            style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
        )
    }
}

@Composable
private fun SplitParticipants(
    participants: List<SplitParticipant>,
    overrides: SnapshotStateMap<String, TextFieldValue>,
    equalShare: BigDecimal?,
    isFiat: Boolean,
    unit: String,
    divisor: Int,
) {
    BasicText(
        text = stringResource(R.string.chat_split_shares_label_fmt, divisor.toString()),
        style = ZappTheme.typography.chip.copy(color = ZappTheme.colors.textMuted),
    )
    participants.forEach { participant ->
        SplitParticipantRow(
            name = participant.displayName,
            value =
                overrides[participant.publicKey]
                    ?: TextFieldValue(equalShare?.formatAmount(isFiat) ?: ""),
            unit = unit,
            onValueChange = { overrides[participant.publicKey] = it },
        )
    }
}

@Composable
private fun SplitParticipantRow(
    name: String,
    value: TextFieldValue,
    unit: String,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = name,
            style = ZappTheme.typography.body.copy(color = ZappTheme.colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(modifier = Modifier.width(150.dp)) {
            ZappInputField(
                value = value,
                onValueChange = onValueChange,
                placeholder = stringResource(R.string.chat_split_total_placeholder),
                trailingIcon = { UnitLabel(unit) },
                keyboardType = KeyboardType.Decimal,
            )
        }
    }
}

@Composable
private fun UnitLabel(text: String, onToggle: (() -> Unit)? = null) {
    if (onToggle != null) {
        val toggleDescription = stringResource(R.string.chat_split_currency_toggle)
        Box(
            modifier =
                Modifier
                    .clickable(onClick = onToggle)
                    .semantics {
                        role = Role.Button
                        contentDescription = toggleDescription
                    },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = text,
                style =
                    ZappTheme.typography.caption.copy(
                        color = ZappTheme.colors.text,
                        fontWeight = FontWeight.Black,
                    ),
            )
        }
    } else {
        BasicText(
            text = text,
            style = ZappTheme.typography.caption.copy(color = ZappTheme.colors.textMuted),
        )
    }
}

private const val ZEC_DECIMALS = 8
private const val FIAT_DECIMALS = 2

private object SplitBillLogic {
    fun shouldDefaultToFiat(
        canToggleFiat: Boolean,
        currency: Int,
        total: TextFieldValue,
        overrides: Map<String, TextFieldValue>,
    ): Boolean = canToggleFiat && currency == CURRENCY_ZEC && total.text.isBlank() && overrides.isEmpty()

    fun unit(isFiat: Boolean, fiatRate: ZecFiatRate?, zecUnit: String): String =
        if (isFiat) requireNotNull(fiatRate).symbol else zecUnit
}

private fun computeShares(
    participants: List<SplitParticipant>,
    overrides: Map<String, TextFieldValue>,
    equalShare: BigDecimal?,
    isFiat: Boolean,
    fiatRate: ZecFiatRate?,
): List<SplitShareInput> =
    participants.mapNotNull { participant ->
        val display = overrides[participant.publicKey]?.text?.toBigDecimalLocalized() ?: equalShare
        display
            ?.takeIf { it > BigDecimal.ZERO }
            ?.let {
                val zec = if (isFiat && fiatRate != null) fiatRate.fiatToZec(it) else it
                SplitShareInput(participant.publicKey, participant.displayName, zec)
            }
    }

private fun convertedTotal(index: Int, current: BigDecimal?, rate: ZecFiatRate?): String? {
    if (current == null || rate == null) return null
    val toFiat = index == CURRENCY_FIAT
    val converted = if (toFiat) rate.zecToFiat(current) else rate.fiatToZec(current)
    return converted.formatAmount(toFiat)
}

private fun equivalentOf(total: BigDecimal?, isFiat: Boolean, rate: ZecFiatRate?): String? =
    total?.takeIf { it > BigDecimal.ZERO }?.let { amount ->
        if (isFiat && rate != null) {
            "${rate.fiatToZec(amount).formatAmount(false)} ZEC"
        } else if (rate != null) {
            "${rate.symbol}${rate.zecToFiat(amount).formatAmount(true)}"
        } else {
            null
        }
    }

private fun BigDecimal.formatAmount(isFiat: Boolean): String =
    NumberFormat
        .getNumberInstance(NUMBER_FORMAT_LOCALE)
        .apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = if (isFiat) FIAT_DECIMALS else ZEC_DECIMALS
            roundingMode = RoundingMode.HALF_UP
        }.format(this)
