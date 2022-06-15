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

package com.android.server.connectivity.mdns;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;

/** A wrapper class of {@link NetworkInterface} to be mocked in unit tests. */
public class NetworkInterfaceWrapper {
    private final NetworkInterface mNetworkInterface;

    public NetworkInterfaceWrapper(NetworkInterface networkInterface) {
        mNetworkInterface = networkInterface;
    }

    public NetworkInterface getNetworkInterface() {
        return mNetworkInterface;
    }

    public boolean isUp() throws SocketException {
        return mNetworkInterface.isUp();
    }

    public boolean isLoopback() throws SocketException {
        return mNetworkInterface.isLoopback();
    }

    public boolean isPointToPoint() throws SocketException {
        return mNetworkInterface.isPointToPoint();
    }

    public boolean isVirtual() {
        return mNetworkInterface.isVirtual();
    }

    public boolean supportsMulticast() throws SocketException {
        return mNetworkInterface.supportsMulticast();
    }

    public List<InterfaceAddress> getInterfaceAddresses() {
        return mNetworkInterface.getInterfaceAddresses();
    }

    @Override
    public String toString() {
        return mNetworkInterface.toString();
    }
}
