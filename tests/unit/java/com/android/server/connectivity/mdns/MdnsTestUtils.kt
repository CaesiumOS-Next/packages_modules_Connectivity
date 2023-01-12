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

package com.android.server.connectivity.mdns

import com.android.net.module.util.HexDump
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.assertTrue

/**
 * Compute 2001:db8::1 --> 1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.B.D.1.0.0.2.ip6.arpa
 */
fun getReverseV6AddressName(addr: InetAddress): Array<String> {
    assertTrue(addr is Inet6Address)
    return addr.address.flatMapTo(mutableListOf("arpa", "ip6")) {
        HexDump.toHexString(it).toCharArray().map(Char::toString)
    }.reversed().toTypedArray()
}
