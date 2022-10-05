/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.connectivity.mdns;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.net.module.util.PacketReader;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.MulticastSocket;

public class MulticastPacketReader extends PacketReader {
    @NonNull
    private final MulticastSocket mSocket;
    @NonNull
    private ParcelFileDescriptor mPfd;
    @NonNull
    private final PacketHandler mPacketHandler;

    interface PacketHandler {
        void handlePacket(byte[] recvbuf, int length);
    }

    /**
     * Create a new {@link MulticastPacketReader}.
     * @param socket Socket to read from. This will *not* be closed when the reader terminates.
     * @param buffer Buffer to read packets into. Will only be used from the handler thread.
     */
    protected MulticastPacketReader(@NonNull MulticastSocket socket, @NonNull Handler h,
            @NonNull byte[] buffer, @NonNull PacketHandler packetHandler) {
        super(h, buffer);
        // This creates a dup() of the socket fd, which MdnsPacketReader will close on exit, but the
        // original socket needs to be closed by the caller.
        mSocket = socket;
        mPacketHandler = packetHandler;
    }

    @Override
    protected FileDescriptor createFd() {
        if (mPfd != null) throw new IllegalStateException("Fd already created");
        // Keep a reference to the PFD as it would close the fd in its finalizer otherwise
        mPfd = ParcelFileDescriptor.fromDatagramSocket(mSocket);
        return mPfd.getFileDescriptor();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            mPfd.close();
        } catch (IOException e) {
            Log.e(MulticastPacketReader.class.getSimpleName(), "Error closing fd", e);
        }
    }

    @Override
    protected void handlePacket(byte[] recvbuf, int length) {
        mPacketHandler.handlePacket(recvbuf, length);
    }
}
