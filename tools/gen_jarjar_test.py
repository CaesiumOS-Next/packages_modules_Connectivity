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
import glob
import shutil
import unittest


class TestGenJarjar(unittest.TestCase):
    def test_gen_rules(self):
        # dexdump opens jars with follow_symlinks=False; make a copy of the apistubs as it is
        # provided via a symlink in the test directory otherwise.
        stubs_jar = "stubs.jar"
        shutil.copy("framework-connectivity.stubs.module_lib.jar", stubs_jar)
        args = gen_jarjar.parser.parse_args([
            "--jars", "jarjar-rules-generator-testjavalib.jar",
            "--prefix", "jarjar.prefix",
            "--output", "test-output-rules.txt",
            "--apistubs", stubs_jar,
            "--unsupportedapi", "testdata/test-unsupportedappusage.txt",
            "--excludes", "testdata/test-jarjar-excludes.txt",
            "--dexdump", "dexdump",
        ])
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


if __name__ == '__main__':
    # Need verbosity=2 for the test results parser to find results
    unittest.main(verbosity=2)
