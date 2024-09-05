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

package com.android.net.module.util.netlink;

import static android.system.OsConstants.AF_UNSPEC;

import static com.android.net.module.util.NetworkStackConstants.ETHER_ADDR_LEN;
import static com.android.net.module.util.netlink.NetlinkConstants.IFF_UP;
import static com.android.net.module.util.netlink.NetlinkConstants.RTM_NEWLINK;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_ACK;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import android.net.MacAddress;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A NetlinkMessage subclass for rtnetlink link messages.
 *
 * RtNetlinkLinkMessage.parse() must be called with a ByteBuffer that contains exactly one netlink
 * message.
 *
 * see also:
 *
 *     include/uapi/linux/rtnetlink.h
 *
 * @hide
 */
public class RtNetlinkLinkMessage extends NetlinkMessage {
    public static final short IFLA_ADDRESS   = 1;
    public static final short IFLA_IFNAME    = 3;
    public static final short IFLA_MTU       = 4;
    public static final short IFLA_INET6_ADDR_GEN_MODE = 8;
    public static final short IFLA_AF_SPEC = 26;

    public static final short IN6_ADDR_GEN_MODE_NONE = 1;
    // The maximum buffer size to hold an interface name including the null-terminator '\0'.
    private static final int IFNAMSIZ = 16;

    // The default value of MTU, which means no MTU is specified.
    private static final int DEFAULT_MTU = -1;

    @NonNull
    private StructIfinfoMsg mIfinfomsg;
    private int mMtu;
    @Nullable
    private MacAddress mHardwareAddress;
    @Nullable
    private String mInterfaceName;

    public RtNetlinkLinkMessage(
            @NonNull StructNlMsgHdr header,
            @NonNull StructIfinfoMsg ifinfomsg) {
        super(header);
        mIfinfomsg = ifinfomsg;
        mMtu = DEFAULT_MTU;
        mHardwareAddress = null;
        mInterfaceName = null;
    }

    /**
     * Sets the MTU for this object.
     *
     * @param mtu The desired MTU value.
     * @return {@code true} if the MTU was successfully set,
     *         {@code false} if the provided MTU is invalid.
     */
    public boolean setMtu(int mtu) {
        if (mtu < 0) {
            return false;
        }
        mMtu = mtu;
        return true;
    }

    /**
     * Sets the hardware address (MAC address) for this object.
     *
     * @param hardwareAddress The new MAC address to set.
     * @return {@code true} if the hardware address was successfully set,
     *         {@code false} if the provided address is invalid.
     */
    public boolean setHardwareAddress(MacAddress hardwareAddress) {
        if (hardwareAddress == null) {
            return false;
        }
        mHardwareAddress = hardwareAddress;
        return true;
    }

    /**
     * Sets the network interface name for this object.
     *
     * @param interfaceName The interface name.
     * @return {@code true} if the interface name was successfully set,
     *         {@code false} if the provided name is invalid.
     */
    public boolean setInterfaceName(String interfaceName) {
        if (interfaceName == null || interfaceName.isEmpty()
                || interfaceName.length() + 1 > IFNAMSIZ) {
            return false;
        }
        mInterfaceName = interfaceName;
        return true;
    }

    @NonNull
    public StructIfinfoMsg getIfinfoHeader() {
        return mIfinfomsg;
    }

    public int getMtu() {
        return mMtu;
    }

    @Nullable
    public MacAddress getHardwareAddress() {
        return mHardwareAddress;
    }

    @Nullable
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Parse rtnetlink link message from {@link ByteBuffer}. This method must be called with a
     * ByteBuffer that contains exactly one netlink message.
     *
     * @param header netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes.
     */
    @Nullable
    public static RtNetlinkLinkMessage parse(@NonNull final StructNlMsgHdr header,
            @NonNull final ByteBuffer byteBuffer) {
        StructIfinfoMsg ifinfoMsg = StructIfinfoMsg.parse(byteBuffer);
        if (ifinfoMsg == null) {
            return null;
        }
        final RtNetlinkLinkMessage linkMsg = new RtNetlinkLinkMessage(header, ifinfoMsg);

        // IFLA_MTU
        final int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = StructNlAttr.findNextAttrOfType(IFLA_MTU, byteBuffer);
        if (nlAttr != null) {
            linkMsg.setMtu(nlAttr.getValueAsInt(DEFAULT_MTU));
        }

        // IFLA_ADDRESS
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(IFLA_ADDRESS, byteBuffer);
        if (nlAttr != null) {
            linkMsg.setHardwareAddress(nlAttr.getValueAsMacAddress());
        }

        // IFLA_IFNAME
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(IFLA_IFNAME, byteBuffer);
        if (nlAttr != null) {
            linkMsg.setInterfaceName(nlAttr.getValueAsString());
        }

        return linkMsg;
    }

