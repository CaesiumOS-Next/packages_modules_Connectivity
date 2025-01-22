/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.net;

import android.bluetooth.BluetoothSocket;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Forwards packets from a BluetoothSocket of type L2CAP to a tun fd and vice versa.
 */
public class L2capPacketForwarder {
    private static final String TAG = "L2capPacketForwarder";

    // rfc7668 recommends 1280
    // TODO: Set /proc/sys/net/ipv6/conf/${iface}/mtu to 1280 and the link MTU to 1528 to accept
    // slightly larger packets on ingress.
    // MTU determines the value of the read buffers, so use the larger of the two.
    @VisibleForTesting
    public static final int MTU = 1528;
    private final IReadWriteFd mTunFd;
    private final IReadWriteFd mL2capFd;
    private final L2capThread mIngressThread;
    private final L2capThread mEgressThread;

    private interface IReadWriteFd {
        /** Read from fd */
        int read(byte[] bytes, int off, int len) throws IOException;
        /** Write to fd */
        void write(byte[] bytes, int off, int len) throws IOException;
        /** Shutdown read end */
        void shutdownRead();
        /** Shutdown write end */
        void shutdownWrite();
        /** Close the fd */
        void close();
    }

    @VisibleForTesting
    public static class BluetoothSocketWrapper implements IReadWriteFd {
        private final BluetoothSocket mSocket;
        private final InputStream mInputStream;
        private final OutputStream mOutputStream;

        public BluetoothSocketWrapper(BluetoothSocket socket) throws IOException {
            // TODO: assert that MTU can fit within SDU.
            mSocket = socket;
            try {
                mInputStream = socket.getInputStream();
                mOutputStream = socket.getOutputStream();
            } catch (IOException e) {
                // Per the API docs, this should not actually be possible.
                Log.wtf(TAG, "Failed to get Input/OutputStream", e);
                throw e;
            }
        }

        /**
         * Read from BluetoothSocket.
         */
        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            int bytesRead = mInputStream.read(bytes, off, len);
            if (bytesRead > MTU) {
                // b/391623333: BluetoothSockets of type L2CAP use an intermediate read buffer of
                // size = LE L2CAP SDU (64KB for devices without hardware offload support) and do
                // not properly expose SocketInputStream / SOCK_SEQPACKET semantics. This means that
                // a partial read does not drop the entire packet.  So if a packet exceeds MTU
                // (which is smaller than SDU / the BluetoothSocket's intermediate buffer), read
                // from the InputStream until the read fits within MTU.  Ignore all bytes including
                // the last read. This has the unfortunate side effect that if a packet is exactly
                // the size of the read buffer (MTU + 1), the next packet will be dropped as well
                // (if it fits within MTU).
                //
                // b/391623333: Note that mInputStream.available() is broken for L2CAP sockets and
                // cannot be used here. TODO: consider using ParcelFileDescriptor directly to fix
                // this.
                do {
                    bytesRead = mInputStream.read(bytes, off, len);
                } while (bytesRead > MTU);
                return -1;
            }
            return bytesRead;
        }

        /**
         * Write to BluetoothSocket.
         */
        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            mOutputStream.write(bytes, off, len);
        }

        @Override
        public void shutdownRead() {
            // BluetoothSocket does not expose methods to shutdown read / write individually;
            // however, BluetoothSocket#close() shuts down both read and write and is safe to be
            // called multiple times from any thread.
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "shutdownRead: Failed to close BluetoothSocket", e);
            }
        }

        @Override
        public void shutdownWrite() {
            // BluetoothSocket does not expose methods to shutdown read / write individually;
            // however, BluetoothSocket#close() shuts down both read and write and is safe to be
            // called multiple times from any thread.
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "shutdownWrite: Failed to close BluetoothSocket", e);
            }
        }

        @Override
        public void close() {
            // BluetoothSocket#close() is safe to be called multiple times.
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "close: Failed to close BluetoothSocket", e);
            }
        }
    }

    @VisibleForTesting
    public static class FdWrapper implements IReadWriteFd {
        private final FileDescriptor mFd;

        public FdWrapper(FileDescriptor fd) {
            mFd = fd;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            try {
                return Os.read(mFd, bytes, off, len);
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
        }

        /**
         * Write to BluetoothSocket.
         */
        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            try {
                Os.write(mFd, bytes, off, len);
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void shutdownRead() {
            try {
                Os.shutdown(mFd, OsConstants.SHUT_RD);
            } catch (ErrnoException e) {
                Log.w(TAG, "shutdownRead: Failed to shutdown(fd, SHUT_RD)", e);
            }
        }

        @Override
        public void shutdownWrite() {
            try {
                Os.shutdown(mFd, OsConstants.SHUT_WR);
            } catch (ErrnoException e) {
                Log.w(TAG, "shutdownWrite: Failed to shutdown(fd, SHUT_WR)", e);
            }
        }

        @Override
        public void close() {
            try {
                Os.close(mFd);
            } catch (ErrnoException e) {
                Log.w(TAG, "close: Failed to close fd", e);
            }
        }
    }

    private class L2capThread extends Thread {
        // Set mBuffer length to MTU + 1 to catch read() overflows.
        private final byte[] mBuffer = new byte[MTU + 1];
        private volatile boolean mIsRunning = true;

        private final String mLogTag;
        private IReadWriteFd mReadFd;
        private IReadWriteFd mWriteFd;

        L2capThread(String logTag, IReadWriteFd readFd, IReadWriteFd writeFd) {
            mLogTag = logTag;
            mReadFd = readFd;
            mWriteFd = writeFd;
        }

        @Override
        public void run() {
            while (mIsRunning) {
                try {
                    final int readBytes = mReadFd.read(mBuffer, 0 /*off*/, mBuffer.length);
                    // No bytes to write, continue.
                    if (readBytes <= 0) continue;

                    // If the packet exceeds MTU, drop it. IReadWriteFd#read() implements
                    // SOCK_SEQPACKET semantics.
                    if (readBytes > MTU) continue;

                    mWriteFd.write(mBuffer, 0 /*off*/, readBytes);
                } catch (IOException e) {
                    Log.e(mLogTag, "L2capThread exception", e);
                }
            }
        }

        public void tearDown() {
            mIsRunning = false;

            mReadFd.shutdownRead();
            mWriteFd.shutdownWrite();

            try {
                join();
            } catch (InterruptedException e) {
                // join() interrupted in tearDown path, do nothing.
            }
        }
    }

    @VisibleForTesting
    L2capPacketForwarder(IReadWriteFd tunFd, IReadWriteFd l2capFd) {
        mTunFd = tunFd;
        mL2capFd = l2capFd;

        mIngressThread = new L2capThread("L2capThread-Ingress", l2capFd, tunFd);
        mEgressThread = new L2capThread("L2capThread-Egress", tunFd, l2capFd);

        mIngressThread.start();
        mEgressThread.start();
    }

    /**
     * Tear down the L2capPacketForwarder.
     *
     * This operation closes both the passed tun fd and BluetoothSocket.
     **/
    public void tearDown() {
        mIngressThread.tearDown();
        mEgressThread.tearDown();

        // In order to interrupt a blocking read on the BluetoothSocket, the BluetoothSocket must be
        // closed (which triggers shutdown()). This means, the BluetoothSocket must be closed inside
        // L2capPacketForwarder. Tear down the tun fd alongside it for consistency.
        mTunFd.close();
        mL2capFd.close();
    }
}
