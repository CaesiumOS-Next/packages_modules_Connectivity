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

package com.android.server.connectivity.mdns;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest.permission;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.text.format.DateUtils;

import com.android.net.module.util.HexDump;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests for {@link MdnsSocketClient} */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsSocketClientTests {
    private static final long TIMEOUT = 500;
    private final byte[] mBuffer = new byte[10];
    final AtomicBoolean mEnableMulticastResponse = new AtomicBoolean(true);
    final AtomicBoolean mEnableUnicastResponse = new AtomicBoolean(true);

    @Mock private Context mContext;
    @Mock private WifiManager mWifiManager;
    @Mock private MdnsSocket mMulticastSocket;
    @Mock private MdnsSocket mUnicastSocket;
    @Mock private MulticastLock mMulticastLock;
    @Mock private MdnsSocketClient.Callback mCallback;

    private MdnsSocketClient mMdnsClient;

    @Before
    public void setup() throws RuntimeException, IOException {
        MockitoAnnotations.initMocks(this);

        when(mWifiManager.createMulticastLock(ArgumentMatchers.anyString()))
                .thenReturn(mMulticastLock);

        mMdnsClient = new MdnsSocketClient(mContext, mMulticastLock) {
                    @Override
                    MdnsSocket createMdnsSocket(int port) throws IOException {
                        if (port == MdnsConstants.MDNS_PORT) {
                            return mMulticastSocket;
                        }
                        return mUnicastSocket;
                    }
                };
        mMdnsClient.setCallback(mCallback);

        doAnswer(
                (InvocationOnMock invocationOnMock) -> {
                    final byte[] dataIn = HexDump.hexStringToByteArray(
                            "0000840000000004"
                            + "00000003134A6F68"
                            + "6E6E792773204368"
                            + "726F6D6563617374"
                            + "0B5F676F6F676C65"
                            + "63617374045F7463"
                            + "70056C6F63616C00"
                            + "0010800100001194"
                            + "006C2369643D3937"
                            + "3062663534376237"
                            + "3533666336336332"
                            + "6432613336626238"
                            + "3936616261380576"
                            + "653D30320D6D643D"
                            + "4368726F6D656361"
                            + "73741269633D2F73"
                            + "657475702F69636F"
                            + "6E2E706E6716666E"
                            + "3D4A6F686E6E7927"
                            + "73204368726F6D65"
                            + "636173740463613D"
                            + "350473743D30095F"
                            + "7365727669636573"
                            + "075F646E732D7364"
                            + "045F756470C03100"
                            + "0C00010000119400"
                            + "02C020C020000C00"
                            + "01000011940002C0"
                            + "0CC00C0021800100"
                            + "000078001C000000"
                            + "001F49134A6F686E"
                            + "6E79277320436872"
                            + "6F6D6563617374C0"
                            + "31C0F30001800100"
                            + "0000780004C0A864"
                            + "68C0F3002F800100"
                            + "0000780005C0F300"
                            + "0140C00C002F8001"
                            + "000011940009C00C"
                            + "00050000800040");
                    if (mEnableMulticastResponse.get()) {
                        DatagramPacket packet = invocationOnMock.getArgument(0);
                        packet.setData(dataIn);
                    }
                    return null;
                })
                .when(mMulticastSocket)
                .receive(any(DatagramPacket.class));
        doAnswer(
                (InvocationOnMock invocationOnMock) -> {
                    final byte[] dataIn = HexDump.hexStringToByteArray(
                            "0000840000000004"
                            + "00000003134A6F68"
                            + "6E6E792773204368"
                            + "726F6D6563617374"
                            + "0B5F676F6F676C65"
                            + "63617374045F7463"
                            + "70056C6F63616C00"
                            + "0010800100001194"
                            + "006C2369643D3937"
                            + "3062663534376237"
                            + "3533666336336332"
                            + "6432613336626238"
                            + "3936616261380576"
                            + "653D30320D6D643D"
                            + "4368726F6D656361"
                            + "73741269633D2F73"
                            + "657475702F69636F"
                            + "6E2E706E6716666E"
                            + "3D4A6F686E6E7927"
                            + "73204368726F6D65"
                            + "636173740463613D"
                            + "350473743D30095F"
                            + "7365727669636573"
                            + "075F646E732D7364"
                            + "045F756470C03100"
                            + "0C00010000119400"
                            + "02C020C020000C00"
                            + "01000011940002C0"
                            + "0CC00C0021800100"
                            + "000078001C000000"
                            + "001F49134A6F686E"
                            + "6E79277320436872"
                            + "6F6D6563617374C0"
                            + "31C0F30001800100"
                            + "0000780004C0A864"
                            + "68C0F3002F800100"
                            + "0000780005C0F300"
                            + "0140C00C002F8001"
                            + "000011940009C00C"
                            + "00050000800040");
                    if (mEnableUnicastResponse.get()) {
                        DatagramPacket packet = invocationOnMock.getArgument(0);
                        packet.setData(dataIn);
                    }
                    return null;
                })
                .when(mUnicastSocket)
                .receive(any(DatagramPacket.class));
    }

    @After
    public void tearDown() {
        mMdnsClient.stopDiscovery();
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testSendPackets_useSeparateSocketForUnicast()
            throws InterruptedException, IOException {
        //MdnsConfigsFlagsImpl.useSeparateSocketToSendUnicastQuery.override(true);
        //MdnsConfigsFlagsImpl.checkMulticastResponse.override(true);
        //MdnsConfigsFlagsImpl.checkMulticastResponseIntervalMs
        //        .override(DateUtils.SECOND_IN_MILLIS);
        mMdnsClient.startDiscovery();
        Thread multicastReceiverThread = mMdnsClient.multicastReceiveThread;
        Thread unicastReceiverThread = mMdnsClient.unicastReceiveThread;
        Thread sendThread = mMdnsClient.sendThread;

        assertTrue(multicastReceiverThread.isAlive());
        assertTrue(sendThread.isAlive());
        assertTrue(unicastReceiverThread.isAlive());

        // Sends a packet.
        DatagramPacket packet = new DatagramPacket(mBuffer, 0, 5);
        mMdnsClient.sendMulticastPacket(packet);
        // mMulticastSocket.send() will be called on another thread. If we verify it immediately,
        // it may not be called yet. So timeout is added.
        verify(mMulticastSocket, timeout(TIMEOUT).times(1)).send(packet);
        verify(mUnicastSocket, timeout(TIMEOUT).times(0)).send(packet);

        // Verify the packet is sent by the unicast socket.
        mMdnsClient.sendUnicastPacket(packet);
        verify(mMulticastSocket, timeout(TIMEOUT).times(1)).send(packet);
        verify(mUnicastSocket, timeout(TIMEOUT).times(1)).send(packet);

        // Stop the mMdnsClient, and ensure that it stops in a reasonable amount of time.
        // Run part of the test logic in a background thread, in case stopDiscovery() blocks
        // for a long time (the foreground thread can fail the test early).
        final CountDownLatch stopDiscoveryLatch = new CountDownLatch(1);
        Thread testThread = new Thread(
                new Runnable() {
                    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
                    @Override
                    public void run() {
                        mMdnsClient.stopDiscovery();
                        stopDiscoveryLatch.countDown();
                    }
                });
        testThread.start();
        assertTrue(stopDiscoveryLatch.await(DateUtils.SECOND_IN_MILLIS, TimeUnit.MILLISECONDS));

        // We should be able to join in a reasonable amount of time, to prove that the
        // the mMdnsClient exited without sending the large queue of packets.
        testThread.join(DateUtils.SECOND_IN_MILLIS);

        assertFalse(multicastReceiverThread.isAlive());
        assertFalse(sendThread.isAlive());
        assertFalse(unicastReceiverThread.isAlive());
    }

    @Test
    public void testSendPackets_useSameSocketForMulticastAndUnicast()
            throws InterruptedException, IOException {
        mMdnsClient.startDiscovery();
        Thread multicastReceiverThread = mMdnsClient.multicastReceiveThread;
        Thread unicastReceiverThread = mMdnsClient.unicastReceiveThread;
        Thread sendThread = mMdnsClient.sendThread;

        assertTrue(multicastReceiverThread.isAlive());
        assertTrue(sendThread.isAlive());
        assertNull(unicastReceiverThread);

        // Sends a packet.
        DatagramPacket packet = new DatagramPacket(mBuffer, 0, 5);
        mMdnsClient.sendMulticastPacket(packet);
        // mMulticastSocket.send() will be called on another thread. If we verify it immediately,
        // it may not be called yet. So timeout is added.
        verify(mMulticastSocket, timeout(TIMEOUT).times(1)).send(packet);
        verify(mUnicastSocket, timeout(TIMEOUT).times(0)).send(packet);

        // Verify the packet is sent by the multicast socket as well.
        mMdnsClient.sendUnicastPacket(packet);
        verify(mMulticastSocket, timeout(TIMEOUT).times(2)).send(packet);
        verify(mUnicastSocket, timeout(TIMEOUT).times(0)).send(packet);

        // Stop the mMdnsClient, and ensure that it stops in a reasonable amount of time.
        // Run part of the test logic in a background thread, in case stopDiscovery() blocks
        // for a long time (the foreground thread can fail the test early).
        final CountDownLatch stopDiscoveryLatch = new CountDownLatch(1);
        Thread testThread = new Thread(
                new Runnable() {
                    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
                    @Override
                    public void run() {
                        mMdnsClient.stopDiscovery();
                        stopDiscoveryLatch.countDown();
                    }
                });
        testThread.start();
        assertTrue(stopDiscoveryLatch.await(DateUtils.SECOND_IN_MILLIS, TimeUnit.MILLISECONDS));

        // We should be able to join in a reasonable amount of time, to prove that the
        // the mMdnsClient exited without sending the large queue of packets.
        testThread.join(DateUtils.SECOND_IN_MILLIS);

        assertFalse(multicastReceiverThread.isAlive());
        assertFalse(sendThread.isAlive());
        assertNull(unicastReceiverThread);
    }

    @Test
    public void testStartStop() throws IOException {
        for (int i = 0; i < 5; i++) {
            mMdnsClient.startDiscovery();

            Thread multicastReceiverThread = mMdnsClient.multicastReceiveThread;
            Thread socketThread = mMdnsClient.sendThread;

            assertTrue(multicastReceiverThread.isAlive());
            assertTrue(socketThread.isAlive());

            mMdnsClient.stopDiscovery();

            assertFalse(multicastReceiverThread.isAlive());
            assertFalse(socketThread.isAlive());
        }
    }

    @Test
    public void testStopDiscovery_queueIsCleared() throws IOException {
        mMdnsClient.startDiscovery();
        mMdnsClient.stopDiscovery();
        mMdnsClient.sendMulticastPacket(new DatagramPacket(mBuffer, 0, 5));

        synchronized (mMdnsClient.multicastPacketQueue) {
            assertTrue(mMdnsClient.multicastPacketQueue.isEmpty());
        }
    }

    @Test
    public void testSendPacket_afterDiscoveryStops() throws IOException {
        mMdnsClient.startDiscovery();
        mMdnsClient.stopDiscovery();
        mMdnsClient.sendMulticastPacket(new DatagramPacket(mBuffer, 0, 5));

        synchronized (mMdnsClient.multicastPacketQueue) {
            assertTrue(mMdnsClient.multicastPacketQueue.isEmpty());
        }
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testSendPacket_queueReachesSizeLimit() throws IOException {
        //MdnsConfigsFlagsImpl.mdnsPacketQueueMaxSize.override(2L);
        mMdnsClient.startDiscovery();
        for (int i = 0; i < 100; i++) {
            mMdnsClient.sendMulticastPacket(new DatagramPacket(mBuffer, 0, 5));
        }

        synchronized (mMdnsClient.multicastPacketQueue) {
            assertTrue(mMdnsClient.multicastPacketQueue.size() <= 2);
        }
    }

    @Test
    public void testMulticastResponseReceived_useSeparateSocketForUnicast() throws IOException {
        mMdnsClient.setCallback(mCallback);

        mMdnsClient.startDiscovery();

        verify(mCallback, timeout(TIMEOUT).atLeast(1))
                .onResponseReceived(any(MdnsResponse.class));
    }

    @Test
    public void testMulticastResponseReceived_useSameSocketForMulticastAndUnicast()
            throws Exception {
        mMdnsClient.startDiscovery();

        verify(mCallback, timeout(TIMEOUT).atLeastOnce())
                .onResponseReceived(any(MdnsResponse.class));

        mMdnsClient.stopDiscovery();
    }

    @Test
    public void testFailedToParseMdnsResponse_useSeparateSocketForUnicast() throws IOException {
        mMdnsClient.setCallback(mCallback);

        // Both multicast socket and unicast socket receive malformed responses.
        byte[] dataIn = HexDump.hexStringToByteArray("0000840000000004");
        doAnswer(
                (InvocationOnMock invocationOnMock) -> {
                    // Malformed data.
                    DatagramPacket packet = invocationOnMock.getArgument(0);
                    packet.setData(dataIn);
                    return null;
                })
                .when(mMulticastSocket)
                .receive(any(DatagramPacket.class));
        doAnswer(
                (InvocationOnMock invocationOnMock) -> {
                    // Malformed data.
                    DatagramPacket packet = invocationOnMock.getArgument(0);
                    packet.setData(dataIn);
                    return null;
                })
                .when(mUnicastSocket)
                .receive(any(DatagramPacket.class));

        mMdnsClient.startDiscovery();

        verify(mCallback, timeout(TIMEOUT).atLeast(1))
                .onFailedToParseMdnsResponse(anyInt(), eq(MdnsResponseErrorCode.ERROR_END_OF_FILE));

        mMdnsClient.stopDiscovery();
    }

    @Test
    public void testFailedToParseMdnsResponse_useSameSocketForMulticastAndUnicast()
            throws IOException {
        doAnswer(
                (InvocationOnMock invocationOnMock) -> {
                    final byte[] dataIn = HexDump.hexStringToByteArray("0000840000000004");
                    DatagramPacket packet = invocationOnMock.getArgument(0);
                    packet.setData(dataIn);
                    return null;
                })
                .when(mMulticastSocket)
                .receive(any(DatagramPacket.class));

        mMdnsClient.startDiscovery();

        verify(mCallback, timeout(TIMEOUT).atLeast(1))
                .onFailedToParseMdnsResponse(1, MdnsResponseErrorCode.ERROR_END_OF_FILE);

        mMdnsClient.stopDiscovery();
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testMulticastResponseIsNotReceived() throws IOException, InterruptedException {
        //MdnsConfigsFlagsImpl.checkMulticastResponse.override(true);
        //MdnsConfigsFlagsImpl.checkMulticastResponseIntervalMs
        //        .override(DateUtils.SECOND_IN_MILLIS);
        //MdnsConfigsFlagsImpl.useSeparateSocketToSendUnicastQuery.override(true);
        mEnableMulticastResponse.set(false);
        mEnableUnicastResponse.set(true);

        mMdnsClient.startDiscovery();
        DatagramPacket packet = new DatagramPacket(mBuffer, 0, 5);
        mMdnsClient.sendUnicastPacket(packet);
        mMdnsClient.sendMulticastPacket(packet);

        // Wait for the timer to be triggered.
        Thread.sleep(MdnsConfigs.checkMulticastResponseIntervalMs() * 2);

        assertFalse(mMdnsClient.receivedMulticastResponse);
        assertTrue(mMdnsClient.receivedUnicastResponse);
        assertTrue(mMdnsClient.cannotReceiveMulticastResponse.get());

        // Allow multicast response and verify the states again.
        mEnableMulticastResponse.set(true);
        Thread.sleep(DateUtils.SECOND_IN_MILLIS);

        // Verify cannotReceiveMulticastResponse is reset to false.
        assertTrue(mMdnsClient.receivedMulticastResponse);
        assertTrue(mMdnsClient.receivedUnicastResponse);
        assertFalse(mMdnsClient.cannotReceiveMulticastResponse.get());

        // Stop the discovery and start a new session. Don't respond the unicsat query either in
        // this session.
        mEnableMulticastResponse.set(false);
        mEnableUnicastResponse.set(false);
        mMdnsClient.stopDiscovery();
        mMdnsClient.startDiscovery();

        // Verify the states are reset.
        assertFalse(mMdnsClient.receivedMulticastResponse);
        assertFalse(mMdnsClient.receivedUnicastResponse);
        assertFalse(mMdnsClient.cannotReceiveMulticastResponse.get());

        mMdnsClient.sendUnicastPacket(packet);
        mMdnsClient.sendMulticastPacket(packet);
        Thread.sleep(MdnsConfigs.checkMulticastResponseIntervalMs() * 2);

        // Verify cannotReceiveMulticastResponse is not set the true because we didn't receive the
        // unicast response either. This is expected for users who don't have any cast device.
        assertFalse(mMdnsClient.receivedMulticastResponse);
        assertFalse(mMdnsClient.receivedUnicastResponse);
        assertFalse(mMdnsClient.cannotReceiveMulticastResponse.get());
    }
}