    /**
     *  Write a rtnetlink link message to {@link byte} array.
     */
    public byte[] packToByteArray(ByteOrder order) {
        byte[] bytes = new byte[calculateMessageLength()];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
        pack(buffer);
        return bytes;
    }

    /**
     * Write a rtnetlink link message to {@link ByteBuffer}.
     */
    @VisibleForTesting
    protected void pack(ByteBuffer byteBuffer) {
        // Update the length of the header.
        mHeader.nlmsg_len = calculateMessageLength();

        mHeader.pack(byteBuffer);
        mIfinfomsg.pack(byteBuffer);

        if (mMtu != DEFAULT_MTU) {
            final StructNlAttr mtu = new StructNlAttr(IFLA_MTU, mMtu);
            mtu.pack(byteBuffer);
        }
        if (mHardwareAddress != null) {
            final StructNlAttr hardwareAddress = new StructNlAttr(IFLA_ADDRESS, mHardwareAddress);
            hardwareAddress.pack(byteBuffer);
        }
        if (mInterfaceName != null) {
            final StructNlAttr ifname = new StructNlAttr(IFLA_IFNAME, mInterfaceName);
            ifname.pack(byteBuffer);
        }
    }

    /**
     *  Calculate the byte length of the packed buffer.
     *  The value should be the same as nlmsg_len of its header.
     */
    private int calculateMessageLength() {
        int length = StructNlMsgHdr.STRUCT_SIZE + StructIfinfoMsg.STRUCT_SIZE;
        if (mMtu != DEFAULT_MTU) {
            length += NetlinkConstants.alignedLengthOf(StructNlAttr.NLA_HEADERLEN + Integer.BYTES);
        }
        if (mHardwareAddress != null) {
            length += NetlinkConstants.alignedLengthOf(
                    StructNlAttr.NLA_HEADERLEN + ETHER_ADDR_LEN);
        }
        if (mInterfaceName != null) {
            length += NetlinkConstants.alignedLengthOf(
                    // The string should be end with '\0', so the length should plus 1.
                    StructNlAttr.NLA_HEADERLEN + mInterfaceName.length() + 1);
        }

        return length;
    }

    /**
     * Create a link message to set the operational state (up or down) of a network interface.
     *
     * @param interfaceIndex The index of the network interface, obtained from
     *                       {@code if_nametoindex()}.
     * @param sequenceNumber The sequence number to use for the Netlink message.
     * @param isUp           {@code true} to set the interface up, {@code false} to set it down.
     * @return A `RtNetlinkLinkMessage` instance configured to set the link state.
     */
    public static RtNetlinkLinkMessage createSetLinkUpMessage(int interfaceIndex,
            int sequenceNumber, boolean isUp) {
        return new RtNetlinkLinkMessage(
                new StructNlMsgHdr(0, RTM_NEWLINK, (short) (NLM_F_REQUEST | NLM_F_ACK),
                        sequenceNumber),
                new StructIfinfoMsg((short) AF_UNSPEC, (short) 0, interfaceIndex,
                                    isUp ? IFF_UP : 0, IFF_UP));
    }

    @Override
    public String toString() {
        return "RtNetlinkLinkMessage{ "
                + "nlmsghdr{" + mHeader.toString(OsConstants.NETLINK_ROUTE) + "}, "
                + "Ifinfomsg{" + mIfinfomsg.toString() + "}, "
                + "Hardware Address{" + mHardwareAddress + "}, "
                + "MTU{" + mMtu + "}, "
                + "Ifname{" + mInterfaceName + "} "
                + "}";
    }
}
