/**
 * Copyright (c) 2024, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <string>
#include <unordered_map>
#include <unordered_set>

#include "android-base/thread_annotations.h"
#include "bpf/BpfMap.h"
#include "bpf/BpfRingbuf.h"

// For SatelliteEvent, SatelliteConfigValue struct definition
#include "netd.h"

namespace android {
namespace bpf {

// SatelliteBufferEventPoller is responsible for interactions with the BPF ring
// buffer including polling and identifying the UID's exceeding the threshold
// limit to be blocked.
class SatelliteBufferEventPoller {
public:
  SatelliteBufferEventPoller();

  // Starts satellite event with the given interface.
  void Start(uint32_t *satelliteEventConfig, int writeFd);

  // Stops tracing and release any held state.
  void Stop();

  // Consumes all available events from the ringbuffer.
  bool ConsumeAll();

  // Starts satellite buffer event with given interface to ring buffer
  static void startSatelliteBufferEventMonitoring(uint32_t *config,
                                                  int writeFd);

  // Stops satellite buffer event to a ring buffer and release any held state.
  static void stopSatelliteBufferEvent();

  static SatelliteBufferEventPoller *currentRunningObject;

  static SatelliteBufferEventPoller *getInstance();

  static bool init();

private:
  // Poll based on polling count for satellite event at ring buffer .
  void PollAndSchedule();
  bool ConsumeAllLocked();
  bool initSatelliteBpfMap();

  // This method tracks the satellite uids from satellite event read from ring
  // buffer for max data usage to check if exceeded or not.
  void TraceUidsWithThresholdLimitExceeded(
      const std::vector<SatelliteEvent> &SatelliteBufferEvents);

  // This method updates the pipe with satellite uids to be blocked  by write
  // file descriptor.
  void UpdateSatelliteUidsTobeBlockedToPipe();

  // This method get the new uids to be updated to pipe
  int *geUidArrayToUpdatePipe();

  // max data usage limit allowed, received part of satellite event config from
  // jni
  int mMaxDataUsageAllowed;

  // polling interval to monitor the satellite buffer event from ring buffer
  int mPollingCount;

  // fd used to write satellite uid to the pipe, to be read at java code by read
  // fd
  int mWriteFd;

  // determines current polling status. To be reset to false when generating
  // satellite buffer event to a ring bufer is stopped on moving out of
  // satellite network
  bool isPolling;

  int mCurrSatelliteUidBlockingArraySize;
  int mUidArrayToPipeSize;

  // Array holding the uid items to be updated to pipe using write fd. Contains
  // only new uids added to session block list on polling.
  int *mCurrSatelliteUidBlockingArray = nullptr;

  // satellite uid blocking identified from satellite event from ring buffer
  std::vector<int> mSatelliteSessionUidBlockList{};
  // map holding the satellite uid stats read from each satellite event from
  // ring buffer
  std::unordered_map<int, int> mSatelliteEventMap;

  // The BPF ring buffer handle.
  std::unique_ptr<BpfRingbuf<SatelliteEvent>> mRingBuffer;

  // The satellite event config map (really a 1-element array).
  BpfMap<uint32_t, SatelliteConfigValue> mSatelliteConfigurationMap;
};

} // namespace bpf
} // namespace android
