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

package com.android.net.module.util.netlink;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Predicate;

import com.android.net.module.util.netlink.RtNetlinkRouteMessage;
import com.android.net.module.util.netlink.StructRtMsg;
import android.system.ErrnoException;
import android.util.Log;
import androidx.annotation.NonNull;

import static android.system.OsConstants.NETLINK_ROUTE;
import static com.android.net.module.util.netlink.NetlinkConstants.hexify;
import static com.android.net.module.util.netlink.NetlinkConstants.NLMSG_DONE;
import static com.android.net.module.util.netlink.NetlinkUtils.DEFAULT_RECV_BUFSIZE;
import static com.android.net.module.util.netlink.NetlinkUtils.IO_TIMEOUT_MS;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST;

public class RtNetlinkMulticastRouteDumpMessage extends NetlinkMessage {
    public static final String TAG = "NetlinkMulticastRoute";
    public static final short RTNL_FAMILY_IP6MR = 129;

    @NonNull
    private StructRtMsg mRtMsg;

    public RtNetlinkMulticastRouteDumpMessage(@NonNull final StructNlMsgHdr header,
            @NonNull final StructRtMsg rtMsg) {
        super(header);
        mRtMsg = rtMsg;
    }

     /**
      * Write a rtnetlink mrule message to {@link ByteBuffer}.
      */
    protected void pack(ByteBuffer byteBuffer) {
         getHeader().pack(byteBuffer);
         mRtMsg.pack(byteBuffer);
     }
 
    public static byte[] newMulticastRouteDumpRequest() {
         final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
         nlmsghdr.nlmsg_type = NetlinkConstants.RTM_GETROUTE;
         nlmsghdr.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
         short short_zero = 0;
         long long_zero = 0;

         final StructRtMsg rtMsg =
                 new StructRtMsg(RTNL_FAMILY_IP6MR, short_zero, short_zero, short_zero,
                        short_zero, short_zero, short_zero, short_zero, long_zero);
         final RtNetlinkMulticastRouteDumpMessage msg =
                new RtNetlinkMulticastRouteDumpMessage(nlmsghdr, rtMsg);
 
         final byte[] bytes = new byte[msg.getRequiredSpace()];
         nlmsghdr.nlmsg_len = bytes.length;
         final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
         byteBuffer.order(ByteOrder.nativeOrder());
         msg.pack(byteBuffer);
         return bytes;
     }

    public static void sendNetlinkDumpRequest(FileDescriptor fd)
            throws InterruptedIOException, ErrnoException {
        final byte[] dumpMsg = newMulticastRouteDumpRequest();
        NetlinkUtils.sendMessage(fd, dumpMsg, 0, dumpMsg.length, IO_TIMEOUT_MS);
    }

    public interface UpdateMulticastRoute {
        void updateMulticastRouteInactiveDuration(int iifIndex, Inet6Address source,
                Inet6Address group, long expires);
    }

    public static void processNetlinkDumpMessages(FileDescriptor dumpFd, UpdateMulticastRoute func)
            throws InterruptedIOException, ErrnoException {
        while (true) {
            final ByteBuffer buf = NetlinkUtils.recvMessage(
                    dumpFd, NetlinkUtils.DEFAULT_RECV_BUFSIZE, IO_TIMEOUT_MS);

            while (buf.remaining() > 0) {
                final int position = buf.position();
                final NetlinkMessage nlMsg = NetlinkMessage.parse(buf, NETLINK_ROUTE);
                if (nlMsg == null) {
                    // Move to the position where parse started for error log.
                    buf.position(position);
                    Log.e(TAG, "Failed to parse netlink message: " + hexify(buf));
                    break;
                }

                if (nlMsg.getHeader().nlmsg_type == NLMSG_DONE) {
                    return;
                }

                if (!(nlMsg instanceof RtNetlinkRouteMessage)) {
                    Log.wtf(TAG, "Received unexpected netlink message: " + nlMsg);
                    continue;
                }

                final RtNetlinkRouteMessage routeMsg = (RtNetlinkRouteMessage) nlMsg;
                func.updateMulticastRouteInactiveDuration(routeMsg.getInterfaceIndex(),
                        (Inet6Address) routeMsg.getSource().getAddress(),
                        (Inet6Address) routeMsg.getDestination().getAddress(),
                        routeMsg.getExpires());
            }
        }
    }
    

    private int getRequiredSpace() {
        int spaceRequired = StructNlMsgHdr.STRUCT_SIZE + StructRtMsg.STRUCT_SIZE;
        return spaceRequired;
    }
 
     @Override
     public String toString() {
         return "RtNetlinkMulticastRouteDumpMessage{ "
                 + "nlmsghdr{" + mHeader.toString(NETLINK_ROUTE) + "}, "
                 + "}";
     }
 }