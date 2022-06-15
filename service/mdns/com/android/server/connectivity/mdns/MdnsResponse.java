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

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/** An mDNS response. */
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
public class MdnsResponse {
    private final List<MdnsRecord> mRecords;
    private final List<MdnsPointerRecord> mPointerRecords;
    private MdnsServiceRecord mServiceRecord;
    private MdnsTextRecord mTextRecord;
    private MdnsInetAddressRecord mInet4AddressRecord;
    private MdnsInetAddressRecord mInet6AddressRecord;
    private long mLastUpdateTime;

    /** Constructs a new, empty response. */
    public MdnsResponse(long now) {
        mLastUpdateTime = now;
        mRecords = new LinkedList<>();
        mPointerRecords = new LinkedList<>();
    }

    // This generic typed helper compares records for equality.
    // Returns True if records are the same.
    private <T> boolean recordsAreSame(T a, T b) {
        return ((a == null) && (b == null)) || ((a != null) && (b != null) && a.equals(b));
    }

    /**
     * Adds a pointer record.
     *
     * @return <code>true</code> if the record was added, or <code>false</code> if a matching
     * pointer
     * record is already present in the response.
     */
    public synchronized boolean addPointerRecord(MdnsPointerRecord pointerRecord) {
        if (!mPointerRecords.contains(pointerRecord)) {
            mPointerRecords.add(pointerRecord);
            mRecords.add(pointerRecord);
            return true;
        }

        return false;
    }

    /** Gets the pointer records. */
    public synchronized List<MdnsPointerRecord> getPointerRecords() {
        // Returns a shallow copy.
        return new LinkedList<>(mPointerRecords);
    }

    public synchronized boolean hasPointerRecords() {
        return !mPointerRecords.isEmpty();
    }

    @VisibleForTesting
    /* package */ synchronized void clearPointerRecords() {
        mPointerRecords.clear();
    }

