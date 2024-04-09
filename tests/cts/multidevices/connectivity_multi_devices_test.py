# Lint as: python3
"""Connectivity multi devices tests."""
import base64
import time
import sys
import uuid

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device
import adb_utils

CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE = "com.google.snippet.connectivity"
COUNTER_DROPPED_ETHERTYPE_DENYLISTED_NAME = "DROPPED_ETHERTYPE_DENYLISTED"
APF_COUNTER_POLLING_MAX_RETRY_COUNT = 10

class UpstreamType:
  NONE = 0
  CELLULAR = 1
  WIFI = 2


class UnexpectedBehaviorException(Exception):
  pass

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

  def _setup_hotspot_and_client_for_upstream_type(self, upstream_type):
    """Setup the hotspot with a connected client with the specified upstream type.

    This creates a hotspot, make the client connect
    to it, and verify the packet is forwarded by the hotspot.
    And returns interface name of both if successful.
    """
    server = self.serverDevice.connectivity_multi_devices_snippet
    client = self.clientDevice.connectivity_multi_devices_snippet
    self.server = server
    self.client = client

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
    elif upstream_type == UpstreamType.NONE:
      pass # No-op.
    else:
      raise ValueError(f"Invalid upstream type: {upstream_type}")

    # Generate ssid/passphrase with random characters to make sure nearby devices won't
    # connect unexpectedly. Note that total length of ssid cannot go over 32.
    testSsid = "HOTSPOT-" + self.generate_uuid32_base64()
    testPassphrase = self.generate_uuid32_base64()

    # Create a hotspot with fixed SSID and password.
    hotspotInterface = server.startHotspot(testSsid, testPassphrase)

    # Make the client connects to the hotspot.
    clientNetwork = client.connectToWifi(testSsid, testPassphrase, upstream_type != UpstreamType.NONE)
    clientInterface = client.getInterfaceForNetwork(clientNetwork)

    return hotspotInterface, clientInterface


  def _cleanup_tethering_for_upstream_type(self, upstream_type):
    if upstream_type == UpstreamType.CELLULAR:
      self.server.unrequestCellular()
    # Teardown the hotspot.
    self.server.stopAllTethering()

  def test_hotspot_upstream_wifi(self):
    try:
      # Connectivity of the client verified by asserting the validated capability.
      self._setup_hotspot_and_client_for_upstream_type(UpstreamType.WIFI)
    finally:
      self._cleanup_tethering_for_upstream_type(UpstreamType.WIFI)

  def test_hotspot_upstream_cellular(self):
    try:
      # Connectivity of the client verified by asserting the validated capability.
      self._setup_hotspot_and_client_for_upstream_type(UpstreamType.CELLULAR)
    finally:
      self._cleanup_tethering_for_upstream_type(UpstreamType.CELLULAR)

  def test_apf_drop_ethercat(self):
    try:
      server_iface, client_iface = self._setup_hotspot_and_client_for_upstream_type(UpstreamType.NONE)
      # FIXME: Use device idle mode instead.
      adb_utils.set_screen_state(self.clientDevice, True)
      adb_utils.set_screen_state(self.clientDevice, False)
      count_before_test = self.client.getApfCounter(client_iface, COUNTER_DROPPED_ETHERTYPE_DENYLISTED_NAME)
      self.server.sendBroadcastEmptyEtherCatPacket(server_iface)
      for _ in range(0, APF_COUNTER_POLLING_MAX_RETRY_COUNT):
        count_after_test = self.client.getApfCounter(client_iface, COUNTER_DROPPED_ETHERTYPE_DENYLISTED_NAME)
        if count_after_test > count_before_test:
          return
        time.sleep(1)
      raise UnexpectedBehaviorException("Counter " + COUNTER_DROPPED_ETHERTYPE_DENYLISTED_NAME
                                        + " did not increase: " + count_after_test)
    finally:
      self._cleanup_tethering_for_upstream_type(UpstreamType.NONE)

if __name__ == "__main__":
  # Take test args
  if "--" in sys.argv:
    index = sys.argv.index("--")
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]
  test_runner.main()
