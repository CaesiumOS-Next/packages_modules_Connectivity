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

package com.android.threadnetwork.demoapp;

import static com.google.common.io.BaseEncoding.base16;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class Ipv6VpnService extends VpnService {
    public static final String BROADCAST_VPN_STATUS =
            "com.android.threadnetwork.demoapp.Ipv6VpnService.STATUS";
    public static final String BROADCAST_VPN_STOP =
            "com.android.threadnetwork.demoapp.Ipv6VpnService.STOP";
    private static final String TAG = Ipv6VpnService.class.getSimpleName();
    private static final int MTU = 1280;
    private static AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread mThread;
    private BroadcastReceiver stopReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) {
                        return;
                    }
                    if (BROADCAST_VPN_STOP.equals(intent.getAction())) {
                        stop();
                    }
                }
            };

    public static Intent getServiceIntent(Context context) {
        return new Intent(context, Ipv6VpnService.class);
    }

    public static boolean isRunning() {
        return isRunning.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        registerReceiver(stopReceiver, new IntentFilter(BROADCAST_VPN_STOP));
        start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private void start() {
        if (mThread != null) {
            Log.w(TAG, "The VPN service is already running");
            return;
        }

        Log.i(TAG, "Starting the VPN service");

        mThread =
                new Thread(
                        () -> {
                            ParcelFileDescriptor tunIf =
                                    new Builder()
                                            .setSession("MyVPNService")
                                            .addAddress("2001:1983::de8", 128)
                                            .addRoute("2001:1983::", 64)
                                            .setMtu(MTU)
                                            .setBlocking(false)
                                            .allowBypass()
                                            .establish();

                            StructPollfd[] fds = new StructPollfd[1];
                            byte[] packet = new byte[MTU];
                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    fds[0] = new StructPollfd();
                                    fds[0].fd = tunIf.getFileDescriptor();
                                    fds[0].events = (short) OsConstants.POLLIN;
                                    fds[0].revents = 0;
                                    fds[0].userData = null;

                                    int res = Os.poll(fds, 1000);
                                    if (res == 0) {
                                        continue;
                                    } else if (res == -1) {
                                        Log.e(TAG, "Os.poll() failed");
                                        break;
                                    }

                                    int length =
                                            Os.read(
                                                    tunIf.getFileDescriptor(),
                                                    packet,
                                                    0,
                                                    packet.length);
                                    if (length > 0) {
                                        Log.i(
                                                TAG,
                                                String.format(
                                                        "received user input: length=%d, %s",
                                                        length,
                                                        base16().encode(packet, 0, length)));
                                    }
                                } catch (InterruptedIOException e) {
                                    Log.i(TAG, "The Tun interface IO thread is interrupted");
                                    break;
                                } catch (ErrnoException e) {
                                    Log.e(TAG, "Failed to read from tun interface", e);
                                    break;
                                }
                            }

                            try {
                                tunIf.close();
                            } catch (IOException e) {
                                // Ignore
                            }
                        },
                        "MyThreadVPNRunnable");

        mThread.start();
        isRunning.set(true);
    }

    private void stop() {
        if (mThread != null) {
            Log.i(TAG, "Stopping the VPN service");

            mThread.interrupt();
            try {
                mThread.join();
            } catch (InterruptedException e) {
                // Ignore
            }
            mThread = null;
            isRunning.set(false);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy of my VPN service");
        stop();
        unregisterReceiver(stopReceiver);
        super.onDestroy();
    }
}
