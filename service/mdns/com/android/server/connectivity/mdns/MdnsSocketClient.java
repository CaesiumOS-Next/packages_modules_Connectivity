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

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.SystemClock;
import android.text.format.DateUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@link MdnsSocketClient} maintains separate threads to send and receive mDNS packets for all
 * the requested service types.
 *
 * <p>See https://tools.ietf.org/html/rfc6763 (namely sections 4 and 5).
 */
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
public class MdnsSocketClient {
    private static final String TAG = "MdnsClient";
    // TODO: The following values are copied from cast module. We need to think about the
    // better way to share those.
    private static final String CAST_SENDER_LOG_SOURCE = "CAST_SENDER_SDK";
    private static final String CAST_PREFS_NAME = "google_cast";
    private static final String PREF_CAST_SENDER_ID = "PREF_CAST_SENDER_ID";
    private static final MdnsLogger LOGGER = new MdnsLogger(TAG);
    private static final String MULTICAST_TYPE = "multicast";
    private static final String UNICAST_TYPE = "unicast";

    private static final long SLEEP_TIME_FOR_SOCKET_THREAD_MS =
            MdnsConfigs.sleepTimeForSocketThreadMs();
    // A value of 0 leads to an infinite wait.
    private static final long THREAD_JOIN_TIMEOUT_MS = DateUtils.SECOND_IN_MILLIS;
    private static final int RECEIVER_BUFFER_SIZE = 2048;
    @VisibleForTesting
    final Queue<DatagramPacket> multicastPacketQueue = new ArrayDeque<>();
    @VisibleForTesting
    final Queue<DatagramPacket> unicastPacketQueue = new ArrayDeque<>();
    private final Context mContext;
    private final byte[] mMulticastReceiverBuffer = new byte[RECEIVER_BUFFER_SIZE];
    private final byte[] mUnicastReceiverBuffer;
    private final MdnsResponseDecoder mResponseDecoder;
    private final MulticastLock mMulticastLock;
    private final boolean mUseSeparateSocketForUnicast =
            MdnsConfigs.useSeparateSocketToSendUnicastQuery();
    private final boolean mCheckMulticastResponse = MdnsConfigs.checkMulticastResponse();
    private final long mCheckMulticastResponseIntervalMs =
            MdnsConfigs.checkMulticastResponseIntervalMs();
    private final Object mSocketLock = new Object();
    private final Object mTimerObject = new Object();
    // If multicast response was received in the current session. The value is reset in the
    // beginning of each session.
    @VisibleForTesting
    boolean receivedMulticastResponse;
    // If unicast response was received in the current session. The value is reset in the beginning
    // of each session.
    @VisibleForTesting
    boolean receivedUnicastResponse;
    // If the phone is the bad state where it can't receive any multicast response.
    @VisibleForTesting
    AtomicBoolean cannotReceiveMulticastResponse = new AtomicBoolean(false);
    @VisibleForTesting
    volatile Thread sendThread;
    @VisibleForTesting
    Thread multicastReceiveThread;
    @VisibleForTesting
    Thread unicastReceiveThread;
    private volatile boolean mShouldStopSocketLoop;
    private Callback mCallback;
    private MdnsSocket mMulticastSocket;
    private MdnsSocket mUnicastSocket;
    private int mReceivedPacketNumber = 0;
    private Timer mLogMdnsPacketTimer;
    private AtomicInteger mPacketsCount;
    private Timer mCheckMulticastResponseTimer;

    public MdnsSocketClient(@NonNull Context context, @NonNull MulticastLock multicastLock) {
        mContext = context;
        mMulticastLock = multicastLock;
        mResponseDecoder = new MdnsResponseDecoder(new MdnsResponseDecoder.Clock(), null);
        if (mUseSeparateSocketForUnicast) {
            mUnicastReceiverBuffer = new byte[RECEIVER_BUFFER_SIZE];
        } else {
            mUnicastReceiverBuffer = null;
        }
    }

