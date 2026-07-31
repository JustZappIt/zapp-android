package co.electriccoin.zcash.ui.preference

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthMethodTest {
    @Test
    fun `persisted values round trip`() {
        AuthMethod.entries.forEach { method ->
            assertEquals(method, AuthMethod.fromPersistedValue(method.persistedValue))
        }
    }

    @Test
    fun `unknown persisted value fails closed`() {
        assertEquals(AuthMethod.NONE, AuthMethod.fromPersistedValue("unexpected"))
    }
}
