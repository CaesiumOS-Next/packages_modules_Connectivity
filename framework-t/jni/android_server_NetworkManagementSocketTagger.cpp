/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "NMST_QTagUidNative"

#include <android/multinetwork.h>
#include <errno.h>
#include <fcntl.h>
#include <nativehelper/JNIHelp.h>
#include <netjniutils/netjniutils.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include "jni.h"

namespace android {

static jint tagSocketFd(JNIEnv* env, jclass, jobject fileDescriptor,
                        jint tagNum, jint uid) {
    int userFd = netjniutils::GetNativeFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        ALOGE("Can't get FileDescriptor num");
       return (jint)-1;
    }


    if (__builtin_available(android 33, *)) {
        int res = android_tag_socket_with_uid(userFd, tagNum, uid);
        if (res < 0) return (jint)-errno;

        return (jint)res;
    }

    ALOGE("android_tag_socket_with_uid is not available before sdk 33");
    return (jint)-1;
}

static jint untagSocketFd(JNIEnv* env, jclass, jobject fileDescriptor) {
    int userFd = netjniutils::GetNativeFileDescriptor(env, fileDescriptor);

    if (env->ExceptionCheck()) {
        ALOGE("Can't get FileDescriptor num");
        return (jint)-1;
    }

    if (__builtin_available(android 33, *)) {
        int res = android_untag_socket(userFd);
        if (res < 0) return (jint)-errno;

        return (jint)res;
        ALOGE("android_untag_socket is not available before sdk 33");
    }

    ALOGE("android_untag_socket is not available before sdk 33");
    return (jint)-1;
}

static const JNINativeMethod gQTagUidMethods[] = {
    { "native_tagSocketFd", "(Ljava/io/FileDescriptor;II)I", (void*)tagSocketFd},
    { "native_untagSocketFd", "(Ljava/io/FileDescriptor;)I", (void*)untagSocketFd},
};

int register_android_server_NetworkManagementSocketTagger(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/NetworkManagementSocketTagger",
            gQTagUidMethods, NELEM(gQTagUidMethods));
}

};
