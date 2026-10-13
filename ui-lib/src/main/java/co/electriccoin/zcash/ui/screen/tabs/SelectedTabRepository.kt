package co.electriccoin.zcash.ui.screen.tabs

import co.electriccoin.zcash.ui.screen.tabs.view.ZappTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A tab a screen above the tab shell asks it to show next, taken up once; the shell keeps the tab it shows. */
internal class SelectedTabRepository {
    private val mutableRequested = MutableStateFlow<ZappTab?>(null)
    val requested: StateFlow<ZappTab?> = mutableRequested.asStateFlow()

    fun select(tab: ZappTab) {
        mutableRequested.value = tab
    }

    /** The shell shows [tab] now; a request made since stays. */
    fun consume(tab: ZappTab) {
        mutableRequested.compareAndSet(tab, null)
    }

    /** The next wallet opens where a new one does. */
    fun reset() = select(ZappTab.DEFAULT)
}
