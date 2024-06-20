#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import unittest
from mobly import asserts
from utils.assert_utils import UnexpectedBehaviorError, expect_with_retry


class TestAssertUtils(unittest.TestCase):

  def test_successful_predicate(self):
    """Test when the predicate becomes True within retries."""
    call_count = 0

    def predicate():
      nonlocal call_count
      call_count += 1
      return call_count > 2  # True on the third call

    expect_with_retry(predicate, max_retries=5, retry_interval_sec=0)
    asserts.assert_equal(call_count, 3)  # Ensure it was called exactly 3 times

  def test_unsuccessful_predicate(self):
    """Test when the predicate never becomes True."""

    def always_false():
      return False

    with asserts.assert_raises(UnexpectedBehaviorError):
      expect_with_retry(always_false, max_retries=3, retry_interval_sec=0)

  def test_retry_action_not_called(self):
    """Test that the retry_action is not called if the predicate becomes true within retries."""
    retry_action_called = False

    def retry_action():
      nonlocal retry_action_called
      retry_action_called = True

    def predicate():
      return True  # Predicate succeeds immediately

    expect_with_retry(
        predicate,
        retry_action=retry_action,
        max_retries=5,
        retry_interval_sec=0,
    )
    asserts.assert_false(retry_action_called)  # Assert retry_action was NOT called

  def test_retry_action_called(self):
    """Test that the retry_action is executed when provided."""
    retry_action_called = False

    def retry_action():
      nonlocal retry_action_called
      retry_action_called = True

    def predicate():
      return False

    with asserts.assert_raises(UnexpectedBehaviorError):
      expect_with_retry(
          predicate,
          retry_action=retry_action,
          max_retries=2,
          retry_interval_sec=0,
      )
    asserts.assert_true(retry_action_called)
