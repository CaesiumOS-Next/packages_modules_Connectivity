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

package com.android.networkstack.tethering;

import static com.android.networkstack.tethering.OffloadHardwareInterface.OFFLOAD_HAL_VERSION_HIDL_1_0;
import static com.android.networkstack.tethering.OffloadHardwareInterface.OFFLOAD_HAL_VERSION_HIDL_1_1;
import static com.android.networkstack.tethering.OffloadHardwareInterface.OFFLOAD_HAL_VERSION_NONE;
import static com.android.networkstack.tethering.util.TetheringUtils.uint16;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.tetheroffload.config.V1_0.IOffloadConfig;
import android.hardware.tetheroffload.control.V1_0.IOffloadControl;
import android.hardware.tetheroffload.control.V1_0.NatTimeoutUpdate;
import android.hardware.tetheroffload.control.V1_0.NetworkProtocol;
import android.hardware.tetheroffload.control.V1_1.ITetheringOffloadCallback;
import android.hardware.tetheroffload.control.V1_1.OffloadCallbackEvent;
import android.hidl.manager.V1_0.IServiceManager;
import android.os.Handler;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.system.OsConstants;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.SharedLog;
import com.android.networkstack.tethering.OffloadHardwareInterface.ForwardedStats;
import com.android.networkstack.tethering.OffloadHardwareInterface.OffloadHalCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class OffloadHalHidlImplTest {
    private static final String RMNET0 = "test_rmnet_data0";

    private final SharedLog mLog = new SharedLog("test");
    private final TestLooper mTestLooper = new TestLooper();

    @Mock private IServiceManager mServiceManagerMock;
    private OffloadHalHidlImpl mIOffloadHal;
    private IOffloadConfig mIOffloadConfigMock;
    private IOffloadControl mIOffloadControlMock;
    private android.hardware.tetheroffload.control.V1_1.IOffloadControl mIOffloadControlMockV11;
    private ITetheringOffloadCallback mTetheringOffloadCallback;
    private OffloadHalCallback mOffloadHalCallback;

    private class OffloadHalSpy extends OffloadHalHidlImpl {
        OffloadHalSpy() {
            super(new Handler(mTestLooper.getLooper()), mLog);
        }

        @Override
        protected IServiceManager getServiceManagerMockable() throws RemoteException {
            return mServiceManagerMock;
        }

        @Override
        protected IOffloadConfig getOffloadConfigMockable() throws RemoteException {
            return mIOffloadConfigMock;
        }

        @Override
        protected IOffloadControl getOffloadControlMockable() throws RemoteException {
            return mIOffloadControlMock;
        }

        @Override
        protected android.hardware.tetheroffload.control.V1_1.IOffloadControl
                getOffloadControlMockableV1_1() throws RemoteException {
            return mIOffloadControlMockV11;
        }
    }

    private void initAndValidateOffloadHal(boolean hasConfig, int controlVersion) throws Exception {
        mIOffloadConfigMock = hasConfig ? mock(IOffloadConfig.class) : null;
        mIOffloadControlMock = controlVersion == OFFLOAD_HAL_VERSION_NONE
                ? null
                : mock(IOffloadControl.class);
        if (controlVersion == OFFLOAD_HAL_VERSION_HIDL_1_1) {
            when(mServiceManagerMock.getTransport(eq(
                    android.hardware.tetheroffload.control.V1_1.IOffloadControl.kInterfaceName),
                    anyString()))
                    .thenReturn(IServiceManager.Transport.HWBINDER);
            mIOffloadControlMockV11 = mock(
                    android.hardware.tetheroffload.control.V1_1.IOffloadControl.class);
        } else {
            when(mServiceManagerMock.getTransport(anyString(), anyString()))
                    .thenReturn(IServiceManager.Transport.EMPTY);
            mIOffloadControlMockV11 = null;
        }
        mIOffloadHal.initOffload(mOffloadHalCallback);
        if (hasConfig && controlVersion != OFFLOAD_HAL_VERSION_NONE) {
            final ArgumentCaptor<ITetheringOffloadCallback> mOffloadCallbackCaptor =
                    ArgumentCaptor.forClass(ITetheringOffloadCallback.class);
            verify(mIOffloadControlMock).initOffload(mOffloadCallbackCaptor.capture(), any());
            mTetheringOffloadCallback = mOffloadCallbackCaptor.getValue();
        } else if (mIOffloadControlMock != null) {
            verify(mIOffloadControlMock, never()).initOffload(any(), any());
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mIOffloadHal = new OffloadHalSpy();
        mOffloadHalCallback = spy(new OffloadHalCallback());
    }

    @Test
    public void testInitOffloadSuccess() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
    }

    @Test
    public void testInitOffloadSuccessV1_1() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_1);
    }

    @Test
    public void testInitOffloadFailureNoConfig() throws Exception {
        initAndValidateOffloadHal(false, OFFLOAD_HAL_VERSION_HIDL_1_0);
    }

    @Test
    public void testInitOffloadFailureNoControl() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_NONE);
    }

    @Test
    public void testSetHandles() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
        final FileDescriptor fd1 = new FileDescriptor();
        final FileDescriptor fd2 = new FileDescriptor();
        final NativeHandle handle1 = new NativeHandle(fd1, true);
        final NativeHandle handle2 = new NativeHandle(fd2, true);
        final ArgumentCaptor<NativeHandle> nativeHandleCaptor1 =
                ArgumentCaptor.forClass(NativeHandle.class);
        final ArgumentCaptor<NativeHandle> nativeHandleCaptor2 =
                ArgumentCaptor.forClass(NativeHandle.class);

        mIOffloadHal.setHandles(handle1, handle2);
        verify(mIOffloadConfigMock).setHandles(nativeHandleCaptor1.capture(),
                nativeHandleCaptor2.capture(), any());
        assertEquals(nativeHandleCaptor1.getValue().getFileDescriptor().getInt$(),
                handle1.getFileDescriptor().getInt$());
        assertEquals(nativeHandleCaptor2.getValue().getFileDescriptor().getInt$(),
                handle2.getFileDescriptor().getInt$());
    }

    @Test
    public void testGetForwardedStats() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
        final ForwardedStats stats = mIOffloadHal.getForwardedStats(RMNET0);
        verify(mIOffloadControlMock).getForwardedStats(eq(RMNET0), any());
        assertNotNull(stats);
    }

    @Test
    public void testSetLocalPrefixes() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
        final ArrayList<String> localPrefixes = new ArrayList<>();
        localPrefixes.add("127.0.0.0/8");
        localPrefixes.add("fe80::/64");
        mIOffloadHal.setLocalPrefixes(localPrefixes);
        verify(mIOffloadControlMock).setLocalPrefixes(eq(localPrefixes), any());
    }

    @Test
    public void testSetDataLimit() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
        final long limit = 12345;
        mIOffloadHal.setDataLimit(RMNET0, limit);
        verify(mIOffloadControlMock).setDataLimit(eq(RMNET0), eq(limit), any());
    }

    @Test
    public void testSetDataWarningAndLimitFailure() throws Exception {
        // Verify V1.0 control HAL would reject the function call with exception.
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
        final long warning = 12345;
        final long limit = 67890;
        assertThrows(IllegalArgumentException.class,
                () -> mIOffloadHal.setDataWarningAndLimit(RMNET0, warning, limit));
    }

    @Test
    public void testSetDataWarningAndLimit() throws Exception {
        // Verify V1.1 control HAL could receive this function call.
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_1);
        final long warning = 12345;
        final long limit = 67890;
        mIOffloadHal.setDataWarningAndLimit(RMNET0, warning, limit);
        verify(mIOffloadControlMockV11).setDataWarningAndLimit(eq(RMNET0), eq(warning), eq(limit),
                any());
    }

    @Test
    public void testSetUpstreamParameters() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
        final String v4addr = "192.168.10.1";
        final String v4gateway = "192.168.10.255";
        final ArrayList<String> v6gws = new ArrayList<>(0);
        v6gws.add("2001:db8::1");
        mIOffloadHal.setUpstreamParameters(RMNET0, v4addr, v4gateway, v6gws);
        verify(mIOffloadControlMock).setUpstreamParameters(eq(RMNET0), eq(v4addr), eq(v4gateway),
                eq(v6gws), any());
    }

    @Test
    public void testAddDownstream() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
        final String ifName = "wlan1";
        final String prefix = "192.168.43.0/24";
        mIOffloadHal.addDownstream(ifName, prefix);
        verify(mIOffloadControlMock).addDownstream(eq(ifName), eq(prefix), any());
    }

    @Test
    public void testRemoveDownstream() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);
        final String ifName = "wlan1";
        final String prefix = "192.168.43.0/24";
        mIOffloadHal.removeDownstream(ifName, prefix);
        verify(mIOffloadControlMock).removeDownstream(eq(ifName), eq(prefix), any());
    }

    @Test
    public void testTetheringOffloadCallback() throws Exception {
        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_0);

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_STARTED);
        mTestLooper.dispatchAll();
        verify(mOffloadHalCallback).onStarted();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_STOPPED_ERROR);
        mTestLooper.dispatchAll();
        verify(mOffloadHalCallback).onStoppedError();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_STOPPED_UNSUPPORTED);
        mTestLooper.dispatchAll();
        verify(mOffloadHalCallback).onStoppedUnsupported();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_SUPPORT_AVAILABLE);
        mTestLooper.dispatchAll();
        verify(mOffloadHalCallback).onSupportAvailable();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_STOPPED_LIMIT_REACHED);
        mTestLooper.dispatchAll();
        verify(mOffloadHalCallback).onStoppedLimitReached();

        final NatTimeoutUpdate tcpParams = buildNatTimeoutUpdate(NetworkProtocol.TCP);
        mTetheringOffloadCallback.updateTimeout(tcpParams);
        mTestLooper.dispatchAll();
        verify(mOffloadHalCallback).onNatTimeoutUpdate(eq(OsConstants.IPPROTO_TCP),
                eq(tcpParams.src.addr),
                eq(uint16(tcpParams.src.port)),
                eq(tcpParams.dst.addr),
                eq(uint16(tcpParams.dst.port)));

        final NatTimeoutUpdate udpParams = buildNatTimeoutUpdate(NetworkProtocol.UDP);
        mTetheringOffloadCallback.updateTimeout(udpParams);
        mTestLooper.dispatchAll();
        verify(mOffloadHalCallback).onNatTimeoutUpdate(eq(OsConstants.IPPROTO_UDP),
                eq(udpParams.src.addr),
                eq(uint16(udpParams.src.port)),
                eq(udpParams.dst.addr),
                eq(uint16(udpParams.dst.port)));
        reset(mOffloadHalCallback);

        initAndValidateOffloadHal(true, OFFLOAD_HAL_VERSION_HIDL_1_1);

        // Verify the interface will process the events that comes from V1.1 HAL.
        mTetheringOffloadCallback.onEvent_1_1(OffloadCallbackEvent.OFFLOAD_STARTED);
        mTestLooper.dispatchAll();
        final InOrder inOrder = inOrder(mOffloadHalCallback);
        inOrder.verify(mOffloadHalCallback).onStarted();
        inOrder.verifyNoMoreInteractions();

        mTetheringOffloadCallback.onEvent_1_1(OffloadCallbackEvent.OFFLOAD_WARNING_REACHED);
        mTestLooper.dispatchAll();
        inOrder.verify(mOffloadHalCallback).onWarningReached();
        inOrder.verifyNoMoreInteractions();
    }

    private NatTimeoutUpdate buildNatTimeoutUpdate(final int proto) {
        final NatTimeoutUpdate params = new NatTimeoutUpdate();
        params.proto = proto;
        params.src.addr = "192.168.43.200";
        params.src.port = 100;
        params.dst.addr = "172.50.46.169";
        params.dst.port = 150;
        return params;
    }
}
