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

package com.android.server

import android.os.Build
import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import com.android.frameworks.tests.net.R
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class ApplicationSelfCertifiedNetworkCapabilitiesTest {
    private val mResource = InstrumentationRegistry.getContext().getResources()

    @Test
    fun parseXmlWithBothDeclaration_enableBothLatencyAndBandwidth() {
        val parser = mResource.getXml(R.xml.self_certified_network_capabilities_both)
        val capabilities = ApplicationSelfCertifiedNetworkCapabilities.createFromXml(parser)

        assertTrue(capabilities.hasPrioritizeLatency())
        assertTrue(capabilities.hasPrioritizeBandwidth())
    }

    @Test
    fun parseXmlWithLatencyDeclaration_enableLatency() {
        val parser = mResource.getXml(R.xml.self_certified_network_capabilities_latency)
        val capabilities = ApplicationSelfCertifiedNetworkCapabilities.createFromXml(parser)
        assertTrue(capabilities.hasPrioritizeLatency())
        assertFalse(capabilities.hasPrioritizeBandwidth())
    }

    @Test
    fun parseXmlWithBandwidthDeclaration_enableBandwidth() {
        val parser = mResource.getXml(R.xml.self_certified_network_capabilities_bandwidth)
        val capabilities = ApplicationSelfCertifiedNetworkCapabilities.createFromXml(parser)
        assertFalse(capabilities.hasPrioritizeLatency())
        assertTrue(capabilities.hasPrioritizeBandwidth())
    }

    @Test
    fun parseXmlWithOtherDeclaration_enableNothing() {
        val parser = mResource.getXml(R.xml.self_certified_network_capabilities_other)
        val capabilities = ApplicationSelfCertifiedNetworkCapabilities.createFromXml(parser)
        assertFalse(capabilities.hasPrioritizeBandwidth())
        assertFalse(capabilities.hasPrioritizeBandwidth())
    }

    @Test
    fun parseXmlWithWrongTag_shouldThrowException() {
        val parser: XmlPullParser = mResource.getXml(
            R.xml.self_certified_network_capabilities_wrong_tag
        )
        assertFailsWith<IllegalArgumentException>("Invalid tag: uses-network-capability1") {
            ApplicationSelfCertifiedNetworkCapabilities.createFromXml(parser)
        }
    }

    @Test
    fun parseXmlWithWrongDeclaration_shouldThrowException() {
        val parser: XmlPullParser = mResource.getXml(
            R.xml.self_certified_network_capabilities_wrong_declaration
        )
        assertFailsWith<IllegalArgumentException>(
            "Invalid tag: network-capabilities-declaration1"
        ) {
            ApplicationSelfCertifiedNetworkCapabilities.createFromXml(parser)
        }
    }
}
