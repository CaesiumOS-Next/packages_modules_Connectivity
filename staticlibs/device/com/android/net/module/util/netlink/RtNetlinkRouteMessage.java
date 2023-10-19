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

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import static android.system.OsConstants.NETLINK_ROUTE;
import static com.android.net.module.util.NetworkStackConstants.IPV4_ADDR_ANY;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ANY;
import static com.android.net.module.util.netlink.NetlinkConstants.hexify;
import static com.android.net.module.util.netlink.NetlinkConstants.NLMSG_DONE;
import static com.android.net.module.util.netlink.NetlinkUtils.DEFAULT_RECV_BUFSIZE;
import static com.android.net.module.util.netlink.NetlinkUtils.IO_TIMEOUT_MS;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import android.annotation.SuppressLint;
import android.net.IpPrefix;
import android.net.RouteInfo;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A NetlinkMessage subclass for rtnetlink route messages.
 *
 * RtNetlinkRouteMessage.parse() must be called with a ByteBuffer that contains exactly one
 * netlink message.
 *
 * see also:
 *
 *     include/uapi/linux/rtnetlink.h
 *
 * @hide
 */
public class RtNetlinkRouteMessage extends NetlinkMessage {
    public static final short RTA_DST           = 1;
    public static final short RTA_SRC           = 2;
    public static final short RTA_IIF           = 3;
    public static final short RTA_OIF           = 4;
    public static final short RTA_GATEWAY       = 5;
    public static final short RTA_CACHEINFO     = 12;
    public static final short RTA_EXPIRES       = 23;

    public static final short RTNH_F_UNRESOLVED = 32;   // The multicast route is unresolved
    public static final short RTNL_FAMILY_IP6MR = 129;

    public static final String TAG = "NetlinkRouteMessage";

    // For multicast routes, whether the route is resolved or unresolved
    private boolean mIsResolved;
    // The interface index for incoming interface, this is set for multicast
    // routes, see common/net/ipv4/ipmr_base.c mr_fill_mroute
    private int mIifIndex;
    private int mOifIndex;
    @NonNull
    private StructRtMsg mRtmsg;
    @Nullable
    private IpPrefix mSource; // Source address of a route, this is set for multicast routes
    @Nullable
    private IpPrefix mDestination;
    @Nullable
    private InetAddress mGateway;
    @Nullable
    private StructRtaCacheInfo mRtaCacheInfo;
    private long mExpiresClocks;

    private RtNetlinkRouteMessage(StructNlMsgHdr header, StructRtMsg rtMsg) {
        super(header);
        mRtmsg = rtMsg;
        mSource = null;
        mDestination = null;
        mGateway = null;
        mIifIndex = 0;
        mOifIndex = 0;
        mRtaCacheInfo = null;
    }

    public boolean isResolved() {
        return mIsResolved;
    }

    public int getIifIndex() {
        return mIifIndex;
    }

    public int getInterfaceIndex() {
        return mOifIndex;
    }

    @NonNull
    public StructRtMsg getRtMsgHeader() {
        return mRtmsg;
    }

    @NonNull
    public IpPrefix getDestination() {
        return mDestination;
    }

    /*
     * Get source address of a route. This is for multicast routes.
     */
    @NonNull
    public IpPrefix getSource() {
        return mSource;
    }

    @Nullable
    public InetAddress getGateway() {
        return mGateway;
    }

    @Nullable
    public StructRtaCacheInfo getRtaCacheInfo() {
        return mRtaCacheInfo;
    }

    /*
     * RTA_EXPIRES attribute returned by kernel to indicate the clock ticks
     * from the route was last used to now. This is set for multicast routes.
     */
    public long getExpiresClocks() {
        return mExpiresClocks;
    }

    /**
     * Check whether the address families of destination and gateway match rtm_family in
     * StructRtmsg.
     *
     * For example, IPv4-mapped IPv6 addresses as an IPv6 address will be always converted to IPv4
     * address, that's incorrect when upper layer creates a new {@link RouteInfo} class instance
     * for IPv6 route with the converted IPv4 gateway.
     */
    private static boolean matchRouteAddressFamily(@NonNull final InetAddress address,
            int family) {
        return ((address instanceof Inet4Address) && (family == AF_INET))
                || ((address instanceof Inet6Address) &&
                        (family == AF_INET6 || family == RTNL_FAMILY_IP6MR));
    }

