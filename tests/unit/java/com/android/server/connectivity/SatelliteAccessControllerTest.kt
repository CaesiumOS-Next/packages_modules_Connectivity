/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.connectivity

import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import com.android.server.ConnectivityService
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import java.util.concurrent.Executor
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class SatelliteAccessControllerTest {
    private var mPackageManager: PackageManager? = null
    private var mSatelliteAccessController: SatelliteAccessController? = null
    private var mRoleHolderChangedListener: OnRoleHoldersChangedListener? = null
    private var mRoleManager: SatelliteAccessController.RoleManagerAdapter? = null
    var mCallback: ConnectivityService.OnUpdateSatelliteNetworkPreferenceUids? = null
    @Before
    @Throws(PackageManager.NameNotFoundException::class)
    fun setup() {
        val context = Mockito.mock(Context::class.java)
        mPackageManager = Mockito.mock(PackageManager::class.java)
        mRoleManager = Mockito.mock(SatelliteAccessController.RoleManagerAdapter::class.java)
        mCallback = Mockito.mock(
            ConnectivityService.OnUpdateSatelliteNetworkPreferenceUids::class.java)
        Mockito.doReturn(mPackageManager).`when`(context)?.packageManager
        Mockito.doReturn(PackageManager.PERMISSION_GRANTED)
            .`when`(mPackageManager)
            ?.checkPermission(
                "android.permission.SATELLITE_COMMUNICATION", DEFAULT_MESSAGING_APP1)
        Mockito.doReturn(PackageManager.PERMISSION_GRANTED)
            .`when`(mPackageManager)
            ?.checkPermission(
                "android.permission.SATELLITE_COMMUNICATION", DEFAULT_MESSAGING_APP2)

        // Initialise default message application package1
        val applicationInfo1 = ApplicationInfo()
        applicationInfo1.uid = DEFAULT_MESSAGING_APP1_UID
        Mockito.doReturn(applicationInfo1)
            .`when`(mPackageManager)
            ?.getApplicationInfo(ArgumentMatchers.eq(DEFAULT_MESSAGING_APP1),
                ArgumentMatchers.anyInt())

        // Initialise default message application package2
        val applicationInfo2 = ApplicationInfo()
        applicationInfo2.uid = DEFAULT_MESSAGING_APP2_UID
        Mockito.doReturn(applicationInfo2)
            .`when`(mPackageManager)
            ?.getApplicationInfo(ArgumentMatchers.eq(DEFAULT_MESSAGING_APP2),
                ArgumentMatchers.anyInt())
        Mockito.doAnswer(Answer<Void?> { invocation: InvocationOnMock ->
            mRoleHolderChangedListener = invocation.arguments[1] as OnRoleHoldersChangedListener
            null
        }).`when`(mRoleManager)?.addOnRoleHoldersChangedListenerAsUser(
            ArgumentMatchers.any(Executor::class.java),
            ArgumentMatchers.any(OnRoleHoldersChangedListener::class.java),
            ArgumentMatchers.any(UserHandle::class.java))
        mSatelliteAccessController = SatelliteAccessController(context, mRoleManager, mCallback)
    }

    @Test
    fun test_OnRoleSmsChanged_SatellitePreferredUid_Changed() {
        Mockito.doReturn(listOf<Any>()).`when`(mRoleManager)?.getRoleHolders(
            ArgumentMatchers.eq(RoleManager.ROLE_SMS))
        Mockito.verify(mCallback, Mockito.never())?.updateSatelliteNetworkPreferenceUids()

        // check DEFAULT_MESSAGING_APP1 is available as satellite network preferred uid
        var dmas: MutableList<String?> = ArrayList()
        dmas.add(DEFAULT_MESSAGING_APP1)
        Mockito.doReturn(dmas).`when`(mRoleManager)?.getRoleHolders(
            ArgumentMatchers.eq(RoleManager.ROLE_SMS))
        mSatelliteAccessController?.onRoleSmsChanged()
        mSatelliteAccessController
            ?.satelliteNetworkPreferredUids?.let {
                Assert.assertTrue(
                it.contains(DEFAULT_MESSAGING_APP1_UID))
            }
        mSatelliteAccessController
            ?.satelliteNetworkPreferredUids?.let {
                Assert.assertFalse(
                it.contains(DEFAULT_MESSAGING_APP2_UID))
            }
        // Check Callback triggered
        Mockito.verify(mCallback)?.updateSatelliteNetworkPreferenceUids()

        // check DEFAULT_MESSAGING_APP1 and DEFAULT_MESSAGING_APP2 is available
        // as satellite network preferred uid
        dmas = ArrayList()
        dmas.add(DEFAULT_MESSAGING_APP1)
        dmas.add(DEFAULT_MESSAGING_APP2)
        Mockito.doReturn(dmas).`when`(mRoleManager)?.getRoleHolders(
            ArgumentMatchers.eq(RoleManager.ROLE_SMS))
        mSatelliteAccessController?.onRoleSmsChanged()
        mSatelliteAccessController
            ?.satelliteNetworkPreferredUids?.let {
                Assert.assertTrue(
                it.contains(DEFAULT_MESSAGING_APP1_UID))
            }
        mSatelliteAccessController
            ?.satelliteNetworkPreferredUids?.let {
                Assert.assertTrue(
                it.contains(DEFAULT_MESSAGING_APP2_UID))
            }
        // Check Callback triggered
        Mockito.verify(mCallback, Mockito.times(2))
            ?.updateSatelliteNetworkPreferenceUids()

        // check no uid is available as satellite network preferred uid
        dmas = ArrayList()
        Mockito.doReturn(dmas).`when`(mRoleManager)
            ?.getRoleHolders(ArgumentMatchers.eq(RoleManager.ROLE_SMS))
        mSatelliteAccessController?.onRoleSmsChanged()
        mSatelliteAccessController
            ?.satelliteNetworkPreferredUids?.let {
                Assert.assertFalse(
                it.contains(DEFAULT_MESSAGING_APP1_UID))
            }
        mSatelliteAccessController
            ?.satelliteNetworkPreferredUids?.let {
                Assert.assertFalse(
                it.contains(DEFAULT_MESSAGING_APP2_UID))
            }
        Mockito.verify(mCallback, Mockito.times(3))
            ?.updateSatelliteNetworkPreferenceUids()

        // No Change received at OnRoleSmsChanged, check callback not triggered
        dmas = ArrayList()
        Mockito.doReturn(dmas).`when`(mRoleManager)?.getRoleHolders(
            ArgumentMatchers.eq(RoleManager.ROLE_SMS))
        mSatelliteAccessController?.onRoleSmsChanged()
        Mockito.verify(mCallback, Mockito.times(3))
            ?.updateSatelliteNetworkPreferenceUids()
    }

    @Test
    fun test_OnRoleSmsChanged_NoSatelliteCommunicationPermission() {
        Mockito.doReturn(listOf<Any>()).`when`(mRoleManager)?.getRoleHolders(
            ArgumentMatchers.eq(RoleManager.ROLE_SMS))
        Mockito.verify(mCallback, Mockito.never())?.updateSatelliteNetworkPreferenceUids()
        Mockito.doReturn(PackageManager.PERMISSION_DENIED)
            .`when`(mPackageManager)
            ?.checkPermission(
                "android.permission.SATELLITE_COMMUNICATION", DEFAULT_MESSAGING_APP1)

        // check DEFAULT_MESSAGING_APP1 is not available as satellite network preferred uid
        // since satellite communication permission not available.
        val dmas: MutableList<String> = ArrayList()
        dmas.add(DEFAULT_MESSAGING_APP1)
        Mockito.doReturn(dmas).`when`(mRoleManager)?.getRoleHolders(
            ArgumentMatchers.eq(RoleManager.ROLE_SMS))
        mSatelliteAccessController?.onRoleSmsChanged()
        mSatelliteAccessController
            ?.satelliteNetworkPreferredUids?.let {
                Assert.assertFalse(
                it.contains(DEFAULT_MESSAGING_APP1_UID))
            }
        Mockito.verify(mCallback, Mockito.never())?.updateSatelliteNetworkPreferenceUids()
    }

    companion object {
        private const val DEFAULT_MESSAGING_APP1 = "default_messaging_app_1"
        private const val DEFAULT_MESSAGING_APP2 = "default_messaging_app_2"
        private const val DEFAULT_MESSAGING_APP1_UID = 1234
        private const val DEFAULT_MESSAGING_APP2_UID = 5678
    }
}
