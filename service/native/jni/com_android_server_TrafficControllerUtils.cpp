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

#define LOG_TAG "TrafficControllerJni"

#include <aidl/android/net/INetd.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <net/if.h>
#include <vector>

#include "TrafficController.h"
#include "bpf_shared.h"
#include "utils/Log.h"

using android::net::TrafficController;
using android::netdutils::Status;

using UidOwnerMatchType::PENALTY_BOX_MATCH;
using UidOwnerMatchType::HAPPY_BOX_MATCH;

static android::net::TrafficController mTc;

namespace android {

static void native_init(JNIEnv* env, jobject clazz) {
  Status status = mTc.start();
   if (!isOk(status)) {
    ALOGE("%s failed", __func__);
  }
}

static jint native_addNaughtyApp (JNIEnv* env, jobject clazz, jint uid) {
  std::vector<uint32_t> appUids = {static_cast<uint32_t>(abs(uid))};
  Status status = mTc.updateUidOwnerMap(appUids, PENALTY_BOX_MATCH,
      TrafficController::IptOp::IptOpInsert);
  if (!isOk(status)) {
    ALOGE("%s failed, errer code = %d", __func__, status.code());
  }
  return (jint)status.code();
}

static jint native_removeNaughtyApp (JNIEnv* env, jobject clazz, jint uid) {
  std::vector<uint32_t> appUids = {static_cast<uint32_t>(abs(uid))};
  Status status = mTc.updateUidOwnerMap(appUids, PENALTY_BOX_MATCH,
      TrafficController::IptOp::IptOpDelete);
  if (!isOk(status)) {
    ALOGE("%s failed, errer code = %d", __func__, status.code());
  }
  return (jint)status.code();
}

static jint native_addNiceApp (JNIEnv* env, jobject clazz, jint uid) {
  std::vector<uint32_t> appUids = {static_cast<uint32_t>(abs(uid))};
  Status status = mTc.updateUidOwnerMap(appUids, HAPPY_BOX_MATCH,
      TrafficController::IptOp::IptOpInsert);
  if (!isOk(status)) {
    ALOGE("%s failed, errer code = %d", __func__, status.code());
  }
  return (jint)status.code();
}

static jint native_removeNiceApp (JNIEnv* env, jobject clazz, jint uid) {
  std::vector<uint32_t> appUids = {static_cast<uint32_t>(abs(uid))};
  Status status = mTc.updateUidOwnerMap(appUids, HAPPY_BOX_MATCH,
      TrafficController::IptOp::IptOpDelete);
  if (!isOk(status)) {
    ALOGD("%s failed, errer code = %d", __func__, status.code());
  }
  return (jint)status.code();
}

static jint native_setChildChain (JNIEnv* env, jobject clazz, jint childChain, jboolean enable) {
  auto chain = static_cast<ChildChain>(childChain);
  int res = mTc.toggleUidOwnerMap(chain, enable);
  if (!res) {
    ALOGE("%s failed", __func__);
  }
  return (jint)res;
}

static jint native_replaceUidChain (JNIEnv* env, jobject clazz, jstring name, jboolean isAllowlist,
                                jintArray jUids) {
    const char* chainName = env->GetStringUTFChars(name, nullptr);
    int* uids = env->GetIntArrayElements(jUids, 0);
    int numUids = env->GetArrayLength(jUids);
    std::vector<int32_t> uidsToReplace;
    for (int i = 0; i < numUids; i++) {
        uidsToReplace.push_back(static_cast<int32_t>(uids[i]));
    }

    int res = mTc.replaceUidOwnerMap(chainName, isAllowlist, uidsToReplace);
    env->ReleaseStringUTFChars(name, chainName);
    if (!res) {
      ALOGE("%s failed", __func__);
    }
    return (jint)res;
}

static FirewallType getFirewallType(ChildChain chain) {
    switch (chain) {
        case DOZABLE:
            return ALLOWLIST;
        case STANDBY:
            return DENYLIST;
        case POWERSAVE:
            return ALLOWLIST;
        case RESTRICTED:
            return ALLOWLIST;
        case NONE:
        default:
            return DENYLIST;
    }
}

static jint native_setUidRule (JNIEnv* env, jobject clazz, jint childChain, jint uid,
                          jint firewallRule) {
    auto chain = static_cast<ChildChain>(childChain);
    auto rule = static_cast<FirewallRule>(firewallRule);
    FirewallType fType = getFirewallType(chain);

    int res = mTc.changeUidOwnerRule(chain, uid, rule, fType);
    if (!res) {
      ALOGE("%s failed", __func__);
    }
    return (jint)res;
}

static jint native_addUidInterfaceRules (JNIEnv* env, jobject clazz, jstring ifName,
                                    jintArray jUids) {
    const char* interfaceName = env->GetStringUTFChars(ifName, nullptr);
    int ifIndex = if_nametoindex(interfaceName);

    int* uids = env->GetIntArrayElements(jUids, 0);
    int numUids = env->GetArrayLength(jUids);

    std::vector<int32_t> uidsToAdd;
    for (int i = 0; i < numUids; i++) {
        uidsToAdd.push_back(static_cast<int32_t>(uids[i]));
    }

    Status status = mTc.addUidInterfaceRules(ifIndex, uidsToAdd);
    env->ReleaseIntArrayElements(jUids, uids, 0);
    env->ReleaseStringUTFChars(ifName, interfaceName);
    if (!isOk(status)) {
        ALOGE("%s failed", __func__);
    }
    return (jint)status.code();
}

static jint native_removeUidInterfaceRules (JNIEnv* env, jobject clazz, jintArray jUids) {
    int* uids = env->GetIntArrayElements(jUids, 0);
    int numUids = env->GetArrayLength(jUids);

    std::vector<int32_t> uidsToDelete;
    for (int i = 0; i < numUids; i++) {
        uidsToDelete.push_back(static_cast<int32_t>(uids[i]));
    }

    Status status = mTc.removeUidInterfaceRules(uidsToDelete);
    env->ReleaseIntArrayElements(jUids, uids, 0);
    if (!isOk(status)) {
        ALOGE("%s failed", __func__);
    }
    return (jint)status.code();
}

static jint native_swapActiveStatsMap (JNIEnv* env, jobject clazz) {
    Status status = mTc.swapActiveStatsMap();
    if (!isOk(status)) {
        ALOGD("%s failed", __func__);
    }
    return (jint)status.code();
}

std::vector<uid_t> intsToUids(const std::vector<int32_t>& intUids) {
    return {begin(intUids), end(intUids)};
}

static void native_setPermissionForUids (JNIEnv* env, jobject clazz, jint permission,
                                      jintArray jUids) {
    int* uids = env->GetIntArrayElements(jUids, 0);
    int numUids = env->GetArrayLength(jUids);

    std::vector<int32_t> intUids;
    for (int i = 0; i < numUids; i++) {
        intUids.push_back(static_cast<int32_t>(uids[i]));
    }
    mTc.setPermissionForUids(permission, intsToUids(intUids));
}

static jint native_setCounterSet (JNIEnv* env, jobject clazz, jint setNum, jint uid) {
    uid_t callingUid = getuid();
    int res = mTc.setCounterSet(setNum, (uid_t)uid, callingUid);
    if (!res) {
      ALOGE("%s failed", __func__);
    }
    return (jint)res;
}

static jint native_deleteTagData (JNIEnv* env, jobject clazz, jint tagNum, jint uid) {
    uid_t callingUid = getuid();
    int res = mTc.deleteTagData(tagNum, (uid_t)uid, callingUid);
    if (!res) {
      ALOGE("%s failed", __func__);
    }
    return (jint)res;
}

/*
 * JNI registration.
 */
// clang-format off
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    {"native_init", "()V", (void*) native_init},
    {"native_addNaughtyApp", "(I)I",
    (void*) native_addNaughtyApp},
    {"native_removeNaughtyApp", "(I)I",
    (void*)native_removeNaughtyApp},
    {"native_addNiceApp", "(I)I",
    (void*)native_addNiceApp},
    {"native_removeNiceApp", "(I)I",
    (void*)native_removeNiceApp},
    {"native_setChildChain", "(IZ)I",
    (void*)native_setChildChain},
    {"native_replaceUidChain", "(Ljava/lang/String;Z[I)I",
    (void*)native_replaceUidChain},
    {"native_setUidRule", "(III)I",
    (void*)native_setUidRule},
    {"native_addUidInterfaceRules", "(Ljava/lang/String;[I)I",
    (void*)native_addUidInterfaceRules},
    {"native_removeUidInterfaceRules", "([I)I",
    (void*)native_removeUidInterfaceRules},
    {"native_swapActiveStatsMap", "()I",
    (void*)native_swapActiveStatsMap},
    {"native_setPermissionForUids", "(I[I)V",
    (void*)native_setPermissionForUids},
    {"native_setCounterSet", "(II)I",
    (void*)native_setCounterSet},
    {"native_deleteTagData", "(II)I",
    (void*)native_deleteTagData},
};
// clang-format on

int register_com_android_server_TrafficControllerUtils(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
    "com/android/server/TrafficControllerUtils",
    gMethods, NELEM(gMethods));
}

}; // namespace android
