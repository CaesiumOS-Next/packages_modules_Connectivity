# Lint as: python3
"""Connectivity multi devices tests."""

import sys
import time

import adb_utils
from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device
import tether_utils
import tether_utils
from tether_utils import UpstreamType

CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE = "com.google.snippet.connectivity"
COUNTER_DROPPED_ETHERTYPE_DENYLISTED_NAME = "DROPPED_ETHERTYPE_DENYLISTED"
APF_COUNTER_POLLING_MAX_RETRY_COUNT = 10


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

  def test_apf_drop_ethercat(self):
    client = self.clientDevice.connectivity_multi_devices_snippet
    server = self.serverDevice.connectivity_multi_devices_snippet
    try:
      server_iface, client_network = (
          tether_utils.setup_hotspot_and_client_for_upstream_type(
              self.serverDevice, self.clientDevice, UpstreamType.NONE
          )
      )
      client_iface = client.getInterfaceNameFromNetworkHandle(client_network)
      asserts.skip_if(
          not server.isGetRawSocketFromNetworkStackSupported(),
          "Server requires newer NetworkStack module to support get-raw-socket command",
      )

      # FIXME: Use device idle mode instead.
      adb_utils.set_screen_state(self.clientDevice, True)
      adb_utils.set_screen_state(self.clientDevice, False)
      time.sleep(3)

      count_before_test = adb_utils.get_apf_counter(
          self.clientDevice,
          client_iface,
          COUNTER_DROPPED_ETHERTYPE_DENYLISTED_NAME,
      )
      server.sendBroadcastEmptyEtherCatPacket(server_iface)
      # FIXME: Generalize retry.
      for _ in range(0, APF_COUNTER_POLLING_MAX_RETRY_COUNT):
        count_after_test = adb_utils.get_apf_counter(
            self.clientDevice,
            client_iface,
            COUNTER_DROPPED_ETHERTYPE_DENYLISTED_NAME,
        )
        if count_after_test > count_before_test:
          return
        time.sleep(1)
      raise UnexpectedBehaviorException(
          "Counter "
          + COUNTER_DROPPED_ETHERTYPE_DENYLISTED_NAME
          + " did not increase: "
          + count_after_test
      )
    finally:
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.NONE
      )


if __name__ == "__main__":
  # Take test args
  if "--" in sys.argv:
    index = sys.argv.index("--")
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]
  test_runner.main()
