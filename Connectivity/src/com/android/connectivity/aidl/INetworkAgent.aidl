/**
 * Copyright (c) 2020, The Android Open Source Project
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
 * See the License for the specific language governing perNmissions and
 * limitations under the License.
 */
package com.android.connectivity.aidl;

import android.net.NattKeepalivePacketData;
import android.net.TcpKeepalivePacketData;

/** @hide */
oneway interface INetworkAgent {
    void onBandwidthUpdateRequested();
    void onValidationStatusChanged(int validationStatus,
            in @nullable String captivePortalUrl);
    void onSaveAcceptUnvalidated(boolean acceptUnvalidated);
    void onStartNattSocketKeepalive(int slot, long intervalDurationMs,
        in @nullable NattKeepalivePacketData packetData);
    void onStartTcpSocketKeepalive(int slot, long intervalDurationMs,
        in @nullable TcpKeepalivePacketData packetData);
    void onStopSocketKeepalive(int slot);
    void onSignalStrengthThresholdsUpdated(in int[] thresholds);
    void onPreventAutomaticReconnect();
    void onAddNattKeepalivePacketFilter(int slot,
        in NattKeepalivePacketData packetData);
    void onAddTcpKeepalivePacketFilter(int slot,
        in TcpKeepalivePacketData packetData);
    void onRemoveKeepalivePacketFilter(int slot);
}