    /**
     * Parse rtnetlink route message from {@link ByteBuffer}. This method must be called with a
     * ByteBuffer that contains exactly one netlink message.
     *
     * @param header netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes.
     */
    @SuppressLint("NewApi")
    @Nullable
    public static RtNetlinkRouteMessage parse(@NonNull final StructNlMsgHdr header,
            @NonNull final ByteBuffer byteBuffer) {
        final RtNetlinkRouteMessage routeMsg = new RtNetlinkRouteMessage(header, null);

        routeMsg.mRtmsg = StructRtMsg.parse(byteBuffer);
        if (routeMsg.mRtmsg == null) return null;
        int rtmFamily = routeMsg.mRtmsg.family;
        long rtmFlags = routeMsg.mRtmsg.flags;
        routeMsg.mIsResolved = ((rtmFlags & RTNH_F_UNRESOLVED) == 0);

        // RTA_DST
        final int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = StructNlAttr.findNextAttrOfType(RTA_DST, byteBuffer);
        if (nlAttr != null) {
            final InetAddress destination = nlAttr.getValueAsInetAddress();
            // If the RTA_DST attribute is malformed, return null.
            if (destination == null) return null;
            // If the address family of destination doesn't match rtm_family, return null.
            if (!matchRouteAddressFamily(destination, rtmFamily)) return null;
            routeMsg.mDestination = new IpPrefix(destination, routeMsg.mRtmsg.dstLen);
        } else if (rtmFamily == AF_INET) {
            routeMsg.mDestination = new IpPrefix(IPV4_ADDR_ANY, 0);
        } else if (rtmFamily == AF_INET6 || rtmFamily == RTNL_FAMILY_IP6MR) {
            routeMsg.mDestination = new IpPrefix(IPV6_ADDR_ANY, 0);
        } else {
            return null;
        }

        // RTA_SRC
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(RTA_SRC, byteBuffer);
        if (nlAttr != null) {
            final InetAddress source = nlAttr.getValueAsInetAddress();
            // If the RTA_DST attribute is malformed, return null.
            if (source == null) return null;
            // If the address family of destination doesn't match rtm_family, return null.
            if (!matchRouteAddressFamily(source, rtmFamily)) return null;
            routeMsg.mSource = new IpPrefix(source, routeMsg.mRtmsg.srcLen);
        } else if (rtmFamily == AF_INET) {
            routeMsg.mSource = new IpPrefix(IPV4_ADDR_ANY, 0);
        } else if (rtmFamily == AF_INET6 || rtmFamily == RTNL_FAMILY_IP6MR) {
            routeMsg.mSource = new IpPrefix(IPV6_ADDR_ANY, 0);
        } else {
            return null;
        }

        // RTA_GATEWAY
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(RTA_GATEWAY, byteBuffer);
        if (nlAttr != null) {
            routeMsg.mGateway = nlAttr.getValueAsInetAddress();
            // If the RTA_GATEWAY attribute is malformed, return null.
            if (routeMsg.mGateway == null) return null;
            // If the address family of gateway doesn't match rtm_family, return null.
            if (!matchRouteAddressFamily(routeMsg.mGateway, rtmFamily)) return null;
        }

        // RTA_IIF
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(RTA_IIF, byteBuffer);
        if (nlAttr != null) {
            routeMsg.mIifIndex = nlAttr.getValueAsInt(0 /* 0 isn't a valid ifindex */);
        }

        // RTA_OIF
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(RTA_OIF, byteBuffer);
        if (nlAttr != null) {
            // Any callers that deal with interface names are responsible for converting
            // the interface index to a name themselves. This may not succeed or may be
            // incorrect, because the interface might have been deleted, or even deleted
            // and re-added with a different index, since the netlink message was sent.
            routeMsg.mOifIndex = nlAttr.getValueAsInt(0 /* 0 isn't a valid ifindex */);
        }

        // RTA_CACHEINFO
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(RTA_CACHEINFO, byteBuffer);
        if (nlAttr != null) {
            routeMsg.mRtaCacheInfo = StructRtaCacheInfo.parse(nlAttr.getValueAsByteBuffer());
        }

        // RTA_EXPIRES
        byteBuffer.position(baseOffset);
        nlAttr = StructNlAttr.findNextAttrOfType(RTA_EXPIRES, byteBuffer);
        if (nlAttr != null) {
            routeMsg.mExpiresClocks = nlAttr.getValueAsLong();
        }

        return routeMsg;
    }

