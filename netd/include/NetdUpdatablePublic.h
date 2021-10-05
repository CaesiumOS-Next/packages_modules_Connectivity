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

#pragma once

#include <sys/types.h>

#define LIBNETD_UPDATABLE_PUBLIC extern "C" [[gnu::visibility("default")]]

/*
 * Initial function for libnetd_updatable library.
 *
 * Returns 0 on success, or a negative POSIX error code (see errno.h) on
 * failure.
 */
LIBNETD_UPDATABLE_PUBLIC int libnetd_updatable_init(const char* cg2_path);

/*
 * Set the socket tag and owning UID for traffic statistics on the specified socket.
 *
 * Returns 0 on success, or a negative POSIX error code (see errno.h) on failure.
 */
LIBNETD_UPDATABLE_PUBLIC int libnetd_updatable_privilegedTagSocket(int sockFd, uint32_t tag,
                                                                 uid_t uid, uid_t callingUid);

/*
 * Set the socket tag and owning UID for traffic statistics on the specified socket. The callingUid
 * is the effective user ID of the calling process, which will be used for permission check.
 *
 * Returns 0 on success, or a negative POSIX error code (see errno.h) on failure.
 */
LIBNETD_UPDATABLE_PUBLIC int libnetd_updatable_tagSocket(int sockFd, uint32_t tag, uid_t uid,
                                                       uid_t callingUid);

/*
 * Untag a network socket. Future traffic on this socket will no longer be associated with any
 * previously configured tag and uid.
 *
 * Returns 0 on success, or a negative POSIX error code (see errno.h) on failure.
 */
LIBNETD_UPDATABLE_PUBLIC int libnetd_updatable_unTagSocket(int sockFd);
