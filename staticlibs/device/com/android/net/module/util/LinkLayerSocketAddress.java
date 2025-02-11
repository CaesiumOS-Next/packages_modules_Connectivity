/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.net.module.util;

import java.util.Arrays;

/**
 * Link layer socket address that corresponds to Linux's {@code struct sockaddr_ll}.
 */
public class LinkLayerSocketAddress {
    /**
     * Ethernet protocol type.
     */
    public int sll_protocol;

    /**
     * Interface index.
     */
    public int sll_ifindex;

    /**
     * ARP hardware type.
     */
    public int sll_hatype;

    /**
     * Packet type.
     */
    public int sll_pkttype;

    /**
     * Hardware address.
     */
    public byte[] sll_addr;

    /**
     * Constructs an empty new LinkLayerSocketAddress.
     */
    public LinkLayerSocketAddress() {
        sll_protocol = 0;
        sll_ifindex = 0;
        sll_hatype = 0;
        sll_pkttype = 0;
        sll_addr = null;
    }

    @Override
    public String toString() {
        return "LinkLayerSocketAddress{"
                + "sll_protocol=" + sll_protocol
                + ", sll_ifindex=" + sll_ifindex
                + ", sll_hatype=" + sll_hatype
                + ", sll_pkttype=" + sll_pkttype
                + ", sll_addr=" + Arrays.toString(sll_addr)
                + "}";
    }
}
