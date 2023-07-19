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
//! Remote connectivity manager interface that exposes API to start discovery,
//! initiate a connection.

pub use crate::messenger::Messenger;
pub use discovery_filter::DiscoveryFilter;
pub use discovered_device::DiscoveredDevice;
pub use crate::event_listener::EventListener;

/// Enum for reason code for not establishing connection.
pub enum ReasonCode {
    ReasonUnknown,
    ConnectionTimeout,
    ConnectionRefused,
    DeviceUnavailable,
}

trait RemoteConnectivityManager {
    fn startDiscovery(&self, discoveryFilter: DiscoveryFilter,
                    discoveredDevicesReceiver: DiscoveredDevicesReceiver);

    fn stopDiscovery(&self);

    fn connect(&self, eventListener: EventListener, discoveredDevice: DiscoveredDevice,
            pathPrefix: String) -> Messenger;
}

// TODO(salilr): Write MessengerConnectorException.