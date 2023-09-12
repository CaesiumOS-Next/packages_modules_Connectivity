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

#include <gtest/gtest.h>
#include <private/android_filesystem_config.h>

#define BPF_MAP_MAKE_VISIBLE_FOR_TESTING
#include "DnsBpfHelper.h"

using namespace android::bpf;  // NOLINT(google-build-using-namespace): exempted

namespace android {
namespace net {

constexpr int TEST_MAP_SIZE = 2;

constexpr int UNBLOCKED = 0;
constexpr int BLOCKED = 1;

#define ASSERT_VALID(x) ASSERT_TRUE((x).isValid())

class DnsBpfHelperTest : public ::testing::Test {
 protected:
  DnsBpfHelper mDBHelper;
  BpfMap<uint32_t, uint32_t> mFakeConfigurationMap;
  BpfMap<uint32_t, UidOwnerValue> mFakeUidOwnerMap;

  void SetUp() {
    mFakeConfigurationMap.resetMap(BPF_MAP_TYPE_ARRAY, CONFIGURATION_MAP_SIZE);
    ASSERT_VALID(mFakeConfigurationMap);

    mFakeUidOwnerMap.resetMap(BPF_MAP_TYPE_HASH, TEST_MAP_SIZE);
    ASSERT_VALID(mFakeUidOwnerMap);

    mDBHelper.mConfigurationMap = mFakeConfigurationMap;
    ASSERT_VALID(mDBHelper.mConfigurationMap);
    mDBHelper.mUidOwnerMap = mFakeUidOwnerMap;
    ASSERT_VALID(mDBHelper.mUidOwnerMap);
  }

  void ResetAllMaps() {
    mDBHelper.mConfigurationMap.reset();
    mDBHelper.mUidOwnerMap.reset();
  }
};

TEST_F(DnsBpfHelperTest, IsUidNetworkingBlocked) {
  struct TestConfig {
    const uid_t uid;
    const uint32_t enabledRules;
    const uint32_t uidRules;
    const int expectedResult;
    std::string asParameters() const {
      return fmt::format(
          "uid: {}, enabledRules: {}, uidRules: {}, expectedResult: {}",
          uid, enabledRules, uidRules, expectedResult);
    }
  } testConfigs[]{
    // clang-format off
    //   No rule enabled:
    // uid,         enabledRules,                  uidRules,                      expectedResult
    {AID_APP_START, NO_MATCH,                      NO_MATCH,                      UNBLOCKED},

    //   An allowlist rule:
    {AID_APP_START, NO_MATCH,                      DOZABLE_MATCH,                 UNBLOCKED},
    {AID_APP_START, DOZABLE_MATCH,                 NO_MATCH,                      BLOCKED},
    {AID_APP_START, DOZABLE_MATCH,                 DOZABLE_MATCH,                 UNBLOCKED},
    //   A denylist rule
    {AID_APP_START, NO_MATCH,                      STANDBY_MATCH,                 UNBLOCKED},
    {AID_APP_START, STANDBY_MATCH,                 NO_MATCH,                      UNBLOCKED},
    {AID_APP_START, STANDBY_MATCH,                 STANDBY_MATCH,                 BLOCKED},

    //   Multiple rules enabled:
    //     Match only part of the enabled allowlist rules.
    {AID_APP_START, DOZABLE_MATCH|POWERSAVE_MATCH, DOZABLE_MATCH,                 BLOCKED},
    {AID_APP_START, DOZABLE_MATCH|POWERSAVE_MATCH, POWERSAVE_MATCH,               BLOCKED},
    //     Match all of the enabled allowlist rules.
    {AID_APP_START, DOZABLE_MATCH|POWERSAVE_MATCH, DOZABLE_MATCH|POWERSAVE_MATCH, UNBLOCKED},
    //     Match allowlist.
    {AID_APP_START, DOZABLE_MATCH|STANDBY_MATCH,   DOZABLE_MATCH,                 UNBLOCKED},
    //     Match no rule.
    {AID_APP_START, DOZABLE_MATCH|STANDBY_MATCH,   NO_MATCH,                      BLOCKED},
    {AID_APP_START, DOZABLE_MATCH|POWERSAVE_MATCH, NO_MATCH,                      BLOCKED},

    // System UID: always unblocked.
    {AID_SYSTEM,    NO_MATCH,                      NO_MATCH,                      UNBLOCKED},
    {AID_SYSTEM,    NO_MATCH,                      DOZABLE_MATCH,                 UNBLOCKED},
    {AID_SYSTEM,    DOZABLE_MATCH,                 NO_MATCH,                      UNBLOCKED},
    {AID_SYSTEM,    DOZABLE_MATCH,                 DOZABLE_MATCH,                 UNBLOCKED},
    {AID_SYSTEM,    NO_MATCH,                      STANDBY_MATCH,                 UNBLOCKED},
    {AID_SYSTEM,    STANDBY_MATCH,                 NO_MATCH,                      UNBLOCKED},
    {AID_SYSTEM,    STANDBY_MATCH,                 STANDBY_MATCH,                 UNBLOCKED},
    {AID_SYSTEM,    DOZABLE_MATCH|POWERSAVE_MATCH, DOZABLE_MATCH,                 UNBLOCKED},
    {AID_SYSTEM,    DOZABLE_MATCH|POWERSAVE_MATCH, POWERSAVE_MATCH,               UNBLOCKED},
    {AID_SYSTEM,    DOZABLE_MATCH|POWERSAVE_MATCH, DOZABLE_MATCH|POWERSAVE_MATCH, UNBLOCKED},
    {AID_SYSTEM,    DOZABLE_MATCH|STANDBY_MATCH,   DOZABLE_MATCH              ,   UNBLOCKED},
    {AID_SYSTEM,    DOZABLE_MATCH|STANDBY_MATCH,   NO_MATCH,                      UNBLOCKED},
    {AID_SYSTEM,    DOZABLE_MATCH|POWERSAVE_MATCH, NO_MATCH,                      UNBLOCKED},
    // clang-format on
  };

  for (const auto& config : testConfigs) {
    SCOPED_TRACE(config.asParameters());

    // Setup maps.
    EXPECT_RESULT_OK(mFakeConfigurationMap.writeValue(UID_RULES_CONFIGURATION_KEY,
                                                      config.enabledRules, BPF_EXIST));
    EXPECT_RESULT_OK(mFakeUidOwnerMap.writeValue(config.uid, {.iif = 0, .rule = config.uidRules},
                                                 BPF_ANY));

    // Verify the function.
    EXPECT_EQ(config.expectedResult,
      mDBHelper.isUidNetworkingBlocked(config.uid, /*metered=*/false));
  }
}

TEST_F(DnsBpfHelperTest, IsUidNetworkingBlocked_uninitialized) {
  ResetAllMaps();

  EXPECT_EQ(-ENOENT, mDBHelper.isUidNetworkingBlocked(AID_APP_START, /*metered=*/false));
  EXPECT_EQ(UNBLOCKED, mDBHelper.isUidNetworkingBlocked(AID_SYSTEM, /*metered=*/false));
}

}  // namespace net
}  // namespace android
