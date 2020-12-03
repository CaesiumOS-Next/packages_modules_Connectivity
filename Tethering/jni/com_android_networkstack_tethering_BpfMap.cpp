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

#include <linux/bpf.h>
#include <errno.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>

#define BPF_FD_JUST_USE_INT
#include "bpf/BpfUtils.h"

namespace android {

static void throwErrnoException(JNIEnv* env, const char* functionName, int error) {
    jclass cls = env->FindClass("android/system/ErrnoException");
    jmethodID cid = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;I)V");
    jstring msg = env->NewStringUTF(functionName);
    if (cls == nullptr || cid == nullptr || msg == nullptr) return;

    jthrowable errnoException = (jthrowable) env->NewObject(cls, cid, msg, error);
    if (errnoException != nullptr)  env->Throw(errnoException);
}

static void com_android_networkstack_tethering_BpfMap_closeMap(JNIEnv *env, jobject clazz,
        jint fd) {
    jint ret = close(fd);

    if (ret) throwErrnoException(env, "closeMap", errno);
}

static jint com_android_networkstack_tethering_BpfMap_bpfFdGet(JNIEnv *env, jobject clazz,
        jstring path, jint mode) {
    const jsize javaCharsCount = env->GetStringLength(path);
    const jsize byteCountUTF8 = env->GetStringUTFLength(path);
    char pathname[byteCountUTF8 + 1]; // include trailing 0 space.
    pathname[byteCountUTF8] = 0;

    env->GetStringUTFRegion(path, 0, javaCharsCount, pathname);

    return bpf::bpfFdGet(pathname, unsigned(mode));
}

static void com_android_networkstack_tethering_BpfMap_writeToMapEntry(JNIEnv *env, jobject clazz,
        jint fd, jbyteArray key, jbyteArray value, jint flags) {
    const jsize keySize = env->GetArrayLength(key);
    jbyte copiedKey[keySize];
    env->GetByteArrayRegion(key, 0, keySize, copiedKey);

    const jsize valueSize = env->GetArrayLength(value);
    jbyte copiedValue[valueSize];
    env->GetByteArrayRegion(value, 0, valueSize, copiedValue);

    int ret = bpf::writeToMapEntry((int)fd, copiedKey, copiedValue, (int)flags);

    if (ret) throwErrnoException(env, "writeToMapEntry", errno);
}

static jboolean checkResultAndThrow(JNIEnv *env, int ret) {
    if (ret == 0) return true;

    if (errno != ENOENT) throwErrnoException(env, "getNextMapKey", errno);
    return false;
}

static jboolean com_android_networkstack_tethering_BpfMap_deleteMapEntry(JNIEnv *env, jobject clazz,
        jint fd, jbyteArray key) {
    const jsize keySize = env->GetArrayLength(key);
    jbyte copiedKey[keySize];
    env->GetByteArrayRegion(key, 0, keySize, copiedKey);

    // On success, zero is returned.  If the element is not found, -1 is returned and errno is set
    // to ENOENT.
    int ret = bpf::deleteMapEntry((int)fd, copiedKey);

    return checkResultAndThrow(env, ret);
}

static jboolean com_android_networkstack_tethering_BpfMap_getNextMapKey(JNIEnv *env, jobject clazz,
        jint fd, jbyteArray key, jbyteArray nextKey) {
    const jsize keySize = env->GetArrayLength(key);
    jbyte copiedKey[keySize];
    env->GetByteArrayRegion(key, 0, keySize, copiedKey);

    jbyte* data = env->GetByteArrayElements(nextKey, NULL);
    // If key is found, the operation returns zero and sets the "data" pointer to the key of the
    // next element.  If key is not found, the operation returns zero and sets the "data" pointer
    // to the key of the first element.  If key is the last element, -1 is returned and errno is
    // set to ENOENT.  Other possible errno values are ENOMEM, EFAULT, EPERM, and EINVAL.
    int ret = bpf::getNextMapKey((int)fd, copiedKey, data);

    if (data != NULL) env->ReleaseByteArrayElements(nextKey, data, 0);

    return checkResultAndThrow(env, ret);
}

static jboolean com_android_networkstack_tethering_BpfMap_findMapEntry(JNIEnv *env, jobject clazz,
        jint fd, jbyteArray key, jbyteArray value) {
    const jsize keySize = env->GetArrayLength(key);
    jbyte copiedKey[keySize];
    env->GetByteArrayRegion(key, 0, keySize, copiedKey);

    jbyte* data = env->GetByteArrayElements(value, NULL);
    // If an element is found, the operation returns zero and stores the element's value into
    // "data".  If no element is found, the operation returns -1 and sets errno to ENOENT.
    int ret = bpf::findMapEntry((int)fd, copiedKey, data);

    if (data != NULL) env->ReleaseByteArrayElements(value, data, 0);

    return checkResultAndThrow(env, ret);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "closeMap", "(I)V",
        (void*) com_android_networkstack_tethering_BpfMap_closeMap },
    { "bpfFdGet", "(Ljava/lang/String;I)I",
        (void*) com_android_networkstack_tethering_BpfMap_bpfFdGet },
    { "writeToMapEntry", "(I[B[BI)V",
        (void*) com_android_networkstack_tethering_BpfMap_writeToMapEntry },
    { "deleteMapEntry", "(I[B)Z",
        (void*) com_android_networkstack_tethering_BpfMap_deleteMapEntry },
    { "getNextMapKey", "(I[B[B)Z",
        (void*) com_android_networkstack_tethering_BpfMap_getNextMapKey },
    { "findMapEntry", "(I[B[B)Z",
        (void*) com_android_networkstack_tethering_BpfMap_findMapEntry },

};

int register_com_android_networkstack_tethering_BpfMap(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/networkstack/tethering/BpfMap",
            gMethods, NELEM(gMethods));
}

}; // namespace android