    public synchronized boolean hasSubtypes() {
        for (MdnsPointerRecord pointerRecord : mPointerRecords) {
            if (pointerRecord.hasSubtype()) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<String> getSubtypes() {
        List<String> subtypes = null;

        for (MdnsPointerRecord pointerRecord : mPointerRecords) {
            if (pointerRecord.hasSubtype()) {
                if (subtypes == null) {
                    subtypes = new LinkedList<>();
                }
                subtypes.add(pointerRecord.getSubtype());
            }
        }

        return subtypes;
    }

    @VisibleForTesting
    public synchronized void removeSubtypes() {
        Iterator<MdnsPointerRecord> iter = mPointerRecords.iterator();
        while (iter.hasNext()) {
            MdnsPointerRecord pointerRecord = iter.next();
            if (pointerRecord.hasSubtype()) {
                iter.remove();
            }
        }
    }

    /** Sets the service record. */
    public synchronized boolean setServiceRecord(MdnsServiceRecord serviceRecord) {
        if (recordsAreSame(mServiceRecord, serviceRecord)) {
            return false;
        }
        if (mServiceRecord != null) {
            mRecords.remove(mServiceRecord);
        }
        mServiceRecord = serviceRecord;
        if (mServiceRecord != null) {
            mRecords.add(mServiceRecord);
        }
        return true;
    }

    /** Gets the service record. */
    public synchronized MdnsServiceRecord getServiceRecord() {
        return mServiceRecord;
    }

    public synchronized boolean hasServiceRecord() {
        return mServiceRecord != null;
    }

    /** Sets the text record. */
    public synchronized boolean setTextRecord(MdnsTextRecord textRecord) {
        if (recordsAreSame(mTextRecord, textRecord)) {
            return false;
        }
        if (mTextRecord != null) {
            mRecords.remove(mTextRecord);
        }
        mTextRecord = textRecord;
        if (mTextRecord != null) {
            mRecords.add(mTextRecord);
        }
        return true;
    }

    /** Gets the text record. */
    public synchronized MdnsTextRecord getTextRecord() {
        return mTextRecord;
    }

    public synchronized boolean hasTextRecord() {
        return mTextRecord != null;
    }

    /** Sets the IPv4 address record. */
    public synchronized boolean setInet4AddressRecord(MdnsInetAddressRecord newInet4AddressRecord) {
        if (recordsAreSame(mInet4AddressRecord, newInet4AddressRecord)) {
            return false;
        }
        if (mInet4AddressRecord != null) {
            mRecords.remove(mInet4AddressRecord);
        }
        if (newInet4AddressRecord != null && newInet4AddressRecord.getInet4Address() != null) {
            mInet4AddressRecord = newInet4AddressRecord;
            mRecords.add(mInet4AddressRecord);
        }
        return true;
    }

    /** Gets the IPv4 address record. */
    public synchronized MdnsInetAddressRecord getInet4AddressRecord() {
        return mInet4AddressRecord;
    }

    public synchronized boolean hasInet4AddressRecord() {
        return mInet4AddressRecord != null;
    }

    /** Sets the IPv6 address record. */
    public synchronized boolean setInet6AddressRecord(MdnsInetAddressRecord newInet6AddressRecord) {
        if (recordsAreSame(mInet6AddressRecord, newInet6AddressRecord)) {
            return false;
        }
        if (mInet6AddressRecord != null) {
            mRecords.remove(mInet6AddressRecord);
        }
        if (newInet6AddressRecord != null && newInet6AddressRecord.getInet6Address() != null) {
            mInet6AddressRecord = newInet6AddressRecord;
            mRecords.add(mInet6AddressRecord);
        }
        return true;
    }


    /** Gets the IPv6 address record. */
    public synchronized MdnsInetAddressRecord getInet6AddressRecord() {
        return mInet6AddressRecord;
    }

    public synchronized boolean hasInet6AddressRecord() {
        return mInet6AddressRecord != null;
    }

    /** Gets all of the records. */
    public synchronized List<MdnsRecord> getRecords() {
        return new LinkedList<>(mRecords);
    }

    /**
     * Merges any records that are present in another response into this one.
     *
     * @return <code>true</code> if any records were added or updated.
     */
    public synchronized boolean mergeRecordsFrom(MdnsResponse other) {
        mLastUpdateTime = other.mLastUpdateTime;

        boolean updated = false;

        List<MdnsPointerRecord> pointerRecords = other.getPointerRecords();
        if (pointerRecords != null) {
            for (MdnsPointerRecord pointerRecord : pointerRecords) {
                if (addPointerRecord(pointerRecord)) {
                    updated = true;
                }
            }
        }

        MdnsServiceRecord serviceRecord = other.getServiceRecord();
        if (serviceRecord != null) {
            if (setServiceRecord(serviceRecord)) {
                updated = true;
            }
        }

        MdnsTextRecord textRecord = other.getTextRecord();
        if (textRecord != null) {
            if (setTextRecord(textRecord)) {
                updated = true;
            }
        }

        MdnsInetAddressRecord otherInet4AddressRecord = other.getInet4AddressRecord();
        if (otherInet4AddressRecord != null && otherInet4AddressRecord.getInet4Address() != null) {
            if (setInet4AddressRecord(otherInet4AddressRecord)) {
                updated = true;
            }
        }

        MdnsInetAddressRecord otherInet6AddressRecord = other.getInet6AddressRecord();
        if (otherInet6AddressRecord != null && otherInet6AddressRecord.getInet6Address() != null) {
            if (setInet6AddressRecord(otherInet6AddressRecord)) {
                updated = true;
            }
        }

        // If the hostname in the service record no longer matches the hostname in either of the
        // address records, then drop the address records.
        if (mServiceRecord != null) {
            boolean dropAddressRecords = false;

            if (mInet4AddressRecord != null) {
                if (!Arrays.equals(
                        mServiceRecord.getServiceHost(), mInet4AddressRecord.getName())) {
                    dropAddressRecords = true;
                }
            }
            if (mInet6AddressRecord != null) {
                if (!Arrays.equals(
                        mServiceRecord.getServiceHost(), mInet6AddressRecord.getName())) {
                    dropAddressRecords = true;
                }
            }

            if (dropAddressRecords) {
                setInet4AddressRecord(null);
                setInet6AddressRecord(null);
                updated = true;
            }
        }

        return updated;
    }

    /**
     * Tests if the response is complete. A response is considered complete if it contains PTR, SRV,
     * TXT, and A (for IPv4) or AAAA (for IPv6) records.
     */
    public synchronized boolean isComplete() {
        return !mPointerRecords.isEmpty()
                && (mServiceRecord != null)
                && (mTextRecord != null)
                && (mInet4AddressRecord != null || mInet6AddressRecord != null);
    }

    /**
     * Returns the key for this response. The key uniquely identifies the response by its service
     * name.
     */
    public synchronized String getServiceInstanceName() {
        if (mPointerRecords.isEmpty()) {
            return null;
        }
        String[] pointers = mPointerRecords.get(0).getPointer();
        return ((pointers != null) && (pointers.length > 0)) ? pointers[0] : null;
    }

    /**
     * Tests if this response is a goodbye message. This will be true if a service record is present
     * and any of the records have a TTL of 0.
     */
    public synchronized boolean isGoodbye() {
        if (getServiceInstanceName() != null) {
            for (MdnsRecord record : mRecords) {
                // Expiring PTR records with subtypes just signal a change in known supported
                // criteria, not the device itself going offline, so ignore those.
                if ((record instanceof MdnsPointerRecord)
                        && ((MdnsPointerRecord) record).hasSubtype()) {
                    continue;
                }

                if (record.getTtl() == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Writes the response to a packet.
     *
     * @param writer The writer to use.
     * @param now    The current time. This is used to write updated TTLs that reflect the remaining
     *               TTL
     *               since the response was received.
     * @return The number of records that were written.
     * @throws IOException If an error occurred while writing (typically indicating overflow).
     */
    public synchronized int write(MdnsPacketWriter writer, long now) throws IOException {
        int count = 0;
        for (MdnsPointerRecord pointerRecord : mPointerRecords) {
            pointerRecord.write(writer, now);
            ++count;
        }

        if (mServiceRecord != null) {
            mServiceRecord.write(writer, now);
            ++count;
        }

        if (mTextRecord != null) {
            mTextRecord.write(writer, now);
            ++count;
        }

        if (mInet4AddressRecord != null) {
            mInet4AddressRecord.write(writer, now);
            ++count;
        }

        if (mInet6AddressRecord != null) {
            mInet6AddressRecord.write(writer, now);
            ++count;
        }

        return count;
    }
}
