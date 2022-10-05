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

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/** An mDNS response. */
// TODO(b/242631897): Resolve nullness suppression.
@SuppressWarnings("nullness")
public class MdnsResponse {
    private final List<MdnsRecord> records;
    private final List<MdnsPointerRecord> pointerRecords;
    private final List<MdnsServiceRecord> serviceRecords;
    private final List<MdnsTextRecord> textRecords;
    private final List<MdnsInetAddressRecord> inet4AddressRecords;
    private final List<MdnsInetAddressRecord> inet6AddressRecords;
    private long lastUpdateTime;

    /** Constructs a new, empty response. */
    public MdnsResponse(long now) {
        lastUpdateTime = now;
        records = new LinkedList<>();
        pointerRecords = new LinkedList<>();
        serviceRecords = new LinkedList<>();
        textRecords = new LinkedList<>();
        inet4AddressRecords = new LinkedList<>();
        inet6AddressRecords = new LinkedList<>();
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
    /* package */ synchronized void clearPointerRecords() {
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

    public synchronized List<String> getSubtypes() {
        List<String> subtypes = null;

        for (MdnsPointerRecord pointerRecord : pointerRecords) {
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
        Iterator<MdnsPointerRecord> iter = pointerRecords.iterator();
        while (iter.hasNext()) {
            MdnsPointerRecord pointerRecord = iter.next();
            if (pointerRecord.hasSubtype()) {
                iter.remove();
            }
        }
    }

    private static <T extends MdnsRecord> void setRecord(List<T> recordsList, T newRecord) {
        recordsList.clear();
        if (newRecord != null) {
            recordsList.add(newRecord);
        }
    }

    /** Sets the service record. */
    @Deprecated
    public synchronized void setServiceRecord(MdnsServiceRecord serviceRecord) {
        setRecord(serviceRecords, serviceRecord);
    }

    private <T extends MdnsRecord> boolean addRecord(List<T> recordsList, T newRecord) {
        if (!records.contains(newRecord)) {
            recordsList.add(newRecord);
            records.add(newRecord);
            return true;
        }

        return false;
    }

    public synchronized boolean addServiceRecord(MdnsServiceRecord serviceRecord) {
        return addRecord(serviceRecords, serviceRecord);
    }

    /** Gets the service record. */
    @Deprecated
    public synchronized MdnsServiceRecord getServiceRecord() {
        return hasServiceRecord() ? serviceRecords.get(0) : null;
    }

    public synchronized List<MdnsServiceRecord> getServiceRecords() {
        return new ArrayList<>(serviceRecords);
    }

    public synchronized boolean hasServiceRecord() {
        return serviceRecords.size() > 0;
    }

    /** Sets the text record. */
    @Deprecated
    public synchronized void setTextRecord(MdnsTextRecord textRecord) {
        setRecord(textRecords, textRecord);
    }

    public synchronized boolean addTextRecord(MdnsTextRecord textRecord) {
        return addRecord(textRecords, textRecord);
    }

    /** Gets the text record. */
    @Deprecated
    public synchronized MdnsTextRecord getTextRecord() {
        return hasTextRecord() ? textRecords.get(0) : null;
    }

    public synchronized List<MdnsTextRecord> getTextRecords() {
        return new ArrayList<>(textRecords);
    }

    public synchronized boolean hasTextRecord() {
        return textRecords.size() > 0;
    }

    /** Sets the IPv4 address record. */
    @Deprecated
    public synchronized void setInet4AddressRecord(MdnsInetAddressRecord newInet4AddressRecord) {
        if (newInet4AddressRecord != null && newInet4AddressRecord.getInet4Address() == null) {
            newInet4AddressRecord = null;
        }
        setRecord(inet4AddressRecords, newInet4AddressRecord);
    }

    public synchronized boolean addInet4AddressRecord(MdnsInetAddressRecord newInet4AddressRecord) {
        return addRecord(inet4AddressRecords, newInet4AddressRecord);
    }

    /** Gets the IPv4 address record. */
    @Deprecated
    public synchronized MdnsInetAddressRecord getInet4AddressRecord() {
        return hasInet4AddressRecord() ? inet4AddressRecords.get(0) : null;
    }

    public List<MdnsInetAddressRecord> getInet4AddressRecords() {
        return new ArrayList<>(inet4AddressRecords);
    }

    public synchronized boolean hasInet4AddressRecord() {
        return inet4AddressRecords.size() > 0;
    }

    /** Sets the IPv6 address record. */
    @Deprecated
    public synchronized void setInet6AddressRecord(MdnsInetAddressRecord newInet6AddressRecord) {
        if (newInet6AddressRecord != null && newInet6AddressRecord.getInet6Address() == null) {
            newInet6AddressRecord = null;
        }

        setRecord(inet6AddressRecords, newInet6AddressRecord);
    }

    public synchronized boolean addInet6AddressRecord(MdnsInetAddressRecord newInet6AddressRecord) {
        return addRecord(inet6AddressRecords, newInet6AddressRecord);
    }

    /** Gets the IPv6 address record. */
    @Deprecated
    public synchronized MdnsInetAddressRecord getInet6AddressRecord() {
        return hasInet6AddressRecord() ? inet6AddressRecords.get(0) : null;
    }

    public List<MdnsInetAddressRecord> getInet6AddressRecords() {
        return new ArrayList<>(inet6AddressRecords);
    }

    public synchronized boolean hasInet6AddressRecord() {
        return inet6AddressRecords.size() > 0;
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

        for (MdnsPointerRecord pointerRecord : other.getPointerRecords()) {
            if (addPointerRecord(pointerRecord)) {
                updated = true;
            }
        }

        for (MdnsServiceRecord serviceRecord : other.getServiceRecords()) {
            if (addServiceRecord(serviceRecord)) {
                updated = true;
            }
        }

        for (MdnsTextRecord textRecord : other.getTextRecords()) {
            if (addTextRecord(textRecord)) {
                updated = true;
            }
        }

        for (MdnsInetAddressRecord addressRecord : other.getInet4AddressRecords()) {
            if (addInet4AddressRecord(addressRecord)) {
                updated = true;
            }
        }

        for (MdnsInetAddressRecord addressRecord : other.getInet6AddressRecords()) {
            if (addInet6AddressRecord(addressRecord)) {
                updated = true;
            }
        }

        // If the hostname in the service record no longer matches the hostname in either of the
        // address records, then drop the address records.
        if (hasServiceRecord()) {
            boolean dropAddressRecords = false;

            if (hasInet4AddressRecord()) {
                if (!Arrays.equals(
                        getServiceRecord().getServiceHost(), getInet4AddressRecord().getName())) {
                    dropAddressRecords = true;
                }
            }
            if (hasInet6AddressRecord()) {
                if (!Arrays.equals(
                        getServiceRecord().getServiceHost(), getInet6AddressRecord().getName())) {
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
        return !pointerRecords.isEmpty()
                && hasServiceRecord()
                && hasTextRecord()
                && (hasInet4AddressRecord() || hasInet6AddressRecord());
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

        // TODO: handle responses too long for one packet
        for (MdnsServiceRecord serviceRecord : serviceRecords) {
            serviceRecord.write(writer, now);
            ++count;
        }

        for (MdnsTextRecord textRecord : textRecords) {
            textRecord.write(writer, now);
            ++count;
        }

        for (MdnsInetAddressRecord inet4AddressRecord : inet4AddressRecords) {
            inet4AddressRecord.write(writer, now);
            ++count;
        }

        for (MdnsInetAddressRecord inet6AddressRecord : inet6AddressRecords) {
            inet6AddressRecord.write(writer, now);
            ++count;
        }

        return count;
    }
}
