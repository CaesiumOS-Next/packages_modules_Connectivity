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
 * Initialize required BPF maps. This function need to be called before using other functions of
 * this library.
 *
 * Returns 0 on success, a negative POSIX error code (see errno.h) on other failures.
 */
int lib4dns_resolver_init();

/*
 * The function read bpf maps and return whether the given uid is networking blocked or not. The
 * function is supported starting from Android T.
 *
 * The |uid| is a user ID to be queried.
 * The |metered| indicates whether the uid is currently using a billing network.
 *
 * Returns 0(false)/1(true) on success, a negative POSIX error code (see errno.h) on other failures.
 */
int lib4dns_resolver_isUidNetworkingBlocked(uid_t uid, bool metered);

__END_DECLS
