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

#pragma once

#include <sys/cdefs.h>
#include <sys/types.h>

__BEGIN_DECLS

/*
 * The function read bpf maps and return whether the network is blocked for the given uid or not
 * based on the content of maps. It can only be called from process with AID_ROOT (DNS Resolver in
 * netd process). The function is supported starting from Android T.
 *
 * The |uid| is a user ID to be queried.
 * The |netId| is a network to use.
 *
 * Returns 0(false)/1(true) on success.
 * Returns -EOPNOTSUPP when called by non-AID_ROOT process, or when platform version is before T.
 * Returns a negative POSIX error code (see errno.h) on other failures.
 */
int lib4dns_resolver_isUidNetworkingBlocked(uid_t uid, unsigned netId);

__END_DECLS