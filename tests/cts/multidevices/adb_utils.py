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

from mobly.controllers import android_device
import re
import time

BYTE_DECODE_UTF_8 = "utf-8"
MAX_RETRY_COUNT = 10

class UnexpectedBehaviorError(Exception):
    """Raised when there is an unexpected behavior during applying a procedure."""

def set_screen_state(ad: android_device.AndroidDevice,
                     target_state: bool):
    for _ in range(MAX_RETRY_COUNT):
        screen_state = get_screen_state(ad)
        if screen_state == target_state:
            break

        # Toggle power key.
        adb_shell(ad, "input keyevent 26")
        time.sleep(1)

    screen_state = get_screen_state(ad)
    if screen_state != target_state:
        raise UnexpectedBehaviorError("Cannot change screen state to " + target_state)
def get_screen_state(ad: android_device.AndroidDevice):
    screen_state_str = get_value_of_key_from_dumpsys(ad, "power", "mWakefulness")
    match screen_state_str:
        case "Awake":
            return True
        case _:
            return False

def get_value_of_key_from_dumpsys(ad: android_device.AndroidDevice,
                            service: str,
                            key: str):
    output = get_dumpsys_for_service(ad, service)
    pattern = r'{}=(.*)'.format(key)  # Handles values within quotes
    match = re.search(pattern, output)
    if match:
        return match.group(1)
    else:
        return None

def get_dumpsys_for_service(ad: android_device.AndroidDevice, service: str):
    return adb_shell(ad, "dumpsys " + service)

def adb_shell(ad: android_device.AndroidDevice,
              shell_cmd: str
              ) -> str:
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