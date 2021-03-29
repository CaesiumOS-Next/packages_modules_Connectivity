/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE_AVOID
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE_IGNORE
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE_PROMPT
import android.net.ConnectivitySettingsManager.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS
import android.net.ConnectivitySettingsManager.DATA_ACTIVITY_TIMEOUT_MOBILE
import android.net.ConnectivitySettingsManager.DATA_ACTIVITY_TIMEOUT_WIFI
import android.net.ConnectivitySettingsManager.DNS_RESOLVER_MAX_SAMPLES
import android.net.ConnectivitySettingsManager.DNS_RESOLVER_MIN_SAMPLES
import android.net.ConnectivitySettingsManager.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS
import android.net.ConnectivitySettingsManager.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT
import android.net.ConnectivitySettingsManager.MOBILE_DATA_ALWAYS_ON
import android.net.ConnectivitySettingsManager.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT
import android.net.ConnectivitySettingsManager.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS
import android.net.ConnectivitySettingsManager.PRIVATE_DNS_DEFAULT_MODE
import android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_OFF
import android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
import android.net.ConnectivitySettingsManager.WIFI_ALWAYS_REQUESTED
import android.net.ConnectivitySettingsManager.getCaptivePortalMode
import android.net.ConnectivitySettingsManager.getConnectivityKeepPendingIntentDuration
import android.net.ConnectivitySettingsManager.getDnsResolverSampleRanges
import android.net.ConnectivitySettingsManager.getDnsResolverSampleValidityDuration
import android.net.ConnectivitySettingsManager.getDnsResolverSuccessThresholdPercent
import android.net.ConnectivitySettingsManager.getMobileDataActivityTimeout
import android.net.ConnectivitySettingsManager.getMobileDataAlwaysOn
import android.net.ConnectivitySettingsManager.getNetworkSwitchNotificationMaximumDailyCount
import android.net.ConnectivitySettingsManager.getNetworkSwitchNotificationRateDuration
import android.net.ConnectivitySettingsManager.getPrivateDnsDefaultMode
import android.net.ConnectivitySettingsManager.getPrivateDnsModeAsString
import android.net.ConnectivitySettingsManager.getWifiAlwaysRequested
import android.net.ConnectivitySettingsManager.getWifiDataActivityTimeout
import android.net.ConnectivitySettingsManager.setCaptivePortalMode
import android.net.ConnectivitySettingsManager.setConnectivityKeepPendingIntentDuration
import android.net.ConnectivitySettingsManager.setDnsResolverSampleRanges
import android.net.ConnectivitySettingsManager.setDnsResolverSampleValidityDuration
import android.net.ConnectivitySettingsManager.setDnsResolverSuccessThresholdPercent
import android.net.ConnectivitySettingsManager.setMobileDataActivityTimeout
import android.net.ConnectivitySettingsManager.setMobileDataAlwaysOn
import android.net.ConnectivitySettingsManager.setNetworkSwitchNotificationMaximumDailyCount
import android.net.ConnectivitySettingsManager.setNetworkSwitchNotificationRateDuration
import android.net.ConnectivitySettingsManager.setPrivateDnsDefaultMode
import android.net.ConnectivitySettingsManager.setWifiAlwaysRequested
import android.net.ConnectivitySettingsManager.setWifiDataActivityTimeout
import android.os.Build
import android.platform.test.annotations.AppModeFull
import android.provider.Settings
import android.util.Range
import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import junit.framework.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.Objects
import kotlin.test.assertFailsWith

