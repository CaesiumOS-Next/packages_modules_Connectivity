/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.net.cts

import android.Manifest.permission.WRITE_DEVICE_CONFIG
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.apf.ApfCapabilities
import android.os.Build
import android.platform.test.annotations.AppModeFull
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.PropertyUtil.isVendorApiLevelNewerThan
import com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow
import com.android.internal.util.HexDump
import com.android.modules.utils.build.SdkLevel
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.NetworkStackModuleTest
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.google.common.truth.Truth.assertThat
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TIMEOUT_MS = 2000L
private const val APF_NEW_RA_FILTER_VERSION = "apf_new_ra_filter_version"

@AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
@RunWith(DevSdkIgnoreRunner::class)
@NetworkStackModuleTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class ApfIntegrationTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java)!! }
    private val pm by lazy { context.packageManager }
    private lateinit var ifname: String
    private lateinit var networkCallback: TestableNetworkCallback

    @Before
    fun setUp() {
        assumeTrue(pm.hasSystemFeature(FEATURE_WIFI))
        assumeTrue(isVendorApiLevelNewerThan(Build.VERSION_CODES.TIRAMISU))
        networkCallback = TestableNetworkCallback()
        cm.requestNetwork(
                NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                networkCallback
        )
        networkCallback.eventuallyExpect<LinkPropertiesChanged>(TIMEOUT_MS) {
            ifname = assertNotNull(it.lp.interfaceName)
            true
        }

        // APF adb cmds are only implemented in ApfFilter.java. Enable experiment to prevent
        // LegacyApfFilter.java from being used.
        runAsShell(WRITE_DEVICE_CONFIG) {
            DeviceConfig.setProperty(
                    NAMESPACE_CONNECTIVITY,
                    APF_NEW_RA_FILTER_VERSION,
                    "1",  // value => force enabled
                    false // makeDefault
            )
        }
    }

    @After
    fun tearDown() {
        if (::networkCallback.isInitialized) {
            cm.unregisterNetworkCallback(networkCallback)
        }
    }

    fun getApfCapabilities(): ApfCapabilities {
        val caps = runShellCommandOrThrow("cmd network_stack apf $ifname capabilities").trim()
        val (version, maxLen, packetFormat) = caps.split(",").map { it.toInt() }
        return ApfCapabilities(version, maxLen, packetFormat)
    }

    @Test
    fun testGetApfCapabilities() {
        val caps = getApfCapabilities()
        assertThat(caps.apfVersionSupported).isEqualTo(4)
        assertThat(caps.apfPacketFormat).isEqualTo(OsConstants.ARPHRD_ETHER)
        // VSR 15 (V) requires support of at least 2048 bytes of usable memory for new chipsets.
        if (isVendorApiLevelNewerThan(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            assertThat(caps.maximumApfProgramSize).isAtLeast(2048)
        } else {
            assertThat(caps.maximumApfProgramSize).isAtLeast(1024)
        }
    }

    fun installProgram(bytes: ByteArray) {
        val prog = HexDump.toHexString(bytes, 0 /* offset */, bytes.size, false /* upperCase */)
        runShellCommandOrThrow("cmd network_stack apf $ifname install $prog")
    }

    fun readProgram(): ByteArray {
        val progHexString = runShellCommandOrThrow("cmd network_stack apf $ifname read").trim()
        return HexDump.hexStringToByteArray(progHexString)
    }

    @Test
    fun testReadWriteProgram() {
        val caps = getApfCapabilities()
        var maxProgramSize = caps.maximumApfProgramSize
        // Before V, max program size is capped at 2000 due to a firmware bug.
        if (!SdkLevel.isAtLeastV()) {
            maxProgramSize = min(maxProgramSize, 2000)
        }

        // Start from 2 bytes. Since we want the APF program to always pass the data and PASSDROP is
        // opcode 0, there is no meaningful way to test a single byte write, since a read that
        // returns 0 might just indicate 0-initialized memory without a write ever having taken
        // place. In reality, a program is always at least a few hundred bytes.
        for (i in 2..maxProgramSize) {
            // The first byte is 0 (PASSDROP) which will cause all packets to be passed. Note that
            // this is not otherwise a valid APF program.
            val bytes = ByteArray(i)
            Random.nextBytes(bytes, 1 /* fromIndex */)

            installProgram(bytes)
            // a packet filter read always returns the entire memory region.
            val resultBytes = readProgram().sliceArray(0..<i)
            assertThat(resultBytes).isEqualTo(bytes)
        }
    }
}
