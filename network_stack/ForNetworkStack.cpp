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

#define LOG_TAG "ForNetworkStack"

#include <android-base/logging.h>
#include <netdutils/Status.h>

//#include <android-base/unique_fd.h>
//#include <bpf/WaitForProgsLoaded.h>
#include <log/log.h>
#include <netdutils/UidConstants.h>
//#include <private/android_filesystem_config.h>

#include "bpf/BpfMap.h"
#include "netd.h"

#include "ForNetworkStackPublic.h"

using android::bpf::BpfMap;
//using android::bpf::BpfMapRO;

//static BpfMapRO<uint32_t, uint32_t> mConfigurationMap(CONFIGURATION_MAP_PATH);
//static BpfMap<uint32_t, uint8_t> mUidPermissionMap(UID_PERMISSION_MAP_PATH);

/*
int lib4network_stack_init(void) {
    android::base::InitLogging(nullptr); // argv
    LOG(INFO) << __func__ << ": Initializing";
    return 0;

    // Make sure BPF programs are loaded before doing anything
    android::bpf::waitForProgsLoaded();
    ALOGI("BPF programs are loaded");

    auto ret = mUidPermissionMap.init(UID_PERMISSION_MAP_PATH);
    if (!android::netdutils::isOk(ret)) {
        LOG(ERROR) << __func__ << ": BPF handler init failed";
        return -ret.code();
    }
    return 0;
}
*/

/*
int lib4network_stack_isUidBlocked(uid_t uid) {
    static BpfMap<uint32_t, uint8_t> mUidPermissionMap(UID_PERMISSION_MAP_PATH);

    // This implementation is the same logic as method ActivityManager#checkComponentPermission.
    // It implies that the real uid can never be the same as PER_USER_RANGE.
    uint32_t appId = uid % PER_USER_RANGE;
    auto permission = mUidPermissionMap.readValue(appId);
    if (!permission.ok()) return false;


    return (permission.value() & BPF_PERMISSION_INTERNET) ? false : true;
}
*/

int lib4network_stack_isUidBlocked(uid_t uid) {
    // test hack
    if (uid == 0) return false;
    if (uid == 1) return false;
    if (uid == 2) return true;

    return true;
}
