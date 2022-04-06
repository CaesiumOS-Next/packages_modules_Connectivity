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

#include <connectivity_native.h>
#include <log/log.h>

#define NUM_PORTS 10

int main(int, char **) {
    int ret;

    ret = AConnectivityNative_BlockPortForBind(10123)
        && AConnectivityNative_BlockPortForBind(10124);
    if (ret) {
        ALOGE("TestVendorBinary: Error blocking ports: %d", ret);
        return ret;
    }

    ret = AConnectivityNative_UnblockAllPortsForBind();
    if (ret) {
        ALOGE("TestVendorBinary: Error unblocking all ports: %d", ret);
        return ret;
    }

    ret = AConnectivityNative_BlockPortForBind(10125)
        && AConnectivityNative_BlockPortForBind(10126);
    if (ret) {
        ALOGE("TestVendorBinary: Error blocking more ports all ports: %d", ret);
        return ret;
    }

    ret = AConnectivityNative_UnblockPortForBind(10126);
    if (ret) {
        ALOGE("TestVendorBinary: Error unblocking single port: %d", ret);
        return ret;
    }

    int blockedPorts[NUM_PORTS];
    int count = NUM_PORTS;
    ret = AConnectivityNative_GetPortsBlockedForBind(blockedPorts, &count);
    if (ret) {
        ALOGE("TestVendorBinary: Error getting blocked ports: %d", ret);
        return ret;
    }

    ALOGI("TestVendorBinary: %d blocked ports, first %d are:", count, NUM_PORTS);
    for (int i = 0; i < count && i < NUM_PORTS; i++) {
        ALOGI("TestVendorBinary: %d", blockedPorts[i]);
    }

    ret = AConnectivityNative_UnblockAllPortsForBind();
    if (ret) {
        ALOGE("TestVendorBinary: Error unblocking all ports again: %d", ret);
        return ret;
    }

    ret = AConnectivityNative_GetPortsBlockedForBind(blockedPorts, &count);
    if (ret) {
        ALOGE("TestVendorBinary: Error getting blocked ports again: %d", ret);
        return ret;
    }

    ALOGI("TestVendorBinary: (again) %d blocked ports, first %d are:", count, NUM_PORTS);
    for (int i = 0; i < count && i < NUM_PORTS; i++) {
        ALOGI("TestVendorBinary: %d", blockedPorts[i]);
    }

    ret = AConnectivityNative_UnblockAllPortsForBind();
    if (ret) {
        ALOGE("TestVendorBinary: Error unblocking all ports before exit: %d", ret);
        return ret;
    }
    return 0;
}
