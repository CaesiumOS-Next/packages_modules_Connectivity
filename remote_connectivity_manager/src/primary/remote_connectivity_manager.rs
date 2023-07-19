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

pub mod remote_connectivity_manager;

pub enum ReasonCode {
    REASON_UNKNOWN,
    CONNECTION_TIMEOUT,
    CONNECTION_REFUSED,
    DEVICE_UNAVAILABLE,
}

trait RemoteConnectivityManager {
    fn startDiscovery(&self, discoveryFilter: DiscoveryFilter,
                      discoveredDevicesReceiver: DiscoveredDevicesReceiver);

    fn stopDiscovery(&self);

    fn connect(&self, eventListener: EventListener, discoveredDevice: DiscoveredDevice,
               pathPrefix: String) -> Messenger;
}


// TODO(salilr): Write MessengerConnectorException.