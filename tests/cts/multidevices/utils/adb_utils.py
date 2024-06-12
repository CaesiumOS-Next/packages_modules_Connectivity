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

import re
from mobly.controllers import android_device
from utils import assert_utils

BYTE_DECODE_UTF_8 = "utf-8"
MAX_RETRY_COUNT = 10


def set_doze_mode(ad: android_device.AndroidDevice, enable: bool) -> None:
  if enable:
    adb_shell(ad, "cmd battery unplug")
    expect_dumpsys_state_with_retry(ad, "deviceidle", "mCharging", False)
    set_screen_state(ad, False)
    adb_shell(ad, "dumpsys deviceidle enable deep")
    expect_dumpsys_state_with_retry(ad, "deviceidle", "mDeepEnabled", True)
    adb_shell(ad, "dumpsys deviceidle force-idle deep")
    expect_dumpsys_state_with_retry(ad, "deviceidle", "mForceIdle", True)
  else:
    adb_shell(ad, "cmd battery reset")
    expect_dumpsys_state_with_retry(ad, "deviceidle", "mCharging", True)
    adb_shell(ad, "dumpsys deviceidle unforce")
    expect_dumpsys_state_with_retry(ad, "deviceidle", "mForceIdle", False)


def set_screen_state(
    ad: android_device.AndroidDevice, target_state: bool
) -> None:
  assert_utils.expect_with_retry(
      lambda: get_screen_state(ad) == target_state,
      lambda: adb_shell(
          ad, "input keyevent KEYCODE_POWER"
      ),  # Toggle power key again when retry.
  )


def get_screen_state(ad: android_device.AndroidDevice) -> bool:
  return get_value_of_key_from_dumpsys(ad, "power", "mWakefulness") == "Awake"


def get_value_of_key_from_dumpsys(
    ad: android_device.AndroidDevice, service: str, key: str
) -> str:
  output = get_dumpsys_for_service(ad, service)
  # Search for key=value pattern from the dumpsys output.
  # e.g. mWakefulness=Awake
  pattern = r"{}=(.*)".format(key)
  # Only look for the first occurrence.
  match = re.search(pattern, output)
  if match:
    ad.log.debug(
        "Getting key-value from dumpsys: " + key + "=" + match.group(1)
    )
    return match.group(1)
  else:
    return None


def expect_dumpsys_state_with_retry(
    ad: android_device.AndroidDevice,
    service: str,
    key: str,
    expected_state: bool,
    retry_interval_sec: int = 1,
) -> None:
  assert_utils.expect_with_retry(
      predicate=lambda: (
          get_value_of_key_from_dumpsys(ad, service, key).lower() == "true"
      )
      == expected_state,
      retry_interval_sec=retry_interval_sec,
  )


def get_dumpsys_for_service(
    ad: android_device.AndroidDevice, service: str
) -> str:
  return adb_shell(ad, "dumpsys " + service)


def adb_shell(ad: android_device.AndroidDevice, shell_cmd: str) -> str:
  """Runs adb shell command.

  Args:
    ad: Android device object.
    shell_cmd: string of list of strings, adb shell command.

  Returns:
    string, replies from adb shell command.
  """
  ad.log.debug("Executing adb shell %s", shell_cmd)
  data = ad.adb.shell(shell_cmd)
  return data.decode(BYTE_DECODE_UTF_8).strip()
