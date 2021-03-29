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

import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE
import android.net.ConnectivitySettingsManager.CAPTIVE_PORTAL_MODE_AVOID
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
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
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
    private val uiAutomation = instrumentation.uiAutomation

    private val defaultDuration = Duration.ofSeconds(0L)
    private val testTime1 = 5L
    private val testTime2 = 10L

    /*** Reset setting value or delete setting if the setting was not existed before testing. */
    private fun Int.resetToOriginal(name: String, type: String = "global") {
        if (this == -1) {
            uiAutomation.executeShellCommand("settings delete $type $name")
        } else {
            if ("secure" == type) {
                Settings.Secure.putInt(resolver, name, this)
            } else {
                Settings.Global.putInt(resolver, name, this)
            }
        }
    }

    /*** Reset setting value or delete setting if the setting was not existed before testing. */
    private fun String?.resetToOriginal(name: String, type: String = "global") {
        if (this == null) {
            uiAutomation.executeShellCommand("settings delete $type $name")
        } else {
            if ("secure" == type) {
                Settings.Secure.putString(resolver, name, this)
            } else {
                Settings.Global.putString(resolver, name, this)
            }
        }
    }

    @Test
    fun testMobileDataActivityTimeout() {
        val original = Settings.Global.getInt(resolver, DATA_ACTIVITY_TIMEOUT_MOBILE, -1 /* def */)

        Settings.Global.putInt(resolver, DATA_ACTIVITY_TIMEOUT_MOBILE, testTime1.toInt())
        assertEquals(testTime1, getMobileDataActivityTimeout(context, defaultDuration).seconds)

        setMobileDataActivityTimeout(context, Duration.ofSeconds(testTime2))
        assertEquals(testTime2, getMobileDataActivityTimeout(context, defaultDuration).seconds)

        original.resetToOriginal(DATA_ACTIVITY_TIMEOUT_MOBILE)
    }

    @Test
    fun testWifiDataActivityTimeout() {
        val original = Settings.Global.getInt(resolver, DATA_ACTIVITY_TIMEOUT_WIFI, -1 /* def */)

        Settings.Global.putInt(resolver, DATA_ACTIVITY_TIMEOUT_WIFI, testTime1.toInt())
        assertEquals(testTime1, getWifiDataActivityTimeout(context, defaultDuration).seconds)

        setWifiDataActivityTimeout(context, Duration.ofSeconds(testTime2))
        assertEquals(testTime2, getWifiDataActivityTimeout(context, defaultDuration).seconds)

        original.resetToOriginal(DATA_ACTIVITY_TIMEOUT_WIFI)
    }

    @Test
    fun testDnsResolverSampleValidityDuration() {
        val original = Settings.Global.getInt(
                resolver, DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS, -1 /* def */)

        Settings.Global.putInt(resolver, DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS, testTime1.toInt())
        assertEquals(testTime1,
                getDnsResolverSampleValidityDuration(context, defaultDuration).seconds)

        setDnsResolverSampleValidityDuration(context, Duration.ofSeconds(testTime2))
        assertEquals(testTime2,
                getDnsResolverSampleValidityDuration(context, defaultDuration).seconds)

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleValidityDuration(context, Duration.ofSeconds(-1L)) }

        original.resetToOriginal(DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS)
    }

    @Test
    fun testDnsResolverSuccessThresholdPercent() {
        val original = Settings.Global.getInt(
                resolver, DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT, -1 /* def */)

        val percent1 = 5
        Settings.Global.putInt(resolver, DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT, percent1)
        assertEquals(percent1, getDnsResolverSuccessThresholdPercent(context, 0 /* def */))

        val percent2 = 10
        setDnsResolverSuccessThresholdPercent(context, percent2)
        assertEquals(percent2, getDnsResolverSuccessThresholdPercent(context, 0 /* def */))

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSuccessThresholdPercent(context, -1) }
        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSuccessThresholdPercent(context, 120) }

        original.resetToOriginal(DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT)
    }

    @Test
    fun testDnsResolverSampleRanges() {
        val originalMin = Settings.Global.getInt(resolver, DNS_RESOLVER_MIN_SAMPLES, -1 /* def */)
        val originalMax = Settings.Global.getInt(resolver, DNS_RESOLVER_MAX_SAMPLES, -1 /* def */)

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

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleRanges(context, Range(-1, 62)) }
        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setDnsResolverSampleRanges(context, Range(2, 65)) }

        originalMin.resetToOriginal(DNS_RESOLVER_MIN_SAMPLES)
        originalMax.resetToOriginal(DNS_RESOLVER_MAX_SAMPLES)
    }

    @Test
    fun testNetworkSwitchNotificationMaximumDailyCount() {
        val original = Settings.Global.getInt(
                resolver, NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT, -1 /* def */)

        val count1 = 5
        Settings.Global.putInt(resolver, NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT, count1)
        assertEquals(count1, getNetworkSwitchNotificationMaximumDailyCount(context, 0 /* def */))

        val count2 = 15
        setNetworkSwitchNotificationMaximumDailyCount(context, count2)
        assertEquals(count2, getNetworkSwitchNotificationMaximumDailyCount(context, 0 /* def */))

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setNetworkSwitchNotificationMaximumDailyCount(context, -1) }

        original.resetToOriginal(NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT)
    }

    @Test
    fun testNetworkSwitchNotificationRateDuration() {
        val original = Settings.Global.getInt(
                resolver, NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS, -1 /* def */)

        Settings.Global.putInt(
                resolver, NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS, testTime1.toInt())
        assertEquals(testTime1,
                getNetworkSwitchNotificationRateDuration(context, defaultDuration).toMillis())

        setNetworkSwitchNotificationRateDuration(context, Duration.ofMillis(testTime2))
        assertEquals(testTime2,
                getNetworkSwitchNotificationRateDuration(context, defaultDuration).toMillis())

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setNetworkSwitchNotificationRateDuration(context, Duration.ofMillis(-1L)) }

        original.resetToOriginal(NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS)
    }

    @Test
    fun testCaptivePortalMode() {
        val original = Settings.Global.getInt(resolver, CAPTIVE_PORTAL_MODE, -1 /* def */)

        val mode1 = CAPTIVE_PORTAL_MODE_AVOID
        Settings.Global.putInt(resolver, CAPTIVE_PORTAL_MODE, mode1)
        assertEquals(mode1, getCaptivePortalMode(context, 0 /* def */))

        val mode2 = CAPTIVE_PORTAL_MODE_PROMPT
        setCaptivePortalMode(context, mode2)
        assertEquals(mode2, getCaptivePortalMode(context, 0 /* def */))

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setCaptivePortalMode(context, 5 /* mode */) }

        original.resetToOriginal(CAPTIVE_PORTAL_MODE)
    }

    @Test
    fun testPrivateDnsDefaultMode() {
        val original = Settings.Global.getString(resolver, PRIVATE_DNS_DEFAULT_MODE)

        val mode = getPrivateDnsModeAsString(PRIVATE_DNS_MODE_OPPORTUNISTIC)
        Settings.Global.putString(resolver, PRIVATE_DNS_DEFAULT_MODE, mode)
        assertEquals(mode, getPrivateDnsDefaultMode(context))

        setPrivateDnsDefaultMode(context, PRIVATE_DNS_MODE_OFF)
        assertEquals(getPrivateDnsModeAsString(PRIVATE_DNS_MODE_OFF),
                getPrivateDnsDefaultMode(context))

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setPrivateDnsDefaultMode(context, -1) }

        original.resetToOriginal(PRIVATE_DNS_DEFAULT_MODE)
    }

    @Test
    fun testConnectivityKeepPendingIntentDuration() {
        val original = Settings.Secure.getInt(
                resolver, CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS, -1 /* def */)

        Settings.Secure.putInt(
                resolver, CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS, testTime1.toInt())
        assertEquals(testTime1,
                getConnectivityKeepPendingIntentDuration(context, defaultDuration).toMillis())

        setConnectivityKeepPendingIntentDuration(context, Duration.ofMillis(testTime2))
        assertEquals(testTime2,
                getConnectivityKeepPendingIntentDuration(context, defaultDuration).toMillis())

        assertFailsWith<IllegalArgumentException>("Expect fail but argument accepted.") {
            setConnectivityKeepPendingIntentDuration(context, Duration.ofMillis(-1L)) }

        original.resetToOriginal(CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS, "secure")
    }

    @Test
    fun testMobileDataAlwaysOn() {
        val original = Settings.Global.getInt(resolver, MOBILE_DATA_ALWAYS_ON, -1 /* def */)

        Settings.Global.putInt(resolver, MOBILE_DATA_ALWAYS_ON, 0 /* value */)
        assertFalse(getMobileDataAlwaysOn(context, true /* def */))

        setMobileDataAlwaysOn(context, true /* enable */)
        assertTrue(getMobileDataAlwaysOn(context, false /* def */))

        original.resetToOriginal(MOBILE_DATA_ALWAYS_ON)
    }

    @Test
    fun testWifiAlwaysRequested() {
        val original = Settings.Global.getInt(resolver, WIFI_ALWAYS_REQUESTED, -1 /* def */)

        Settings.Global.putInt(resolver, WIFI_ALWAYS_REQUESTED, 0 /* value */)
        assertFalse(getWifiAlwaysRequested(context, true /* def */))

        setWifiAlwaysRequested(context, true /* enable */)
        assertTrue(getWifiAlwaysRequested(context, false /* def */))

        original.resetToOriginal(WIFI_ALWAYS_REQUESTED)
    }
}