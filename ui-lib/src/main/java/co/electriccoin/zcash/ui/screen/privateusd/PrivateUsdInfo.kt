// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.privateusd

import co.electriccoin.zcash.ui.design.util.StringResource

/** What a screen's "i" explains. */
internal data class PrivateUsdInfo(
    val title: StringResource,
    val steps: List<StringResource> = emptyList(),
    val notes: List<StringResource> = emptyList(),
    /** How the title reads aloud, when its symbols don't. */
    val titleDescription: StringResource? = null,
)
