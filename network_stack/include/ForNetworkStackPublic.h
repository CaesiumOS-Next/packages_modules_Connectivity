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

#include <stdint.h>
#include <sys/cdefs.h>
#include <sys/types.h>

__BEGIN_DECLS

/*
 * Initial function for lib4network_stack library.
 *
 * Returns 0 on success, or a negative POSIX error code (see errno.h) on failure.
 */
//int lib4network_stack_init(void);

/*
 * Check if a UID is blocked.  (add more description)
 *
 * Returns 0(false)/1(true) on success,
 * or a negative POSIX error code (see errno.h) on failure.
 */
int lib4network_stack_isUidBlocked(uid_t uid);

__END_DECLS