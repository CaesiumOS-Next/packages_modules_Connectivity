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

// For SatelliteEvent, SatelliteConfigValue and SatelliteCacheValue struct definition
#include "netd.h"


namespace android {
namespace bpf {

// SatelliteBufferEventPoller is responsible for interactions with the BPF ring buffer
// including polling and identifying the UID's exceeding the threshold limit to be blocked.
class SatelliteBufferEventPoller {
 public:
  SatelliteBufferEventPoller() ;

  // Starts satellite event with the given interface.
  bool Start(JNIEnv* env, jobject callback, uint32_t* satelliteEventConfig) EXCLUDES(mMutex);

  // Stops tracing and release any held state.
  bool Stop() EXCLUDES(mMutex);

  // Consumes all available events from the ringbuffer.
  bool ConsumeAll() EXCLUDES(mMutex);

  // Starts satellite buffer event with given interface to ring buffer
  static void startSatelliteBufferEvent(JNIEnv* env, jobject callback, uint32_t* config);

  // Stops satellite buffer event to a ring buffer and release any held state.
  static void stopSatelliteBufferEvent();

  static SatelliteBufferEventPoller* currentRunningObject;

  static SatelliteBufferEventPoller* getInstance();

 private:
  void PollAndSchedule();
  bool ConsumeAllLocked();

  void TraceUidsWithThresholdLimitExceeded(const std::vector<SatelliteEvent>& SatelliteBufferEvents);
  void UpdateSatelliteUidsTobeBlocked();

  std::mutex mMutex;

  // Records the number of successfully started active sessions so that only the
  // first active session attempts setup and only the last cleans up. Note that
  // the session count will remain zero if Start fails. It is expected that Stop
  // will not be called for any trace session where Start fails.
  int mSessionCount;
  int mMaxDataUsageAllowed;
  int mCurrentSatelliteUidsBlockListSize;
  bool isPolling;

  JNIEnv* g_env = nullptr;
  jobject g_globalCallback = nullptr;

  std::vector<int> mSatelliteUidsBlockListCache{};
  std::unordered_map<int, int> mSatelliteEventMap;

  // The BPF ring buffer handle.
  std::unique_ptr<BpfRingbuf<SatelliteEvent>> mRingBuffer;

  // The satellite event config map (really a 1-element array).
  BpfMap<uint32_t, SatelliteConfigValue> mSatelliteConfigurationMap;

  // The satellite tracing config map (really a 1-element array).
  BpfMap<uint32_t, SatelliteCacheValue> mSatelliteCacheMap;
};

}  // namespace bpf
}  // namespace android