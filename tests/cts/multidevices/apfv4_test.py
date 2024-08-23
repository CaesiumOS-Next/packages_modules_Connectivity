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

from absl.testing import parameterized
from net_tests_utils.host.python import apf_test_base

# Constants.
COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED = "DROPPED_ETHERTYPE_NOT_ALLOWED"
ETHER_BROADCAST_ADDR = "FFFFFFFFFFFF"


class ApfV4Test(apf_test_base.ApfTestBase, parameterized.TestCase):

  # APF L2 packets filtering behavior varies across Android versions:
  #  - Android V+: Allowlist - only specified packet types pass.
  #  - Android S-U: Denylist overlay at R.array.config_apfEthTypeDenyList.
  #  - Android R-: Denylist from ApfCapabilities.getApfDrop8023Frames().
  #
  # The tests use ethertypes from the default denylist overlay for backward
  # compatibility.
  @parameterized.parameters(
      "88a2",  # ATA over Ethernet
      "88a4",  # EtherCAT
      "88b8",  # GOOSE (Generic Object Oriented Substation event)
      "88cd",  # SERCOS III
      "88e3",  # Media Redundancy Protocol (IEC62439-2)
  )  # Declare inputs for state_str and expected_result.
  def test_apf_drop_ethertype_not_allowed(self, blocked_ether_type):
    # Ethernet header (14 bytes).
    packet = ETHER_BROADCAST_ADDR  # Destination MAC (broadcast)
    packet += self.server_mac_address.replace(":", "")  # Source MAC
    packet += blocked_ether_type

    #  Zero paddings which just enough to send these empty packets.
    packet += "00" * 46
    self.send_packet_and_expect_counter_increased(
        packet, COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED
    )
