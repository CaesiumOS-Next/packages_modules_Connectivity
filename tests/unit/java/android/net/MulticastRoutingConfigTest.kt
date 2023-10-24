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

import android.os.Build
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner

import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet6Address
import kotlin.test.assertTrue

import android.net.MulticastRoutingConfig.Builder
import android.net.MulticastRoutingConfig.FORWARD_NONE
import android.net.MulticastRoutingConfig.FORWARD_SELECTED
import android.net.MulticastRoutingConfig.FORWARD_WITH_MIN_SCOPE

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class MulticastRoutingConfigTest {

    val address1 = Inet6Address.getByName("2000::8888") as Inet6Address
    val address2 = Inet6Address.getByName("2000::9999") as Inet6Address
    lateinit var config1: MulticastRoutingConfig
    lateinit var config2: MulticastRoutingConfig

    @Test
    fun equalityTests() {

        config1 = Builder(FORWARD_NONE).build()
        config2 = Builder(FORWARD_NONE).build()
        assertTrue(config1.equals(config2))

        config1 = Builder(FORWARD_SELECTED).build()
        config2 = Builder(FORWARD_SELECTED).build()
        assertTrue(config1.equals(config2))

        config1 = Builder(FORWARD_WITH_MIN_SCOPE, 4).build()
        config2 = Builder(FORWARD_WITH_MIN_SCOPE, 4).build()
        assertTrue(config1.equals(config2))

        config1 = Builder(FORWARD_SELECTED).addListeningAddress(address1)
                    .addListeningAddress(address2).build()
        config2 = Builder(FORWARD_SELECTED).addListeningAddress(address2)
                    .addListeningAddress(address1).build()
        assertTrue(config1.equals(config2))
    }

    @Test
    fun toString_equalObjects_returnsEqualStrings() {
        config1 = Builder(FORWARD_SELECTED).addListeningAddress(address1)
                    .addListeningAddress(address2).build()
        config2 = Builder(FORWARD_SELECTED).addListeningAddress(address2)
                    .addListeningAddress(address1).build()

        val str1 = config1.toString()
        val str2 = config2.toString()

        assertTrue(str1.equals(str2))
    }
}
