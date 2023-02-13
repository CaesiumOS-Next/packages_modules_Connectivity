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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/** An mDNS response. */
public class MdnsResponse {
    private final List<MdnsRecord> records;
    private final List<MdnsPointerRecord> pointerRecords;
    private MdnsServiceRecord serviceRecord;
    private MdnsTextRecord textRecord;
    @NonNull private List<MdnsInetAddressRecord> inet4AddressRecords;
    @NonNull private List<MdnsInetAddressRecord> inet6AddressRecords;
    private long lastUpdateTime;
    private final int interfaceIndex;
    @Nullable private final Network network;

    /** Constructs a new, empty response. */
    public MdnsResponse(long now, int interfaceIndex, @Nullable Network network) {
        lastUpdateTime = now;
        records = new LinkedList<>();
        pointerRecords = new LinkedList<>();
        inet4AddressRecords = new LinkedList<>();
        inet6AddressRecords = new LinkedList<>();
        this.interfaceIndex = interfaceIndex;
        this.network = network;
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
        if (!pointerRecords.contains(pointerRecord)) {
            pointerRecords.add(pointerRecord);
            records.add(pointerRecord);
            return true;
        }

        return false;
    }

    /** Gets the pointer records. */
    public synchronized List<MdnsPointerRecord> getPointerRecords() {
        // Returns a shallow copy.
        return new LinkedList<>(pointerRecords);
    }

    public synchronized boolean hasPointerRecords() {
        return !pointerRecords.isEmpty();
    }

    @VisibleForTesting
    synchronized void clearPointerRecords() {
        pointerRecords.clear();
    }

