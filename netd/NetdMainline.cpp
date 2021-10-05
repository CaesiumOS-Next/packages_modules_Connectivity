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

#define LOG_TAG "NetdMainline"

#include "NetdMainline.h"

#include <android-base/logging.h>
#include <netdutils/Status.h>

#include "NetdMainlinePublic.h"

bool libnetd_mainline_init(const char* cg2_path) {
    android::base::InitLogging(/*argv=*/nullptr);
    LOG(INFO) << __func__ << ": Initializing";

    android::net::gNetdMainline = android::net::NetdMainline::getInstance();
    if (!isOk(android::net::gNetdMainline->mBpfHandler.init(cg2_path))) {
        LOG(ERROR) << __func__ << ": BPF handler init failed";
        return false;
    }
    return true;
}

int libnetd_mainline_privilegedTagSocket(int sockFd, uint32_t tag, uid_t uid, uid_t callingUid) {
    if (android::net::gNetdMainline == nullptr) return -EPERM;
    return android::net::gNetdMainline
            ->mBpfHandler.privilegedTagSocket(sockFd, tag, uid, callingUid);
}

int libnetd_mainline_tagSocket(int sockFd, uint32_t tag, uid_t uid, uid_t callingUid) {
    if (android::net::gNetdMainline == nullptr) return -EPERM;
    return android::net::gNetdMainline->mBpfHandler.tagSocket(sockFd, tag, uid, callingUid);
}

int libnetd_mainline_unTagSocket(int sockFd) {
    if (android::net::gNetdMainline == nullptr) return -EPERM;
    return android::net::gNetdMainline->mBpfHandler.untagSocket(sockFd);
}

namespace android {
namespace net {

NetdMainline* gNetdMainline = nullptr;

NetdMainline* NetdMainline::getInstance() {
    // Instantiated on first use.
    static NetdMainline instance;
    return &instance;
}

}  // namespace net
}  // namespace android
