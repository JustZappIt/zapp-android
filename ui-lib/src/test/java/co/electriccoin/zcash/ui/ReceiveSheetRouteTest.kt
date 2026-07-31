package co.electriccoin.zcash.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiveSheetRouteTest {
    @Test
    fun receiveQrAndRequestRoutesUseSheetTransitions() {
        assertTrue("${NavigationTargets.QR_CODE}/{${NavigationArgs.ADDRESS_TYPE}}".isReceiveSheetRoute())
        assertTrue("${NavigationTargets.REQUEST}/{${NavigationArgs.ADDRESS_TYPE}}".isReceiveSheetRoute())
    }

    @Test
    fun otherRoutesKeepStandardTransitions() {
        assertFalse("receive".isReceiveSheetRoute())
        assertFalse(null.isReceiveSheetRoute())
    }
}
