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

struct DiscoveryFilter {
    deviceName: String,
    // TODO(salilr): Import this Capabilites proto.
    // capabilities: Capabilities,
    address: String,
}

impl DiscoveryFilter {
    pub fn new(deviceName: String, address: String) -> Self {
        DiscoveryFilter {deviceName, address}
    }
}

/// Builder for DiscoveryFilter.
pub struct DiscoveryFilterBuilder {
    deviceName: Option<String>,
    address: Option<String>,
}
impl DiscoveryFilterBuilder {
    fn deviceName(&mut self, name: String) -> &mut Self {
        self.deviceName = Some(name);
        self
    }

    fn address(&mut self, address: String) -> &mut Self {
        self.address = Some(address);
        self
    }

    fn build(&mut self) -> DiscoveryFilter {
        DiscoveryFilter {
            deviceName: self.deviceName.clone(),
            address: self.address.clone(),
        }
    }
}