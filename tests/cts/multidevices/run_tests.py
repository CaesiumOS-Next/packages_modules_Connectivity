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

"""Main entrypoint for all of test cases."""

import sys
from apfv4_test import ApfV4Test
from apfv6_test import ApfV6Test
from connectivity_multi_devices_test import ConnectivityMultiDevicesTest
from mobly import suite_runner


if __name__ == "__main__":
  # TODO: make the tests can be executed without manually list classes.
  suite_runner.run_suite([ConnectivityMultiDevicesTest, ApfV4Test, ApfV6Test], sys.argv)
