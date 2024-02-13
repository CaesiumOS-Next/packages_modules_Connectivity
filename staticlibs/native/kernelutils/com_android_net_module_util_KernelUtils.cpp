/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <errno.h>
#include <linux/pfkeyv2.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include "nativehelper/scoped_primitive_array.h"
#include "nativehelper/scoped_utf_chars.h"

#include "bpf/KernelUtils.h"

namespace android {

static jboolean android_net_utils_isKernel64Bit(JNIEnv *env, jclass clazz) {
    return bpf::isKernel64Bit();
}

static jboolean android_net_utils_isKernelX86(JNIEnv *env, jclass clazz) {
    return bpf::isX86();
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "isKernel64Bit", "()Z", (void*) android_net_utils_isKernel64Bit },
    { "isKernelX86", "()Z", (void*) android_net_utils_isKernelX86 },
};

int register_com_android_net_module_util_KernelUtils(JNIEnv* env, char const* class_name) {
    return jniRegisterNativeMethods(env,
            class_name,
            gMethods, NELEM(gMethods));
}

}; // namespace android
