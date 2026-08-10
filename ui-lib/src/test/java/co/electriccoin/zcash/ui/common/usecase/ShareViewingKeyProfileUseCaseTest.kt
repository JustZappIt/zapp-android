package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ShareViewingKeyProfileUseCaseTest {
    @Test
    fun emitsExactVersionedSchemaAndPreservesRawKey() {
        val data =
            ViewingKeyExportResult.Available(
                accountLabel = stringRes("Zapp"),
                accountIndex = 0,
                network = ZcashNetwork.Mainnet,
                availableKeyTypes = setOf(ViewingKeyType.UFVK, ViewingKeyType.UIVK),
                keyType = ViewingKeyType.UFVK,
                encodedKey = RAW_KEY,
            )

        val json = Json.parseToJsonElement(createViewingKeyProfileJson(data, "Zapp")).jsonObject

        assertEquals(
            setOf("schema", "network", "account_name", "account_index", "key_type", "viewing_key"),
            json.keys,
        )
        assertEquals("zapp.zcash-viewing-key.v1", json.getValue("schema").jsonPrimitive.content)
        assertEquals("mainnet", json.getValue("network").jsonPrimitive.content)
        assertEquals("Zapp", json.getValue("account_name").jsonPrimitive.content)
        assertEquals("0", json.getValue("account_index").jsonPrimitive.content)
        assertEquals("ufvk", json.getValue("key_type").jsonPrimitive.content)
        assertEquals(RAW_KEY, json.getValue("viewing_key").jsonPrimitive.content)
    }

    private companion object {
        const val RAW_KEY = "uview1exact+native/value_that_must_not_change"
    }
}
