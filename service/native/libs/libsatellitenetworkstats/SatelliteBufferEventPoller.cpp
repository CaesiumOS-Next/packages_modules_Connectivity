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

  cfgStatus =
      mSatelliteIfIndexConfigurationMap.init(SATELLITE_IFINDEX_CONFIG_MAP_PATH);
  if (!cfgStatus.ok()) {
    ALOGW("Failed to bind satellite interface index map: %s",
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
  while (isPolling) {
    for (int i = 0; i < mPollingCount && isPolling; ++i) {
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    if (isPolling) {
      ConsumeAllLocked();
    }
  }
}

void SatelliteBufferEventPoller::updateSatelliteInterfaceIndexConfig(
    uint32_t *satelliteIfIndexConfig, int ifIndexArraySize) {
  SatelliteIfIndexConfigValue ifIndexConfig;
  // Initialize ifindex6[4] with values from config[]
  for (int i = 0; i < ifIndexArraySize && i < 4 /*Interface index support*/; i++) {
    ifIndexConfig.ifindex6[i] = satelliteIfIndexConfig[i];
    ALOGI("interface index at config : %d", ifIndexConfig.ifindex6[i]);
  }

  // Initialize remaining elements of ifindex6[4] to 0
  for (int i = ifIndexArraySize; i < 4; i++) {
    ifIndexConfig.ifindex6[i] = 0;
    ALOGI("interface index at config : %d", ifIndexConfig.ifindex6[i]);
  }

  // Copy ifindex6[4] to ifindex4[4]
  memcpy(ifIndexConfig.ifindex4, ifIndexConfig.ifindex6,
         sizeof(ifIndexConfig.ifindex6));

  auto res =
      mSatelliteIfIndexConfigurationMap.writeValue(0, ifIndexConfig, BPF_ANY);
  if (!res.ok()) {
    ALOGW("Failed to update satellite interface index config: %s",
          res.error().message().c_str());
    return;
  }
}

void SatelliteBufferEventPoller::initialiseRingBuffer() {
  if (mRingBuffer == nullptr) {
    ALOGD("Ringbuffer was null, re-initialise");

    auto rb = BpfRingbuf<SatelliteEvent>::Create(SATELLITE_EVENT_RINGBUF_PATH);
    if (!rb.ok()) {
      ALOGW("Failed to create ringbuf: %s", rb.error().message().c_str());
      return;
    }
    mRingBuffer = std::move(*rb);
  }
}

void SatelliteBufferEventPoller::Start(uint32_t *satelliteIfIndexConfig,
                                       uint32_t *satelliteConfig,
                                       int ifIndexArraySize) {
  initialiseRingBuffer();

  if (mRingBuffer != nullptr) {
    // Satellite event config to enable generating satellite buffer event to a
    // ring buffer
    SatelliteConfigValue config{
        .time_threshold_ns = satelliteConfig[2],
        .rx_threshold_bytes = static_cast<uint16_t>(satelliteConfig[3]),
        .tx_threshold_bytes = static_cast<uint16_t>(satelliteConfig[4]),
        .rx_threshold_packets = static_cast<uint16_t>(satelliteConfig[5]),
        .tx_threshold_packets = static_cast<uint16_t>(satelliteConfig[6]),
        .rx6_overhead_bytes = static_cast<uint8_t>(satelliteConfig[7]),
        .tx6_overhead_bytes = static_cast<uint8_t>(satelliteConfig[8]),
        .rx4_overhead_bytes = static_cast<uint8_t>(satelliteConfig[9]),
        .tx4_overhead_bytes = static_cast<uint8_t>(satelliteConfig[10])};

    auto res = mSatelliteConfigurationMap.writeValue(0, config, BPF_ANY);
    if (!res.ok()) {
      ALOGW("Failed to enable satellite event ring buffer: %s",
            res.error().message().c_str());
      return;
    }
    // Satellite Interface index config to enable logging of satellite buffer
    // event to desired interface index's
    // Testing purpose
    ALOGI("interface index array size: %d", ifIndexArraySize);
    for (int i = 0; i < ifIndexArraySize; ++i) {
      ALOGI("interface index value: %d", satelliteIfIndexConfig[i]);
    }
    updateSatelliteInterfaceIndexConfig(satelliteIfIndexConfig,
                                        ifIndexArraySize);

    // max data usage allowed per uid
    mMaxDataUsageAllowed = satelliteConfig[1];

    // Polling count with each count to wait for 1 sec
    mPollingCount = satelliteConfig[0];
    isPolling = true;
    PollAndSchedule();
  } else {
    ALOGW("ring buffer initialisation failed");
  }
}

void SatelliteBufferEventPoller::startSatelliteBufferEventMonitoring(
    uint32_t *satelliteInterfaceIndexConfig, uint32_t *satelliteEventConfig,
    int ifIndexArraySize) {
  ALOGD("Start Satellite Event to a RingBuffer");
  getInstance()->Start(satelliteInterfaceIndexConfig, satelliteEventConfig,
                       ifIndexArraySize);
}

// To identify uids to block crossing max data usage limit
void SatelliteBufferEventPoller::findUidWithDataOverUsage(
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

      // identify uid to be blocked based on satellite buffer event at current
      // session
      if (mSatelliteEventMap[event.uid] > mMaxDataUsageAllowed) {
        mSatelliteSessionUidBlockList.push_back(event.uid);
        ALOGI("uid to be blocked:%d", event.uid);
      }
    }
  }
}

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

  findUidWithDataOverUsage(events);
  return true;
}

