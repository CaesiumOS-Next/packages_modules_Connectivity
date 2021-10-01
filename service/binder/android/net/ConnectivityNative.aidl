/**
 * Copyright (c) 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

interface ConnectivityNative {
    /**
     * Blocks a port from being assigned during bind()
     *
     * @param port Int corresponding to port number.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
    void blockPort(in int port);

    /**
     * Unblocks a port that has previously been blocked.
     *
     * @param port Int corresponding to port number.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
    void unblockPort(in int port);

    /**
     * Unblocks all ports that have previously been blocked.
     */
    void unblockAllPorts();

    /**
     * Gets the list of ports that have been blocked.
     *
     * @return List of blocked ports.
     */
    int[] getBlockedPorts();
}