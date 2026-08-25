// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.voting.component

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.design.component.zapp.ZappChipVariant
import co.electriccoin.zcash.ui.design.component.zapp.ZappStatusChip

/** The ZIP number a proposal amends, e.g. "ZIP 233". Reuses the shared square chip. */
@Composable
fun ZipBadge(label: String) = ZappStatusChip(text = label, variant = ZappChipVariant.Muted)