    public synchronized void setCallback(@Nullable Callback callback) {
        mCallback = callback;
    }

    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
    public synchronized void startDiscovery() throws IOException {
        if (mMulticastSocket != null) {
            LOGGER.w("Discovery is already in progress.");
            return;
        }

        receivedMulticastResponse = false;
        receivedUnicastResponse = false;
        cannotReceiveMulticastResponse.set(false);

        mShouldStopSocketLoop = false;
        try {
            // TODO (changed when importing code): consider setting thread stats tag
            mMulticastSocket = createMdnsSocket(MdnsConstants.MDNS_PORT);
            mMulticastSocket.joinGroup();
            if (mUseSeparateSocketForUnicast) {
                // For unicast, use port 0 and the system will assign it with any available port.
                mUnicastSocket = createMdnsSocket(0);
            }
            mMulticastLock.acquire();
        } catch (IOException e) {
            mMulticastLock.release();
            if (mMulticastSocket != null) {
                mMulticastSocket.close();
                mMulticastSocket = null;
            }
            if (mUnicastSocket != null) {
                mUnicastSocket.close();
                mUnicastSocket = null;
            }
            throw e;
        } finally {
            // TODO (changed when importing code): consider resetting thread stats tag
        }
        createAndStartSendThread();
        createAndStartReceiverThreads();
    }

    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
    public void stopDiscovery() {
        LOGGER.log("Stop discovery.");
        if (mMulticastSocket == null && mUnicastSocket == null) {
            return;
        }

        if (MdnsConfigs.clearMdnsPacketQueueAfterDiscoveryStops()) {
            synchronized (multicastPacketQueue) {
                multicastPacketQueue.clear();
            }
            synchronized (unicastPacketQueue) {
                unicastPacketQueue.clear();
            }
        }

        mMulticastLock.release();

        mShouldStopSocketLoop = true;
        waitForSendThreadToStop();
        waitForReceiverThreadsToStop();

        synchronized (mSocketLock) {
            mMulticastSocket = null;
            mUnicastSocket = null;
        }

        synchronized (mTimerObject) {
            if (mCheckMulticastResponseTimer != null) {
                mCheckMulticastResponseTimer.cancel();
                mCheckMulticastResponseTimer = null;
            }
        }
    }

    /** Sends a mDNS request packet that asks for multicast response. */
    public void sendMulticastPacket(@NonNull DatagramPacket packet) {
        sendMdnsPacket(packet, multicastPacketQueue);
    }

    /** Sends a mDNS request packet that asks for unicast response. */
    public void sendUnicastPacket(DatagramPacket packet) {
        if (mUseSeparateSocketForUnicast) {
            sendMdnsPacket(packet, unicastPacketQueue);
        } else {
            sendMdnsPacket(packet, multicastPacketQueue);
        }
    }

    private void sendMdnsPacket(DatagramPacket packet, Queue<DatagramPacket> packetQueueToUse) {
        if (mShouldStopSocketLoop && !MdnsConfigs.allowAddMdnsPacketAfterDiscoveryStops()) {
            LOGGER.w("sendMdnsPacket() is called after discovery already stopped");
            return;
        }
        synchronized (packetQueueToUse) {
            while (packetQueueToUse.size() >= MdnsConfigs.mdnsPacketQueueMaxSize()) {
                packetQueueToUse.remove();
            }
            packetQueueToUse.add(packet);
        }
        triggerSendThread();
    }

    private void createAndStartSendThread() {
        if (sendThread != null) {
            LOGGER.w("A socket thread already exists.");
            return;
        }
        sendThread = new Thread(this::sendThreadMain);
        sendThread.setName("mdns-send");
        sendThread.start();
    }

    private void createAndStartReceiverThreads() {
        if (multicastReceiveThread != null) {
            LOGGER.w("A multicast receiver thread already exists.");
            return;
        }
        multicastReceiveThread =
                new Thread(() -> receiveThreadMain(mMulticastReceiverBuffer, mMulticastSocket));
        multicastReceiveThread.setName("mdns-multicast-receive");
        multicastReceiveThread.start();

        if (mUseSeparateSocketForUnicast) {
            unicastReceiveThread =
                    new Thread(() -> receiveThreadMain(mUnicastReceiverBuffer, mUnicastSocket));
            unicastReceiveThread.setName("mdns-unicast-receive");
            unicastReceiveThread.start();
        }
    }

    private void triggerSendThread() {
        LOGGER.log("Trigger send thread.");
        Thread sendThread = this.sendThread;
        if (sendThread != null) {
            sendThread.interrupt();
        } else {
            LOGGER.w("Socket thread is null");
        }
    }

