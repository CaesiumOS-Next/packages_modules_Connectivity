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

import static android.system.OsConstants.EPROTO;
import static android.system.OsConstants.NETLINK_ROUTE;

import static com.android.net.module.util.netlink.NetlinkConstants.RTM_DELADDRLABEL;
import static com.android.net.module.util.netlink.NetlinkConstants.RTM_GETADDRLABEL;
import static com.android.net.module.util.netlink.NetlinkConstants.RTM_NEWADDRLABEL;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_ACK;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import android.net.IpPrefix;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A NetlinkMessage subclass for rtnetlink address label messages.
 *
 * see also:
 *
 *     include/uapi/linux/rtnetlink.h
 */
public class RtNetlinkIfaddrlabelMessage extends NetlinkMessage {
    public static final short IFAL_ADDRESS = 1;
    public static final short IFAL_LABEL = 2;

    public static final int IPV6_ADDR_LABEL_DEFAULT = (int) 0xffffffff;

    @NonNull
    private IpPrefix mPrefix;

    private int mIfindex;
    private int mLabel;
    private int mSeq;

    @VisibleForTesting
    public RtNetlinkIfaddrlabelMessage(@NonNull StructNlMsgHdr header,
            @NonNull IpPrefix prefix, int label, int seq) {
        super(header);
        mPrefix = prefix;
        mLabel = label;
        mSeq = seq;
        // ifal_flags is never used by the kernel.
    }

    @VisibleForTesting
    public RtNetlinkIfaddrlabelMessage(@NonNull StructNlMsgHdr header,
            @NonNull IpPrefix prefix, int label) {
        this(header, prefix, label, 0);
    }

    public IpPrefix getPrefix() {
        return mPrefix;
    }

    public int getIfindex() {
        return mIfindex;
    }

    public int getLabel() {
        return mLabel;
    }

    public int getSeq() {
        return mSeq;
    }

    private static int labelPrefixLength(InetAddress addr, int prefixLen) {
        // Address labels use IPv4-mapped prefixes to represent IPv4 addresses.
        return (addr instanceof Inet4Address) ? prefixLen + 96 : prefixLen;
    }

    private static int labelPrefixLength(IpPrefix prefix) {
        return labelPrefixLength(prefix.getAddress(), prefix.getPrefixLength());
    }

    /**
     * Parse an ifaddrlabelmsg and its attributes from {@code ByteBuffer}. This method must be
     * called with a ByteBuffer that contains exactly one netlink message.
     *
     * @param header netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes.
     */
    @Nullable
    public static RtNetlinkIfaddrlabelMessage parse(@NonNull final StructNlMsgHdr header,
            @NonNull final ByteBuffer byteBuffer) {
        final StructIfaddrlblmsg ifaddrlblmsg = StructIfaddrlblmsg.parse(byteBuffer);
        if (ifaddrlblmsg == null) return null;

        // IFAL_ADDRESS
        final int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = StructNlAttr.findNextAttrOfType(IFAL_ADDRESS, byteBuffer);
        if (nlAttr == null) return null;

        InetAddress addr = nlAttr.getValueAsInetAddress();
        if (addr == null) return null;
        int prefixLen = ifaddrlblmsg.prefixlen;
        if (addr instanceof Inet4Address) prefixLen -= 96;
        final IpPrefix prefix = new IpPrefix(addr, prefixLen);

        // IFAL_LABEL
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(IFAL_LABEL, byteBuffer);
        if (nlAttr == null) return null;
        final int label = nlAttr.getValueAsInteger();

        return new RtNetlinkIfaddrlabelMessage(header, prefix, label, ifaddrlblmsg.seq);
    }

    /**
     * Write this RtNetlinkIfaddrlabelMessage address message to {@link ByteBuffer}.
     */
    @VisibleForTesting
    protected void pack(ByteBuffer byteBuffer) {
        getHeader().pack(byteBuffer);

        final StructIfaddrlblmsg ifaddrlblmsg = new StructIfaddrlblmsg(
                labelPrefixLength(mPrefix), mIfindex, mSeq);
        ifaddrlblmsg.pack(byteBuffer);

        final StructNlAttr address = new StructNlAttr(IFAL_ADDRESS, mPrefix.getAddress());
        address.pack(byteBuffer);

        final StructNlAttr label = new StructNlAttr(IFAL_LABEL, mLabel);
        label.pack(byteBuffer);
    }

