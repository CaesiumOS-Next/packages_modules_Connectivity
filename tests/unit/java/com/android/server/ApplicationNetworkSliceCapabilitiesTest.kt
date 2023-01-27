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

import android.content.res.Resources
import android.os.Build
import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import com.android.frameworks.tests.net.R
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class ApplicationNetworkSliceCapabilitiesTest {
    private lateinit var mResource: Resources

    @Before
    fun setUp() {
        mResource = InstrumentationRegistry.getContext().getResources()
    }

    @Test
    fun parseXmlWithBothDeclaration_enableBothLatencyAndBandwidth() {
        val parser: XmlPullParser = mResource.getXml(R.xml.network_slice_both)
        val capabilities = ApplicationNetworkSliceCapabilities.createFromXml(parser)

        assertTrue(capabilities.isNetCapabilityPrioritizeLatency)
        assertTrue(capabilities.isNetCapabilityPrioritizeBandwidth)
    }

    @Test
    fun parseXmlWithLatencyDeclaration_enableLatency() {
        val parser: XmlPullParser = mResource.getXml(R.xml.network_slice_latency)
        val capabilities = ApplicationNetworkSliceCapabilities.createFromXml(parser)
        assertTrue(capabilities.isNetCapabilityPrioritizeLatency)
        assertFalse(capabilities.isNetCapabilityPrioritizeBandwidth)
    }

    @Test
    fun parseXmlWithBandwidthDeclaration_enableBandwidth() {
        val parser: XmlPullParser = mResource.getXml(R.xml.network_slice_bandwidth)
        val capabilities = ApplicationNetworkSliceCapabilities.createFromXml(parser)
        assertFalse(capabilities.isNetCapabilityPrioritizeLatency)
        assertTrue(capabilities.isNetCapabilityPrioritizeBandwidth)
    }

    @Test
    fun parseXmlWithOtherDeclaration_enableNothing() {
        val parser: XmlPullParser = mResource.getXml(R.xml.network_slice_other)
        val capabilities = ApplicationNetworkSliceCapabilities.createFromXml(parser)
        assertFalse(capabilities.isNetCapabilityPrioritizeBandwidth)
        assertFalse(capabilities.isNetCapabilityPrioritizeBandwidth)
    }

    @Test
    fun parseXmlWithWrongNetworkSliceTag_shouldThrowException() {
        val parser: XmlPullParser = mResource.getXml(R.xml.network_slice_wrong_slice);
        assertFailsWith<UnsupportedOperationException>("Invalid tag: network-slice1") {
            ApplicationNetworkSliceCapabilities.createFromXml(parser)
        }
    }

    @Test
    fun parseXmlWithWrongDeclarationTag_shouldThrowException() {
        val parser: XmlPullParser = mResource.getXml(R.xml.network_slice_wrong_declaration);
        assertFailsWith<UnsupportedOperationException>("Invalid tag: declaration1") {
            ApplicationNetworkSliceCapabilities.createFromXml(parser)
        }
    }

    companion object {
        private val TAG = ApplicationNetworkSliceCapabilitiesTest::class.java.simpleName
    }
}