package co.electriccoin.zcash.ui.screen.tabs

import co.electriccoin.zcash.ui.screen.tabs.view.ZappTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectedTabRepositoryTest {
    @Test
    fun `a requested tab is taken up once, so a tab the user picks afterwards stays`() {
        val repository = SelectedTabRepository()

        repository.select(ZappTab.PAY)
        repository.consume(ZappTab.PAY)

        assertNull(repository.requested.value)
    }

    @Test
    fun `a request made while an earlier one is taken up is kept`() {
        val repository = SelectedTabRepository()

        repository.select(ZappTab.PAY)
        repository.select(ZappTab.YOU)
        repository.consume(ZappTab.PAY)

        assertEquals(ZappTab.YOU, repository.requested.value)
    }

    @Test
    fun `the next wallet opens on the default tab`() {
        val repository = SelectedTabRepository()

        repository.reset()

        assertEquals(ZappTab.DEFAULT, repository.requested.value)
    }
}
