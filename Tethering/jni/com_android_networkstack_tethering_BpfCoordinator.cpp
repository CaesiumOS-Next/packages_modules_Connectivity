/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <jni.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>

#include "nativehelper/scoped_utf_chars.h"

#include "BpfSyscallWrappers.h"
#include "bpf/BpfRingbuf.h"
#include "offload.h"

struct JNIJavaMethod {
  const char* name;
  const char* signature;
  jmethodID* id;
  bool is_static{false};
};

namespace android {

std::unique_ptr<android::bpf::BpfRingbuf<TetherCallback>> sRingBuffer;

static jmethodID method_onUpdateConntrackTimeout;

void jniGetMethodsOrDie(JNIEnv* env, const char* className, const JNIJavaMethod* methods,
                        int nMethods);

static jobjectArray getBpfCounterNames(JNIEnv *env) {
    size_t size = BPF_TETHER_ERR__MAX;
    jobjectArray ret = env->NewObjectArray(size, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < size; i++) {
        env->SetObjectArrayElement(ret, i, env->NewStringUTF(bpf_tether_errors[i]));
    }
    return ret;
}

static jobject mCallbacksObj = NULL;
static jboolean startCallback(JNIEnv *env, jobject obj, jstring path) {
  mCallbacksObj = env->NewGlobalRef(obj);

  ALOGI("startCallback");
  ScopedUtfChars pathname(env, path);

  auto rb = android::bpf::BpfRingbuf<TetherCallback>::Create(pathname.c_str());
  if (!rb.ok()) {
    ALOGI("Failed to create ringbuf: %s", rb.error().message().c_str());
    return false;
  }

  sRingBuffer = std::move(*rb);
  return true;
}

static jboolean pollTest(JNIEnv *env, jobject obj) {
  ALOGI("pollTest");

  std::vector<TetherCallback> callbacks;
  base::Result<int> ret = sRingBuffer->ConsumeAll(
      [&](const TetherCallback &cb) { callbacks.push_back(cb); });
  if (!ret.ok()) {
    ALOGI("Failed to poll ringbuf: %s", ret.error().message().c_str());
    return false;
  }

  ALOGI("num of TetherCallbacks %d", callbacks.size());

  for (const TetherCallback &cb : callbacks) {
    ALOGI("cb %d, %d, %d, %d, %d", cb.proto, cb.src4, cb.srcPort, cb.dst4,
          cb.dstPort);
    env->CallVoidMethod(mCallbacksObj, method_onUpdateConntrackTimeout, cb.src4, cb.dst4, cb.proto, cb.srcPort, cb.dstPort);
  }

  return true;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    {"getBpfCounterNames", "()[Ljava/lang/String;", (void *)getBpfCounterNames},
    {"startCallback", "(Ljava/lang/String;)Z", (void *)startCallback},
    {"pollTest", "()Z", (void *)pollTest},
};

int register_com_android_networkstack_tethering_BpfCoordinator(JNIEnv* env) {
    const int ret = jniRegisterNativeMethods(env,
            "com/android/networkstack/tethering/BpfCoordinator",
            gMethods, NELEM(gMethods));

    if (ret != 0) return ret;

    const JNIJavaMethod javaMethods[] = {
            {"onUpdateConntrackTimeout", "(IIBSS)V", &method_onUpdateConntrackTimeout},
    };
    jniGetMethodsOrDie(env, "com/android/networkstack/tethering/BpfCoordinator", javaMethods, NELEM(javaMethods));

    return 0;
}

/** Load the java methods or die*/
void jniGetMethodsOrDie(JNIEnv* env, const char* className, const JNIJavaMethod* methods,
                        int nMethods) {
  jclass clazz = env->FindClass(className);
  if (clazz == nullptr) {
    ALOGE("Native registration unable to find class '{}' aborting...", className);
  }

  for (int i = 0; i < nMethods; i++) {
    const JNIJavaMethod& method = methods[i];
    if (method.is_static) {
      *method.id = env->GetStaticMethodID(clazz, method.name, method.signature);
    } else {
      *method.id = env->GetMethodID(clazz, method.name, method.signature);
    }
    if (method.id == nullptr) {
      ALOGE("In class {}: Unable to find '{}' with signature={} is_static={}", className,
                 method.name, method.signature, method.is_static);
    }
  }

  env->DeleteLocalRef(clazz);
}

}; // namespace android
