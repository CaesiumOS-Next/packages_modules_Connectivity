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
#define LOG_TAG "SatelliteDataUsageControllerNative"
#include "bpf/BpfUtils.h"
#include "jni.h"
#include "libsatellitenetworkstats/SatelliteBufferEventPoller.h"
#include <bpf/BpfClassic.h>
#include <bpf/KernelUtils.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <string.h>
#include <utils/Log.h>

using android::bpf::SatelliteBufferEventPoller;

namespace android {

static jboolean nativeInitSatelliteBufferEvent(JNIEnv *env, jclass clazz) {
  bool init_status = SatelliteBufferEventPoller::init();

  if (!init_status) {
    ALOGW("satellite event initialisation with bpf netd failed");
    return 0;
  }
  return 1;
}

static void nativeStartSatelliteBufferEventMonitoring(JNIEnv *env, jclass clazz, jintArray indexCfg,
                                                      jintArray eventCfg) {
  // Copy JNI array tp C++ int array for satellite event config
  jint *cArray = env->GetIntArrayElements(eventCfg, NULL);
  // Cast to uint32_t* (be cautious about potential negative values)
  uint32_t *eventConfig = reinterpret_cast<uint32_t *>(cArray);
  // Copy JNI array to C++ int array for satellite interface index config
  jint *iArray = env->GetIntArrayElements(indexCfg, NULL);
  // Cast to uint32_t* (be cautious about potential negative values)
  uint32_t *indexConfig = reinterpret_cast<uint32_t *>(iArray);
  // get the Interface index array size
  jsize indexCfgSize = env->GetArrayLength(indexCfg);
  int ifIndexArraySize = (int)indexCfgSize;
  SatelliteBufferEventPoller::startSatelliteBufferEventMonitoring(
      indexConfig, eventConfig, ifIndexArraySize);
}

static void
nativeUpdateSatelliteBufferEventMonitoringIfaces(JNIEnv *env, jclass clazz, jintArray indexCfg) {
  // Copy JNI array tp C++ int array for satellite interface index config
  jint *iArray = env->GetIntArrayElements(indexCfg, NULL);
  // Cast to uint32_t* (be cautious about potential negative values)
  uint32_t *indexConfig = reinterpret_cast<uint32_t *>(iArray);
  // get the Interface index array size
  jsize indexCfgSize = env->GetArrayLength(indexCfg);
  int ifIndexArraySize = (int)indexCfgSize;
  SatelliteBufferEventPoller::updateSatelliteBufferEventMonitoringIfaces(
      indexConfig, ifIndexArraySize);
}

static const JNINativeMethod gMethods[] = {
    {"nativeInitSatelliteBufferEvent", "()Z",
     (void *)nativeInitSatelliteBufferEvent},
    {"nativeStartSatelliteBufferEventMonitoring", "([I[I)V",
     (void *)nativeStartSatelliteBufferEventMonitoring},
    {"nativeUpdateSatelliteBufferEventMonitoringIfaces", "([I)V",
     (void *)nativeUpdateSatelliteBufferEventMonitoringIfaces},
};

int register_com_android_server_connectivity_SatelliteDataUsageController(
    JNIEnv *env) {
  return jniRegisterNativeMethods(env,
                                  "android/net/connectivity/com/android/server/"
                                  "connectivity/SatelliteDataUsageController",
                                  gMethods, NELEM(gMethods));
}

} // namespace android