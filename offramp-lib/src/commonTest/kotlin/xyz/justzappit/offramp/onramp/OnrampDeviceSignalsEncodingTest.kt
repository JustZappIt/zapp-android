package xyz.justzappit.offramp.onramp

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnrampDeviceSignalsEncodingTest {
    @Test
    fun absentTrackingPreferenceIsExplicitButAbsentSeonSessionIsOmitted() {
        val signals =
            OnrampDeviceSignals(
                userAgent = "Zapp/1 (iOS 18; iPhone)",
                platform = "iOS",
                language = "en-IN",
                languages = listOf("en-IN"),
                screenWidth = 1179,
                screenHeight = 2556,
                devicePixelRatio = 3.0,
                timezone = "Asia/Kolkata",
                timezoneOffset = -330,
                cookiesEnabled = true,
                doNotTrack = null,
                online = true,
                touchSupport = true,
                maxTouchPoints = 5,
                vendor = "Apple",
                appVersion = "1.2.3",
                colorDepth = 24,
                pixelDepth = 24,
            )

        val encoded = ONRAMP_REQUEST_JSON.encodeToString(signals)

        assertTrue(encoded.contains("\"doNotTrack\":null"))
        assertTrue(encoded.contains("\"timezoneOffset\":-330"))
        assertFalse(encoded.contains("\"seonSession\""))
    }
}
