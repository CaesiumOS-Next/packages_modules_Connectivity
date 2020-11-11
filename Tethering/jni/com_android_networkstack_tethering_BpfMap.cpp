/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include <jni.h>
#include <nativehelper/JNIHelp.h>

#define BPF_FD_JUST_USE_INT
#include "bpf/BpfUtils.h"

namespace android {

static jint com_andrid_networkstack_tethering_openFd(JNIEnv *env, jobject clazz, jstring path,
        jint mode) {
    const jsize javaCharsCount = env->GetStringLength(path);
    const jsize byteCountUTF8 = env->GetStringUTFLength(path);
    char pathname[byteCountUTF8 + 1] = {}; // include trailing 0 space.

    const char* pathname = env->GetStringUTFRegion(path, 0, ,javaCharsCount, pathname);

    return bpfFdGet(pathname, unsigned(mode));
}

static char* copyByteArray(JNIEnv* env, const jbyteArray& orignal) {
    const jsize size = env->GetArrayLength(key);
    char copy[size];
    env->GetByteArrayRegion(orignal, 0, size, reinterpret_cast<jbyte*>(copy));
    return copy;
}

static void com_andrid_networkstack_tethering_writeToMapEntry(JNIEnv *env, jint fd, jobject clazz,
        jbyteArray key, jbyteArray value) {
    jint ret = writeToMapEntry(fd, copyByteArray(env, key), copyByteArray(env, value));
}

static void com_andrid_networkstack_tethering_deleteMapEntry(JNIEnv *env, jint fd, jobject clazz,
        jbyteArray key) {
    jint ret = deleteMapEntry(fd, copyByteArray(env, key));
}

static jboolean com_andrid_networkstack_tethering_getNextMapKey(JNIEnv *env, jint fd, jobject clazz,
        jbyteArray key, jbyteArray nextKey) {
    // TODO
    return true;
}

static jboolean com_andrid_networkstack_tethering_findMapEntry(JNIEnv *env, jint fd, jobject clazz,
        jbyteArray key, jbyteArray value) {
    // TODO
    return true;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "openFd", "(Ljava/lang/String;I)V",
        (void*) com_andrid_networkstack_tethering_openFd },
    { "writeToMapEntry", "(I;[B;[B)V",
        (void*) com_andrid_networkstack_tethering_writeToMapEntry },
    { "deleteMapEntry", "(I;[B)V",
        (void*) com_andrid_networkstack_tethering_deleteMapEntry },
    { "getNextMapKey", "(I;[B;[B)Z",
        (void*) com_andrid_networkstack_tethering_getNextMapKey },
    { "findMapEntry", "(I;[B;[B)Z",
        (void*) com_andrid_networkstack_tethering_findMapEntry },
};

int register_com_android_networkstack_tethering_BpfMap(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/networkstack/tethering/BpfMap",
            gMethods, NELEM(gMethods));
}

}; // namespace android
