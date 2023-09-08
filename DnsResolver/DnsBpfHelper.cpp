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

#define LOG_TAG "DnsBpfHelper"

#include "DnsBpfHelper.h"

#include <android-base/logging.h>
#include <cutils/android_filesystem_config.h>

namespace android {
namespace net {

static constexpr int UNBLOCKED = 0;
static constexpr int BLOCKED = 1;

static bool isSystemUid(uint32_t uid) { return uid < AID_APP_START; }

base::Result<void> DnsBpfHelper::init() {
  auto result = mConfigurationMap.init(CONFIGURATION_MAP_PATH);
  if (!result.ok()) return result;

  return mUidOwnerMap.init(UID_OWNER_MAP_PATH);
}

int DnsBpfHelper::isUidNetworkingBlocked(uid_t uid, bool) {
  if (isSystemUid(uid)) return UNBLOCKED;
  if (!mConfigurationMap.isValid() || !mUidOwnerMap.isValid()) {
    LOG(ERROR) << __func__
               << ": BPF maps are not ready. Without calling lib4dns_resolver_init in advance?";
    return -ENOENT;
  }

  auto enabledRules = mConfigurationMap.readValue(UID_RULES_CONFIGURATION_KEY);
  if (!enabledRules.ok()) {
    LOG(ERROR) << __func__
               << ": Failed to read enabled rules from configuration_map: "
               << strerror(enabledRules.error().code());
    return -enabledRules.error().code();
  }

  auto value = mUidOwnerMap.readValue(uid);
  uint32_t uidRules = value.ok() ? value.value().rule : 0;

  if (isBlockedByUidRules(enabledRules.value(), uidRules)) return BLOCKED;

  // TODO: Read meter network and data saver settings from bpf maps. For metered network, check
  // penalty box, happy box and data saver settings.

  return UNBLOCKED;
}

}  // namespace net
}  // namespace android
