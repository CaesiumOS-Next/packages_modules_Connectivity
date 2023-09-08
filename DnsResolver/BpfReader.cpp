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

#define LOG_TAG "BpfReader"

#include "BpfReader.h"

#include <android-base/logging.h>
#include <android-modules-utils/sdk_level.h>
#include <cutils/android_filesystem_config.h>

namespace android {
namespace net {

using netdutils::Status;
using netdutils::statusFromErrno;
using netdutils::status::ok;

static constexpr int UNBLOCKED = 0;
static constexpr int BLOCKED = 1;

static int isSystemUid(uint32_t uid) { return uid < AID_APP_START; }

Status BpfReader::initMaps() {
  if (!android::modules::sdklevel::IsAtLeastT()) {
    LOG(ERROR) << __func__ << ": Unsupported before Android T.";
    return statusFromErrno(EOPNOTSUPP, "");
  }

  RETURN_IF_NOT_OK(mConfigurationMap.init(CONFIGURATION_MAP_PATH));
  RETURN_IF_NOT_OK(mUidOwnerMap.init(UID_OWNER_MAP_PATH));
  return ok;
}

// TODO: Move to a common place in p/m/c for both netd.c and this.
// DROP_IF_SET is set of rules that DROP if rule is globally enabled, and per-uid bit is set
#define DROP_IF_SET (STANDBY_MATCH | OEM_DENY_1_MATCH | OEM_DENY_2_MATCH | OEM_DENY_3_MATCH)
// DROP_IF_UNSET is set of rules that should DROP if globally enabled, and per-uid bit is NOT set
#define DROP_IF_UNSET (DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH | LOW_POWER_STANDBY_MATCH)

int BpfReader::isUidNetworkingBlocked(uid_t uid, unsigned) {
  if (isSystemUid(uid)) return UNBLOCKED;

  auto enabledRules = mConfigurationMap.readValue(UID_RULES_CONFIGURATION_KEY);
  if (!enabledRules.ok()) {
    LOG(ERROR) << __func__
               << ": Failed to read enabled rules from configuration_map: "
               << strerror(enabledRules.error().code());
    return -enabledRules.error().code();
  }

  auto value = mUidOwnerMap.readValue(uid);
  uint32_t uidRules = value.ok() ? value.value().rule : 0;

  if (enabledRules.value() & (DROP_IF_SET | DROP_IF_UNSET) & (uidRules ^ DROP_IF_UNSET)) {
    return BLOCKED;
  }

  // TODO: Read meter network and data saver settings from bpf maps. For metered network, check
  // penalty box, happy box and data saver settings.

  return UNBLOCKED;
}

}  // namespace net
}  // namespace android
