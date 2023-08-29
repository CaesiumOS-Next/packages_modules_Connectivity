/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.net.BpfNetMapsConstants.DOZABLE_MATCH
import android.net.BpfNetMapsConstants.STANDBY_MATCH
import android.net.BpfNetMapsConstants.UID_RULES_CONFIGURATION_KEY
import android.net.BpfNetMapsUtils.getMatchByFirewallChain
import android.os.Build.VERSION_CODES
import com.android.net.module.util.IBpfMap
import com.android.net.module.util.Struct.S32
import com.android.net.module.util.Struct.U32
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestBpfMap
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_UID1 = 1234
private const val TEST_UID2 = TEST_UID1 + 1
private const val NO_IIF = 0

// pre-T devices does not support Bpf.
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(VERSION_CODES.S_V2)
class BpfNetMapsReaderTest {
    private val testConfigurationMap: IBpfMap<S32, U32> = TestBpfMap()
    private val testUidOwnerMap: IBpfMap<S32, UidOwnerValue> = TestBpfMap()
    private val bpfNetMapsReader = BpfNetMapsReader(
        TestDependencies(testConfigurationMap, testUidOwnerMap))

    class TestDependencies(
        private val configMap: IBpfMap<S32, U32>,
        private val uidOwnerMap: IBpfMap<S32, UidOwnerValue>
    ) : BpfNetMapsReader.Dependencies() {
        override fun getConfigurationMap() = configMap
        override fun getUidOwnerMap() = uidOwnerMap
    }

    private fun doTestIsChainEnabled(chain: Int) {
        testConfigurationMap.updateEntry(
            UID_RULES_CONFIGURATION_KEY,
            U32(getMatchByFirewallChain(chain))
        )
        assertTrue(bpfNetMapsReader.isChainEnabled(chain))
        testConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, U32(0))
        assertFalse(bpfNetMapsReader.isChainEnabled(chain))
    }

    @Test
    @Throws(Exception::class)
    fun testIsChainEnabled() {
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_DOZABLE)
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_STANDBY)
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_POWERSAVE)
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_RESTRICTED)
        doTestIsChainEnabled(ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY)
    }

    @Test
    fun testFirewallChainList() {
        // Verify that when a firewall chain constant is added, it should also be included in
        // firewall chain list.
        val declaredChains = ConnectivityManager::class.java.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && it.name.startsWith("FIREWALL_CHAIN_")
        }
        assertEquals(BpfNetMapsConstants.FIREWALL_CHAINS.size, declaredChains.size)
        declaredChains.forEach {
            assertTrue(BpfNetMapsConstants.FIREWALL_CHAINS.contains(it.get(null)))
        }
    }

    private fun mockChainEnabled(chain: Int, enabled: Boolean) {
        val config = testConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).`val`
        val newConfig = if (enabled) {
            config or getMatchByFirewallChain(chain)
        } else {
            config and getMatchByFirewallChain(chain).inv()
        }
        testConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, U32(newConfig))
    }

    @Test
    fun testIsUidNetworkingBlockedByFirewallChains() {
        // With everything disabled by default, verify the return value is false.
        testConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, U32(0))
        assertFalse(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID1))

        // Enable dozable chain but does not provide allowed list. Verify the network is blocked
        // for all uids.
        mockChainEnabled(ConnectivityManager.FIREWALL_CHAIN_DOZABLE, true)
        assertTrue(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID1))
        assertTrue(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID2))

        // Add uid1 to dozable allowed list. Verify the network is not blocked for uid1, while
        // uid2 is blocked.
        testUidOwnerMap.updateEntry(S32(TEST_UID1), UidOwnerValue(NO_IIF, DOZABLE_MATCH))
        assertFalse(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID1))
        assertTrue(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID2))

        // Enable powersave chain to block uid1. Uid1 is allowed by dozable chain but denied by
        // powersave chain, verify the blocking takes higher priority, while uid2 is not affected.
        mockChainEnabled(ConnectivityManager.FIREWALL_CHAIN_POWERSAVE, true)
        assertTrue(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID1))
        assertTrue(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID2))

        // Enable standby chain with denied list. Uid1 is allowed by dozable chain, but denied by
        // standby chain. Verify the blocking takes higher priority.
        mockChainEnabled(ConnectivityManager.FIREWALL_CHAIN_POWERSAVE, false)
        mockChainEnabled(ConnectivityManager.FIREWALL_CHAIN_STANDBY, true)
        testUidOwnerMap.updateEntry(S32(TEST_UID1), UidOwnerValue(NO_IIF, STANDBY_MATCH))
        assertTrue(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID1))

        // Disable the chains. Verify the network is not blocked for all uids.
        mockChainEnabled(ConnectivityManager.FIREWALL_CHAIN_DOZABLE, false)
        mockChainEnabled(ConnectivityManager.FIREWALL_CHAIN_STANDBY, false)
        assertFalse(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID1))
        assertFalse(bpfNetMapsReader.isUidBlockedByFirewallChains(TEST_UID2))
    }
}
