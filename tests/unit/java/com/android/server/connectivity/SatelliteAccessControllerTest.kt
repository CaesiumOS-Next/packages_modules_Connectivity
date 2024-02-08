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

import android.Manifest
import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.UserHandle
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEFAULT_MESSAGING_APP1 = "default_messaging_app_1"
private const val DEFAULT_MESSAGING_APP2 = "default_messaging_app_2"
private const val DEFAULT_MESSAGING_APP3 = "default_messaging_app_3"
private const val DEFAULT_MESSAGING_APP4 = "default_messaging_app_4"
private const val DEFAULT_MESSAGING_APP1_UID = 1234
private const val DEFAULT_MESSAGING_APP2_UID = 5678
private const val DEFAULT_MESSAGING_APP3_UID = 9999
private const val DEFAULT_MESSAGING_APP4_UID = 8888

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class SatelliteAccessControllerTest {
    private val context = mock(Context::class.java)
    private val mPackageManager = mock(PackageManager::class.java)
    private val mHandler = mock(Handler::class.java)
    private val mRoleManager =
        mock(SatelliteAccessController.Dependencies::class.java)
    private val mCallback = mock(Consumer::class.java) as Consumer<Set<Int>>
    private val mSatelliteAccessController =
        SatelliteAccessController(context, mRoleManager, mCallback, mHandler)
    private var mRoleHolderChangedListener: OnRoleHoldersChangedListener? = null
    @Before
    @Throws(PackageManager.NameNotFoundException::class)
    fun setup() {
        doReturn(context).`when`(context).createContextAsUser(any(), anyInt())
        doReturn(mPackageManager).`when`(context).packageManager
        doReturn(PackageManager.PERMISSION_GRANTED)
            .`when`(mPackageManager)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, DEFAULT_MESSAGING_APP1)
        doReturn(PackageManager.PERMISSION_GRANTED)
            .`when`(mPackageManager)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, DEFAULT_MESSAGING_APP2)
        doReturn(PackageManager.PERMISSION_GRANTED)
            .`when`(mPackageManager)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, DEFAULT_MESSAGING_APP3)
        doReturn(PackageManager.PERMISSION_GRANTED)
            .`when`(mPackageManager)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, DEFAULT_MESSAGING_APP4)

        // Initialise default message application package1
        val applicationInfo1 = ApplicationInfo()
        applicationInfo1.uid = DEFAULT_MESSAGING_APP1_UID
        doReturn(applicationInfo1)
            .`when`(mPackageManager)
            .getApplicationInfo(eq(DEFAULT_MESSAGING_APP1), anyInt())

        // Initialise default message application package2
        val applicationInfo2 = ApplicationInfo()
        applicationInfo2.uid = DEFAULT_MESSAGING_APP2_UID
        doReturn(applicationInfo2)
            .`when`(mPackageManager)
            .getApplicationInfo(eq(DEFAULT_MESSAGING_APP2), anyInt())

        // Initialise default message application package2
        val applicationInfo3 = ApplicationInfo()
        applicationInfo3.uid = DEFAULT_MESSAGING_APP3_UID
        doReturn(applicationInfo3)
            .`when`(mPackageManager)
            .getApplicationInfo(eq(DEFAULT_MESSAGING_APP3), anyInt())

        // Initialise default message application package2
        val applicationInfo4 = ApplicationInfo()
        applicationInfo4.uid = DEFAULT_MESSAGING_APP4_UID
        doReturn(applicationInfo4)
            .`when`(mPackageManager)
            .getApplicationInfo(eq(DEFAULT_MESSAGING_APP4), anyInt())

        // Get registered listener using captor
        val listenerCaptor = ArgumentCaptor.forClass(
            OnRoleHoldersChangedListener::class.java
        )
        mSatelliteAccessController.start()
        verify(mRoleManager).addOnRoleHoldersChangedListenerAsUser(
            any(Executor::class.java), listenerCaptor.capture(), any(UserHandle::class.java))
        mRoleHolderChangedListener = listenerCaptor.value
    }

    @Test
    fun test_onRoleHoldersChanged_SatelliteFallbackUid_Changed_SingleUser() {
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        val satelliteNetworkFallbackUidsCaptor =
            ArgumentCaptor.forClass(Set::class.java) as ArgumentCaptor<Set<Int>>
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, never()).accept(any())

        // check DEFAULT_MESSAGING_APP1 is available as satellite network fallback uid
        doReturn(listOf(DEFAULT_MESSAGING_APP1))
            .`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback).accept(satelliteNetworkFallbackUidsCaptor.capture())
        var satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(1, satelliteNetworkFallbackUids.size)
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))

        // check DEFAULT_MESSAGING_APP1 and DEFAULT_MESSAGING_APP2 is available
        // as satellite network Fallback uid
        val dmas: MutableList<String> = ArrayList()
        dmas.add(DEFAULT_MESSAGING_APP1)
        dmas.add(DEFAULT_MESSAGING_APP2)
        doReturn(dmas).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, times(2))
            .accept(satelliteNetworkFallbackUidsCaptor.capture())
        satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(2, satelliteNetworkFallbackUids.size)
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))

        // check no uid is available as satellite network fallback uid
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, times(3))
            .accept(satelliteNetworkFallbackUidsCaptor.capture())
        satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(0, satelliteNetworkFallbackUids.size)
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))

        // No Change received at OnRoleSmsChanged, check callback not triggered
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, times(3))
            .accept(satelliteNetworkFallbackUidsCaptor.capture())
    }

    @Test
    fun test_onRoleHoldersChanged_NoSatelliteCommunicationPermission() {
        doReturn(listOf<Any>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, never()).accept(any())

        // check DEFAULT_MESSAGING_APP1 is not available as satellite network fallback uid
        // since satellite communication permission not available.
        doReturn(PackageManager.PERMISSION_DENIED)
            .`when`(mPackageManager)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, DEFAULT_MESSAGING_APP1)
        doReturn(listOf(DEFAULT_MESSAGING_APP1))
            .`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, never()).accept(any())
    }

    @Test
    fun test_onRoleHoldersChanged_RoleSms_NotAvailable() {
        doReturn(listOf(DEFAULT_MESSAGING_APP1))
            .`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_BROWSER,
            UserHandle.of(1))
        verify(mCallback, never()).accept(any())
    }

    @Test
    fun test_onRoleHoldersChanged_SatelliteNetworkFallbackUid_Changed_multiUser() {
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        val satelliteNetworkFallbackUidsCaptor =
            ArgumentCaptor.forClass(Set::class.java) as ArgumentCaptor<Set<Int>>
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, never()).accept(any())

        // check DEFAULT_MESSAGING_APP1 is available as satellite network fallback user id1
        doReturn(listOf(DEFAULT_MESSAGING_APP1))
            .`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback).accept(satelliteNetworkFallbackUidsCaptor.capture())
        var satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(1, satelliteNetworkFallbackUids.size)
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))

        // check DEFAULT_MESSAGING_APP1 and DEFAULT_MESSAGING_APP2 is available
        // as satellite network fallback user id1
        val dmasUser1: MutableList<String> = ArrayList()
        dmasUser1.add(DEFAULT_MESSAGING_APP1)
        dmasUser1.add(DEFAULT_MESSAGING_APP2)
        doReturn(dmasUser1).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, times(2))
            .accept(satelliteNetworkFallbackUidsCaptor.capture())
        satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(2, satelliteNetworkFallbackUids.size)
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))

        // check DEFAULT_MESSAGING_APP1 and DEFAULT_MESSAGING_APP2 is available
        // as satellite network fallback user id2
        val dmasUser2: MutableList<String> = ArrayList()
        dmasUser2.add(DEFAULT_MESSAGING_APP3)
        dmasUser2.add(DEFAULT_MESSAGING_APP4)
        doReturn(dmasUser2).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(2))
        verify(mCallback, times(3))
            .accept(satelliteNetworkFallbackUidsCaptor.capture())
        satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(4, satelliteNetworkFallbackUids.size)
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP3_UID))
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP4_UID))

        // check no uid is available as satellite network fallback user id1
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(1))
        verify(mCallback, times(4))
            .accept(satelliteNetworkFallbackUidsCaptor.capture())
        satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(2, satelliteNetworkFallbackUids.size)
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP3_UID))
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP4_UID))

        // check DEFAULT_MESSAGING_APP3 is available as satellite network fallback user id2
        doReturn(listOf(DEFAULT_MESSAGING_APP3))
            .`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(2))
        verify(mCallback, times(5))
            .accept(satelliteNetworkFallbackUidsCaptor.capture())
        satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(1, satelliteNetworkFallbackUids.size)
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))
        assertTrue(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP3_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP4_UID))

        // check no uid is available as satellite network fallback user id2
        doReturn(listOf<String>()).`when`(mRoleManager).getRoleHolders(RoleManager.ROLE_SMS)
        mRoleHolderChangedListener!!.onRoleHoldersChanged(RoleManager.ROLE_SMS,
            UserHandle.of(2))
        verify(mCallback, times(6))
            .accept(satelliteNetworkFallbackUidsCaptor.capture())
        satelliteNetworkFallbackUids = satelliteNetworkFallbackUidsCaptor.value
        assertEquals(0, satelliteNetworkFallbackUids.size)
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP1_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP2_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP3_UID))
        assertFalse(satelliteNetworkFallbackUids.contains(DEFAULT_MESSAGING_APP4_UID))
    }
}
