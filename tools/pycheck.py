#!/usr/bin/env python3
#
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

import subprocess
import sys


def _enforce_pyformat(files: list[str]) -> None:
  """Runs pyformat on the given files if it's available."""

  try:
    # Check if pyformat is installed
    subprocess.run(["which", "pyformat"], check=True, stdout=subprocess.DEVNULL)

    # Run pyformat and check output.
    modified = subprocess.run(["pyformat"] + files, capture_output=True, check=True).stdout

    if modified:
      print("Format error: run pyformat -i " + " ".join(preupload_files))
      sys.exit(1)

  except subprocess.CalledProcessError:
    print(
        "Error: pyformat not found or failed to run. Please install pyformat."
    )


def _get_preupload_files() -> list[str]:
  """Gets file names to process from command-line arguments."""

  # Exclude the script name itself from the list
  return sys.argv[1:]


if __name__ == "__main__":
  preupload_files = _get_preupload_files()
  # pyformat skips all non .py files automatically.
  _enforce_pyformat(preupload_files)
