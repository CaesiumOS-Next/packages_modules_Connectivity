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
//! Discovered device interface.
#![allow(dead_code)]

pub use connection_info::ConnectionInfo;
/// Struct to encapsulate a discovered device.

/// Struct encapsulating the discovered device.
#[derive(Debug)]
pub struct DiscoveredDevice {
    display_name: String,
    connection_info: ConnectionInfo,
}

/// Implementation of DiscoveredDevice struct.
impl DiscoveredDevice {
    /// Getter for display name.
    pub fn get_display_name(&self) -> &String { &self.display_name }
    /// Getter for connection
    pub fn get_connection_info(&self) -> &ConnectionInfo { &self.connection_info }
}

/// Builder for DiscoveredDevice.
/// TODO(salilr): Use derive_builder crate instead of implementing manually.
pub struct DiscoveredDeviceBuilder {
    _display_name: Option<String>,
    _connection_info: Option<ConnectionInfo>,
}

/// Implementation of DiscoveredDeviceBuilder.
impl DiscoveredDeviceBuilder {
    /// Constructor for builder.
    pub fn new() -> DiscoveredDeviceBuilder {
        DiscoveredDeviceBuilder {
            _display_name: None,
            _connection_info: None,
        }
    }
    /// Setter for display name.
    pub fn set_display_name(&mut self, name: String) -> &mut Self {
        self._display_name = Some(name);
        self
    }
    /// Setter for connection info.
    pub fn set_connection_info(&mut self, connection_info: ConnectionInfo) -> &mut Self {
        self._connection_info = Some(connection_info);
        self
    }
    /// Method to build DiscoveredDevice object.
    pub fn build(&mut self) -> DiscoveredDevice {
        DiscoveredDevice {
            display_name: self._display_name.clone().unwrap_or_default(),
            connection_info: self._connection_info.clone().unwrap_or_default(),
        }
    }
}

/// Default implementation of DiscoveredDeviceBuilder.
impl Default for DiscoveredDeviceBuilder {
    fn default() -> Self {
        Self::new()
    }
}

/// Struct for discovery callback.
pub struct DiscoveredDevicesReceiver<'a> {
    on_discovered: &'a dyn Fn(DiscoveredDevice),
    on_lost: &'a dyn Fn(DiscoveredDevice),
}

