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
mSessionCount =  0;
isPolling = false;
mCurrentSatelliteUidsBlockListSize = 0;
mMaxDataUsageAllowed = 0;
}

void SatelliteBufferEventPoller::PollAndSchedule() {
 // Sleep for 30 seconds
 while(isPolling) {
 std::this_thread::sleep_for(std::chrono::seconds(30));

  ALOGD("stop thread");
 // if (mMutex.try_lock()) {
    ConsumeAllLocked();
 //   mMutex.unlock();
 // }
  }
}

SatelliteBufferEventPoller* SatelliteBufferEventPoller::currentRunningObject = nullptr; // Initialize the static member
SatelliteBufferEventPoller* SatelliteBufferEventPoller::getInstance() {
 if (currentRunningObject==nullptr) {
   ALOGI("current object is null");
   currentRunningObject = new SatelliteBufferEventPoller();
 }
 return currentRunningObject;
}

void SatelliteBufferEventPoller::startSatelliteBufferEvent(
        JNIEnv* env, jobject callback, uint32_t* satelliteEventConfig) {
  ALOGD("Start Satellite Buffer Event to a RingBuffer");
  getInstance()->Start(env, callback, satelliteEventConfig);
  // getInstance()->Start(satelliteEventConfig);
}

bool SatelliteBufferEventPoller::Start(
         JNIEnv* env, jobject callback, uint32_t* satelliteConfig) {
  std::scoped_lock<std::mutex> lock(mMutex);
  if (mSessionCount > 0) {
    mSessionCount++;
    return true;
  }

  auto cfgStatus = mSatelliteConfigurationMap.init(SATELLITE_CONFIG_MAP_PATH);
  if (!cfgStatus.ok()) {
    ALOGW("Failed to bind satellite config map: %s", cfgStatus.error().message().c_str());
    return false;
  }

  auto cacheStatus = mSatelliteCacheMap.init(SATELLITE_CACHE_MAP_PATH);
  if (!cacheStatus.ok()) {
    ALOGW("Failed to satellite cache map: %s", cacheStatus.error().message().c_str());
    return false;
  }

  auto rb = BpfRingbuf<SatelliteEvent>::Create(SATELLITE_EVENT_RINGBUF_PATH);
  if (!rb.ok()) {
    ALOGW("Failed to create ringbuf: %s", rb.error().message().c_str());
    return false;
  }

  mRingBuffer = std::move(*rb);

  // Update Satellite event config
  SatelliteConfigValue config { .time_threshold_ns = satelliteConfig[1],
  .ifindex = satelliteConfig[3], .rx_threshold_bytes = static_cast<uint16_t>(satelliteConfig[4]),
  .tx_threshold_bytes = static_cast<uint16_t>(satelliteConfig[5])} ;

  mMaxDataUsageAllowed = satelliteConfig[0];
  ALOGI("max data usage allowed per uid: %d", mMaxDataUsageAllowed);

  auto res = mSatelliteConfigurationMap.writeValue(0, config, BPF_ANY);
  if (!res.ok()) {
    ALOGW("Failed to enable satellite event ring buffer: %s", res.error().message().c_str());
    return false;
  }

  isPolling= true;
  g_env = env;
  g_globalCallback = g_env->NewGlobalRef(callback);;
  PollAndSchedule();

  mSessionCount++;
  return true;
}

bool SatelliteBufferEventPoller::Stop() {
 // std::scoped_lock<std::mutex> lock(mMutex);
  if (mSessionCount == 0) return false;  // This should never happen

  // If this isn't the last session, don't clean up yet.
  if (--mSessionCount > 0) return true;

  SatelliteConfigValue config {0};
  auto res = mSatelliteConfigurationMap.writeValue(0, config, BPF_ANY);
  if (!res.ok()) {
    ALOGW("Failed to disable satellite event ring buffer: %s", res.error().message().c_str());
  }

  // Make sure everything in the system has actually seen the 'false' we just
  // wrote, things should now be well and truly disabled.
  synchronizeKernelRCU();

  // Drain remaining events from the ring buffer now that satellite buffer event is disabled.
  // This prevents the next satellite buffer event from seeing stale events and allows writing
  // the last batch uid's at the events for enforcement.
  ConsumeAllLocked();

  mRingBuffer.reset();
  mCurrentSatelliteUidsBlockListSize = 0;
  mSatelliteUidsBlockListCache.clear();
  mSatelliteEventMap.clear();
  isPolling = false;
  ALOGI("reset polling: %d", isPolling);

  return res.ok();
}

bool SatelliteBufferEventPoller::ConsumeAll() {
  std::scoped_lock<std::mutex> lock(mMutex);
  return ConsumeAllLocked();
}

void SatelliteBufferEventPoller::TraceUidsWithThresholdLimitExceeded(
const std::vector<SatelliteEvent>& SatelliteBufferEvents) {
    if (SatelliteBufferEvents.empty()) return;

	for (const SatelliteEvent& event : SatelliteBufferEvents) {
	    auto it = std::find(mSatelliteUidsBlockListCache.begin(), mSatelliteUidsBlockListCache.end(), event.uid);
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
    ALOGW("Tracing is not active");
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
  ALOGI("satellite event size:%d",eventSize);

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