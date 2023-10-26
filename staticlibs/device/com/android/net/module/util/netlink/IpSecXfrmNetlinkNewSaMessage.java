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

import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessageUtils.XFRMA_REPLAY_ESN_VAL;
import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessageUtils.XFRMA_REPLAY_VAL;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A NetlinkMessage subclass for XFRM_MSG_NEWSA messages.
 *
 * <p>see also: &lt;linux_src&gt;/include/uapi/linux/xfrm.h
 *
 * <p>XFRM_MSG_NEWSA syntax
 *
 * <ul>
 *   <li>TLV: xfrm_usersa_info
 *   <li>Attributes: XFRMA_ALG_CRYPT, XFRMA_ALG_AUTH, XFRMA_OUTPUT_MARK, XFRMA_IF_ID,
 *       XFRMA_REPLAY_ESN_VAL,XFRMA_REPLAY_VAL
 * </ul>
 *
 * @hide
 */
public class IpSecXfrmNetlinkNewSaMessage extends NetlinkMessage {
    private IpSecStructXfrmUsersaInfo mXfrmUsersaInfo;
    private List<StructNlAttr> mAttrList = new ArrayList<>();

    private IpSecXfrmNetlinkNewSaMessage(@NonNull StructNlMsgHdr header) {
        super(header);
        mXfrmUsersaInfo = null;
    }

    /**
     * Parse XFRM_MSG_NEWSA message from ByteBuffer.
     *
     * @param header netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes. MUST be
     *     native order
     */
    @Nullable
    public static NetlinkMessage parse(@NonNull ByteBuffer byteBuffer) {
        // BEGIN: Move to a base class
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
        // END: Move to a base class

        android.util.Log.i("TEST", "construct IpSecXfrmNetlinkNewSaMessage ");
        final IpSecXfrmNetlinkNewSaMessage msg = new IpSecXfrmNetlinkNewSaMessage(nlmsghdr);
        msg.mXfrmUsersaInfo = IpSecStructXfrmUsersaInfo.parse(byteBuffer);

        if (msg.mXfrmUsersaInfo == null) {
            return null;
        }

        int parsedLength = IpSecStructXfrmUsersaInfo.STRUCT_SIZE;

        while (parsedLength < payloadLength) {
            android.util.Log.i("TEST", "byteBuffer.position " + byteBuffer.position());
            final StructNlAttr attr = StructNlAttr.parse(byteBuffer);
            if (attr.nla_type == XFRMA_REPLAY_ESN_VAL) {
                android.util.Log.i("TEST", "attr.nla_type XFRMA_REPLAY_ESN_VAL ");
                final IpSecStructXfrmReplayStateEsn replayStateEsn = IpSecStructXfrmReplayStateEsn.parse(attr.nla_value);
                android.util.Log.i("TEST", "parse XFRMA_REPLAY_ESN_VAL done byteBuffer.position " + byteBuffer.position());

            } else if(attr.nla_type == XFRMA_REPLAY_VAL) {
                android.util.Log.i("TEST", "attr.nla_type XFRMA_REPLAY_VAL ");
                final IpSecStructXfrmReplayState replayState = IpSecStructXfrmReplayState.parse(attr.nla_value);
                android.util.Log.i("TEST", "parse XFRMA_REPLAY_VAL done byteBuffer.position " + byteBuffer.position());
            }
            
            parsedLength += attr.nla_len;
            android.util.Log.i("TEST", "attr.nla_type " + attr.nla_type);
            android.util.Log.i("TEST", "attr.nla_len " + attr.nla_len);
        }

        return msg;
    }

    private static NetlinkMessage parseCtlMessage(
            @NonNull StructNlMsgHdr nlmsghdr, @NonNull ByteBuffer byteBuffer, int payloadLength) {
        switch (nlmsghdr.nlmsg_type) {
            case NetlinkConstants.NLMSG_ERROR:
                return (NetlinkMessage) NetlinkErrorMessage.parse(nlmsghdr, byteBuffer);
            default:
                {
                    // Other netlink control messages. Just parse the header for now,
                    // pretending the whole message was consumed.
                    byteBuffer.position(byteBuffer.position() + payloadLength);
                    return new NetlinkMessage(nlmsghdr);
                }
        }
    }
}
