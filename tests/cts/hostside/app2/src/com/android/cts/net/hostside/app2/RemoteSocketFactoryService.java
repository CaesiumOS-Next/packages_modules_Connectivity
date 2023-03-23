/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.cts.net.hostside.app2;

import android.app.Service;
import android.content.Intent;
import android.net.InetAddresses;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import com.android.cts.net.hostside.IRemoteSocketFactory;

import java.io.FileDescriptor;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;


public class RemoteSocketFactoryService extends Service {

    private static final String TAG = RemoteSocketFactoryService.class.getSimpleName();

    private IRemoteSocketFactory.Stub mBinder = new IRemoteSocketFactory.Stub() {
        @Override
        public ParcelFileDescriptor openSocketFd(String host, int port, int timeoutMs) {
            try {
                Socket s = new Socket(host, port);
                s.setSoTimeout(timeoutMs);
                return ParcelFileDescriptor.fromSocket(s);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public String getPackageName() {
            return RemoteSocketFactoryService.this.getPackageName();
        }

        @Override
        public int getUid() {
            return Process.myUid();
        }

        @Override
        public ParcelFileDescriptor openDatagramSocketFd() {
            try {
                final DatagramSocket s = new DatagramSocket();
                return ParcelFileDescriptor.fromDatagramSocket(s);
            } catch (SocketException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public ParcelFileDescriptor[] openLoopbackSocketFdPair(int timeoutMs) {
            try {
                final ServerSocket listen = new ServerSocket(0);
                final Socket client = new Socket();
                final InetSocketAddress sockAddr = new InetSocketAddress(
                        InetAddresses.parseNumericAddress("::1"), listen.getLocalPort());
                client.connect(sockAddr, timeoutMs);

                listen.setSoTimeout(timeoutMs);
                final Socket server = listen.accept();
                listen.close();

                // Using Os.setsockoptTimeval instead of Socket#setSoTimeout since
                // openLoopbackSocketFdPair returns fds. Socket#setSoTimeout keeps timeout value in
                // Socket object and does not pass to FileDescriptor or kernel. So timeout value is
                // ignored if read is called directly with fd.
                final FileDescriptor clientFd = client.getFileDescriptor$();
                final FileDescriptor serverFd = server.getFileDescriptor$();
                Os.setsockoptTimeval(clientFd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                        StructTimeval.fromMillis(timeoutMs));
                Os.setsockoptTimeval(serverFd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                        StructTimeval.fromMillis(timeoutMs));
                return new ParcelFileDescriptor[]{
                        ParcelFileDescriptor.dup(serverFd),
                        ParcelFileDescriptor.dup(clientFd),
                };
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
