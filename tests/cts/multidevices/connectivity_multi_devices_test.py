# Lint as: python3
"""Connectivity multi devices tests."""
import base64
import sys
import uuid

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device

CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE = "com.google.snippet.connectivity"


class UpstreamType:
  CELLULAR = 1
  WIFI = 2


class ConnectivityMultiDevicesTest(base_test.BaseTestClass):

  def setup_class(self):
    # Declare that two Android devices are needed.
    self.clientDevice, self.serverDevice = self.register_controller(
        android_device, min_number=2
    )

    def setup_device(device):
      device.load_snippet(
          "connectivity_multi_devices_snippet",
          CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE,
      )

    # Set up devices in parallel to save time.
    utils.concurrent_exec(
        setup_device,
        ((self.clientDevice,), (self.serverDevice,)),
        max_workers=2,
        raise_on_exception=True,
    )

  @staticmethod
  def generate_uuid32_base64():
    """Generates a UUID32 and encodes it in Base64.

    Returns:
        str: The Base64-encoded UUID32 string. Which is 22 characters.
    """
    return base64.b64encode(uuid.uuid1().bytes).decode("utf-8").strip("=")

  def _do_test_hotspot_for_upstream_type(self, upstream_type):
    """Test hotspot with the specified upstream type.

    This test create a hotspot, make the client connect
    to it, and verify the packet is forwarded by the hotspot.
    """
    server = self.serverDevice.connectivity_multi_devices_snippet
    client = self.clientDevice.connectivity_multi_devices_snippet

    # Assert pre-conditions specific to each upstream type.
    asserts.skip_if(not client.hasWifiFeature(), "Client requires Wifi feature")
    asserts.skip_if(
      not server.hasHotspotFeature(), "Server requires hotspot feature"
    )
    if upstream_type == UpstreamType.CELLULAR:
      asserts.skip_if(
          not server.hasTelephonyFeature(), "Server requires Telephony feature"
      )
      server.requestCellularAndEnsureDefault()
    elif upstream_type == UpstreamType.WIFI:
      asserts.skip_if(
          not server.isStaApConcurrencySupported(),
          "Server requires Wifi AP + STA concurrency",
      )
      server.ensureWifiIsDefault()
    else:
      raise ValueError(f"Invalid upstream type: {upstream_type}")

    # Generate ssid/passphrase with random characters to make sure nearby devices won't
    # connect unexpectedly. Note that total length of ssid cannot go over 32.
    testSsid = "HOTSPOT-" + self.generate_uuid32_base64()
    testPassphrase = self.generate_uuid32_base64()

    try:
      # Create a hotspot with fixed SSID and password.
      server.startHotspot(testSsid, testPassphrase)

      # Make the client connects to the hotspot.
      client.connectToWifi(testSsid, testPassphrase, True)

    finally:
      if upstream_type == UpstreamType.CELLULAR:
        server.unrequestCellular()
      # Teardown the hotspot.
      server.stopAllTethering()

  def _do_test_mdns_advertising_discovery_resolution_via_hotspot(self):
    """Test mdns advertising, discovery and resolution via hotspot.

    This test creates a hotspot, make the client connect to it, and verifies mDns advertising,
    discovery, and resolution functionality.
    """

    server = self.serverDevice.connectivity_multi_devices_snippet
    client = self.clientDevice.connectivity_multi_devices_snippet

    # Assert pre-conditions specific to each upstream type.
    asserts.skip_if(not client.hasWifiFeature(), "Client requires Wifi feature")
    asserts.skip_if(
        not server.hasHotspotFeature(), "Server requires hotspot feature"
    )
    asserts.skip_if(
        not server.isStaApConcurrencySupported(),
      "Server requires Wifi AP + STA concurrency",
    )
    server.ensureWifiIsDefault()

    # Generate ssid/passphrase with random characters to make sure nearby devices won't
    # connect unexpectedly. Note that total length of ssid cannot go over 32.
    testSsid = "HOTSPOT-" + self.generate_uuid32_base64()
    testPassphrase = self.generate_uuid32_base64()

    try:
        # Create a hotspot with fixed SSID and password.
      server.startHotspot(testSsid, testPassphrase)

      # Make the client connects to the hotspot.
      client.connectToWifi(testSsid, testPassphrase, True)

      # Register a mDns service
      client.registerMDnsService()

      # Discover a mDns service and resolve retrieved mDns service
      server.discoverAndResolveMDnsService()

    finally:
      # Teardown the hotspot.
      server.stopAllTethering()
      # Unregister the mDns service
      client.unregisterMDnsService()
      # Stop discovery
      server.stopMDnsServiceDiscovery()

  def _do_test_mdns_advertising_discovery_resolution_via_wifip2p(self):
    """Test mdns advertising, discovery and resolution via wifip2p.

    This test creates a wifp2p, make the client connect to it, and verifies mDns advertising,
    discovery, and resolution functionality.
    """
    server = self.serverDevice.connectivity_multi_devices_snippet
    client = self.clientDevice.connectivity_multi_devices_snippet

    # Assert pre-conditions specific to each upstream type.
    asserts.skip_if(not server.hasWifiFeature(), "Server requires Wifi feature")
    asserts.skip_if(not client.hasWifiFeature(), "Client requires Wifi feature")
    asserts.skip_if(not server.isP2pSupported(), "Server requires Wi-fi Direct feature")
    asserts.skip_if(not client.isP2pSupported(), "Client requires Wi-fi Direct feature")

    try:
      # Start Wi-Fi P2P on both server and client.
      serverName = server.startWifiP2p()
      clientName = client.startWifiP2p()

      # Discover Wi-Fi P2p peers
      server.discoverWifiP2pPeers(clientName)
      client.discoverWifiP2pPeers(serverName)

      # Create a Wi-Fi P2P group
      server.creteWifiP2pGroup()
      server.discoverWifiP2pPeers(clientName)

      # Connect to a Wi-Fi P2P device
      server.connectToWifiP2pDevice(clientName)

      client.acceptGroupInvitation()
      server.acceptGroupInvitation()

      client.ensureDeviceConnected()
      server.ensureDeviceConnected()

      # Register a mDns service
      client.registerMDnsService()

      # Discover a mDns service and resolve retrieved mDns service
      server.discoverAndResloveMDnsService()

    finally:
      # Unregister the mDns service
      client.unregisterMDnsService()
      # Stop discovery
      server.stopMDnsServiceDiscovery()
      # Stop Wi-Fi P2P
      server.stopWifiP2p()
      client.stopWifiP2p()

  def test_hotspot_upstream_wifi(self):
    self._do_test_hotspot_for_upstream_type(UpstreamType.WIFI)

  def test_hotspot_upstream_cellular(self):
    self._do_test_hotspot_for_upstream_type(UpstreamType.CELLULAR)

  def test_mdns_advertising_discovery_resolution_via_hotspot(self):
    self._do_test_mdns_advertising_discovery_resolution_via_hotspot()

  def test_mdns_advertising_discovery_resolution_via_wifip2p(self):
    self._do_test_mdns_advertising_discovery_resolution_via_wifip2p()

if __name__ == "__main__":
  # Take test args
  if "--" in sys.argv:
    index = sys.argv.index("--")
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]
  test_runner.main()
