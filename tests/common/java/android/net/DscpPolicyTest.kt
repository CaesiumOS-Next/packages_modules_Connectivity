/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.system.OsConstants
import android.util.Range
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.SC_V2
import com.android.testutils.assertParcelingIsLossless
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import kotlin.test.assertEquals

@RunWith(DevSdkIgnoreRunner::class)
// DscpPolicy was added in T
@IgnoreUpTo(SC_V2) // TODO: Use to Build.VERSION_CODES.SC_V2 when available
class DscpPolicyTest {
    private val LOCAL_IPV4_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.1")
    private val TEST_TARGET_IPV4_ADDR =
        InetAddresses.parseNumericAddress("8.8.8.8") as Inet4Address
    private val LOCAL_PORT = 555

    @Test
    fun testBuilder() {
        val policyId = 1
        val dscpValue = 2
        val range = Range(1234, 5678)
        val policy = DscpPolicy.Builder(policyId, dscpValue)
            .setDestinationPortRange(range)
            .setDestinationAddress(TEST_TARGET_IPV4_ADDR)
            .setSourceAddress(LOCAL_IPV4_ADDRESS)
            .setSourcePort(LOCAL_PORT)
            .setProtocol(OsConstants.IPPROTO_UDP).build()

        assertEquals(policyId, policy.policyId)
        assertEquals(dscpValue, policy.dscpValue)
        assertEquals(range, policy.destinationPortRange)
        assertEquals(TEST_TARGET_IPV4_ADDR, policy.destinationAddress)
        assertEquals(LOCAL_IPV4_ADDRESS, policy.sourceAddress)
        assertEquals(LOCAL_PORT, policy.sourcePort)
        assertEquals(OsConstants.IPPROTO_UDP, policy.protocol)

        // Test parceling
        assertParcelingIsLossless(policy)
    }
}
