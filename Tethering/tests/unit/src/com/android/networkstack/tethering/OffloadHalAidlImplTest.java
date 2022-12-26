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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.tetheroffload.ForwardedStats;
import android.hardware.tetheroffload.IOffload;
import android.hardware.tetheroffload.IPv4AddrPortPair;
import android.hardware.tetheroffload.ITetheringOffloadCallback;
import android.hardware.tetheroffload.NatTimeoutUpdate;
import android.hardware.tetheroffload.NetworkProtocol;
import android.hardware.tetheroffload.OffloadCallbackEvent;
import android.os.Handler;
import android.os.NativeHandle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.system.OsConstants;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.SharedLog;
import com.android.networkstack.tethering.OffloadHardwareInterface.OffloadHalCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class OffloadHalAidlImplTest {
    private static final String RMNET0 = "test_rmnet_data0";
    private static final String INVALID_IFACE = "invalid";

    private final SharedLog mLog = new SharedLog("test");
    private final TestLooper mTestLooper = new TestLooper();

    private IOffload mIOffloadMock;
    private OffloadHalAidlImpl mIOffloadHal;
    private ITetheringOffloadCallback mTetheringOffloadCallback;
    private OffloadHalCallback mOffloadHalCallback;

    private class OffloadHalSpy extends OffloadHalAidlImpl {
        OffloadHalSpy() {
            super(new Handler(mTestLooper.getLooper()), mLog);
        }

        @Override
        protected IOffload getOffloadMockable() {
            return mIOffloadMock;
        }
    }

    private void startAndValidateOffloadHal(boolean hasService) throws Exception {
        mIOffloadMock = hasService ? mock(IOffload.class) : null;
        if (hasService) {
            final ArgumentCaptor<ITetheringOffloadCallback> mOffloadCallbackCaptor =
                    ArgumentCaptor.forClass(ITetheringOffloadCallback.class);
            doNothing().when(mIOffloadMock).initOffload(mOffloadCallbackCaptor.capture());
            assertTrue(mIOffloadHal.initOffload(mOffloadHalCallback));
            verify(mIOffloadMock).initOffload(any());
            mTetheringOffloadCallback = mOffloadCallbackCaptor.getValue();
        } else {
            assertFalse(mIOffloadHal.initOffload(mOffloadHalCallback));
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
        startAndValidateOffloadHal(true);
    }

    @Test
    public void testInitOffloadFailure() throws Exception {
        startAndValidateOffloadHal(false);
    }

    @Test
    public void testStopOffloadSuccess() throws Exception {
        startAndValidateOffloadHal(true);
        doNothing().when(mIOffloadMock).stopOffload();
        assertTrue(mIOffloadHal.stopOffload());
        verify(mIOffloadMock).stopOffload();
    }

    @Test
    public void testStopOffloadFailure() throws Exception {
        startAndValidateOffloadHal(true);
        doThrow(new RemoteException()).when(mIOffloadMock).stopOffload();
        assertFalse(mIOffloadHal.stopOffload());
    }

    @Test
    public void testSetHandlesSuccess() throws Exception {
        startAndValidateOffloadHal(true);
        final FileDescriptor fd1 = new FileDescriptor();
        final FileDescriptor fd2 = new FileDescriptor();
        final NativeHandle handle1 = new NativeHandle(fd1, true);
        final NativeHandle handle2 = new NativeHandle(fd2, true);
        final ArgumentCaptor<ParcelFileDescriptor> fdCaptor1 =
                ArgumentCaptor.forClass(ParcelFileDescriptor.class);
        final ArgumentCaptor<ParcelFileDescriptor> fdCaptor2 =
                ArgumentCaptor.forClass(ParcelFileDescriptor.class);

        doNothing().when(mIOffloadMock).setHandles(any(), any());
        assertTrue(mIOffloadHal.setHandles(handle1, handle2));
        verify(mIOffloadMock).setHandles(fdCaptor1.capture(), fdCaptor2.capture());
        assertEquals(fdCaptor1.getValue().getFd(), fd1.getInt$());
        assertEquals(fdCaptor2.getValue().getFd(), fd2.getInt$());
    }

    @Test
    public void testSetHandlesFailure() throws Exception {
        startAndValidateOffloadHal(true);
        final NativeHandle handle1 = new NativeHandle(new FileDescriptor(), true);
        final NativeHandle handle2 = new NativeHandle(new FileDescriptor(), true);
        doThrow(new RemoteException()).when(mIOffloadMock).setHandles(any(), any());
        assertFalse(mIOffloadHal.setHandles(handle1, handle2));
    }

    @Test
    public void testGetForwardedStatsSuccess() throws Exception {
        startAndValidateOffloadHal(true);
        final ForwardedStats returnStats = new ForwardedStats();
        returnStats.rxBytes = 12345;
        returnStats.txBytes = 67890;
        when(mIOffloadMock.getForwardedStats(anyString())).thenReturn(returnStats);
        final OffloadHardwareInterface.ForwardedStats stats =
                mIOffloadHal.getForwardedStats(RMNET0);
        verify(mIOffloadMock).getForwardedStats(eq(RMNET0));
        assertNotNull(stats);
        assertEquals(stats.rxBytes, returnStats.rxBytes);
        assertEquals(stats.txBytes, returnStats.txBytes);
    }

    @Test
    public void testGetForwardedStatsFailure() throws Exception {
        startAndValidateOffloadHal(true);
        when(mIOffloadMock.getForwardedStats(anyString()))
                .thenThrow(new RemoteException());
        final OffloadHardwareInterface.ForwardedStats stats =
                mIOffloadHal.getForwardedStats(INVALID_IFACE);
        assertNotNull(stats);
        assertEquals(stats.rxBytes, 0);
        assertEquals(stats.txBytes, 0);
    }

    @Test
    public void testSetLocalPrefixesSuccess() throws Exception {
        startAndValidateOffloadHal(true);
        final ArrayList<String> localPrefixes = new ArrayList<>();
        localPrefixes.add("127.0.0.0/8");
        localPrefixes.add("fe80::/64");
        final String[] localPrefixesArray =
                localPrefixes.toArray(new String[localPrefixes.size()]);
        doNothing().when(mIOffloadMock).setLocalPrefixes(any());
        assertTrue(mIOffloadHal.setLocalPrefixes(localPrefixes));
        verify(mIOffloadMock).setLocalPrefixes(eq(localPrefixesArray));
    }

    @Test
    public void testSetLocalPrefixesFailure() throws Exception {
        startAndValidateOffloadHal(true);
        final ArrayList<String> localPrefixes = new ArrayList<>();
        localPrefixes.add("invalid");
        localPrefixes.add("fe80::/64");
        doThrow(new RemoteException()).when(mIOffloadMock).setLocalPrefixes(any());
        assertFalse(mIOffloadHal.setLocalPrefixes(localPrefixes));
    }

    @Test
    public void testSetDataLimitSuccess() throws Exception {
        startAndValidateOffloadHal(true);
        final long limit = 12345;
        doNothing().when(mIOffloadMock).setDataWarningAndLimit(anyString(), anyLong(), anyLong());
        assertTrue(mIOffloadHal.setDataLimit(RMNET0, limit));
        verify(mIOffloadMock).setDataWarningAndLimit(eq(RMNET0), eq(Long.MAX_VALUE), eq(limit));
    }

    @Test
    public void testSetDataWarningAndLimitSuccess() throws Exception {
        startAndValidateOffloadHal(true);
        final long warning = 12345;
        final long limit = 67890;
        doNothing().when(mIOffloadMock).setDataWarningAndLimit(anyString(), anyLong(), anyLong());
        assertTrue(mIOffloadHal.setDataWarningAndLimit(RMNET0, warning, limit));
        verify(mIOffloadMock).setDataWarningAndLimit(eq(RMNET0), eq(warning), eq(limit));
    }

    @Test
    public void testSetDataWarningAndLimitFailure() throws Exception {
        startAndValidateOffloadHal(true);
        final long warning = 12345;
        final long limit = 67890;
        doThrow(new RemoteException())
                .when(mIOffloadMock).setDataWarningAndLimit(anyString(), anyLong(), anyLong());
        assertFalse(mIOffloadHal.setDataWarningAndLimit(INVALID_IFACE, warning, limit));
    }

    @Test
    public void testSetUpstreamParametersSuccess() throws Exception {
        startAndValidateOffloadHal(true);
        final String v4addr = "192.168.10.1";
        final String v4gateway = "192.168.10.255";
        final ArrayList<String> v6gws = new ArrayList<>(0);
        v6gws.add("2001:db8::1");
        String[] v6gwsArray = v6gws.toArray(new String[v6gws.size()]);
        doNothing().when(mIOffloadMock).setUpstreamParameters(anyString(), anyString(), anyString(),
                any());
        assertTrue(mIOffloadHal.setUpstreamParameters(RMNET0, v4addr, v4gateway, v6gws));
        verify(mIOffloadMock).setUpstreamParameters(eq(RMNET0), eq(v4addr), eq(v4gateway),
                  eq(v6gwsArray));
    }

    @Test
    public void testSetUpstreamParametersFailure() throws Exception {
        startAndValidateOffloadHal(true);
        final String v4addr = "192.168.10.1";
        final String v4gateway = "192.168.10.255";
        final ArrayList<String> v6gws = new ArrayList<>(0);
        v6gws.add("2001:db8::1");
        doThrow(new RemoteException()).when(mIOffloadMock).setUpstreamParameters(anyString(),
                anyString(), anyString(), any());
        assertFalse(mIOffloadHal.setUpstreamParameters(INVALID_IFACE, v4addr, v4gateway, v6gws));
    }

    @Test
    public void testUpdateDownstreamSuccess() throws Exception {
        startAndValidateOffloadHal(true);
        final String ifName = "wlan1";
        final String prefix = "192.168.43.0/24";
        doNothing().when(mIOffloadMock).addDownstream(anyString(), anyString());
        doNothing().when(mIOffloadMock).removeDownstream(anyString(), anyString());
        assertTrue(mIOffloadHal.addDownstream(ifName, prefix));
        verify(mIOffloadMock).addDownstream(eq(ifName), eq(prefix));
        assertTrue(mIOffloadHal.removeDownstream(ifName, prefix));
        verify(mIOffloadMock).removeDownstream(eq(ifName), eq(prefix));
    }

    @Test
    public void testUpdateDownstreamFailure() throws Exception {
        startAndValidateOffloadHal(true);
        final String prefix = "192.168.43.0/24";
        doThrow(new RemoteException()).when(mIOffloadMock).addDownstream(anyString(), anyString());
        doThrow(new RemoteException()).when(mIOffloadMock).removeDownstream(anyString(),
                anyString());
        assertFalse(mIOffloadHal.addDownstream(INVALID_IFACE, prefix));
        assertFalse(mIOffloadHal.removeDownstream(INVALID_IFACE, prefix));
    }

    @Test
    public void testTetheringOffloadCallback() throws Exception {
        startAndValidateOffloadHal(true);

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_STARTED);
        mTestLooper.dispatchAll();
        final InOrder inOrder = inOrder(mOffloadHalCallback);
        inOrder.verify(mOffloadHalCallback).onStarted();
        inOrder.verifyNoMoreInteractions();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_STOPPED_ERROR);
        mTestLooper.dispatchAll();
        inOrder.verify(mOffloadHalCallback).onStoppedError();
        inOrder.verifyNoMoreInteractions();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_STOPPED_UNSUPPORTED);
        mTestLooper.dispatchAll();
        inOrder.verify(mOffloadHalCallback).onStoppedUnsupported();
        inOrder.verifyNoMoreInteractions();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_SUPPORT_AVAILABLE);
        mTestLooper.dispatchAll();
        inOrder.verify(mOffloadHalCallback).onSupportAvailable();
        inOrder.verifyNoMoreInteractions();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_STOPPED_LIMIT_REACHED);
        mTestLooper.dispatchAll();
        inOrder.verify(mOffloadHalCallback).onStoppedLimitReached();
        inOrder.verifyNoMoreInteractions();

        mTetheringOffloadCallback.onEvent(OffloadCallbackEvent.OFFLOAD_WARNING_REACHED);
        mTestLooper.dispatchAll();
        inOrder.verify(mOffloadHalCallback).onWarningReached();
        inOrder.verifyNoMoreInteractions();

        final NatTimeoutUpdate tcpParams = buildNatTimeoutUpdate(NetworkProtocol.TCP);
        mTetheringOffloadCallback.updateTimeout(tcpParams);
        mTestLooper.dispatchAll();
        inOrder.verify(mOffloadHalCallback).onNatTimeoutUpdate(eq(OsConstants.IPPROTO_TCP),
                eq(tcpParams.src.addr),
                eq(tcpParams.src.port),
                eq(tcpParams.dst.addr),
                eq(tcpParams.dst.port));
        inOrder.verifyNoMoreInteractions();

        final NatTimeoutUpdate udpParams = buildNatTimeoutUpdate(NetworkProtocol.UDP);
        mTetheringOffloadCallback.updateTimeout(udpParams);
        mTestLooper.dispatchAll();
        inOrder.verify(mOffloadHalCallback).onNatTimeoutUpdate(eq(OsConstants.IPPROTO_UDP),
                eq(udpParams.src.addr),
                eq(udpParams.src.port),
                eq(udpParams.dst.addr),
                eq(udpParams.dst.port));
        inOrder.verifyNoMoreInteractions();
    }

    private NatTimeoutUpdate buildNatTimeoutUpdate(final int proto) {
        final NatTimeoutUpdate params = new NatTimeoutUpdate();
        params.proto = proto;
        params.src = new IPv4AddrPortPair();
        params.dst = new IPv4AddrPortPair();
        params.src.addr = "192.168.43.200";
        params.src.port = 100;
        params.dst.addr = "172.50.46.169";
        params.dst.port = 150;
        return params;
    }
}
