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

#include <android-base/logging.h>
#include <android-modules-utils/sdk_level.h>
#include <errno.h>

#include "DnsBpfHelper.h"
#include "ForDnsResolverPublic.h"

static android::net::DnsBpfHelper sDnsBpfHelper;

int lib4dns_resolver_init() {
  if (!android::modules::sdklevel::IsAtLeastT()) {
    LOG(ERROR) << __func__ << ": Unsupported before Android T.";
    return -EOPNOTSUPP;
  }

  android::base::Result<void> result = sDnsBpfHelper.init();
  if (!result.ok()) {
    LOG(ERROR) << __func__ << ": " << result.error();
    return -result.error().code();
  }

  return 0;
}

int lib4dns_resolver_isUidNetworkingBlocked(uid_t uid, bool metered) {
  return sDnsBpfHelper.isUidNetworkingBlocked(uid, metered);
}
