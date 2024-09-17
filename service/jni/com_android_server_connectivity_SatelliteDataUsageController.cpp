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

#include <cutils/qtaguid.h>
#include <dirent.h>
#include <errno.h>
#include <string.h>
#include <fcntl.h>
#include <inttypes.h>
#include <jni.h>
#include <nativehelper/ScopedUtfChars.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include "bpf/BpfUtils.h"
#include "libsatellitenetworkstats/SatelliteBufferEventPoller.h"

using android::bpf::SatelliteBufferEventPoller;

namespace android {

static void nativeStartSatelliteBufferEvent(JNIEnv* env, jclass clazz, jobject callback, jintArray config) {
     // Copy JNI array tp C++ int array
        jint *cArray = env->GetIntArrayElements(config, NULL);
     // Cast to uint32_t* (be cautious about potential negative values)
        uint32_t* eventConfig = reinterpret_cast<uint32_t*>(cArray);

        SatelliteBufferEventPoller::startSatelliteBufferEvent(env, callback, eventConfig);
}

static void nativeStopSatelliteBufferEvent() {
   SatelliteBufferEventPoller::stopSatelliteBufferEvent();
}

static const JNINativeMethod gMethods[] = {
        {
            "nativeStartSatelliteBufferEvent",
            "(Landroid/net/connectivity/com/android/server/connectivity/SatelliteDataUsageController;[I)V",
            (void*)nativeStartSatelliteBufferEvent
        },
        {
            "nativeStopSatelliteBufferEvent",
            "()V",
            (void*)nativeStopSatelliteBufferEvent
        },
};

int register_com_android_server_connectivity_SatelliteDataUsageController(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "android/net/connectivity/com/android/server/connectivity/SatelliteDataUsageController", gMethods,
            NELEM(gMethods));
}

}