/**
 * Tests for [ConnectivitySettingsManager].
 *
 * Build, install and run with:
 * atest android.net.ConnectivitySettingsManagerTest
 */
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
@SmallTest
@AppModeFull(reason = "WRITE_SECURE_SETTINGS permission can't be granted to instant apps")
class ConnectivitySettingsManagerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val resolver = context.contentResolver

    private val defaultDuration = Duration.ofSeconds(0L)
    private val testTime1 = 5L
    private val testTime2 = 10L

    /*** Reset setting value or delete setting if the setting was not existed before testing. */
    private fun <T> resetSettings(
        name: String,
        type: String,
        value: T,
        resetter: (cr: ContentResolver, n: String, v: T) -> Unit
    ) {
        if (value == -1 || Objects.equals(value, null)) {
            instrumentation.uiAutomation.executeShellCommand("settings delete $type $name")
        } else {
            resetter(resolver, name, value)
        }
    }

    fun <T, R> testSetting(
        name: String,
        type: String,
        value1: T,
        value2: T,
        def: T,
        getter: (c: Context, d: T) -> T,
        setter: (c: Context, v: T) -> Unit,
        defValue: R,
        testValue: R,
        getSetting: (cr: ContentResolver, n: String, d: R) -> R,
        putSetting: (cr: ContentResolver, n: String, v: R) -> Unit
    ) {
        val original = getSetting(resolver, name, defValue)

        try {
            putSetting(resolver, name, testValue)
            assertEquals(value1, getter(context, def))

            setter(context, value2)
            assertEquals(value2, getter(context, def))
        } finally {
            resetSettings(name, type, original, resetter = { cr, n, v -> putSetting(cr, n, v) })
        }
    }

    @Test
    fun testMobileDataActivityTimeout() {
        testSetting(name = DATA_ACTIVITY_TIMEOUT_MOBILE, type = "global",
                value1 = Duration.ofSeconds(testTime1), value2 = Duration.ofSeconds(testTime2),
                def = defaultDuration,
                getter = { c, d -> getMobileDataActivityTimeout(c, d) },
                setter = { c, v -> setMobileDataActivityTimeout(c, v) },
                defValue = -1, testValue = testTime1.toInt(),
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })
    }

    @Test
    fun testWifiDataActivityTimeout() {
        testSetting(name = DATA_ACTIVITY_TIMEOUT_WIFI, type = "global",
                value1 = Duration.ofSeconds(testTime1), value2 = Duration.ofSeconds(testTime2),
                def = defaultDuration,
                getter = { c, d -> getWifiDataActivityTimeout(c, d) },
                setter = { c, v -> setWifiDataActivityTimeout(c, v) },
                defValue = -1, testValue = testTime1.toInt(),
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })
    }

    @Test
    fun testDnsResolverSampleValidityDuration() {
        testSetting(name = DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS, type = "global",
                value1 = Duration.ofSeconds(testTime1), value2 = Duration.ofSeconds(testTime2),
                def = defaultDuration,
                getter = { c, d -> getDnsResolverSampleValidityDuration(c, d) },
                setter = { c, v -> setDnsResolverSampleValidityDuration(c, v) },
                defValue = -1, testValue = testTime1.toInt(),
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleValidityDuration(context, Duration.ofSeconds(-1L)) }
    }

    @Test
    fun testDnsResolverSuccessThresholdPercent() {
        testSetting(name = DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT, type = "global",
                value1 = 5, value2 = 10, def = 0,
                getter = { c, d -> getDnsResolverSuccessThresholdPercent(c, d) },
                setter = { c, v -> setDnsResolverSuccessThresholdPercent(c, v) },
                defValue = -1, testValue = 5,
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSuccessThresholdPercent(context, -1) }
        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSuccessThresholdPercent(context, 120) }
    }

    @Test
    fun testDnsResolverSampleRanges() {
        val originalMin = Settings.Global.getInt(resolver, DNS_RESOLVER_MIN_SAMPLES, -1 /* def */)
        val originalMax = Settings.Global.getInt(resolver, DNS_RESOLVER_MAX_SAMPLES, -1 /* def */)

        try {
            val min1 = 1
            val max1 = 63
            Settings.Global.putInt(resolver, DNS_RESOLVER_MIN_SAMPLES, min1)
            Settings.Global.putInt(resolver, DNS_RESOLVER_MAX_SAMPLES, max1)

            var range = getDnsResolverSampleRanges(context)
            assertEquals(min1, range.lower)
            assertEquals(max1, range.upper)

            val min2 = 2
            val max2 = 62
            setDnsResolverSampleRanges(context, Range(min2, max2))
            range = getDnsResolverSampleRanges(context)
            assertEquals(min2, range.lower)
            assertEquals(max2, range.upper)
        } finally {
            resetSettings(name = DNS_RESOLVER_MIN_SAMPLES, type = "global", value = originalMin,
                    resetter = { cr, n, v -> Settings.Global.putInt(cr, n, v) })
            resetSettings(name = DNS_RESOLVER_MAX_SAMPLES, type = "global", value = originalMax,
                    resetter = { cr, n, v -> Settings.Global.putInt(cr, n, v) })
        }

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleRanges(context, Range(-1, 62)) }
        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleRanges(context, Range(2, 65)) }
    }

    @Test
    fun testNetworkSwitchNotificationMaximumDailyCount() {
        testSetting(name = NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT, type = "global",
                value1 = 5, value2 = 15, def = 0,
                getter = { c, d -> getNetworkSwitchNotificationMaximumDailyCount(c, d) },
                setter = { c, v -> setNetworkSwitchNotificationMaximumDailyCount(c, v) },
                defValue = -1, testValue = 5,
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setNetworkSwitchNotificationMaximumDailyCount(context, -1) }
    }

    @Test
    fun testNetworkSwitchNotificationRateDuration() {
        testSetting(name = NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS, type = "global",
                value1 = Duration.ofMillis(testTime1), value2 = Duration.ofMillis(testTime2),
                def = defaultDuration,
                getter = { c, d -> getNetworkSwitchNotificationRateDuration(c, d) },
                setter = { c, v -> setNetworkSwitchNotificationRateDuration(c, v) },
                defValue = -1, testValue = testTime1.toInt(),
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setNetworkSwitchNotificationRateDuration(context, Duration.ofMillis(-1L)) }
    }

    @Test
    fun testCaptivePortalMode() {
        testSetting(name = CAPTIVE_PORTAL_MODE, type = "global",
                value1 = CAPTIVE_PORTAL_MODE_AVOID, value2 = CAPTIVE_PORTAL_MODE_PROMPT,
                def = CAPTIVE_PORTAL_MODE_IGNORE,
                getter = { c, d -> getCaptivePortalMode(c, d) },
                setter = { c, v -> setCaptivePortalMode(c, v) },
                defValue = -1, testValue = CAPTIVE_PORTAL_MODE_AVOID,
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setCaptivePortalMode(context, 5 /* mode */) }
    }

    @Test
    fun testPrivateDnsDefaultMode() {
        val original = Settings.Global.getString(resolver, PRIVATE_DNS_DEFAULT_MODE)

        try {
            val mode = getPrivateDnsModeAsString(PRIVATE_DNS_MODE_OPPORTUNISTIC)
            Settings.Global.putString(resolver, PRIVATE_DNS_DEFAULT_MODE, mode)
            assertEquals(mode, getPrivateDnsDefaultMode(context))

            setPrivateDnsDefaultMode(context, PRIVATE_DNS_MODE_OFF)
            assertEquals(getPrivateDnsModeAsString(PRIVATE_DNS_MODE_OFF),
                    getPrivateDnsDefaultMode(context))
        } finally {
            resetSettings(name = PRIVATE_DNS_DEFAULT_MODE, type = "global", value = original,
                    resetter = { cr, n, v -> Settings.Global.putString(cr, n, v) })
        }

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setPrivateDnsDefaultMode(context, -1) }
    }

    @Test
    fun testConnectivityKeepPendingIntentDuration() {
        testSetting(name = CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS, type = "secure",
                value1 = Duration.ofMillis(testTime1), value2 = Duration.ofMillis(testTime2),
                def = defaultDuration,
                getter = { c, d -> getConnectivityKeepPendingIntentDuration(c, d) },
                setter = { c, v -> setConnectivityKeepPendingIntentDuration(c, v) },
                defValue = -1, testValue = testTime1.toInt(),
                getSetting = { cr, n, d -> Settings.Secure.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Secure.putInt(cr, n, v) })

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setConnectivityKeepPendingIntentDuration(context, Duration.ofMillis(-1L)) }
    }

    @Test
    fun testMobileDataAlwaysOn() {
        testSetting(name = MOBILE_DATA_ALWAYS_ON, type = "global",
                value1 = false, value2 = true, def = true,
                getter = { c, d -> getMobileDataAlwaysOn(c, d) },
                setter = { c, v -> setMobileDataAlwaysOn(c, v) },
                defValue = -1, testValue = 0,
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })
    }

    @Test
    fun testWifiAlwaysRequested() {
        testSetting(name = WIFI_ALWAYS_REQUESTED, type = "global",
                value1 = false, value2 = true, def = true,
                getter = { c, d -> getWifiAlwaysRequested(c, d) },
                setter = { c, v -> setWifiAlwaysRequested(c, v) },
                defValue = -1, testValue = 0,
                getSetting = { cr, n, d -> Settings.Global.getInt(cr, n, d) },
                putSetting = { cr, n, v -> Settings.Global.putInt(cr, n, v) })
    }
}