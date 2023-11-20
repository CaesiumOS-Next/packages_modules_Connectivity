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

package com.android.networkstack.tethering.wear;

import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.LocalLog;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.net.module.util.PacketReader;
import com.android.networkstack.tethering.wear.WearableConnection.WriteDataBuffer;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

/** TUN network interface non-blocking IO. */
public class TunDevice extends PacketReader {

    private static final String TAG = "WearTethering";
    private static final boolean VDBG = true;

    private final String mInterfaceName;
    private final ParcelFileDescriptor mPfd;
    private WriteDataBuffer mWriteDataBuffer;
    private final LocalLog mLog = new LocalLog(500);

    public TunDevice(Handler handler, String name, ParcelFileDescriptor pfd) {
        super(handler);
        this.mInterfaceName = name;
        this.mPfd = pfd;
    }

    /**
     * Activate asynchronous processing of TUN network packets.
     *
     * <p>Start fill the buffer when TUN data available; start accepting transfer call to send
     * network data to TUN.
     */
    public void start(WriteDataBuffer buffer) {
        mWriteDataBuffer = buffer;
        start();
    }

    /** send network packet to TUN. */
    public boolean sendNetworkPacket(ByteBuffer packet) {
        if (VDBG) {
            Log.d(TAG, logstr("transfer: " + packet.remaining()));
        }

        ByteBuffer savePacket = packet.duplicate();
        try {
            int len = packet.remaining();
            mLog.log("write " + len);
            int written = Os.write(mPfd.getFileDescriptor(), packet);
            if (written != len) {
                logError(
                        "Abort TAP. write return " + written,
                        new IOException("Invalid result from Os.write"));
            }
            return true;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EAGAIN) {
                Log.e(TAG, "transfer: EAGAIN  " + packet.remaining());
            }
            handleReadError(e);
        } catch (InterruptedIOException e) {
            logError("TUN closed inside transfer", e);
        }
        return false;
    }

    @Override
    protected void handlePacket(@NonNull byte[] recvbuf, int length) {
        if (VDBG) {
            Log.d(TAG, logstr("handlePacket: " + length));
        }
        mLog.log("read " + length);
        mWriteDataBuffer.put(recvbuf, 0, length);
        if (mWriteDataBuffer.remainingWrite() < DEFAULT_RECV_BUF_SIZE) {
            // clear INTPUT events and set them when buffer available
            stop();
            mLog.log("pause read; free buffer " + mWriteDataBuffer.remainingWrite());
            mWriteDataBuffer.setOnBufferAvailable(
                    DEFAULT_RECV_BUF_SIZE,
                    (b, l) -> {
                        mLog.log("resume read: free buffer " + l);
                        start();
                    });
        }
    }

    @Nullable
    @Override
    protected FileDescriptor createFd() {
        try {
            FileDescriptor fd = mPfd.dup().getFileDescriptor();
            IoUtils.setBlocking(fd, false);
            return fd;
        } catch (IOException e) {
            Log.e(TAG, "createFd: Failed prepare fd- " + mPfd.getFd() + " for async read: ", e);
            return null;
        }
    }

    @Override
    protected void logError(@NonNull String msg, @Nullable Exception e) {
        if (e != null) {
            Log.e(TAG, logstr("Error: " + msg), e);
        } else {
            Log.e(TAG, logstr("Error: " + msg));
        }
        mLog.log("Error " + msg + (e != null ? e.toString() : ""));
        // TODO: Abort tethering connection
    }

    private String logstr(String msg) {
        return "[tun:" + mInterfaceName + "] " + msg;
    }

    /** Dump debugging information. */
    public void dump(PrintWriter pw) {
        mLog.dump(pw);
    }
}
