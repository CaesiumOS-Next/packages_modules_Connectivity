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

package com.android.net.module.util.netlink.ipsec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.net.module.util.netlink.NetlinkConstants;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.StructNlMsgHdr;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Base calss for XFRM netlink messages */
public abstract class IpSecXfrmNetlinkMessage extends NetlinkMessage {
    // TODO: b/308011229 Remove it when OsConstants.IPPROTO_ESP is exposed
    public static final int IPPROTO_ESP = 50;
    // TODO: b/308011229 Remove it when OsConstants.NETLINK_XFRM is exposed
    public static final int NETLINK_XFRM = 6;

    public static final int XFRM_MODE_TRANSPORT = 0;
    public static final int XFRM_MODE_TUNNEL = 1;

    public static final BigInteger XFRM_INF = new BigInteger("FFFFFFFFFFFFFFFF", 16);

    public IpSecXfrmNetlinkMessage(@NonNull StructNlMsgHdr header) {
        super(header);
    }

    /**
     * Parse XFRM message from ByteBuffer.
     *
     * @param nlmsghdr netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes. MUST be
     *     native order
     */
    @Nullable
    public static IpSecXfrmNetlinkMessage parse(
            @NonNull StructNlMsgHdr nlmsghdr, @NonNull ByteBuffer byteBuffer) {
        switch (nlmsghdr.nlmsg_type) {
            case NetlinkConstants.XFRM_MSG_NEWSA:
                // TODO: Support XFRM_MSG_NEWSA
                return null;
            case NetlinkConstants.XFRM_MSG_GETSA:
                return IpSecXfrmNetlinkGetSaMessage.parse(nlmsghdr, byteBuffer);
            default:
                return null;
        }
    }

    protected static StructNlMsgHdr newXfrmNlMsgHdr(int payloadLen, short type, short flags) {
        final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_len = StructNlMsgHdr.STRUCT_SIZE + payloadLen;
        nlmsghdr.nlmsg_type = type;
        nlmsghdr.nlmsg_flags = flags;
        nlmsghdr.nlmsg_seq = 0;

        return nlmsghdr;
    }

    protected static ByteBuffer newXfrmNlMsgByteBuffer(int payloadLen) {
        final int length = StructNlMsgHdr.STRUCT_SIZE + payloadLen;
        final ByteBuffer byteBuffer = ByteBuffer.allocate(length);
        byteBuffer.order(ByteOrder.nativeOrder());

        return byteBuffer;
    }

    protected abstract void packPayload(@NonNull ByteBuffer byteBuffer);

    /** Write a XFRM message to {@link ByteBuffer}. */
    public void pack(@NonNull ByteBuffer byteBuffer) {
        getHeader().pack(byteBuffer);
        packPayload(byteBuffer);
    }
}
