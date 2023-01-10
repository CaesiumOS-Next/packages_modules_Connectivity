/*
 * Copyright (C) 2023 The Android Open Source Project
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

#define LOG_TAG "NetworkStatsNative"

#include <inttypes.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

#include "netdbpf/NetworkTraceHandler.h"

namespace android {

static void nativeInitNetworkTracing(JNIEnv* env, jclass clazz) {
  android::bpf::NetworkTraceHandler::InitPerfettoTracing();
}

static const JNINativeMethod gMethods[] = {
    {"nativeInitNetworkTracing", "()V", (void*)nativeInitNetworkTracing},
};

int register_android_server_NetworkStatsServiceInitializer(JNIEnv* env) {
  return jniRegisterNativeMethods(
      env, "com/android/server/NetworkStatsServiceInitializer", gMethods,
      NELEM(gMethods));
}
}  // namespace android