    private static byte[] makeNetlinkMessage(int type, int flags, StructIfaddrlblmsg msg,
            StructNlAttr... attrs) {
        // TODO: remove all this and just do new RtNetlinkIfaddrlabelMessage() and pack().
        final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_type = (short) type;
        nlmsghdr.nlmsg_flags = (short) flags;

        int spaceRequired = StructNlMsgHdr.STRUCT_SIZE + msg.STRUCT_SIZE;
        for (StructNlAttr attr : attrs) {
            spaceRequired += attr.getAlignedLength();
        }
        nlmsghdr.nlmsg_len = spaceRequired;

        final byte[] bytes = new byte[NetlinkConstants.alignedLengthOf(spaceRequired)];
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());
        nlmsghdr.pack(byteBuffer);
        msg.pack(byteBuffer);
        for (StructNlAttr attr : attrs) {
            attr.pack(byteBuffer);
        }

        return bytes;
    }

    /** Creates a netlink request to dump all addrlabels on the system. */
    public static byte[] makeDumpRequest() {
        return makeNetlinkMessage(RTM_GETADDRLABEL, NLM_F_REQUEST | NLM_F_DUMP,
                new StructIfaddrlblmsg(0));
    }

    private static byte[] makeGetRequest(InetAddress addr, int ifindex) {
        StructNlAttr attr = new StructNlAttr(IFAL_ADDRESS, addr.getAddress());
        return makeNetlinkMessage(RTM_GETADDRLABEL, NLM_F_REQUEST,
                new StructIfaddrlblmsg(128, ifindex, 0), attr);
    }

    /** Gets the addrlabel for the specified address on the specified interface. */
    public static int queryLabel(InetAddress addr, int ifindex) throws IOException, ErrnoException {
        final byte[] msg = makeGetRequest(addr, ifindex);

        FileDescriptor fd = null;
        try {
            fd = NetlinkUtils.netlinkSocketForProto(NETLINK_ROUTE);
            NetlinkUtils.connectToKernel(fd);
            NetlinkUtils.sendMessage(fd, msg, 0, msg.length, NetlinkUtils.IO_TIMEOUT_MS);
            ByteBuffer buf = NetlinkUtils.recvMessage(fd, 4096, NetlinkUtils.IO_TIMEOUT_MS);
            NetlinkMessage response = NetlinkMessage.parse(buf, NETLINK_ROUTE);
            if (response instanceof NetlinkErrorMessage) {
                final int errno = ((NetlinkErrorMessage) response).getNlMsgError().error;
                throw new ErrnoException("queryLabel", errno);
            } else if (response instanceof RtNetlinkIfaddrlabelMessage) {
                return ((RtNetlinkIfaddrlabelMessage) response).getLabel();
            } else {
                throw new ErrnoException("queryLabel", EPROTO);
            }
        } finally {
            try {
                if (fd != null) Os.close(fd);
            } catch (ErrnoException ignored) {
            }
        }
    }

    private static byte[] makeSetRequest(int msgtype, IpPrefix prefix, int ifindex, int label) {
        final StructNlAttr addrAttr = new StructNlAttr(IFAL_ADDRESS, prefix.getAddress());
        final StructNlAttr labelAttr = new StructNlAttr(IFAL_LABEL, label);
        return makeNetlinkMessage(msgtype, NLM_F_REQUEST | NLM_F_ACK,
                new StructIfaddrlblmsg(labelPrefixLength(prefix), ifindex, 0),
                addrAttr, labelAttr);
    }

    /** Adds the specified label to the kernel policy table. */
    public static void addLabel(IpPrefix prefix, int ifindex, int label) throws ErrnoException {
        final byte[] msg = makeSetRequest(RTM_NEWADDRLABEL, prefix, ifindex, label);
        NetlinkUtils.sendOneShotKernelMessage(NETLINK_ROUTE, msg);
    }

    /** Removes the specified label from the kernel policy table. */
    public static void removeLabel(IpPrefix prefix, int ifindex, int label) throws ErrnoException {
        final byte[] msg = makeSetRequest(RTM_DELADDRLABEL, prefix, ifindex, label);
        NetlinkUtils.sendOneShotKernelMessage(NETLINK_ROUTE, msg);
    }

    @Override
    public String toString() {
        return "RtNetlinkIfaddrlabelMessage{ "
                + "nlmsghdr{" + mHeader.toString(NETLINK_ROUTE) + "}, "
                + "prefix{" + mPrefix + "}, "
                + "ifindex{" + mIfindex + "}, "
                + "label{" + mLabel + "}, "
                + "seq{" + mSeq + "} "
                + "}";
    }
}
