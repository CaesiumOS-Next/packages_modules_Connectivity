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

#include "connectivity_native.h"

#include <android/binder_manager.h>
#include <aidl/android/net/connectivity/aidl/ConnectivityNative.h>

using aidl::android::net::connectivity::aidl::IConnectivityNative;


static std::shared_ptr<IConnectivityNative> getBinder() {
    AIBinder *conn = AServiceManager_getService("connectivity_native");
    ndk::SpAIBinder sBinder = ndk::SpAIBinder(reinterpret_cast<AIBinder*>(conn));
    return aidl::android::net::connectivity::aidl::IConnectivityNative::fromBinder(sBinder);
}

int AConnectivityNative_BlockPortForBind(int port) {
    std::shared_ptr<IConnectivityNative> c = getBinder();
    return c->blockPortForBind(port).getStatus();
}

int AConnectivityNative_UnblockPortForBind(int port) {
    std::shared_ptr<IConnectivityNative> c = getBinder();
    return c->unblockPortForBind(port).getStatus();
}

int AConnectivityNative_UnblockAllPortsForBind() {
    std::shared_ptr<IConnectivityNative> c = getBinder();
    return c->unblockAllPortsForBind().getStatus();
}

int AConnectivityNative_GetPortsBlockedForBind(int *ports, int *count) {
    std::shared_ptr<IConnectivityNative> c = getBinder();
    std::vector<int32_t> actualBlockedPorts;
    ndk::ScopedAStatus status = c->getPortsBlockedForBind(&actualBlockedPorts);
    if (!status.isOk()) {
        return status.getStatus();
    }

    for (int i = 0; i < *count && i < actualBlockedPorts.size(); i++) {
        ports[i] = actualBlockedPorts[i];
    }
    *count = actualBlockedPorts.size();
    return 0;
}
