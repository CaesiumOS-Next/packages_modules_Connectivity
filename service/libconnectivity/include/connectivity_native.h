/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef LIBCONNECTIVITY_CONNECTIVITY_NATIVE_H_
#define LIBCONNECTIVITY_CONNECTIVITY_NATIVE_H_

#include <sys/cdefs.h>

__BEGIN_DECLS

/**
 * Blocks a port from being assigned during bind(). The caller is responsible for updating
 * /proc/sys/net/ipv4/ip_local_port_range with the port being blocked so that calls to connect()
 * will not automatically assign one of the blocked ports.
 * Will return success even if port was already blocked.
 *
 * @param port Int corresponding to port number.
 */
int AConnectivityNative_BlockPortForBind(int port) __INTRODUCED_IN(__ANDROID_API_T__);

/**
 * Unblocks a port that has previously been blocked.
 * Will return success even if port was already unblocked.
 *
 * @param port Int corresponding to port number.
 */
int AConnectivityNative_UnblockPortForBind(int port) __INTRODUCED_IN(__ANDROID_API_T__);

/**
 * Unblocks all ports that have previously been blocked.
 */
int AConnectivityNative_UnblockAllPortsForBind() __INTRODUCED_IN(__ANDROID_API_T__);

/**
 * Gets the list of ports that have been blocked.
 *
 * @param ports Array of ports that will be filled with the port numbers.
 * @param count Pointer to the size of the ports array; the value will be set to the total number of
 *              blocked ports, which may be larger than the ports array that was filled.
 */
int AConnectivityNative_GetPortsBlockedForBind(int *ports, int *count)
    __INTRODUCED_IN(__ANDROID_API_T__);

__END_DECLS


#endif
