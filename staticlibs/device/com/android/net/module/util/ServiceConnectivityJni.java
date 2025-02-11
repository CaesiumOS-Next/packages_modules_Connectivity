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

package com.android.net.module.util;

import android.annotation.NonNull;
import android.system.ErrnoException;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.NioUtils;

/**
 * Contains JNI functions for use in service-connectivity
 */
public class ServiceConnectivityJni {
    static {
        final String libName = JniUtil.getJniLibraryName(ServiceConnectivityJni.class.getPackage());
        if (libName.equals("android_net_connectivity_com_android_net_module_util_jni")) {
            // This library is part of service-connectivity.jar when in the system server,
            // so libservice-connectivity.so is the library to load.
            System.loadLibrary("service-connectivity");
        } else {
            System.loadLibrary(libName);
        }
    }

    /**
     * Create a timerfd.
     *
     * @throws ErrnoException if the timerfd creation is failed.
     */
    public static native int createTimerFd() throws ErrnoException;

    /**
     * Set given time to the timerfd.
     *
     * @param timeMs target time
     * @throws ErrnoException if setting expiration time is failed.
     */
    public static native void setTimerFdTime(int fd, long timeMs) throws ErrnoException;

    /** Create tun/tap interface */
    public static native int createTunTap(boolean isTun, boolean hasCarrier,
            boolean setIffMulticast, @NonNull String iface);

    /** Enable carrier on tun/tap interface */
    public static native void setTunTapCarrierEnabled(@NonNull String iface, int tunFd,
            boolean enabled);

    /** Bring up tun/tap interface */
    public static native void bringUpInterface(String iface);

    /**
     * Receives a message from a socket and writes it in a ByteBuffer. When a
     * LinkLayerSocketAddress is provided, it is filled with the source address of the message.
     *
     * See <a href="http://man7.org/linux/man-pages/man2/recvfrom.2.html">recvfrom(2)</a>.
     *
     * @param fd The socket file descriptor.
     * @param buffer The ByteBuffer where to put the data.
     * @param flags The flags controlling the call.
     * @param llSocketAddress The holder for the link layer address.
     * @return the number of bytes received or a negative value on failure.
     * @throws ErrnoException if recvfrom fails.
     */
    public static int recvfrom(FileDescriptor fd, ByteBuffer buffer, int flags,
            LinkLayerSocketAddress llSocketAddress) throws ErrnoException {
        final int bytesReceived;
        final int position = buffer.position();

        if (buffer.isDirect()) {
            bytesReceived = recvfromBytes(fd.getInt$(), buffer.array(), position,
                    buffer.remaining(), flags, llSocketAddress);
        } else {
            bytesReceived = recvfromBytes(fd.getInt$(), NioUtils.unsafeArray(buffer),
                    NioUtils.unsafeArrayOffset(buffer) + position, buffer.remaining(),
                    flags, llSocketAddress);
        }

        if (bytesReceived > 0) {
            buffer.position(position + bytesReceived);
        }
        return bytesReceived;
    }

    /**
     * Receive a message from a socket in a byte buffer and collect the link layer source address.
     *
     * @param fd The socket file descriptor.
     * @param buffer The buffer to fill with the content received.
     * @param byteOffset The offset to write in the buffer.
     * @param byteCount The number of bytes available in the buffer.
     * @param flags The flags controlling the call.
     * @param llSocketAddress The holder for the link layer address.
     * @return the number of bytes written in the buffer or a negative value on failure.
     * @throws ErrnoException if recvfrom fails.
     */
    private static native int recvfromBytes(int fd, byte[] buffer, int byteOffset,
            int byteCount, int flags, LinkLayerSocketAddress llSocketAddress)
            throws ErrnoException;
}
