# Lint as: python3
"""Connectivity multi devices tests."""
import sys
from mobly import base_test
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device
import tether_utils
from tether_utils import UpstreamType
import wifip2p_utils

CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE = "com.google.snippet.connectivity"


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

  def test_hotspot_upstream_wifi(self):
    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
          self.serverDevice, self.clientDevice, UpstreamType.WIFI
      )
    finally:
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.WIFI
      )

  def test_hotspot_upstream_cellular(self):
    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
          self.serverDevice, self.clientDevice, UpstreamType.CELLULAR
      )
    finally:
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.CELLULAR
      )

  def test_mdns_advertising_discovery_resolution_via_hotspot(self):
    server = self.serverDevice.connectivity_multi_devices_snippet
    client = self.clientDevice.connectivity_multi_devices_snippet

    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
        self.serverDevice, self.clientDevice, UpstreamType.WIFI
      )
      # Register a mDns service
      client.registerMDnsService()
      # Discover a mDns service and resolve retrieved mDns service
      server.discoverAndResolveMDnsService()
    finally:
      tether_utils.cleanup_tethering_for_upstream_type(
        self.serverDevice, UpstreamType.WIFI
      )
      # Unregister the mDns service
      client.unregisterMDnsService()
      # Stop discovery
      server.stopMDnsServiceDiscovery()

  def test_mdns_advertising_discovery_resolution_via_wifip2p(self):
    server = self.serverDevice.connectivity_multi_devices_snippet
    client = self.clientDevice.connectivity_multi_devices_snippet

    try:
      # Connectivity of the client verified by asserting the validated capability.
      wifip2p_utils.setup_wifi_p2p_server_and_client(
        self.serverDevice, self.clientDevice
      )
      # Register a mDns service
      client.registerMDnsService()
      # Discover a mDns service and resolve retrieved mDns service
      server.discoverAndResolveMDnsService()
    finally:
      wifip2p_utils.cleanup_wifi_p2p(
        self.serverDevice, UpstreamType.WIFI
      )
      # Unregister the mDns service
      client.unregisterMDnsService()
      # Stop discovery
      server.stopMDnsServiceDiscovery()

if __name__ == "__main__":
  # Take test args
  if "--" in sys.argv:
    index = sys.argv.index("--")
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]
  test_runner.main()
