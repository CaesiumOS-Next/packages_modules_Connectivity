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

import android.os.SystemClock;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for mDNS records. Stores the header fields and provides methods for reading
 * the record from and writing it to a packet.
 */
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
public abstract class MdnsRecord {
    public static final int TYPE_A = 0x0001;
    public static final int TYPE_AAAA = 0x001C;
    public static final int TYPE_PTR = 0x000C;
    public static final int TYPE_SRV = 0x0021;
    public static final int TYPE_TXT = 0x0010;

    /** Status indicating that the record is current. */
    public static final int STATUS_OK = 0;
    /** Status indicating that the record has expired (TTL reached 0). */
    public static final int STATUS_EXPIRED = 1;
    /** Status indicating that the record should be refreshed (Less than half of TTL remains.) */
    public static final int STATUS_NEEDS_REFRESH = 2;

    protected final String[] mName;
    private final int mType;
    private final int mCls;
    private final long mReceiptTimeMillis;
    private final long mTtlMillis;
    private Object mKey;

    /**
     * Constructs a new record with the given name and type.
     *
     * @param reader The reader to read the record from.
     * @throws IOException If an error occurs while reading the packet.
     */
    protected MdnsRecord(String[] name, int type, MdnsPacketReader reader) throws IOException {
        mName = name;
        mType = type;
        mCls = reader.readUInt16();
        mTtlMillis = TimeUnit.SECONDS.toMillis(reader.readUInt32());
        mReceiptTimeMillis = SystemClock.elapsedRealtime();

        final int dataLength = reader.readUInt16();
        reader.setLimit(dataLength);
        readData(reader);
        reader.clearLimit();
    }

    /**
     * Converts an array of labels into their dot-separated string representation. This method
     * should
     * be used for logging purposes only.
     */
    public static String labelsToString(String[] labels) {
        if (labels == null) {
            return null;
        }
        return TextUtils.join(".", labels);
    }

    /** Tests if |list1| is a suffix of |list2|. */
    public static boolean labelsAreSuffix(String[] list1, String[] list2) {
        int offset = list2.length - list1.length;

        if (offset < 1) {
            return false;
        }

        for (int i = 0; i < list1.length; ++i) {
            if (!list1[i].equals(list2[i + offset])) {
                return false;
            }
        }

        return true;
    }

    /** Returns the record's receipt (creation) time. */
    public final long getReceiptTime() {
        return mReceiptTimeMillis;
    }

    /** Returns the record's name. */
    public String[] getName() {
        return mName;
    }

    /** Returns the record's original TTL, in milliseconds. */
    public final long getTtl() {
        return mTtlMillis;
    }

    /** Returns the record's type. */
    public final int getType() {
        return mType;
    }

    /**
     * Returns the record's remaining TTL.
     *
     * @param now The current system time.
     * @return The remaning TTL, in milliseconds.
     */
    public long getRemainingTTL(final long now) {
        long age = now - mReceiptTimeMillis;
        if (age > mTtlMillis) {
            return 0;
        }

        return mTtlMillis - age;
    }

    /**
     * Reads the record's payload from a packet.
     *
     * @param reader The reader to use.
     * @throws IOException If an I/O error occurs.
     */
    protected abstract void readData(MdnsPacketReader reader) throws IOException;

    /**
     * Writes the record to a packet.
     *
     * @param writer The writer to use.
     * @param now    The current system time. This is used when writing the updated TTL.
     */
    @VisibleForTesting
    public final void write(MdnsPacketWriter writer, long now) throws IOException {
        writer.writeLabels(mName);
        writer.writeUInt16(mType);
        writer.writeUInt16(mCls);

        writer.writeUInt32(TimeUnit.MILLISECONDS.toSeconds(getRemainingTTL(now)));

        int dataLengthPos = writer.getWritePosition();
        writer.writeUInt16(0); // data length
        int dataPos = writer.getWritePosition();

        writeData(writer);

        // Calculate amount of data written, and overwrite the data field earlier in the packet.
        int endPos = writer.getWritePosition();
        int dataLength = endPos - dataPos;
        writer.rewind(dataLengthPos);
        writer.writeUInt16(dataLength);
        writer.unrewind();
    }

    /**
     * Writes the record's payload to a packet.
     *
     * @param writer The writer to use.
     * @throws IOException If an I/O error occurs.
     */
    protected abstract void writeData(MdnsPacketWriter writer) throws IOException;

    /** Gets the status of the record. */
    public int getStatus(final long now) {
        final long age = now - mReceiptTimeMillis;
        if (age > mTtlMillis) {
            return STATUS_EXPIRED;
        }
        if (age > (mTtlMillis / 2)) {
            return STATUS_NEEDS_REFRESH;
        }
        return STATUS_OK;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MdnsRecord)) {
            return false;
        }

        MdnsRecord otherRecord = (MdnsRecord) other;

        return Arrays.equals(mName, otherRecord.mName) && (mType == otherRecord.mType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(mName), mType);
    }

    /**
     * Returns an opaque object that uniquely identifies this record through a combination of its
     * type
     * and name. Suitable for use as a key in caches.
     */
    public final Object getKey() {
        if (mKey == null) {
            mKey = new Key(mType, mName);
        }
        return mKey;
    }

    private static final class Key {
        private final int mRecordType;
        private final String[] mRecordName;

        Key(int recordType, String[] recordName) {
            mRecordType = recordType;
            mRecordName = recordName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }

            Key otherKey = (Key) other;

            return (mRecordType == otherKey.mRecordType) && Arrays.equals(mRecordName,
                    otherKey.mRecordName);
        }

        @Override
        public int hashCode() {
            return (mRecordType * 31) + Arrays.hashCode(mRecordName);
        }
    }
}
