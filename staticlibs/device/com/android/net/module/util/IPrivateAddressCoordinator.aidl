/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.net.module.util;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

/** @hide */
@Descriptor("value=no.jarjar.com.android.net.module.util.IPrivateAddressCoordinator")
interface IPrivateAddressCoordinator {
   void updateUpstreamPrefix(in LinkProperties linkProperties,
                             in NetworkCapabilities nc,
                             in Network network);
   void removeUpstreamPrefix(in Network network);
   void maybeRemoveDeprecatedUpstreams();
   int allocateClientId();
   LinkAddress requestDownstreamAddress(in int clientId,
                                        in int scope,
                                        in int interfaceType,
                                        in boolean useLastAddress);
   void releaseDownstream(in int clientId);
   void clearUpstreamPrefixes();
}