    private void waitForReceiverThreadsToStop() {
        if (multicastReceiveThread != null) {
            waitForThread(multicastReceiveThread);
            multicastReceiveThread = null;
        }

        if (unicastReceiveThread != null) {
            waitForThread(unicastReceiveThread);
            unicastReceiveThread = null;
        }
    }

    private void waitForSendThreadToStop() {
        LOGGER.log("wait For Send Thread To Stop");
        if (sendThread == null) {
            LOGGER.w("socket thread is already dead.");
            return;
        }
        waitForThread(sendThread);
        sendThread = null;
    }

    private void waitForThread(Thread thread) {
        long startMs = SystemClock.elapsedRealtime();
        long waitMs = THREAD_JOIN_TIMEOUT_MS;
        while (thread.isAlive() && (waitMs > 0)) {
            try {
                thread.interrupt();
                thread.join(waitMs);
                if (thread.isAlive()) {
                    LOGGER.w("Failed to join thread: " + thread);
                }
                break;
            } catch (InterruptedException e) {
                // Compute remaining time after at least a single join call, in case the clock
                // resolution is poor.
                waitMs = THREAD_JOIN_TIMEOUT_MS - (SystemClock.elapsedRealtime() - startMs);
            }
        }
    }

    private void sendThreadMain() {
        List<DatagramPacket> multicastPacketsToSend = new ArrayList<>();
        List<DatagramPacket> unicastPacketsToSend = new ArrayList<>();
        boolean shouldThreadSleep;
        try {
            while (!mShouldStopSocketLoop) {
                try {
                    // Make a local copy of all packets, and clear the queue.
                    // Send packets that ask for multicast response.
                    multicastPacketsToSend.clear();
                    synchronized (multicastPacketQueue) {
                        multicastPacketsToSend.addAll(multicastPacketQueue);
                        multicastPacketQueue.clear();
                    }

                    // Send packets that ask for unicast response.
                    if (mUseSeparateSocketForUnicast) {
                        unicastPacketsToSend.clear();
                        synchronized (unicastPacketQueue) {
                            unicastPacketsToSend.addAll(unicastPacketQueue);
                            unicastPacketQueue.clear();
                        }
                    }

                    // Send all the packets.
                    sendPackets(multicastPacketsToSend, mMulticastSocket);
                    sendPackets(unicastPacketsToSend, mUnicastSocket);

                    // Sleep ONLY if no more packets have been added to the queue, while packets
                    // were being sent.
                    synchronized (multicastPacketQueue) {
                        synchronized (unicastPacketQueue) {
                            shouldThreadSleep =
                                    multicastPacketQueue.isEmpty() && unicastPacketQueue.isEmpty();
                        }
                    }
                    if (shouldThreadSleep) {
                        Thread.sleep(SLEEP_TIME_FOR_SOCKET_THREAD_MS);
                    }
                } catch (InterruptedException e) {
                    // Don't log the interruption as it's expected.
                }
            }
        } finally {
            LOGGER.log("Send thread stopped.");
            try {
                mMulticastSocket.leaveGroup();
            } catch (Exception t) {
                LOGGER.e("Failed to leave the group.", t);
            }

            // Close the socket first. This is the only way to interrupt a blocking receive.
            try {
                // This is a race with the use of the file descriptor (b/27403984).
                mMulticastSocket.close();
                if (mUnicastSocket != null) {
                    mUnicastSocket.close();
                }
            } catch (Exception t) {
                LOGGER.e("Failed to close the mdns socket.", t);
            }
        }
    }

    private void receiveThreadMain(byte[] receiverBuffer, MdnsSocket socket) {
        DatagramPacket packet = new DatagramPacket(receiverBuffer, receiverBuffer.length);

        while (!mShouldStopSocketLoop) {
            try {
                // This is a race with the use of the file descriptor (b/27403984).
                synchronized (mSocketLock) {
                    // This checks is to make sure the socket was not set to null.
                    if (socket != null
                            && (socket == mMulticastSocket || socket == mUnicastSocket)) {
                        socket.receive(packet);
                    }
                }

                if (!mShouldStopSocketLoop) {
                    String responseType = socket == mMulticastSocket
                            ? MULTICAST_TYPE : UNICAST_TYPE;
                    processResponsePacket(packet, responseType);
                }
            } catch (IOException e) {
                if (!mShouldStopSocketLoop) {
                    LOGGER.e("Failed to receive mDNS packets.", e);
                }
            }
        }
        LOGGER.log("Receive thread stopped.");
    }

