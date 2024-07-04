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

from net_tests_utils.host.python import adb_utils, apf_test_base, apf_utils, assert_utils, tether_utils
from scapy.all import Ether, Raw
from scapy.contrib.ethercat import EtherCat

# Constants.
COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED = "DROPPED_ETHERTYPE_NOT_ALLOWED"
ETHER_BROADCAST = "FF:FF:FF:FF:FF:FF"


class ApfV4Test(apf_test_base.ApfTestBase):

  def test_apf_drop_ethercat(self):
    self.send_packet_and_expect_counter_increased(
        Ether(src=self.server_mac_address, dst=ETHER_BROADCAST) / EtherCat(),
        COUNTER_DROPPED_ETHERTYPE_NOT_ALLOWED,
    )
