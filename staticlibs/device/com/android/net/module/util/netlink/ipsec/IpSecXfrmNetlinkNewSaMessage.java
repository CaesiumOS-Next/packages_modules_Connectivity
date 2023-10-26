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

import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.StructNlMsgHdr;

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
    private IpSecXfrmNetlinkNewSaMessage(@NonNull StructNlMsgHdr header) {
        super(header);
    }
}
