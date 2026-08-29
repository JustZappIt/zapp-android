// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.reclaim.OnChainProof
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `socialVerify` is the one write in this feature and it carries a nested dynamic tuple — a
 * `bytes[]` inside a struct inside a struct inside an array — which is where a hand-rolled ABI
 * encoder goes wrong silently: the call still submits, the attestor signature recovers to some
 * other address, and the revert names nothing.
 *
 * The fixture is viem's `encodeFunctionData` over claim data lifted from a real X verification on
 * Base mainnet (tx `0xe2c1e551…f792ac`), including its two attestor claims and their real
 * signatures. Long provider strings are shortened; nothing structural is.
 */
class SocialVerifyCalldataParityTest {
    @Test
    fun `socialVerify calldata matches viem`() {
        val got = ReputationCalls.socialVerifyCalldata(SocialPlatform.X, listOf(FIRST_CLAIM, SECOND_CLAIM))
        assertEquals(VIEM_SOCIAL_VERIFY, "0x" + got.toHex())
    }

    @Test
    fun `the on-chain social names keep the contract's exact casing`() {
        // Case-sensitive on chain: "x" or "linkedin" verifies nothing, and the failure is a
        // revert with no cause. Pinned so a rename or a lowercasing helper cannot reach them.
        assertEquals(
            listOf("LinkedIn", "X", "GitHub", "Instagram", "Facebook", "Binance"),
            SocialPlatform.entries.map { it.onChainName },
        )
    }

    @Test
    fun `a proof with no signature is refused before it reaches the chain`() {
        assertFailsWith<IllegalArgumentException> {
            FIRST_CLAIM.copy(signatures = emptyList())
        }
    }

    @Test
    fun `an empty proof list is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ReputationCalls.socialVerifyCalldata(SocialPlatform.X, emptyList())
        }
    }

    private companion object {
        val FIRST_CLAIM =
            OnChainProof(
                provider = "http",
                parameters = "{\"url\":\"https://x.com/i/api/graphql\",\"method\":\"GET\"}",
                context =
                    "{\"contextAddress\":\"0x448f857Ea117138E85D062C6Ce89E90A337874d6\"," +
                        "\"contextMessage\":\"Social verification for X\"}",
                identifier =
                    "0xdf3367a28dfb087c7bfd98cf3919dd60e7a92fd1e355abccf48f7dc789cf4555".hexToBytes(),
                owner = Address.parse("0xdb529A7486D971101c0BA1Ec3E389Dac4BE5a61F"),
                timestampS = 1_788_011_447L,
                epoch = 1L,
                signatures =
                    listOf(
                        (
                            "0x24c4b4521dbc34f0096d759f992e01e8cafd803b65d055756d25ff51f4ccb057" +
                                "2ab54a9479e77682bcd2c72ef450b71d92d4b452367f45eee6edd37be444d3341b"
                        ).hexToBytes(),
                    ),
            )

        val SECOND_CLAIM =
            OnChainProof(
                provider = "http",
                parameters = "{\"url\":\"https://api.x.com/1.1/account/settings.json\"}",
                context =
                    "{\"reclaimSessionId\":\"f721543b42\"," +
                        "\"tee_session_id\":\"432f77d4-4c54-4321-a008-0bf0324e28f6\"}",
                identifier =
                    "0x0e9a43bc690ce1ffcc5429e3df3beb4c215bfde4cd176d739254028427ae3a22".hexToBytes(),
                owner = Address.parse("0xdABB093a9512f8Dc9B1671E3C3b265a4758e710c"),
                timestampS = 1_788_011_446L,
                epoch = 1L,
                signatures =
                    listOf(
                        (
                            "0xd116811a750e14f73aa5692c1c59d28f95e22716418c7ea8eb4aeb971a87485a" +
                                "0b6b520607ecc13b5f4208d52602b10c6b9cd7344d8b68aad3c1c04866628d491b"
                        ).hexToBytes(),
                    ),
            )

        const val VIEM_SOCIAL_VERIFY =
            "0x233c4f0f" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000000000080" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "5800000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000002" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000000000380" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "00000000000000000000000000000000000000000000000000000000000001e0" +
                "0000000000000000000000000000000000000000000000000000000000000060" +
                "00000000000000000000000000000000000000000000000000000000000000a0" +
                "0000000000000000000000000000000000000000000000000000000000000100" +
                "0000000000000000000000000000000000000000000000000000000000000004" +
                "6874747000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000034" +
                "7b2275726c223a2268747470733a2f2f782e636f6d2f692f6170692f67726170" +
                "68716c222c226d6574686f64223a22474554227d000000000000000000000000" +
                "000000000000000000000000000000000000000000000000000000000000006c" +
                "7b22636f6e7465787441646472657373223a2230783434386638353745613131" +
                "3731333845383544303632433643653839453930413333373837346436222c22" +
                "636f6e746578744d657373616765223a22536f6369616c207665726966696361" +
                "74696f6e20666f722058227d0000000000000000000000000000000000000000" +
                "df3367a28dfb087c7bfd98cf3919dd60e7a92fd1e355abccf48f7dc789cf4555" +
                "000000000000000000000000db529a7486d971101c0ba1ec3e389dac4be5a61f" +
                "000000000000000000000000000000000000000000000000000000006a92e3b7" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "00000000000000000000000000000000000000000000000000000000000000a0" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000041" +
                "24c4b4521dbc34f0096d759f992e01e8cafd803b65d055756d25ff51f4ccb057" +
                "2ab54a9479e77682bcd2c72ef450b71d92d4b452367f45eee6edd37be444d334" +
                "1b00000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "00000000000000000000000000000000000000000000000000000000000001c0" +
                "0000000000000000000000000000000000000000000000000000000000000060" +
                "00000000000000000000000000000000000000000000000000000000000000a0" +
                "0000000000000000000000000000000000000000000000000000000000000100" +
                "0000000000000000000000000000000000000000000000000000000000000004" +
                "6874747000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000035" +
                "7b2275726c223a2268747470733a2f2f6170692e782e636f6d2f312e312f6163" +
                "636f756e742f73657474696e67732e6a736f6e227d0000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000059" +
                "7b227265636c61696d53657373696f6e4964223a226637323135343362343222" +
                "2c227465655f73657373696f6e5f6964223a2234333266373764342d34633534" +
                "2d343332312d613030382d306266303332346532386636227d00000000000000" +
                "0e9a43bc690ce1ffcc5429e3df3beb4c215bfde4cd176d739254028427ae3a22" +
                "000000000000000000000000dabb093a9512f8dc9b1671e3c3b265a4758e710c" +
                "000000000000000000000000000000000000000000000000000000006a92e3b6" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "00000000000000000000000000000000000000000000000000000000000000a0" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000041" +
                "d116811a750e14f73aa5692c1c59d28f95e22716418c7ea8eb4aeb971a87485a" +
                "0b6b520607ecc13b5f4208d52602b10c6b9cd7344d8b68aad3c1c04866628d49" +
                "1b00000000000000000000000000000000000000000000000000000000000000"
    }
}