void SatelliteBufferEventPoller::updateMonitoringStatus(
    uint32_t *satelliteIfIndexConfig, int ifIndexArraySize) {

  initialiseRingBuffer();

  // Satellite Interface index config to enable logging of satellite buffer
  // event to desired interface index's
  updateSatelliteInterfaceIndexConfig(satelliteIfIndexConfig, ifIndexArraySize);
  if (!isPolling) {
    isPolling = true;
    PollAndSchedule();
  }
}

void SatelliteBufferEventPoller::stopMonitoringForSatelliteInterfaceIndex() {
  // Update netd satellite interface index config to stop generating the
  // satellite buffer event to ring buffer with the desired iface.
  SatelliteIfIndexConfigValue ifIndexConfig;
  memset(&ifIndexConfig, 0, sizeof(ifIndexConfig));
  auto res =
      mSatelliteIfIndexConfigurationMap.writeValue(0, ifIndexConfig, BPF_ANY);
  if (!res.ok()) {
    ALOGW("Failed to clear satellite interface index config: %s",
          res.error().message().c_str());
  }

  // Drain remaining events from the ring buffer now that satellite buffer event
  // is disabled. This prevents the next satellite buffer event from seeing
  // stale events and allows writing the last batch uids at the events for
  // enforcement.
  ConsumeAllLocked();

  // Reset related buffers and initialisation for the next session
  // mRingBuffer.reset();
  mSatelliteSessionUidBlockList.clear();
  mSatelliteEventMap.clear();
  // TBD
  // isPolling = false; // stop polling
}

void SatelliteBufferEventPoller::updateSatelliteBufferEventMonitoringIfaces(
    uint32_t *satelliteInterfaceIndexConfig, int ifIndexArraySize) {

  if (ifIndexArraySize == 0) { // No satellite interface index active
    ALOGD("stop monitoring");
    getInstance()->stopMonitoringForSatelliteInterfaceIndex();
  } else {
    ALOGD("update interface for monitoring");
    // Test purpose
    ALOGI("interface index array size: %d", ifIndexArraySize);
    for (int i = 0; i < ifIndexArraySize; ++i) {
      ALOGI("interface index value: %d", satelliteInterfaceIndexConfig[i]);
    }
    getInstance()->updateMonitoringStatus(satelliteInterfaceIndexConfig, ifIndexArraySize);
  }
}

} // namespace bpf
} // namespace android