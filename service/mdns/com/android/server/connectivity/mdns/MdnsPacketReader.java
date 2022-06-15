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

import android.util.SparseArray;

import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Simple decoder for mDNS packets. */
public class MdnsPacketReader {
    private final byte[] mBuffer;
    private final int mCount;
    private final SparseArray<LabelEntry> mLabelDictionary;
    private int mPosition;
    private int mLimit;

    /** Constructs a reader for the given packet. */
    public MdnsPacketReader(DatagramPacket packet) {
        mBuffer = packet.getData();
        mCount = packet.getLength();
        mPosition = 0;
        mLimit = -1;
        mLabelDictionary = new SparseArray<>(16);
    }

    /**
     * Sets a temporary limit (from the current read position) for subsequent reads. Any attempt to
     * read past this limit will result in an EOFException.
     *
     * @param limit The new limit.
     * @throws IOException If there is insufficient data for the new limit.
     */
    public void setLimit(int limit) throws IOException {
        if (limit >= 0) {
            if (mPosition + limit <= mCount) {
                mLimit = mPosition + limit;
            } else {
                throw new IOException(
                        String.format(
                                Locale.ROOT,
                                "attempt to set limit beyond available data: %d exceeds %d",
                                mPosition + limit,
                                mCount));
            }
        }
    }

    /** Clears the limit set by {@link #setLimit}. */
    public void clearLimit() {
        mLimit = -1;
    }

    /**
     * Returns the number of bytes left to read, between the current read position and either the
     * limit (if set) or the end of the packet.
     */
    public int getRemaining() {
        return (mLimit >= 0 ? mLimit : mCount) - mPosition;
    }

    /**
     * Reads an unsigned 8-bit integer.
     *
     * @throws EOFException If there are not enough bytes remaining in the packet to satisfy the
     *                      read.
     */
    public int readUInt8() throws EOFException {
        checkRemaining(1);
        byte val = mBuffer[mPosition++];
        return val & 0xFF;
    }

    /**
     * Reads an unsigned 16-bit integer.
     *
     * @throws EOFException If there are not enough bytes remaining in the packet to satisfy the
     *                      read.
     */
    public int readUInt16() throws EOFException {
        checkRemaining(2);
        int val = (mBuffer[mPosition++] & 0xFF) << 8;
        val |= (mBuffer[mPosition++]) & 0xFF;
        return val;
    }

    /**
     * Reads an unsigned 32-bit integer.
     *
     * @throws EOFException If there are not enough bytes remaining in the packet to satisfy the
     *                      read.
     */
    public long readUInt32() throws EOFException {
        checkRemaining(4);
        long val = (long) (mBuffer[mPosition++] & 0xFF) << 24;
        val |= (long) (mBuffer[mPosition++] & 0xFF) << 16;
        val |= (long) (mBuffer[mPosition++] & 0xFF) << 8;
        val |= mBuffer[mPosition++] & 0xFF;
        return val;
    }

    /**
     * Reads a sequence of labels and returns them as an array of strings. A sequence of labels is
     * either a sequence of strings terminated by a NUL byte, a sequence of strings terminated by a
     * pointer, or a pointer.
     *
     * @throws EOFException If there are not enough bytes remaining in the packet to satisfy the
     *                      read.
     * @throws IOException  If invalid data is read.
     */
    public String[] readLabels() throws IOException {
        List<String> result = new ArrayList<>(5);
        LabelEntry previousEntry = null;

        while (getRemaining() > 0) {
            byte nextByte = peekByte();

            if (nextByte == 0) {
                // A NUL byte terminates a sequence of labels.
                skip(1);
                break;
            }

            int currentOffset = mPosition;

            boolean isLabelPointer = (nextByte & 0xC0) == 0xC0;
            if (isLabelPointer) {
                // A pointer terminates a sequence of labels. Store the pointer value in the
                // previous label entry.
                int labelOffset = ((readUInt8() & 0x3F) << 8) | (readUInt8() & 0xFF);
                if (previousEntry != null) {
                    previousEntry.nextOffset = labelOffset;
                }

                // Follow the chain of labels starting at this pointer, adding all of them onto the
                // result.
                while (labelOffset != 0) {
                    LabelEntry entry = mLabelDictionary.get(labelOffset);
                    if (entry == null) {
                        throw new IOException(
                                String.format(Locale.ROOT, "Invalid label pointer: %04X",
                                        labelOffset));
                    }
                    result.add(entry.label);
                    labelOffset = entry.nextOffset;
                }
                break;
            } else {
                // It's an ordinary label. Chain it onto the previous label entry (if any), and add
                // it onto the result.
                String val = readString();
                LabelEntry newEntry = new LabelEntry(val);
                mLabelDictionary.put(currentOffset, newEntry);

                if (previousEntry != null) {
                    previousEntry.nextOffset = currentOffset;
                }
                previousEntry = newEntry;
                result.add(val);
            }
        }

        return result.toArray(new String[result.size()]);
    }

    /**
     * Reads a length-prefixed string.
     *
     * @throws EOFException If there are not enough bytes remaining in the packet to satisfy the
     *                      read.
     */
    public String readString() throws EOFException {
        int len = readUInt8();
        checkRemaining(len);
        String val = new String(mBuffer, mPosition, len, MdnsConstants.getUtf8Charset());
        mPosition += len;
        return val;
    }

    /**
     * Reads a specific number of bytes.
     *
     * @param bytes The array to fill.
     * @throws EOFException If there are not enough bytes remaining in the packet to satisfy the
     *                      read.
     */
    public void readBytes(byte[] bytes) throws EOFException {
        checkRemaining(bytes.length);
        System.arraycopy(mBuffer, mPosition, bytes, 0, bytes.length);
        mPosition += bytes.length;
    }

    /**
     * Skips over the given number of bytes.
     *
     * @param count The number of bytes to read and discard.
     * @throws EOFException If there are not enough bytes remaining in the packet to satisfy the
     *                      read.
     */
    public void skip(int count) throws EOFException {
        checkRemaining(count);
        mPosition += count;
    }

    /**
     * Peeks at and returns the next byte in the packet, without advancing the read position.
     *
     * @throws EOFException If there are not enough bytes remaining in the packet to satisfy the
     *                      read.
     */
    public byte peekByte() throws EOFException {
        checkRemaining(1);
        return mBuffer[mPosition];
    }

    /** Returns the current byte position of the reader for the data packet. */
    public int getPosition() {
        return mPosition;
    }

    // Checks if the number of remaining bytes to be read in the packet is at least |count|.
    private void checkRemaining(int count) throws EOFException {
        if (getRemaining() < count) {
            throw new EOFException();
        }
    }

    private static class LabelEntry {
        public final String label;
        public int nextOffset = 0;

        LabelEntry(String label) {
            this.label = label;
        }
    }
}
