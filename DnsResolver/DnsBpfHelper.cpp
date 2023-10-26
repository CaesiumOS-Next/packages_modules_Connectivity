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

static bool isSystemUid(uint32_t uid) { return uid < AID_APP_START; }

base::Result<void> DnsBpfHelper::init() {
  auto result = mConfigurationMap.init(CONFIGURATION_MAP_PATH);
  if (!result.ok()) return result;

  return mUidOwnerMap.init(UID_OWNER_MAP_PATH);
}

base::Result<bool> DnsBpfHelper::isUidNetworkingBlocked(uid_t uid, bool metered) {
  if (isSystemUid(uid)) return false;
  if (!mConfigurationMap.isValid() || !mUidOwnerMap.isValid()) {
    LOG(ERROR) << __func__
               << ": BPF maps are not ready. Without calling lib4dns_resolver_init in advance?";
    return base::Error(ENOENT);
  }

  auto enabledRules = mConfigurationMap.readValue(UID_RULES_CONFIGURATION_KEY);
  if (!enabledRules.ok()) {
    LOG(ERROR) << __func__
               << ": Failed to read enabled rules from configuration_map: "
               << strerror(enabledRules.error().code());
    return base::Error(enabledRules.error().code());
  }

  auto value = mUidOwnerMap.readValue(uid);
  uint32_t uidRules = value.ok() ? value.value().rule : 0;

  // For doze mode, battery saver, low power standby.
  if (isBlockedByUidRules(enabledRules.value(), uidRules)) return true;

  // For data saver.
  if (!metered) return false;

  // The background data setting (PENALTY_BOX_MATCH) and unrestricted data usage setting
  // (HAPPY_BOX_MATCH) for individual apps overwhelm the system wide Data Saver setting.
  if ((uidRules & PENALTY_BOX_MATCH) != 0) return true;
  if ((uidRules & HAPPY_BOX_MATCH) != 0) return false;

  auto dataSaverSetting = mConfigurationMap.readValue(DATA_SAVER_CONFIGURATION_KEY);
  if (!dataSaverSetting.ok()) {
    LOG(ERROR) << __func__
                << ": Failed to read data saver setting from configuration_map: "
                << strerror(dataSaverSetting.error().code());
    return base::Error(dataSaverSetting.error().code());
  }
  if (dataSaverSetting.value() == DATA_SAVER_ENABLED) return true;

  return false;
}

}  // namespace net
}  // namespace android
