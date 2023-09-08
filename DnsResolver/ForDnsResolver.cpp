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

#include <cutils/android_filesystem_config.h>
#include <errno.h>
#include <netdutils/Status.h>

#include "BpfReader.h"
#include "ForDnsResolverPublic.h"

static android::net::BpfReader sBpfReader;

static bool isCalledFromRoot() { return geteuid() == AID_ROOT; }

int lib4dns_resolver_isUidNetworkingBlocked(uid_t uid, unsigned netId) {
  if (!isCalledFromRoot()) return -EOPNOTSUPP;

  // Init once
  static android::netdutils::Status ret = sBpfReader.initMaps();
  if (!android::netdutils::isOk(ret)) return -ret.code();

  return sBpfReader.isUidNetworkingBlocked(uid, netId);
}