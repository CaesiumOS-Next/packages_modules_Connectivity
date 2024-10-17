/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.net.module.util.bpf;

import com.android.net.module.util.Struct;

/**
 * Value for cookie tag map.
 */
public class TrieMapValue extends Struct {

//    @Retention(RetentionPolicy.SOURCE)
//    @LongDef(value = {
//            LOCAL_IP,
//            LOCAL_NETWORK_IP,
//            GLOBAL_IP,
//            MULTICAST_ADDRESS,
//            BROADCAST_ADDRESS,
//            ULA_SITE_IP,
//            LINK_LOCAL_ADDRESS,
//    })
//    public @interface IpAddressType {}
//
//    public static final long LOCAL_IP = 1;
//    public static final long LOCAL_NETWORK_IP = 2;
//    public static final long GLOBAL_IP = 3;
//    public static final long MULTICAST_ADDRESS = 4;
//    public static final long BROADCAST_ADDRESS = 5;
//    public static final long ULA_SITE_IP = 6;
//    public static final long LINK_LOCAL_ADDRESS =7;

    @Field(order = 0, type = Type.U32)
    public final long value;

    public TrieMapValue(final long value) {
        this.value = value;
    }
}
