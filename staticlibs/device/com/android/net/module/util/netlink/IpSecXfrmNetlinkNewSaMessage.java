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

import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.XFRMA_REPLAY_ESN_VAL;
import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.XFRMA_REPLAY_VAL;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.net.module.util.Struct;

import java.nio.ByteBuffer;

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
public class IpSecXfrmNetlinkNewSaMessage extends IpSecXfrmNetlinkMessage {
    private IpSecStructXfrmUsersaInfo mXfrmUsersaInfo;

    @Nullable private IpSecStructXfrmReplayStateEsn mXfrmReplayStateEsn;

    private IpSecXfrmNetlinkNewSaMessage(@NonNull StructNlMsgHdr header) {
        super(header);
    }

    @Override
    protected void packPayload(@NonNull ByteBuffer byteBuffer) {
        mXfrmUsersaInfo.writeToByteBuffer(byteBuffer);
        if (mXfrmReplayStateEsn != null) {
            mXfrmReplayStateEsn.writeToByteBuffer(byteBuffer);
        }
    }

    /**
     * Parse XFRM_MSG_NEWSA message from ByteBuffer.
     *
     * @param header netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes. MUST be
     *     native order
     */
    @Nullable
    public static IpSecXfrmNetlinkNewSaMessage parse(
            @NonNull StructNlMsgHdr nlmsghdr, @NonNull ByteBuffer byteBuffer) {
        // android.util.Log.i("TEST", "construct IpSecXfrmNetlinkNewSaMessage ");
        final IpSecXfrmNetlinkNewSaMessage msg = new IpSecXfrmNetlinkNewSaMessage(nlmsghdr);
        msg.mXfrmUsersaInfo = Struct.parse(IpSecStructXfrmUsersaInfo.class, byteBuffer);

        if (msg.mXfrmUsersaInfo == null) {
            return null;
        }

        final int payloadLen = nlmsghdr.nlmsg_len - StructNlMsgHdr.STRUCT_SIZE;

        int parsedLength = IpSecStructXfrmUsersaInfo.STRUCT_SIZE;
        while (parsedLength < payloadLen) {
            // android.util.Log.i("TEST", "byteBuffer.position " + byteBuffer.position());
            final StructNlAttr attr = StructNlAttr.parse(byteBuffer);
            if (attr.nla_type == XFRMA_REPLAY_ESN_VAL) {
                // android.util.Log.i("TEST", "attr.nla_type XFRMA_REPLAY_ESN_VAL ");
                msg.mXfrmReplayStateEsn = IpSecStructXfrmReplayStateEsn.parse(attr.nla_value);

            } else if (attr.nla_type == XFRMA_REPLAY_VAL) {
                android.util.Log.i("TEST", "attr.nla_type XFRMA_REPLAY_VAL ");
                // TODO:
            }

            parsedLength += attr.nla_len;
            // android.util.Log.i("TEST", "attr.nla_type " + attr.nla_type);
            // android.util.Log.i("TEST", "attr.nla_len " + attr.nla_len);
        }

        return msg;
    }

    public IpSecStructXfrmReplayStateEsn getXfrmReplayStateEsn() {
        return mXfrmReplayStateEsn;
    }
}
