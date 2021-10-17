/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG "ConnForNetd"

#include "ConnectivityForNetd.h"

#include <android-base/logging.h>
#include <netdutils/Status.h>

#include "ConnForNetdPublic.h"

bool libconn_init() {
    android::base::InitLogging(/*argv=*/nullptr);
    LOG(INFO) << __func__ << ": Initializing";

    android::net::gConnectivity = android::net::ConnectivityForNetd::getInstance();
    if (!isOk(android::net::gConnectivity->mBpfHandler.init())) {
        LOG(ERROR) << __func__ << ": BFP init failed";
        return false;
    }
    return true;
}

int libconn_tagSocket(int /*sockFd*/, uint32_t /*tag*/, uid_t /*uid*/, uid_t /*callingUid*/) {
    return 0;
}

int libconn_unTagSocket(int /*sockFd*/) {
    return 0;
}

namespace android {
namespace net {

ConnectivityForNetd* gConnectivity = nullptr;

ConnectivityForNetd* ConnectivityForNetd::getInstance() {
    // Instantiated on first use.
    static ConnectivityForNetd instance;
    return &instance;
}

}  // namespace net
}  // namespace android