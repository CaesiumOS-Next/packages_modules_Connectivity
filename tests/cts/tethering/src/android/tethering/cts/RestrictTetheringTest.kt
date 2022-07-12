/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.tethering.test

import android.Manifest.permission.TETHER_PRIVILEGED
import android.net.TetheringManager
import android.net.TetheringManager.TETHERING_WIFI
import android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED
import android.net.TetheringManager.TetheringRequest
import android.net.cts.util.CtsTetheringUtils
import android.net.cts.util.CtsTetheringUtils.StartTetheringCallback
import android.os.UserManager.DISALLOW_CONFIG_TETHERING

import com.android.bedstead.harrier.BedsteadJUnit4
import com.android.bedstead.harrier.DeviceState
import com.android.bedstead.harrier.annotations.enterprise.PolicyAppliesTest
import com.android.bedstead.harrier.policies.Delegation
import com.android.bedstead.nene.TestApis
import com.android.bedstead.remotedpc.RemoteDpc.DPC_COMPONENT_NAME
import com.android.testutils.runAsShell

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import java.util.concurrent.Executor

const val RETRY_TIMES = 100
const val RETRY_INTERVAL_MS = 50L

@RunWith(BedsteadJUnit4::class)
class RestrictTetheringTest {
    companion object {
        @JvmField @ClassRule @Rule val sDeviceState: DeviceState = DeviceState()

        @JvmField val sContext = TestApis.context().instrumentedContext()
    }

    private val mCtsTetheringUtils by lazy { CtsTetheringUtils(sContext) }
    private val mCallback by lazy { mCtsTetheringUtils.registerTetheringEventCallback() }
    private val mTetheringManager by lazy {
        sContext.getSystemService(TetheringManager::class.java)
    }

    @After
    fun tearDown() {
        mCtsTetheringUtils.unregisterTetheringEventCallback(mCallback)
    }

    @Test
    @PolicyAppliesTest(policy = Delegation::class)
    fun testRestrictTethering() {
        // if tethering is restricted before this test, wifi tethering would not be supported.
        mCallback.assumeWifiTetheringSupported(sContext)
        mCallback.expectNoTetheringActive()
        try {
            // start wifi tethering.
            mCtsTetheringUtils.startWifiTethering(mCallback)

            // wifi tethering should be stopped and tethering is not supported after restricted.
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING)
            mCtsTetheringUtils.expectSoftApDisabled()
            assertFalse(isTetheringSupported())

            // bug: onTetheredInterfacesChanged would not fired if tethering is restricted.
            // TODO: fire the callback and test it here.

            // tethering is not supported and could not be start after restricted.
            val callback = StartTetheringCallback()
            val request = TetheringRequest.Builder(
                    TETHERING_WIFI).setShouldShowEntitlementUi(false).build()
            val executor = Executor() { r -> r.run() }
            mTetheringManager.startTethering(request, executor, callback)
            callback.onTetheringFailed(TETHER_ERROR_UNSUPPORTED)

            // clear the restriction, then tethering could be start successfully.
            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING)
            waitingTetheringSupported()

            startWifiTethering()

            mCtsTetheringUtils.stopWifiTethering(mCallback)
        } finally {
            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING)
            mCtsTetheringUtils.stopAllTethering()
        }
    }

    // TODO: After no onTetheredInterfacesChanged callback bug fix, we could just reuse mCallback
    // to start wifi tethering instead of registering new callback.
    private fun startWifiTethering() {
        val callback = mCtsTetheringUtils.registerTetheringEventCallback()
        try {
            mCtsTetheringUtils.startWifiTethering(callback)
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(callback)
        }
    }

    private fun isTetheringSupported(): Boolean {
        return runAsShell(TETHER_PRIVILEGED) { mTetheringManager.isTetheringSupported() }
    }

    // TODO: use #onSupportedTetheringTypes callback once it expose as API.
    private fun waitingTetheringSupported() {
        for (i in 1..RETRY_TIMES) {
            if (isTetheringSupported()) return

            Thread.sleep(RETRY_INTERVAL_MS)
        }

        fail("Tethering still not be supported after 5 seconds")
    }
}
