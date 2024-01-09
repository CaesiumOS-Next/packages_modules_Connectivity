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

#include <android-base/unique_fd.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/scoped_utf_chars.h>
#include <string.h>
#include <sys/socket.h>

#include "bpf/KernelUtils.h"

namespace android {

using base::unique_fd;

static jboolean com_android_net_module_util_KernelUtil_isKernel64Bit(JNIEnv *env,
        jclass clazz) {
    return bpf::isKernel64Bit();
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "native_isKernel64Bit", "()Z",
        (void*) com_android_net_module_util_KernelUtil_isKernel64Bit },
};

int register_com_android_net_module_util_KernelUtils(JNIEnv* env, char const* class_name) {
    return jniRegisterNativeMethods(env,
            class_name,
            gMethods, NELEM(gMethods));
}

}; // namespace android
