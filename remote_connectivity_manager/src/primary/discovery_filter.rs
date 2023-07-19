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

//! Struct that encapsulates discovery filter.
#![allow(dead_code)]

/// DiscoveryFilter struct used to discover peer devices.
#[derive(Default, Debug)]
pub struct DiscoveryFilter {
    device_name: String,
    // TODO(salilr): Import this Capabilites proto.
    // capabilities: Capabilities,
    address: String,
}

impl DiscoveryFilter {
    /// Getter for device name.
    pub fn get_device_name(&self) -> &String { &self.device_name }

    /// Getter for address
    pub fn get_address(&self) -> &String { &self.address }
}

/// Builder for DiscoveryFilter.
pub struct DiscoveryFilterBuilder {
    device_name: Option<String>,
    address: Option<String>,
    //capability:
}
impl DiscoveryFilterBuilder {
    /// Constructor for builder.
    pub fn new() -> DiscoveryFilterBuilder {
        DiscoveryFilterBuilder {
            device_name: None,
            address: None,
        }
    }

    /// Method to add device name to the builder.
    pub fn set_device_name(&mut self, name: String) -> &mut Self {
        self.device_name = Some(name);
        self
    }

    /// Method to device address to the builder.
    pub fn set_address(&mut self, address: String) -> &mut Self {
        self.address = Some(address);
        self
    }

    /// Method to build the DiscoveryFilter object.
    pub fn build(&mut self) -> DiscoveryFilter {
        DiscoveryFilter {
            device_name: self.device_name.clone().unwrap_or_default(),
            address: self.address.clone().unwrap_or_default(),
        }
    }
}

impl Default for DiscoveryFilterBuilder {
    fn default() -> Self {
        Self::new()
    }
}
    
    