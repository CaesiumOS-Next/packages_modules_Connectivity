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

import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.StateMachine;

import java.io.PrintWriter;

/** Provides Wear Tether Protocol communication over Bluetooth socket. */
public class WearableConnection extends StateMachine {

    private static final String TAG = "WearTethering";
    private static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);
    private static final int MAX_BUFFERED_INBOUND_PACKETS = 4;
    private static final int MAX_BUFFERED_OUTBOUND_PACKETS = 4;
    private static final int MAX_ETHERNET_PACKET_SIZE = 1522;
    public static final int MAX_WRITE_BUFFERED_SIZE =
            MAX_ETHERNET_PACKET_SIZE * MAX_BUFFERED_OUTBOUND_PACKETS;

    static final int MSG_SEND_WRITE_BUFFER = 1;
    private static final int MSG_BT_OUT_AVAILABLE = 2;
    static final int MSG_BT_READ_STOPPED = 3;

    public static final int MSG_CHECK_SHOULDACK = 10;
    public static final int MSG_CHECK_RETRANSMIT = 11;

    public static final int MSG_TETHER_ENABLED = 50;
    public static final int MSG_TETHER_DISABLED = 51;
    public static final int MSG_DISCONNECT = 52;
    public static final int MSG_SEND_HANDSHAKE = 53;

    private final String mLinkTag;

    private final TunDevice mTun;
    final WriteDataBuffer mWriteBuffer = new WriteDataBuffer(MAX_WRITE_BUFFERED_SIZE);

    private static final SparseArray<String> sWhatToString =
            MessageUtils.findMessageNames(
                    new Class[] {WearableConnection.class}, new String[] {"MSG_"});

    public WearableConnection(String tag, Looper looper, TunDevice tun) {
        super("WearableConnectionSM", looper);
        if (VDBG) {
            setDbg(true);
        }
        mLinkTag = tag;
        mTun = tun;
        mWriteBuffer.mSm = this;
    }

    @Override
    protected String getWhatToString(int what) {
        if (sWhatToString.size() == 0) {
            MessageUtils.findMessageNames(
                    new Class[] {WearableConnection.class}, new String[] {"MSG_"});
        }
        sWhatToString.get(what, "UNKNOWN-" + what);
        return super.getWhatToString(what);
    }

    private String logstr(String msg) {
        return "[BtLink:" + mLinkTag + "] " + msg;
    }

    /**
     * Disconnect BtLink, stop reading or sending packets as there is no connection.
     *
     * <p>Bluetooth has disconnected or adapter is down. Sockets might not immideately signal that
     * other end is not reachable.
     *
     * <p>BtLink will stop all packet processing, stops reading Bluetooth thread, closes socket and
     * quits.
     */
    public void disconnect() {
        sendMessage(MSG_DISCONNECT);
    }

    /** Listener to receive events about buffer availability. */
    public interface WriteBufferAvailableListener {

        /** Callback to handle buffer available event. */
        void onBufferWriteAvailable(WriteDataBuffer buffer, int len);
    }

    /**
     * Stores network packets bytes from TAP to be send to the remote.
     *
     * <p>Window of packets stored in the buffer is controlled by Wear Tethering Flow Control and
     * availability of Bluetooth socket buffers.
     *
     * <p>Size of the WriteDataBuffer is constant; if room is running out for coming network
     * packets, TAP device will stall reading.
     */
    public static class WriteDataBuffer {

        private final byte[] mBuffer;
        private final int mCapacity;
        private WriteBufferAvailableListener mWriteBufferAvailableListener;
        private int mReadPos;
        private int mWritePos;
        private int mSize;
        private int mAvailableMark;
        private StateMachine mSm;

        @VisibleForTesting
        WriteDataBuffer(byte[] buffer, int r, int w, int s) {
            mBuffer = buffer;
            mCapacity = buffer.length;
            mReadPos = r;
            mWritePos = w;
            mSize = s;
        }

        public WriteDataBuffer(int size) {
            this(new byte[size], 0, 0, 0);
        }

        /** Set handler of buffer available events. */
        public void setOnBufferAvailable(int mark, WriteBufferAvailableListener listener) {
            mWriteBufferAvailableListener = listener;
            mAvailableMark = mark;
        }

        /** Move write window; free up len bytes to accept more network data. */
        public void advance(int len) {
            if (len < 0 || len > remaining()) {
                throw new IllegalArgumentException("Invalid len=" + len + ", size=" + remaining());
            }

            mSize -= len;
            if (mSize == 0) {
                // Reset to the beginning for better contiguous access.
                mReadPos = 0;
                mWritePos = 0;
            } else {
                mReadPos = (mReadPos + len) % mCapacity;
            }
            if (mWriteBufferAvailableListener != null && remainingWrite() >= mAvailableMark) {
                mWriteBufferAvailableListener.onBufferWriteAvailable(this, remainingWrite());
                mWriteBufferAvailableListener = null;
            }
        }

        /** return amount of network packets bytes stored in the write buffer. */
        public int remaining() {
            return mSize;
        }

        /** Relative retrieve bytes from buffer, store in array starting from dstPos.*/
        public void get(int offset, byte[] dst, int dstPos, int dstLen) {
            if (offset + dstLen > remaining()) {
                throw new IllegalArgumentException(
                        "Invalid get " + offset + " + " + dstLen + " < size=" + remaining());
            }

            int srcPos = (mReadPos + offset) % mCapacity;

            while (dstLen > 0) {
                final int copyLen = getCopyLen(srcPos, mWritePos, dstLen);
                if (dst != null) {
                    System.arraycopy(mBuffer, srcPos, dst, dstPos, copyLen);
                }
                dstPos += copyLen;
                dstLen -= copyLen;
                srcPos = (srcPos + copyLen) % mCapacity;
            }
        }

        /** Return amount of bytes availale for write to buffer. */
        public int remainingWrite() {
            return mCapacity - mSize;
        }

        private int getCopyLen(int startPos, int endPos, int len) {
            if (startPos < endPos) {
                return Math.min(len, endPos - startPos);
            } else {
                return Math.min(len, mCapacity - startPos);
            }
        }

        /** Realtive put bytes into buffer copy array starting from pos. */
        public void put(byte[] buffer, int pos, int len) {
            if (len > remainingWrite()) {
                throw new IllegalArgumentException(
                        "Invalid len=" + len + ", remaining=" + remainingWrite());
            }

            while (len > 0) {
                final int copyLen = getCopyLen(mWritePos, mReadPos, len);
                System.arraycopy(buffer, pos, mBuffer, mWritePos, copyLen);
                pos += copyLen;
                len -= copyLen;
                mSize += copyLen;
                mWritePos = (mWritePos + copyLen) % mCapacity;
            }

            if (mSm != null) {
                mSm.sendMessage(MSG_SEND_WRITE_BUFFER);
            } else {
                Log.w(TAG, "SM is null");
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{c=");
            sb.append(mCapacity);
            sb.append(",s=");
            sb.append(mSize);
            sb.append(",r=");
            sb.append(mReadPos);
            sb.append(",w=");
            sb.append(mWritePos);
            sb.append('}');
            return sb.toString();
        }
    }

    /** Dump debugging information. */
    public void dump(PrintWriter writer) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(writer, "  ");
        super.dump(null, ipw, null);
        ipw.append("mWriteBuffer:").println(mWriteBuffer);

        ipw.println("\nmTun:");
        ipw.increaseIndent();
        mTun.dump(ipw);
        ipw.decreaseIndent();
    }
}
