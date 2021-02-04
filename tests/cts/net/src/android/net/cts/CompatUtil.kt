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

package android.net.cts

import android.net.EthernetNetworkSpecifier
import android.net.NetworkSpecifier
import android.net.TestNetworkSpecifier
import android.os.Build
import com.android.testutils.isDevSdkInRange

object CompatUtil {
    @JvmStatic
    fun makeTestNetworkSpecifier(ifName: String): NetworkSpecifier {
        // Until R, there was no TestNetworkSpecifier, StringNetworkSpecifier was used instead
        if (isDevSdkInRange(minExclusive = null, maxInclusive = Build.VERSION_CODES.R)) {
            return makeStringNetworkSpecifier(ifName)
        }
        return TestNetworkSpecifier(ifName)
    }

    @JvmStatic
    fun makeEthernetNetworkSpecifier(ifName: String): NetworkSpecifier {
        // Until R, there was no EthernetNetworkSpecifier, StringNetworkSpecifier was used instead
        if (isDevSdkInRange(minExclusive = null, maxInclusive = Build.VERSION_CODES.R)) {
            return makeStringNetworkSpecifier(ifName)
        }
        return EthernetNetworkSpecifier(ifName)
    }

    private fun makeStringNetworkSpecifier(specifier: String): NetworkSpecifier {
        // StringNetworkSpecifier was removed after R (and was hidden API before that)
        return Class.forName("android.net.StringNetworkSpecifier")
                .getConstructor(String::class.java).newInstance(specifier) as NetworkSpecifier
    }
}