    /**
     * Write a rtnetlink address message to {@link ByteBuffer}.
     */
    @VisibleForTesting
    protected void pack(ByteBuffer byteBuffer) {
        getHeader().pack(byteBuffer);
        mRtmsg.pack(byteBuffer);

        if (mDestination != null) {
            final StructNlAttr destination = new StructNlAttr(RTA_DST, mDestination.getAddress());
            destination.pack(byteBuffer);
        }

        if (mGateway != null) {
            final StructNlAttr gateway = new StructNlAttr(RTA_GATEWAY, mGateway.getAddress());
            gateway.pack(byteBuffer);
        }
        if (mOifIndex != 0) {
            final StructNlAttr ifindex = new StructNlAttr(RTA_OIF, mOifIndex);
            ifindex.pack(byteBuffer);
        }
        if (mRtaCacheInfo != null) {
            final StructNlAttr cacheInfo = new StructNlAttr(RTA_CACHEINFO,
                    mRtaCacheInfo.writeToBytes());
            cacheInfo.pack(byteBuffer);
        }
    }

    @Override
    public String toString() {
        return "RtNetlinkRouteMessage{ "
                + "nlmsghdr{" + mHeader.toString(OsConstants.NETLINK_ROUTE) + "}, "
                + "Rtmsg{" + mRtmsg.toString() + "}, "
                + "source{" + mSource.getAddress().getHostAddress() + "}, "
                + "destination{" + mDestination.getAddress().getHostAddress() + "}, "
                + "gateway{" + (mGateway == null ? "" : mGateway.getHostAddress()) + "}, "
                + "iifindex{" + mIifIndex + "}, "
                + "oifindex{" + mOifIndex + "}, "
                + "rta_cacheinfo{" + (mRtaCacheInfo == null ? "" : mRtaCacheInfo.toString()) + "} "
                + "expiresClocks{" + mExpiresClocks + "}"
                + "}";
    }

    /**
     * Construct a RTM_GETROUTE message for dumping multicast routes from kernel.
     */
    private static byte[] newMulticastRouteDumpRequest() {
        final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_type = NetlinkConstants.RTM_GETROUTE;
        nlmsghdr.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
        short shortZero = 0;
        long longZero = 0;

        final StructRtMsg rtMsg =
                new StructRtMsg(RTNL_FAMILY_IP6MR, shortZero, shortZero, shortZero,
                    shortZero, shortZero, shortZero, shortZero, longZero);
        final RtNetlinkRouteMessage msg =
            new RtNetlinkRouteMessage(nlmsghdr, rtMsg);

        final int spaceRequired = StructNlMsgHdr.STRUCT_SIZE + StructRtMsg.STRUCT_SIZE;
        final byte[] bytes = new byte[spaceRequired];
        nlmsghdr.nlmsg_len = bytes.length;
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());
        msg.pack(byteBuffer);
        return bytes;
     }

    private static void sendNetlinkDumpRequest(FileDescriptor fd)
            throws InterruptedIOException, ErrnoException {
        final byte[] dumpMsg = newMulticastRouteDumpRequest();
        NetlinkUtils.sendMessage(fd, dumpMsg, 0, dumpMsg.length, IO_TIMEOUT_MS);
    }

    private static List<RtNetlinkRouteMessage> processNetlinkDumpMessages(FileDescriptor dumpFd)
            throws InterruptedIOException, ErrnoException {
        List<RtNetlinkRouteMessage> multicastRoutes = new ArrayList<>();
        while (true) {
            final ByteBuffer buf = NetlinkUtils.recvMessage(
                    dumpFd, NetlinkUtils.DEFAULT_RECV_BUFSIZE, IO_TIMEOUT_MS);
            if (buf == null) {
                return multicastRoutes;
            }
            while (buf.remaining() > 0) {
                final int position = buf.position();
                final NetlinkMessage nlMsg = NetlinkMessage.parse(buf, NETLINK_ROUTE);
                if (nlMsg == null) {
                    // Move to the position where parse started for error log.
                    buf.position(position);
                    Log.e(TAG, "Failed to parse netlink message: " + hexify(buf));
                    return multicastRoutes;
                }

                if (nlMsg.getHeader().nlmsg_type == NLMSG_DONE) {
                    return multicastRoutes;
                }

                if (!(nlMsg instanceof RtNetlinkRouteMessage)) {
                    Log.wtf(TAG, "Received unexpected netlink message: " + nlMsg);
                    continue;
                }

                final RtNetlinkRouteMessage routeMsg = (RtNetlinkRouteMessage) nlMsg;
                multicastRoutes.add(routeMsg);
            }
        }
    }

    /*
     * Get the list of multicast route messages from kernel.
     */
    public static List<RtNetlinkRouteMessage> getMulticastRoutes()
            throws InterruptedIOException, ErrnoException {
        FileDescriptor fd = NetlinkUtils.netlinkSocketForProto(NETLINK_ROUTE);
        sendNetlinkDumpRequest(fd);
        return processNetlinkDumpMessages(fd);
    }
}
