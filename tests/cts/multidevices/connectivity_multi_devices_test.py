# Lint as: python3
"""Connectivity multi devices tests."""
import random
import sys

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device

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

  def test_hotspot(self):
    """Create a hotspot, make the client connect to it and verify the packet is

    forwarded by the hotspot.
    """
    # Append ssid/passphrase with 4-digit random number to make sure nearby devices won't
    # connect unexpectedly.
    testSsid = "TEST_HOTSPOT_SSID_" + str(random.randint(0, 9999)).zfill(4)
    testPassphrase = "TEST_HOTSPOT_PASSPHRASE_" + str(
        random.randint(0, 9999)
    ).zfill(4)
    server = self.serverDevice.connectivity_multi_devices_snippet
    client = self.clientDevice.connectivity_multi_devices_snippet

    # Assert pre-conditions.
    asserts.skip_if(not client.hasWifiFeature(), "Client requires Wifi feature")
    asserts.skip_if(
        not server.hasTelephonyFeature(), "Server requires Telephony feature"
    )
    asserts.skip_if(
        not server.hasHotspotFeature(), "Server requires hotspot feature"
    )

    # Use cellular as upstream to exercise mobile data.
    server.ensureCellularIsDefault()

    try:
      # Create a hotspot with fixed SSID and password.
      server.startHotspot(testSsid, testPassphrase)

      # Make the client connects to the hotspot.
      client.connectToWifi(testSsid, testPassphrase, True)

    finally:
      # Teardown the hotspot.
      server.stopAllTethering()
      server.ensureWifiConnected()


if __name__ == "__main__":
  # Take test args
  if "--" in sys.argv:
    index = sys.argv.index("--")
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]
  test_runner.main()
