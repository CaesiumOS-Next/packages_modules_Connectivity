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

//! Crate that encapsulates all connection related information.
#![allow(dead_code)]

// mod connection {
// extern crate derive_builder;

/// Enum describing connection types.
#[derive(Clone, Default, Debug)]
pub enum ConnectionType {
    #[default]
    /// Default variant.
    Unknown,
    /// Connection type Bluetooth.
    Bluetooth,
    /// Connection type Bluetooth LE.
    BluetoothLe,
}

/// Struct encapsulating the connection information.
#[derive(Clone, Default, Debug)]
pub struct ConnectionInfo {
    connection_id: u8,
    peer_address: String,
    connection_type: ConnectionType,
}
impl ConnectionInfo {
    /// Getter for connection id.
    pub fn get_connection_id(&self) -> u8 { self.connection_id }
    /// Getter for connection type.
    pub fn get_connection_type(&self) -> &ConnectionType { &self.connection_type }
    /// Getter for peer address.
    pub fn get_peer_address(&self) -> &String { &self.peer_address }
}

/// ConnectionInfo object builder.
pub struct ConnectionInfoBuilder {
    _connection_id: Option<u8>,
    _peer_address: Option<String>,
    _connection_type: Option<ConnectionType>,
}
impl ConnectionInfoBuilder {
    /// Constructor for builder.
    pub fn new() -> ConnectionInfoBuilder {
        ConnectionInfoBuilder {
            _connection_id: None,
            _peer_address: None,
            _connection_type: None,
        }
    }

    /// Method to set connection id.
    pub fn set_connection_id(&mut self, connection_id: u8) -> &mut Self {
        self._connection_id = Some(connection_id);
        self
    }

    /// Method to set peer address.
    pub fn set_peer_address(&mut self, peer_address: String) -> &mut Self {
        self._peer_address = Some(peer_address);
        self
    }

    /// Method to set connection type.
    pub fn set_connection_type(&mut self, connection_type: ConnectionType) -> &mut Self {
        self._connection_type = Some(connection_type);
        self
    }

    /// Method to return ConnectionInfo object.
    pub fn build(&mut self) -> ConnectionInfo {
        ConnectionInfo {
            connection_id: self._connection_id.unwrap_or_default(),
            peer_address: self._peer_address.clone().unwrap_or_default(),
            connection_type: self._connection_type.clone().unwrap_or_default(),
        }
    }
}

/// Default implementation for builder.
impl Default for ConnectionInfoBuilder {
    fn default() -> Self {
        Self::new()
    }    
}
// }