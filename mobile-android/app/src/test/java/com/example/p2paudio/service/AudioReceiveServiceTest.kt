package com.example.p2paudio.service

import android.net.wifi.WifiManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioReceiveServiceTest {

    @Test
    fun receiverWifiLockModeUsesLowLatencyOnAndroidQAndAbove() {
        assertEquals(
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            receiverWifiLockModeForSdk(Build.VERSION_CODES.Q)
        )
        assertEquals(
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            receiverWifiLockModeForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun receiverWifiLockModeUsesHighPerfBeforeAndroidQ() {
        assertEquals(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            receiverWifiLockModeForSdk(Build.VERSION_CODES.P)
        )
    }
}
