/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "SatelliteEvent"

#define ATRACE_TAG ATRACE_TAG_NETWORK

#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include <bpf/BpfUtils.h>
#include <cutils/trace.h>
#include <log/log.h>
#include <string.h>
#include <thread>

#include "libsatellitenetworkstats/SatelliteBufferEventPoller.h"

namespace android {
namespace bpf {

SatelliteBufferEventPoller::SatelliteBufferEventPoller() {
  isPolling = false;
  mMaxDataUsageAllowed = 0;
  mUidArrayToPipeSize = 0;
  mCurrSatelliteUidBlockingArraySize = 0;
  mCurrSatelliteUidBlockingArray = nullptr;
}

SatelliteBufferEventPoller *SatelliteBufferEventPoller::currentRunningObject =
    nullptr; // Initialize the static member
SatelliteBufferEventPoller *SatelliteBufferEventPoller::getInstance() {
  if (currentRunningObject == nullptr) {
    ALOGW("current object is null");
    currentRunningObject = new SatelliteBufferEventPoller();
  }
  return currentRunningObject;
}

bool SatelliteBufferEventPoller::initSatelliteBpfMap() {
  auto cfgStatus = mSatelliteConfigurationMap.init(SATELLITE_CONFIG_MAP_PATH);
  if (!cfgStatus.ok()) {
    ALOGW("Failed to bind satellite config map: %s",
          cfgStatus.error().message().c_str());
    return false;
  }

  auto rb = BpfRingbuf<SatelliteEvent>::Create(SATELLITE_EVENT_RINGBUF_PATH);
  if (!rb.ok()) {
    ALOGW("Failed to create ringbuf: %s", rb.error().message().c_str());
    return false;
  }

  mRingBuffer = std::move(*rb);
  return true;
}

bool SatelliteBufferEventPoller::init() {
  return getInstance()->initSatelliteBpfMap();
}

void SatelliteBufferEventPoller::PollAndSchedule() {
  while (true) {

    int i = 0;
    // Polling count determines the count with each count adding a interval of 1
    // sec as wait time
    for (i = 0; i < mPollingCount; i++) {
      if (!isPolling)
        return;
      else {
        // Broken to smaller timer to help exit the current running jni code
        // thread on stopping the satellite buffer event to ring buffer with
        // satellite network lost or on moving to new network ( wifi or cellular
        // from satellite)
        std::this_thread::sleep_for(std::chrono::seconds(1));
      }
    }
    ConsumeAllLocked();
  }
}

void SatelliteBufferEventPoller::Start(uint32_t *satelliteConfig, int writeFd) {
  if (writeFd != -1) {
    if (mRingBuffer == nullptr) {
      ALOGD("Ringbuffer was null, re-initialise");

      auto rb =
          BpfRingbuf<SatelliteEvent>::Create(SATELLITE_EVENT_RINGBUF_PATH);
      if (!rb.ok()) {
        ALOGW("Failed to create ringbuf: %s", rb.error().message().c_str());
        return;
      }
      mRingBuffer = std::move(*rb);
    }

    if (mRingBuffer != nullptr) {
      // Satellite event config to enable generating satellite buffer event to a
      // ring buffer
      SatelliteConfigValue config{
          .time_threshold_ns = satelliteConfig[2],
          .ifindex6 = satelliteConfig[3],
          .ifindex4 = satelliteConfig[4],
          .rx_threshold_bytes = static_cast<uint16_t>(satelliteConfig[5]),
          .tx_threshold_bytes = static_cast<uint16_t>(satelliteConfig[6]),
          .rx_threshold_packets = static_cast<uint16_t>(satelliteConfig[7]),
          .tx_threshold_packets = static_cast<uint16_t>(satelliteConfig[8]),
          .rx6_overhead_bytes = static_cast<uint8_t>(satelliteConfig[9]),
          .tx6_overhead_bytes = static_cast<uint8_t>(satelliteConfig[10]),
          .rx4_overhead_bytes = static_cast<uint8_t>(satelliteConfig[11]),
          .tx4_overhead_bytes = static_cast<uint8_t>(satelliteConfig[12])};

      auto res = mSatelliteConfigurationMap.writeValue(0, config, BPF_ANY);
      if (!res.ok()) {
        ALOGW("Failed to enable satellite event ring buffer: %s",
              res.error().message().c_str());
        return;
      }
      // max data usage allowed per uid
      mMaxDataUsageAllowed = satelliteConfig[1];

      // Polling count with each count to wait for 1 sec
      mPollingCount = satelliteConfig[0];
      mWriteFd = writeFd;
      isPolling = true;
      PollAndSchedule();
    } else {
      ALOGW("ring buffer initialisation failed");
    }
  } else {
    ALOGW("invalid write fd");
  }
}

void SatelliteBufferEventPoller::startSatelliteBufferEventMonitoring(
    uint32_t *satelliteEventConfig, int writeFd) {
  ALOGD("Start Satellite Buffer Event to a RingBuffer");
  getInstance()->Start(satelliteEventConfig, writeFd);
}

void SatelliteBufferEventPoller::TraceUidsWithThresholdLimitExceeded(
    const std::vector<SatelliteEvent> &SatelliteBufferEvents) {
  if (SatelliteBufferEvents.empty())
    return;

  // clear the map, to track uids from satellite events read from ring buffer at
  // current polling session
  mSatelliteEventMap.clear();

  for (const SatelliteEvent &event : SatelliteBufferEvents) {
    auto it = std::find(mSatelliteSessionUidBlockList.begin(),
                        mSatelliteSessionUidBlockList.end(), event.uid);
    if (it == mSatelliteSessionUidBlockList.end()) {
      mSatelliteEventMap[event.uid] += event.rx_bytes + event.tx_bytes;

      // update satellite blocking uids list cache, if current data usage
      // exceeds max data usage allowed at current polling session. Note: Max
      // data usage allowed config received at satellite event config from jni.
      if (mSatelliteEventMap[event.uid] > mMaxDataUsageAllowed) {
        mSatelliteSessionUidBlockList.push_back(event.uid);
        ALOGI("uid added to list to be blocked:%d", event.uid);
      }
    }
  }
}

int *SatelliteBufferEventPoller::geUidArrayToUpdatePipe() {
  int *currUidArrayToPipe;
  currUidArrayToPipe = new int[mSatelliteSessionUidBlockList.size()];
  if (mCurrSatelliteUidBlockingArraySize == 0) {
    // Step 1: If the array is empty, allocate memory and copy all elements
    for (int i = 0; i < mSatelliteSessionUidBlockList.size(); ++i) {
      currUidArrayToPipe[i] = mSatelliteSessionUidBlockList[i];
    }
    mUidArrayToPipeSize = mSatelliteSessionUidBlockList.size();
  } else {
    // Step 2 (and subsequent steps): Overwrite with only the new uids at pipe
    int satelliteSessionUidBlockListSize =
        0; // Keep track of the new size to write to pipe
    for (int newUid : mSatelliteSessionUidBlockList) {
      bool found = false;
      // Find the diff from the last reported uids
      for (int i = 0; i < mCurrSatelliteUidBlockingArraySize; ++i) {
        if (newUid == mCurrSatelliteUidBlockingArray[i]) {
          found = true;
          break;
        }
      }
      if (!found) {
        currUidArrayToPipe[satelliteSessionUidBlockListSize++] = newUid;
      }
    }
    mUidArrayToPipeSize = satelliteSessionUidBlockListSize;
  }
  return currUidArrayToPipe;
}

void SatelliteBufferEventPoller::UpdateSatelliteUidsTobeBlockedToPipe() {
  int *uidArrayToPipe = geUidArrayToUpdatePipe();

  // Write the new blocking uids to the pipe
  if (write(mWriteFd, uidArrayToPipe, mUidArrayToPipeSize * sizeof(int)) ==
      -1) {
    ALOGW("error writing current satellite uid blocking list elements to pipe");
    return;
  }

  mCurrSatelliteUidBlockingArray =
      new int[mSatelliteSessionUidBlockList.size()];
  for (int i = 0; i < mSatelliteSessionUidBlockList.size(); ++i) {
    mCurrSatelliteUidBlockingArray[i] = mSatelliteSessionUidBlockList[i];
  }
  mCurrSatelliteUidBlockingArraySize = mSatelliteSessionUidBlockList.size();
}

bool SatelliteBufferEventPoller::ConsumeAll() { return ConsumeAllLocked(); }

bool SatelliteBufferEventPoller::ConsumeAllLocked() {
  if (mRingBuffer == nullptr) {
    ALOGW("satellite buffer event to ring buffer is not active");
    return false;
  }

  std::vector<SatelliteEvent> events;
  base::Result<int> ret = mRingBuffer->ConsumeAll(
      [&](const SatelliteEvent &event) { events.push_back(event); });
  if (!ret.ok()) {
    ALOGW("Failed to poll ringbuf: %s", ret.error().message().c_str());
    return false;
  }

  ATRACE_INT("SatelliteBufferEvents", events.size());
  uint32_t eventSize = events.size();
  ALOGI("satellite event size: %d", eventSize);

  // To identify uids to block crossing max data usage limit
  TraceUidsWithThresholdLimitExceeded(events);

  if (!mSatelliteSessionUidBlockList.empty() &&
      (mSatelliteSessionUidBlockList.size() >
       mCurrSatelliteUidBlockingArraySize)) {
    ALOGD("update pipe");
    UpdateSatelliteUidsTobeBlockedToPipe();
  }
  return true;
}

void SatelliteBufferEventPoller::Stop() {
  // Update netd satellite event config to stop generating satellite buffer
  // event to ring buffer
  SatelliteConfigValue config{0};
  auto res = mSatelliteConfigurationMap.writeValue(0, config, BPF_ANY);
  if (!res.ok()) {
    ALOGW("Failed to disable satellite event ring buffer: %s",
          res.error().message().c_str());
  }

  // Drain remaining events from the ring buffer now that satellite buffer event
  // is disabled. This prevents the next satellite buffer event from seeing
  // stale events and allows writing the last batch uids at the events for
  // enforcement.
  ConsumeAllLocked();

  // Reset related buffers and initialisation for the next session
  mRingBuffer.reset();
  mSatelliteSessionUidBlockList.clear();
  mSatelliteEventMap.clear();
  mCurrSatelliteUidBlockingArray = nullptr;
  mCurrSatelliteUidBlockingArraySize = 0;
  mUidArrayToPipeSize = 0;
  isPolling = false; // stop polling
}

void SatelliteBufferEventPoller::stopSatelliteBufferEvent() {
  ALOGD("Stop Satellite Buffer Event to RingBuffer");
  getInstance()->Stop();
}

} // namespace bpf
} // namespace android