    public synchronized boolean hasSubtypes() {
        for (MdnsPointerRecord pointerRecord : pointerRecords) {
            if (pointerRecord.hasSubtype()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public synchronized List<String> getSubtypes() {
        List<String> subtypes = null;
        for (MdnsPointerRecord pointerRecord : pointerRecords) {
            String pointerRecordSubtype = pointerRecord.getSubtype();
            if (pointerRecordSubtype != null) {
                if (subtypes == null) {
                    subtypes = new LinkedList<>();
                }
                subtypes.add(pointerRecordSubtype);
            }
        }

        return subtypes;
    }

    @VisibleForTesting
    public synchronized void removeSubtypes() {
        Iterator<MdnsPointerRecord> iter = pointerRecords.iterator();
        while (iter.hasNext()) {
            MdnsPointerRecord pointerRecord = iter.next();
            if (pointerRecord.hasSubtype()) {
                iter.remove();
            }
        }
    }

    /** Sets the service record. */
    public synchronized boolean setServiceRecord(MdnsServiceRecord serviceRecord) {
        if (recordsAreSame(this.serviceRecord, serviceRecord)) {
            return false;
        }
        if (this.serviceRecord != null) {
            records.remove(this.serviceRecord);
        }
        this.serviceRecord = serviceRecord;
        if (this.serviceRecord != null) {
            records.add(this.serviceRecord);
        }
        return true;
    }

    /** Gets the service record. */
    public synchronized MdnsServiceRecord getServiceRecord() {
        return serviceRecord;
    }

    public synchronized boolean hasServiceRecord() {
        return serviceRecord != null;
    }

    /** Sets the text record. */
    public synchronized boolean setTextRecord(MdnsTextRecord textRecord) {
        if (recordsAreSame(this.textRecord, textRecord)) {
            return false;
        }
        if (this.textRecord != null) {
            records.remove(this.textRecord);
        }
        this.textRecord = textRecord;
        if (this.textRecord != null) {
            records.add(this.textRecord);
        }
        return true;
    }

    /** Gets the text record. */
    public synchronized MdnsTextRecord getTextRecord() {
        return textRecord;
    }

    public synchronized boolean hasTextRecord() {
        return textRecord != null;
    }

    /** Add the IPv4 address record. */
    public synchronized boolean addInet4AddressRecord(
            @Nullable MdnsInetAddressRecord newInet4AddressRecord) {
        if (inet4AddressRecords.contains(newInet4AddressRecord)) {
            return false;
        }
        if (newInet4AddressRecord != null && newInet4AddressRecord.getInet4Address() != null) {
            inet4AddressRecords.add(newInet4AddressRecord);
            records.add(newInet4AddressRecord);
        }
        return true;
    }

    /** Gets the IPv4 address records. */
    public synchronized List<MdnsInetAddressRecord> getInet4AddressRecords() {
        return new LinkedList<>(inet4AddressRecords);
    }

    /** Return the first IPv4 address record or null if no record. */
    @Nullable
    public synchronized MdnsInetAddressRecord getInet4AddressRecord() {
        return inet4AddressRecords.isEmpty() ? null : inet4AddressRecords.get(0);
    }

    /** Check whether response has IPv4 address record */
    public synchronized boolean hasInet4AddressRecord() {
        return !inet4AddressRecords.isEmpty();
    }

    /** Clear all IPv4 address records */
    synchronized void clearInet4AddressRecords() {
        for (MdnsInetAddressRecord record : inet4AddressRecords) {
            records.remove(record);
        }
        inet4AddressRecords.clear();
    }

    /** Sets the IPv6 address records. */
    public synchronized boolean addInet6AddressRecord(
            @Nullable MdnsInetAddressRecord newInet6AddressRecord) {
        if (inet6AddressRecords.contains(newInet6AddressRecord)) {
            return false;
        }
        if (newInet6AddressRecord != null && newInet6AddressRecord.getInet6Address() != null) {
            inet6AddressRecords.add(newInet6AddressRecord);
            records.add(newInet6AddressRecord);
        }
        return true;
    }

    /**
     * Returns the index of the network interface at which this response was received. Can be set to
     * {@link MdnsSocket#INTERFACE_INDEX_UNSPECIFIED} if unset.
     */
    public int getInterfaceIndex() {
        return interfaceIndex;
    }

    /**
     * Returns the network at which this response was received, or null if the network is unknown.
     */
    @Nullable
    public Network getNetwork() {
        return network;
    }

    /** Gets all IPv6 address records. */
    public synchronized List<MdnsInetAddressRecord> getInet6AddressRecords() {
        return new LinkedList<>(inet6AddressRecords);
    }

    /** Return the first IPv6 address record or null if no record. */
    @Nullable
    public synchronized MdnsInetAddressRecord getInet6AddressRecord() {
        return inet6AddressRecords.isEmpty() ? null : inet6AddressRecords.get(0);
    }

    /** Check whether response has IPv6 address record */
    public synchronized boolean hasInet6AddressRecord() {
        return !inet6AddressRecords.isEmpty();
    }

    /** Clear all IPv6 address records */
    synchronized void clearInet6AddressRecords() {
        for (MdnsInetAddressRecord record : inet6AddressRecords) {
            records.remove(record);
        }
        inet6AddressRecords.clear();
    }

    /** Gets all of the records. */
    public synchronized List<MdnsRecord> getRecords() {
        return new LinkedList<>(records);
    }

    /**
     * Merges any records that are present in another response into this one.
     *
     * @return <code>true</code> if any records were added or updated.
     */
    public synchronized boolean mergeRecordsFrom(MdnsResponse other) {
        lastUpdateTime = other.lastUpdateTime;

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

        if (other.hasInet4AddressRecord()) {
            final List<MdnsInetAddressRecord> otherInet4AddressRecords =
                    other.getInet4AddressRecords();
            clearInet4AddressRecords();
            for (MdnsInetAddressRecord inetAddressRecord : otherInet4AddressRecords) {
                addInet4AddressRecord(inetAddressRecord);
            }
            updated = true;
        }


        if (other.hasInet6AddressRecord()) {
            final List<MdnsInetAddressRecord> otherInet6AddressRecords =
                    other.getInet6AddressRecords();
            clearInet6AddressRecords();
            for (MdnsInetAddressRecord inetAddressRecord : otherInet6AddressRecords) {
                addInet6AddressRecord(inetAddressRecord);
            }
            updated = true;
        }

        // If the hostname in the service record no longer matches the hostname in either of the
        // address records, then drop the address records.
        if (this.serviceRecord != null) {
            for (MdnsInetAddressRecord inetAddressRecord : getInet4AddressRecords()) {
                if (!Arrays.equals(
                        this.serviceRecord.getServiceHost(), inetAddressRecord.getName())) {
                    records.remove(inetAddressRecord); // Drop record.
                    updated = true;
                }
            }
            for (MdnsInetAddressRecord inetAddressRecord : getInet6AddressRecords()) {
                if (!Arrays.equals(
                        this.serviceRecord.getServiceHost(), inetAddressRecord.getName())) {
                    records.remove(inetAddressRecord); // Drop record.
                    updated = true;
                }
            }
        }
        return updated;
    }

    /**
     * Tests if the response is complete. A response is considered complete if it contains PTR, SRV,
     * TXT, and A (for IPv4) or AAAA (for IPv6) records.
     */
    public synchronized boolean isComplete() {
        return !pointerRecords.isEmpty()
                && (serviceRecord != null)
                && (textRecord != null)
                && (!inet4AddressRecords.isEmpty() || !inet6AddressRecords.isEmpty());
    }

    /**
     * Returns the key for this response. The key uniquely identifies the response by its service
     * name.
     */
    public synchronized String getServiceInstanceName() {
        if (pointerRecords.isEmpty()) {
            return null;
        }
        String[] pointers = pointerRecords.get(0).getPointer();
        return ((pointers != null) && (pointers.length > 0)) ? pointers[0] : null;
    }

    /**
     * Tests if this response is a goodbye message. This will be true if a service record is present
     * and any of the records have a TTL of 0.
     */
    public synchronized boolean isGoodbye() {
        if (getServiceInstanceName() != null) {
            for (MdnsRecord record : records) {
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
        for (MdnsPointerRecord pointerRecord : pointerRecords) {
            pointerRecord.write(writer, now);
            ++count;
        }

        if (serviceRecord != null) {
            serviceRecord.write(writer, now);
            ++count;
        }

        if (textRecord != null) {
            textRecord.write(writer, now);
            ++count;
        }

        for (MdnsInetAddressRecord inetAddressRecord : inet4AddressRecords) {
            inetAddressRecord.write(writer, now);
            ++count;
        }

        for (MdnsInetAddressRecord inetAddressRecord : inet6AddressRecords) {
            inetAddressRecord.write(writer, now);
            ++count;
        }

        return count;
    }
}
