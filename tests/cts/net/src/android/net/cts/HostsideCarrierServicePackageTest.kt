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
package android.net.cts

import com.android.tradefed.invoker.TestInformation
import com.android.tradefed.targetprep.BuildError
import com.android.tradefed.targetprep.TargetSetupError
import com.android.tradefed.targetprep.suite.SuiteApkInstaller
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import org.junit.Test
import org.junit.runner.RunWith

const val CARRIER_SERVICE_APK = "CtsCarrierServicePackage.apk"
const val CTS_APK = "CtsNetTestCases.apk"
const val TEST_APP_PKG = "android.net.cts"
const val TEST_CLASS_NAME = ".NetworkAgentTest"
const val TEST_METHOD = "testAllowedUids"

@RunWith(DeviceJUnit4ClassRunner::class)
class HostsideCarrierServicePackageTest : BaseHostJUnit4Test() {
    private fun TestInformation.installPackage(apk: String) {
        val userId = device.getCurrentUser()
        val installer = SuiteApkInstaller().apply {
            // Force the apk clean up
            setCleanApk(true)
            addTestFileName(apk)
            setUserId(userId)
            setShouldGrantPermission(true)
            addInstallArg("-t")
        }
        try {
            installer.setUp(this)
        } catch (e: BuildError) {
            throw TargetSetupError(e.message, e, device.deviceDescriptor, e.errorId)
        }
    }

    private fun TestInformation.uninstallPackage(packageName: String) {
        device.uninstallPackage(packageName)
    }

    @Test
    fun testAllowedUidsWithServicePackage() {
        getTestInformation().uninstallPackage(CTS_APK)
        getTestInformation().uninstallPackage(CARRIER_SERVICE_APK)
        getTestInformation().installPackage(CARRIER_SERVICE_APK)
        getTestInformation().installPackage(CTS_APK)
        runDeviceTests(TEST_APP_PKG, TEST_APP_PKG + TEST_CLASS_NAME, TEST_METHOD)
        getTestInformation().uninstallPackage(CARRIER_SERVICE_APK)
    }
}
