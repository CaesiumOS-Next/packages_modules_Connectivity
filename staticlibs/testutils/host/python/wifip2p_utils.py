#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

from mobly import asserts
from mobly.controllers import android_device


def assume_wifi_p2p_test_preconditions(
    server_device: android_device, client_device: android_device
) -> None:
  server = server_device.connectivity_multi_devices_snippet
  client = client_device.connectivity_multi_devices_snippet

  # Assert pre-conditions
  asserts.skip_if(not server.hasWifiFeature(), "Server requires Wifi feature")
  asserts.skip_if(not client.hasWifiFeature(), "Client requires Wifi feature")
  asserts.skip_if(
      not server.isP2pSupported(), "Server requires Wi-fi P2P feature"
  )
  asserts.skip_if(
      not client.isP2pSupported(), "Client requires Wi-fi P2P feature"
  )


def setup_wifi_p2p_server_and_client(
    server_device: android_device, client_device: android_device
) -> None:
  """Set up the Wi-Fi P2P server and client, then connect them to establish a Wi-Fi P2P connection."""
  server = server_device.connectivity_multi_devices_snippet
  client = client_device.connectivity_multi_devices_snippet

  # Start Wi-Fi P2P on both server and client.
  server.startWifiP2p()
  client.startWifiP2p()

  # Get the current device name
  serverName = server.getDeviceName()
  clientName = client.getDeviceName()

  # Server creates a Wi-Fi P2P group
  server.createGroup()

  # Discover Wi-Fi P2p peers on both devices
  server.discoverPeers()
  client.discoverPeers()

  # Ensure the target device has been discovered
  client.ensureDeviceDiscovered(serverName)
  server.ensureDeviceDiscovered(clientName)

  # Sever invites the device to the group
  server.inviteDeviceToGroup()

  # Wait for a p2p connection changed intent to ensure the invitation has been received
  client.waitForP2pConnectionChanged(True)
  # Accept the group invitation
  client.acceptGroupInvitation()

  # Sever waits for connection request from client and accept joining
  server.waitForPeerConnectionRequestAndAcceptJoining()

  # Wait for a p2p connection changed intent to ensure joining the group
  client.waitForP2pConnectionChanged(False)

  # Ensure Wi-Fi P2P connected on both devices
  client.ensureDeviceConnected(serverName)
  server.ensureDeviceConnected(clientName)


def cleanup_wifi_p2p(
    server_device: android_device, client_device: android_device
) -> None:
  # Stop Wi-Fi P2P
  server_device.connectivity_multi_devices_snippet.stopWifiP2p()
  client_device.connectivity_multi_devices_snippet.stopWifiP2p()
