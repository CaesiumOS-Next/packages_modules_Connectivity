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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Base calss for XFRM netlink messages */
// Developer notes: The Linux kernel includes a number of XFRM structs that are not standard netlink
// attributes (e.g., xfrm_usersa_id). These structs are unlikely to change size, so this XFRM
// netlink message implementation assumes their sizes will remain stable. If any non-attribute
// struct size changes, it should be caught by CTS and then developers should add
// kernel-version-based behvaiours.
public abstract class IpSecXfrmNetlinkMessage extends NetlinkMessage {
    // TODO: STOPSHIP: b/308011229 Move to NetlinkConstants
    /* see include/uapi/linux/xfrm.h */
    public static final short XFRM_MSG_NEWSA = 16;
    public static final short XFRM_MSG_GETSA = 18;

    public static final BigInteger XFRM_INF = new BigInteger("FFFFFFFFFFFFFFFF", 16);

    public IpSecXfrmNetlinkMessage(@NonNull StructNlMsgHdr header) {
        super(header);
    }

    // STOPSHIP: b/308011229 #parse(ByteBuffer, int) and #parseCtlMessage(StructNlMsgHdr,
    // ByteBuffer, int) are copied from NetlinkMessage. These copies are identical to the original
    // methods except that the copied methods only handle XFRM and control messages. Followup CLs
    // should remove the copies and use NetlinkMessage as the code entry to parse XFRM messages.

    /**
     * Parsing netlink messages for reserved control message or specific netlink message. The
     * netlink family is required for parsing specific netlink message. See man-pages/netlink.
     */
    @Nullable
    public static NetlinkMessage parse(@NonNull ByteBuffer byteBuffer, int nlFamily) {
        final int startPosition = (byteBuffer != null) ? byteBuffer.position() : -1;
        final StructNlMsgHdr nlmsghdr = StructNlMsgHdr.parse(byteBuffer);
        if (nlmsghdr == null) {
            return null;
        }

        final int messageLength = NetlinkConstants.alignedLengthOf(nlmsghdr.nlmsg_len);
        final int payloadLength = messageLength - StructNlMsgHdr.STRUCT_SIZE;
        if (payloadLength < 0 || payloadLength > byteBuffer.remaining()) {
            // Malformed message or runt buffer.  Pretend the buffer was consumed.
            byteBuffer.position(byteBuffer.limit());
            return null;
        }

        // Reserved control messages. The netlink family is ignored.
        // See NLMSG_MIN_TYPE in include/uapi/linux/netlink.h.
        if (nlmsghdr.nlmsg_type <= NetlinkConstants.NLMSG_MAX_RESERVED) {
            return parseCtlMessage(nlmsghdr, byteBuffer, payloadLength);
        }

        // Netlink family messages. The netlink family is required. Note that the reason for using
        // if-statement is that switch-case can't be used because the OsConstants.NETLINK_* are
        // not constant.
        final NetlinkMessage parsed = parse(nlmsghdr, byteBuffer);

        // Advance to the end of the message, regardless of whether the parsing code consumed
        // all of it or not.
        byteBuffer.position(startPosition + messageLength);

        return parsed;
    }

    @NonNull
    private static NetlinkMessage parseCtlMessage(
            @NonNull StructNlMsgHdr nlmsghdr, @NonNull ByteBuffer byteBuffer, int payloadLength) {
        switch (nlmsghdr.nlmsg_type) {
            case NetlinkConstants.NLMSG_ERROR:
                return (NetlinkMessage) NetlinkErrorMessage.parse(nlmsghdr, byteBuffer);
            default: {
                // Other netlink control messages. Just parse the header for now,
                // pretending the whole message was consumed.
                byteBuffer.position(byteBuffer.position() + payloadLength);
                return new NetlinkMessage(nlmsghdr);
            }
        }
    }

    /**
     * Parse XFRM message from ByteBuffer.
     *
     * @param nlmsghdr netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes. MUST be
     *     host order
     */
    @Nullable
    public static IpSecXfrmNetlinkMessage parse(
            @NonNull StructNlMsgHdr nlmsghdr, @NonNull ByteBuffer byteBuffer) {
        switch (nlmsghdr.nlmsg_type) {
            case XFRM_MSG_NEWSA:
                // TODO: Support XFRM_MSG_NEWSA
                return null;
            case XFRM_MSG_GETSA:
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
