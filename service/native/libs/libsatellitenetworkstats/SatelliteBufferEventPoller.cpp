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
#include <thread>
#include <string.h>

#include "libsatellitenetworkstats/SatelliteBufferEventPoller.h"

namespace android {
namespace bpf {

SatelliteBufferEventPoller::SatelliteBufferEventPoller() {
isPolling = false;
mCurrentSatelliteUidsBlockListSize = 0;
mMaxDataUsageAllowed = 0;
}

void SatelliteBufferEventPoller::PollAndSchedule() {
     while(true) {
        int i = 0;
        // Polling count determines the count with each count adding a interval of 1 sec as wait
        // time
        for (i =0 ; i < mPollingCount ; i++) {
            if (!isPolling) return;
            else {
              // Broken to smaller timer to help exit the current running jni code thread
              // on stopping the satellite buffer event to ring buffer with satellite network lost
              // or on moving to new network ( wifi or cellular from satellite)
              std::this_thread::sleep_for(std::chrono::seconds(1));
            }
        }
        ConsumeAllLocked();
     }
 }

SatelliteBufferEventPoller* SatelliteBufferEventPoller::currentRunningObject = nullptr; // Initialize the static member
SatelliteBufferEventPoller* SatelliteBufferEventPoller::getInstance() {
 if (currentRunningObject==nullptr) {
   ALOGW("current object is null");
   currentRunningObject = new SatelliteBufferEventPoller();
 }
 return currentRunningObject;
}

void SatelliteBufferEventPoller::startSatelliteBufferEvent(
        JNIEnv* env, jobject callback, uint32_t* satelliteEventConfig) {
  ALOGD("Start Satellite Buffer Event to a RingBuffer");
  getInstance()->Start(env, callback, satelliteEventConfig);
}

bool SatelliteBufferEventPoller::Start(
         JNIEnv* env, jobject callback, uint32_t* satelliteConfig) {

  auto cfgStatus = mSatelliteConfigurationMap.init(SATELLITE_CONFIG_MAP_PATH);
  if (!cfgStatus.ok()) {
    ALOGW("Failed to bind satellite config map: %s", cfgStatus.error().message().c_str());
    return false;
  }

  auto rb = BpfRingbuf<SatelliteEvent>::Create(SATELLITE_EVENT_RINGBUF_PATH);
  if (!rb.ok()) {
    ALOGW("Failed to create ringbuf: %s", rb.error().message().c_str());
    return false;
  }

  mRingBuffer = std::move(*rb);

  // Satellite event config to enable generating satellite buffer event to a ring buffer
   SatelliteConfigValue config { .time_threshold_ns = satelliteConfig[2],
   .ifindex6 = satelliteConfig[3], .ifindex4 = satelliteConfig[4],
   .rx_threshold_bytes = static_cast<uint16_t>(satelliteConfig[5]),
   .tx_threshold_bytes = static_cast<uint16_t>(satelliteConfig[6]),
   .rx_threshold_packets = static_cast<uint16_t>(satelliteConfig[7]),
   .tx_threshold_packets = static_cast<uint16_t>(satelliteConfig[8]),
   .rx6_overhead_bytes = static_cast<uint8_t>(satelliteConfig[9]),
   .tx6_overhead_bytes = static_cast<uint8_t>(satelliteConfig[10]),
   .rx4_overhead_bytes = static_cast<uint8_t>(satelliteConfig[11]),
   .tx4_overhead_bytes = static_cast<uint8_t>(satelliteConfig[12])} ;

   auto res = mSatelliteConfigurationMap.writeValue(0, config, BPF_ANY);
   if (!res.ok()) {
     ALOGW("Failed to enable satellite event ring buffer: %s", res.error().message().c_str());
     return false;
   }
   // max data usage allowed per uid
   mMaxDataUsageAllowed = satelliteConfig[1];

   mPollingCount = satelliteConfig[0]; ; // Polling count with each count to wait for 1 sec

  g_env = env;
  g_globalCallback = g_env->NewGlobalRef(callback);
  isPolling= true;
  // start polling
  PollAndSchedule();

  return true;
}

bool SatelliteBufferEventPoller::Stop() {

  // Update netd satellite event config to stop generating satellite buffer event to ring buffer
  SatelliteConfigValue config {0};
  auto res = mSatelliteConfigurationMap.writeValue(0, config, BPF_ANY);
  if (!res.ok()) {
    ALOGW("Failed to disable satellite event ring buffer: %s", res.error().message().c_str());
  }

  // Drain remaining events from the ring buffer now that satellite buffer event is disabled.
  // This prevents the next satellite buffer event from seeing stale events and allows writing
  // the last batch uids at the events for enforcement.
  ConsumeAllLocked();

  // Reset related buffers and initialisation for the next session
  mRingBuffer.reset();
  mCurrentSatelliteUidsBlockListSize = 0;
  mSatelliteUidsBlockListCache.clear();
  mSatelliteEventMap.clear();
  isPolling = false; // stop polling

  return res.ok();
}

bool SatelliteBufferEventPoller::ConsumeAll() {
  return ConsumeAllLocked();
}

void SatelliteBufferEventPoller::TraceUidsWithThresholdLimitExceeded(
const std::vector<SatelliteEvent>& SatelliteBufferEvents) {
    if (SatelliteBufferEvents.empty()) return;

    for (const SatelliteEvent& event : SatelliteBufferEvents) {
         auto it = std::find(mSatelliteUidsBlockListCache.begin(),
                                    mSatelliteUidsBlockListCache.end(), event.uid);
         if (it == mSatelliteUidsBlockListCache.end()) {
         mSatelliteEventMap[event.uid] += event.rx_bytes + event.tx_bytes;

           if (mSatelliteEventMap[event.uid] > mMaxDataUsageAllowed) {
               mSatelliteEventMap.erase(event.uid);
               mSatelliteUidsBlockListCache.push_back(event.uid);
               ALOGI("uid added to list to be blocked:%d", event.uid);
           }
        }
    }
}

void SatelliteBufferEventPoller::UpdateSatelliteUidsTobeBlocked() {
       if(g_globalCallback == nullptr) {
          ALOGW("Callback found null");
       }
       // Create a JNI int array
       jsize arrayLength = mSatelliteUidsBlockListCache.size();
       jintArray intArray = g_env->NewIntArray(arrayLength);

       // Copy C++ int array to JNI array
       jint *intArrayElements = g_env->GetIntArrayElements(intArray, NULL);
       std::copy(mSatelliteUidsBlockListCache.begin(), mSatelliteUidsBlockListCache.end(), intArrayElements);
       g_env->ReleaseIntArrayElements(intArray, intArrayElements, 0);

       // callback with uids to be blocked sent to SatelliteDataUsageController
       jclass callbackClass = g_env->GetObjectClass(g_globalCallback);
       if(callbackClass == nullptr) {
             ALOGW("callback class found null");
       } else {
                  jmethodID callbackMethod = g_env->GetMethodID(callbackClass, "onSatelliteUidBlockingListReceived", "([I)V");
                  if(callbackMethod == nullptr) {
                       ALOGW("method id found null");
                  } else {
                       // Invoke the callback method
                       ALOGD("uid blocking list triggered");
                       g_env->CallVoidMethod(g_globalCallback, callbackMethod, intArray);
                  }
       }

       // Clean up jintArray
       g_env->DeleteLocalRef(intArray);

       // Update Current uidBlockListSize to avoid callback triggers everytime after reading satellite buffer event
       mCurrentSatelliteUidsBlockListSize = mSatelliteUidsBlockListCache.size();
}

bool SatelliteBufferEventPoller::ConsumeAllLocked() {
  if (mRingBuffer == nullptr) {
    ALOGW("satellite buffer event to ring buffer is not active");
    return false;
  }

  std::vector<SatelliteEvent> events;
  base::Result<int> ret = mRingBuffer->ConsumeAll(
      [&](const SatelliteEvent& event) { events.push_back(event); });
  if (!ret.ok()) {
    ALOGW("Failed to poll ringbuf: %s", ret.error().message().c_str());
    return false;
  }

  ATRACE_INT("SatelliteBufferEvents", events.size());
  uint32_t eventSize = events.size();

  // To identify uids to block crossing max data usage limit
  TraceUidsWithThresholdLimitExceeded(events);

  if (!mSatelliteUidsBlockListCache.empty()
          && (mSatelliteUidsBlockListCache.size() > mCurrentSatelliteUidsBlockListSize)) {
       ALOGD("callback triggered");
       UpdateSatelliteUidsTobeBlocked();
  }

  return true;
}

void SatelliteBufferEventPoller::stopSatelliteBufferEvent() {
  ALOGI("Stop Satellite Buffer Event to RingBuffer");
  getInstance()->Stop();
}

}  // namespace bpf
}  // namespace android