#  Copyright (C) 2022 The Android Open Source Project
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

import gen_jarjar
import unittest


class TestGenJarjar(unittest.TestCase):
    @staticmethod
    def _make_args(target_jar, *extra_args):
        args = [
            '--jars', target_jar,
            '--prefix', 'jarjar.prefix',
            '--output', 'test-output-rules.txt',
            '--apistubs', 'framework-connectivity.stubs.module_lib.jar',
            '--unsupportedapi', 'testdata/test-unsupportedappusage.txt',
            '--excludes', 'testdata/test-jarjar-excludes.txt',
        ]
        args.extend(extra_args)
        return gen_jarjar.parse_arguments(args)

    def test_gen_rules(self):
        args = self._make_args('jarjar-rules-generator-testjavalib.jar')
        gen_jarjar.make_jarjar_rules(args)

        with open(args.output) as out:
            lines = out.readlines()

        self.assertListEqual([
            'rule test.utils.TestUtilClass jarjar.prefix.@0\n',
            'rule test.utils.TestUtilClassTest jarjar.prefix.@0\n',
            'rule test.utils.TestUtilClassTest$* jarjar.prefix.@0\n',
            'rule test.utils.TestUtilClass$TestInnerClass jarjar.prefix.@0\n',
            'rule test.utils.TestUtilClass$TestInnerClassTest jarjar.prefix.@0\n',
            'rule test.utils.TestUtilClass$TestInnerClassTest$* jarjar.prefix.@0\n'], lines)

    def test_disallowed_class(self):
        args = self._make_args('jarjar-rules-generator-testdisallowedclasslib.jar',
                               '--disallow-jarjar-api-packages')
        self.assertRaisesRegex(Exception, 'Classes \\[\'android.net.DisallowedApiPackageClass\'\\] '
                                          'are not allowed to be jarjared.*',
                               lambda: gen_jarjar.make_jarjar_rules(args))

    def test_disallowed_class_allowlisted(self):
        args = self._make_args('jarjar-rules-generator-testdisallowedclasslib.jar',
                               '--disallow-jarjar-api-packages',
                               '--disallow-exceptions', 'testdata/test-disallow-exceptions.txt')
        gen_jarjar.make_jarjar_rules(args)
        with open(args.output) as out:
            lines = out.readlines()

        self.assertListEqual([
            'rule android.net.DisallowedApiPackageClass jarjar.prefix.@0\n',
            'rule android.net.DisallowedApiPackageClassTest jarjar.prefix.@0\n',
            'rule android.net.DisallowedApiPackageClassTest$* jarjar.prefix.@0\n',
        ], lines)


if __name__ == '__main__':
    # Need verbosity=2 for the test results parser to find results
    unittest.main(verbosity=2)
