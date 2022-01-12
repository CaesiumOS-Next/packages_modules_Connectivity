/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * bpf_existence_test.cpp - checks that the device runs expected BPF programs
 */

#include <cstdint>
#include <string>
#include <vector>

#include <android-base/strings.h>
#include <android-base/properties.h>
#include <android-modules-utils/sdk_level.h>

#include <gtest/gtest.h>

using std::vector;
using std::string;

using android::modules::sdklevel::IsAtLeastR;
using android::modules::sdklevel::IsAtLeastS;
using android::modules::sdklevel::IsAtLeastT;

class BpfExistenceTest : public ::testing::Test {
};

static const vector<string> INTRODUCED_R = {
    "/sys/fs/bpf/prog_offload_schedcls_ingress_tether_ether",
    "/sys/fs/bpf/prog_offload_schedcls_ingress_tether_rawip",
};

static const vector<string> INTRODUCED_S = {
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream4_ether",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream4_rawip",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream6_ether",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream6_rawip",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_upstream4_ether",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_upstream4_rawip",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_upstream6_ether",
    "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_upstream6_rawip",
};

static const vector<string> REMOVED_S = {
    "/sys/fs/bpf/prog_offload_schedcls_ingress_tether_ether",
    "/sys/fs/bpf/prog_offload_schedcls_ingress_tether_rawip",
};

static const vector<string> INTRODUCED_T = {
};

static const vector<string> REMOVED_T = {
};

void addAll(vector<string>* a, const vector<string>& b) {
    a->insert(std::end(*a), std::begin(b), std::end(b));
}

void removeAll(vector<string>* a, const vector<string> b) {
    for (const auto& toRemove : b) {
        auto iter = std::find(a->begin(), a->end(), toRemove);
        while (iter != a->end()) {
            a->erase(iter);
            iter = std::find(a->begin(), a->end(), toRemove);
        }
    }
}

vector<string> getExpectedFiles() {
    vector<string> out;

    if (!IsAtLeastR()) return out;
    addAll(&out, INTRODUCED_R);

    if (!IsAtLeastS()) return out;
    addAll(&out, INTRODUCED_S);
    removeAll(&out, REMOVED_S);

    // Nothing introduced or removed in SCV2.

    if (!IsAtLeastT()) return out;
    addAll(&out, INTRODUCED_T);
    removeAll(&out, REMOVED_T);

    return out;
}

vector<string> getUnexpectedFiles() {
    vector<string> out;

    if (!IsAtLeastS()) return out;
    addAll(&out, REMOVED_S);

    // Nothing introduced or removed in SCV2.

    if (!IsAtLeastT()) return out;
    addAll(&out, REMOVED_T);

    return out;
}


void checkFiles() {
    const vector<string> mustExist = getExpectedFiles();
    const vector<string> mustNotExist = getUnexpectedFiles();

    for (const auto& file : mustExist) {
        EXPECT_EQ(0, access(file.c_str(), R_OK)) << file << " does not exist";
    }
    for (const auto& file : mustNotExist) {
        int ret = access(file.c_str(), R_OK);
        int err = errno;
        EXPECT_EQ(-1, ret) << file << " unexpectedly exists";
        if (ret == -1) {
            EXPECT_EQ(ENOENT, err) << " accessing " << file << " failed with errno " << err;
        }
    }
}

TEST_F(BpfExistenceTest, TestPrograms) {
    // Pre-flight check to ensure test has been updated.
    uint64_t buildVersionSdk = android::base::GetUintProperty<uint64_t>("ro.build.version.sdk", 0);
    ASSERT_NE(0, buildVersionSdk) << "Unable to determine device SDK version";
    if (buildVersionSdk > 33 && buildVersionSdk != 10000) {
            FAIL() << "Unknown OS version " << buildVersionSdk << ", please update this test";
    }

    ASSERT_EQ(0, getuid()) << "This test must run as root.";

    checkFiles();
}
