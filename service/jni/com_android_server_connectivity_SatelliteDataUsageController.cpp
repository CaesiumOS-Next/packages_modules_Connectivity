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

// #include <android/file_descriptor_jni.h>
#include <bpf/BpfClassic.h>
#include <bpf/KernelUtils.h>
// #include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <string.h>
#include <utils/Log.h>

#include "jni.h"

#include "bpf/BpfUtils.h"
#include "libsatellitenetworkstats/SatelliteBufferEventPoller.h"

using android::bpf::SatelliteBufferEventPoller;

namespace android {
int write_fd = -1;

static jint nativeInitSatelliteBufferEvent(JNIEnv *env, jclass clazz) {
  int p[2] = {-1, -1};
  int read_fd = -1;

  if (pipe2(p, O_CLOEXEC) == -1) {
    ALOGW("pipe2 was not success");
  } else {
    read_fd = p[0];
    write_fd = p[1];
  }

  if (read_fd < 0) {
    jniThrowErrnoException(env, "resNetworkQuery", -read_fd);
    return -1;
  }

  bool init_status = SatelliteBufferEventPoller::init();

  if (!init_status) {
    ALOGW("satellite event initialisation with bpf netd failed");
    close(p[0]); // Close the read end of the pipe
    close(p[1]); // Close the write end of the pipe
    read_fd = -1;
  }
  return read_fd;
}

static void nativeStartSatelliteBufferEventMonitoring(JNIEnv *env, jclass clazz,
                                                      jintArray config) {

  // Copy JNI array tp C++ int array
  jint *cArray = env->GetIntArrayElements(config, NULL);
  // Cast to uint32_t* (be cautious about potential negative values)
  uint32_t *eventConfig = reinterpret_cast<uint32_t *>(cArray);

  SatelliteBufferEventPoller::startSatelliteBufferEventMonitoring(eventConfig,
                                                                  write_fd);
}

static void nativeStopSatelliteBufferEvent() {
  SatelliteBufferEventPoller::stopSatelliteBufferEvent();
}

static const JNINativeMethod gMethods[] = {
    {"nativeInitSatelliteBufferEvent", "()I",
     (void *)nativeInitSatelliteBufferEvent},
    {"nativeStartSatelliteBufferEventMonitoring", "([I)V",
     (void *)nativeStartSatelliteBufferEventMonitoring},
    {"nativeStopSatelliteBufferEvent", "()V",
     (void *)nativeStopSatelliteBufferEvent},
};

int register_com_android_server_connectivity_SatelliteDataUsageController(
    JNIEnv *env) {
  return jniRegisterNativeMethods(env,
                                  "android/net/connectivity/com/android/server/"
                                  "connectivity/SatelliteDataUsageController",
                                  gMethods, NELEM(gMethods));
}

} // namespace android