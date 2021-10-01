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

package com.android.server.connectivity

import com.android.networkstack.tethering.util.TetheringUtils.getTetheringJniLibraryName

import android.net.InetAddresses
import android.os.Build
import android.platform.test.annotations.AppModeFull
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.server.connectivity.VendorConnectivityService
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileDescriptor

@SmallTest
@RunWith(AndroidJUnit4::class)
@AppModeFull(reason = "Socket cannot bind in instant app mode")
class VendorConnectivityTest {
    private val IPV4_LOOPBACK = InetAddresses.parseNumericAddress("127.0.0.1")

    private var service = VendorConnectivityService()

    @Test // @IgnoreUpTo(Build.VERSION_CODES.T) // TODO: uncomment once T is in VERSION_CODES
    fun testPortBlockedUdp() {
        val port = 1111
        service.blockPort(port);

        val sock =
                Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP)

        assertFailsWith<ErrnoException> {
            Os.bind(sock, IPV4_LOOPBACK, port)
        }
    }
}