    private int processResponsePacket(@NonNull DatagramPacket packet, String responseType)
            throws IOException {
        int packetNumber = ++mReceivedPacketNumber;

        List<MdnsResponse> responses = new LinkedList<>();
        int errorCode = mResponseDecoder.decode(packet, responses);
        if (errorCode == MdnsResponseDecoder.SUCCESS) {
            if (responseType.equals(MULTICAST_TYPE)) {
                receivedMulticastResponse = true;
                if (cannotReceiveMulticastResponse.getAndSet(false)) {
                    // If we are already in the bad state, receiving a multicast response means
                    // we are recovered.
                    LOGGER.e(
                            "Recovered from the state where the phone can't receive any multicast"
                                    + " response");
                }
            } else {
                receivedUnicastResponse = true;
            }
            for (MdnsResponse response : responses) {
                String serviceInstanceName = response.getServiceInstanceName();
                LOGGER.log("mDNS %s response received: %s", responseType, serviceInstanceName);
                if (mCallback != null) {
                    mCallback.onResponseReceived(response);
                }
            }
        } else if (errorCode != MdnsResponseErrorCode.ERROR_NOT_RESPONSE_MESSAGE) {
            LOGGER.w(String.format("Error while decoding %s packet (%d): %d",
                    responseType, packetNumber, errorCode));
            if (mCallback != null) {
                mCallback.onFailedToParseMdnsResponse(packetNumber, errorCode);
            }
        }
        return errorCode;
    }

    @VisibleForTesting
    MdnsSocket createMdnsSocket(int port) throws IOException {
        return new MdnsSocket(new MulticastNetworkInterfaceProvider(mContext), port);
    }

    private void sendPackets(List<DatagramPacket> packets, MdnsSocket socket) {
        String requestType = socket == mMulticastSocket ? "multicast" : "unicast";
        for (DatagramPacket packet : packets) {
            if (mShouldStopSocketLoop) {
                break;
            }
            try {
                LOGGER.log("Sending a %s mDNS packet...", requestType);
                socket.send(packet);

                // Start the timer task to monitor the response.
                synchronized (mTimerObject) {
                    if (socket == mMulticastSocket) {
                        if (cannotReceiveMulticastResponse.get()) {
                            // Don't schedule the timer task if we are already in the bad state.
                            return;
                        }
                        if (mCheckMulticastResponseTimer != null) {
                            // Don't schedule the timer task if it's already scheduled.
                            return;
                        }
                        if (mCheckMulticastResponse && mUseSeparateSocketForUnicast) {
                            // Only when mUseSeparateSocketForUnicast is true, we can tell if we
                            // received a multicast or unicast response.
                            mCheckMulticastResponseTimer = new Timer();
                            mCheckMulticastResponseTimer.schedule(
                                    new TimerTask() {
                                        @Override
                                        public void run() {
                                            synchronized (mTimerObject) {
                                                if (mCheckMulticastResponseTimer == null) {
                                                    // Discovery already stopped.
                                                    return;
                                                }
                                                if ((!receivedMulticastResponse)
                                                        && receivedUnicastResponse) {
                                                    LOGGER.e(String.format(
                                                            "Haven't received multicast response"
                                                                    + " in the last %d ms.",
                                                            mCheckMulticastResponseIntervalMs));
                                                    cannotReceiveMulticastResponse.set(true);
                                                }
                                                mCheckMulticastResponseTimer = null;
                                            }
                                        }
                                    },
                                    mCheckMulticastResponseIntervalMs);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.e(String.format("Failed to send a %s mDNS packet.", requestType), e);
            }
        }
        packets.clear();
    }

    public boolean isOnIPv6OnlyNetwork() {
        return mMulticastSocket.isOnIPv6OnlyNetwork();
    }

    /** Callback for {@link MdnsSocketClient}. */
    public interface Callback {
        void onResponseReceived(@NonNull MdnsResponse response);

        void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode);
    }
}
