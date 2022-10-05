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

import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.net.module.util.HexDump;
import com.android.server.connectivity.mdns.MdnsInetAddressRecord;
import com.android.server.connectivity.mdns.MdnsNsecRecord;
import com.android.server.connectivity.mdns.MdnsPointerRecord;
import com.android.server.connectivity.mdns.MdnsRecord;
import com.android.server.connectivity.mdns.MdnsServiceRecord;
import com.android.server.connectivity.mdns.MdnsTextRecord;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 *
 * Not thread-safe
 */
public class MdnsRecordRepository {
    private static final String TAG = MdnsRecordRepository.class.getSimpleName();

    // TTLs as per https://www.rfc-editor.org/rfc/rfc6762#section-10
    // TTL for records with a host name as the resource record's name (e.g., A, AAAA, HINFO) or a
    // host name contained within the resource record's rdata (e.g., SRV, reverse mapping PTR
    // record)
    private static final long NAME_RECORDS_TTL_MILLIS = TimeUnit.SECONDS.toMillis(120);
    // TTL for other records
    private static final long NON_NAME_RECORDS_TTL_MILLIS = TimeUnit.MINUTES.toMillis(75);

    private static final String[] DNS_SD_SERVICE_NAME =
            new String[] { "_services", "_dns-sd", "_udp", "local" };

    @NonNull
    private final NetworkInterface mIface;
    @NonNull
    private final byte[] mPacketCreationBuffer;

    // Service unique ID -> records for service
    // ID 0 reserved for non-service-specific records (like address records)
    @NonNull
    private final SparseArray<List<RecordInfo>> mServices = new SparseArray<>();
    private final SparseArray<AdvertisementInfo> mServicesOld = new SparseArray<>();
    @NonNull
    private String[] mDeviceHostname;

    public MdnsRecordRepository(@NonNull NetworkInterface iface,
            @NonNull byte[] packetCreationBuffer) {
        mIface = iface;
        mPacketCreationBuffer = packetCreationBuffer;
        mDeviceHostname = getHostname(iface);

        final List<RecordInfo> generalRecords = new ArrayList<>();
        // TODO: handle interface address changes, and set hostname based on one address
        for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
            final String[] revDnsAddr = getReverseDnsAddress(addr.getAddress());
            generalRecords.add(new RecordInfo(
                    null /* serviceInfo */,
                    new MdnsPointerRecord(
                        revDnsAddr,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        NAME_RECORDS_TTL_MILLIS,
                        mDeviceHostname),
                    RecordInfo.RECORD_STATE_UNIQUE));

            generalRecords.add(new RecordInfo(
                    null /* serviceInfo */,
                    new MdnsInetAddressRecord(
                        mDeviceHostname,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        NAME_RECORDS_TTL_MILLIS,
                        addr.getAddress()),
                    RecordInfo.RECORD_STATE_UNIQUE));
        }

        mServices.put(0, generalRecords);
    }

    private static class RecordInfo {
        static final int RECORD_STATE_PROBING = 0;
        static final int RECORD_STATE_PROBING_COMPLETE = 1;
        static final int RECORD_STATE_UNIQUE = 2;
        static final int RECORD_STATE_SHARED = 3;

        final MdnsRecord record;
        final NsdServiceInfo serviceInfo;
        int state;

         RecordInfo(NsdServiceInfo serviceInfo, MdnsRecord record, int state) {
            this.serviceInfo = serviceInfo;
            this.record = record;
            this.state = state;
        }
    }

    public ProbingInfo addService(int serviceId, NsdServiceInfo serviceInfo) throws IOException {
        final List<RecordInfo> serviceRecords = new ArrayList<>(4);
        final List<MdnsRecord> probingRecords = new ArrayList<>(2);

        final String[] serviceName = splitServiceName(serviceInfo);
        final String[] serviceType = splitServiceName(serviceInfo.getServiceType());

        final MdnsTextRecord serviceAttrsRecord = new MdnsTextRecord(serviceName,
                0L /* receiptTimeMillis */,
                true /* cacheFlush */, // Service name is verified unique after probing
                NON_NAME_RECORDS_TTL_MILLIS,
                attributesToList(serviceInfo.getAttributes()));

        serviceRecords.add(new RecordInfo(
                serviceInfo,
                serviceAttrsRecord,
                RecordInfo.RECORD_STATE_SHARED));

        serviceRecords.add(new RecordInfo(
                serviceInfo,
                new MdnsPointerRecord(
                        DNS_SD_SERVICE_NAME,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        NON_NAME_RECORDS_TTL_MILLIS,
                        serviceType),
                RecordInfo.RECORD_STATE_SHARED));
        serviceRecords.add(new RecordInfo(
                serviceInfo,
                new MdnsPointerRecord(
                        serviceType,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        NON_NAME_RECORDS_TTL_MILLIS,
                        serviceName),
                RecordInfo.RECORD_STATE_SHARED));

        // The SRV record has cacheFlush cleared when probing but set when announcing, as it was
        // verified unique: https://www.rfc-editor.org/rfc/rfc6762#section-10.2
        final MdnsServiceRecord srvRecord = new MdnsServiceRecord(serviceName,
                0L /* receiptTimeMillis */,
                true /* cacheFlush */,
                NAME_RECORDS_TTL_MILLIS, 0 /* servicePriority */, 0 /* serviceWeight */,
                serviceInfo.getPort(),
                mDeviceHostname);
        serviceRecords.add(new RecordInfo(
                serviceInfo,
                srvRecord,
                RecordInfo.RECORD_STATE_PROBING));
        probingRecords.add(new MdnsServiceRecord(serviceName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                NAME_RECORDS_TTL_MILLIS, 0 /* servicePriority */, 0 /* serviceWeight */,
                serviceInfo.getPort(),
                mDeviceHostname));

        mServices.put(serviceId, serviceRecords);

        return new ProbingInfo(serviceId, probingRecords, mPacketCreationBuffer);
    }

    private static List<String> attributesToList(Map<String, byte[]> attrs) {
        final List<String> out = new ArrayList<>(attrs.size());
        for (Map.Entry<String, byte[]> attr : attrs.entrySet()) {
            // String constructor will replace malformed string with the charset default replacement
            // TODO: consider writing attributes as byte instead of strings, if useful
            out.add(attr.getKey() + "=" + new String(attr.getValue(), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static class AdvertisementInfo {
        public final NsdServiceInfo service;

        public final String[] serviceName;
        public final String[] serviceType;

        public long lastAdvertised;

        private AdvertisementInfo(NsdServiceInfo service) {
            this.service = service;
            this.serviceName = splitServiceName(service);
            this.serviceType = splitServiceName(service.getServiceType());
        }
    }

    public static class AnswerRecords {
        public final List<MdnsRecord> answers;
        public final List<MdnsRecord> additionalAnswers;

        public AnswerRecords(
                List<MdnsRecord> answers,
                List<MdnsRecord> additionalAnswers) {
            this.answers = answers;
            this.additionalAnswers = additionalAnswers;
        }
    }

    /**
     * Remove a service from the repository.
     * @param id ID of the service, used at registration time.
     * @return The number of remaining services after the operation.
     */
    public int removeService(int id) {
        mServices.delete(id);
        return mServices.size();
    }

    public AnswerRecords getRecordsToAnswer(@NonNull NetworkInterface iface, long now) {
        // - Don't reply if already advertised less than 1 second ago (p.15), except for probe queries (250ms)
        // - Don't reply if record is in known answers of the querier (7.1) if TTL is > half
        // TODO: write
        return null;
    }

    public MdnsAnnouncer.AnnouncementInfo getRecordsToAnnounce(ProbingInfo probeSuccessInfo)
            throws IOException {

        final List<RecordInfo> recordInfo = mServices.get(probeSuccessInfo.getServiceId());
        if (recordInfo == null) throw new IOException(
                "Service is not registered: " + probeSuccessInfo.getServiceId());
        for (RecordInfo record : recordInfo) {
            if (record.state == RecordInfo.RECORD_STATE_PROBING) {
                record.state = RecordInfo.RECORD_STATE_PROBING_COMPLETE;
            }
        }

        final ArrayList<MdnsRecord> answers = new ArrayList<>();
        final ArrayList<MdnsRecord> additionalAnswers = new ArrayList<>();

        // Interface address pointer records
        final List<InterfaceAddress> addresses = mIface.getInterfaceAddresses();
        final int[] ptrTypes = new int[] { MdnsRecord.TYPE_PTR };
        for (InterfaceAddress addr : addresses) {
            final String[] revDnsAddr = getReverseDnsAddress(addr.getAddress());
            answers.add(new MdnsPointerRecord(
                    revDnsAddr,
                    0L,
                    true /* cacheFlush */,
                    NAME_RECORDS_TTL_MILLIS,
                    mDeviceHostname));


            additionalAnswers.add(new MdnsNsecRecord(
                    revDnsAddr,
                    0L,
                    true /* cacheFlush */,
                    NAME_RECORDS_TTL_MILLIS,
                    revDnsAddr /* nextDomain */,
                    ptrTypes));
        }

        // All service records
        for (RecordInfo info : recordInfo) {
            answers.add(info.record);
        }

        // TXT records
//        for (int i = 0; i < mServicesOld.size(); i++) {
//            final AdvertisementInfo info = mServicesOld.valueAt(i);
//            answers.add(new MdnsTextRecord(
//                    info.serviceName,
//                    0L,
//                    true /* cacheFlush */,
//                    NON_NAME_RECORDS_TTL_MILLIS,
//                    new ArrayList<>(0)));
//        }
//
//        // Service PTR records
//        for (int i = 0; i < mServicesOld.size(); i++) {
//            final AdvertisementInfo info = mServicesOld.valueAt(i);
//            answers.add(new MdnsPointerRecord(
//                new String[] { "_services", "_dns-sd", "_udp", "local" },
//                    0L,
//                false /* cacheFlush */,
//                NON_NAME_RECORDS_TTL_MILLIS,
//                info.serviceType));
//            answers.add(new MdnsPointerRecord(
//                    info.serviceType,
//                    0L,
//                    false /* cacheFlush */,
//                    NON_NAME_RECORDS_TTL_MILLIS,
//                    info.serviceName));
//        }

        // A, AAAA records
        for (InterfaceAddress addr : addresses) {
            answers.add(new MdnsInetAddressRecord(
                    mDeviceHostname,
                    0L,
                    true /* cacheFlush */,
                    NAME_RECORDS_TTL_MILLIS,
                    addr.getAddress()));
        }

        // Service SRV records
        final int[] srvTxtTypes = new int[] { MdnsRecord.TYPE_SRV, MdnsRecord.TYPE_TXT };
        for (int i = 0; i < mServicesOld.size(); i++) {
            final AdvertisementInfo info = mServicesOld.valueAt(i);
            answers.add(new MdnsServiceRecord(info.serviceName,
                    0L,
                    true /* cacheFlush */,
                    NON_NAME_RECORDS_TTL_MILLIS, 0 /* servicePriority */, 0 /* serviceWeight */,
                    info.service.getPort(),
                    mDeviceHostname));

            additionalAnswers.add(new MdnsNsecRecord(
                    info.serviceName,
                    0L,
                    true /* cacheFlush */,
                    NAME_RECORDS_TTL_MILLIS,
                    info.serviceName /* nextDomain */,
                    srvTxtTypes));
        }

        // A / AAAA NSEC record for the hostname. If A / AAAA records were added this record shows
        // there is no other address. If they were not, it indicates that the device does not have
        // IPv4 or IPv6 addresses.
        // https://www.rfc-editor.org/rfc/rfc6762#section-6.2
        additionalAnswers.add(new MdnsNsecRecord(
                mDeviceHostname,
                0L,
                true /* cacheFlush */,
                NAME_RECORDS_TTL_MILLIS,
                mDeviceHostname /* nextDomain */,
                new int[] { MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA }));

        return new MdnsAnnouncer.AnnouncementInfo(mIface, probeSuccessInfo.getServiceId(),
                answers, additionalAnswers, mPacketCreationBuffer);
    }

    public List<ProbingInfo> maybeUpdateForConflicts(MdnsPacket packet) {
        // TODO: check for conflicts, return new ProbingInfo for services that were updated
        return Collections.emptyList();
    }

    private static String[] getReverseDnsAddress(InetAddress addr) {
        // xxx.xxx.xxx.xxx.in-addr.arpa (up to 28 characters)
        // or 32 hex characters separated by dots + .ip6.arpa
        final byte[] addrBytes = addr.getAddress();
        final List<String> out = new ArrayList<>();
        if (addr instanceof Inet4Address) {
            for (int i = addrBytes.length - 1; i >= 0; i--) {
                out.add(String.valueOf(Byte.toUnsignedInt(addrBytes[i])));
            }
            out.add("in-addr");
        } else {
            final String hexAddr = HexDump.toHexString(addrBytes);

            for (int i = hexAddr.length() - 1; i >= 0; i--) {
                out.add(String.valueOf(hexAddr.charAt(i)));
            }
            out.add("ip6");
        }
        out.add("arpa");

        final String[] outArr = new String[out.size()];
        return out.toArray(outArr);
    }

    private static String[] splitServiceName(NsdServiceInfo info) {
        final String[] typeSplit = splitName(info.getServiceType());
        final String[] serviceSplit = splitName(info.getServiceName());
        final String[] names = new String[typeSplit.length + serviceSplit.length + 1];
        System.arraycopy(serviceSplit, 0, names, 0, serviceSplit.length);
        System.arraycopy(typeSplit, 0, names, serviceSplit.length, typeSplit.length);
        names[names.length - 1] = "local";

        return names;
    }

    private static String[] splitServiceName(String name) {
        final String[] nameSplit = splitName(name);
        final String[] names = new String[nameSplit.length + 1];
        System.arraycopy(nameSplit, 0, names, 0, nameSplit.length);
        names[nameSplit.length] = "local";

        return names;
    }

    private static String[] splitName(String name) {
        // String.split(pattern, 0) removes trailing empty strings, which would appear when
        // splitting "domain.name." (with a dot a the end)
        return name.split("\\.", 0);
    }

    private String[] getHostname(@NonNull NetworkInterface iface) {
        // TODO: use interface IP address (preferably link-local IPv6) instead of hardware, or UUID
        // if not availble.

        // Generate the hostname from the interface hardware address. This allows minimizing
        // possible conflicts to the point that probing for it is no longer necessary (as per
        // https://datatracker.ietf.org/doc/html/rfc6762#section-8.1 last paragraph), and does not
        // leak more information than what could already be obtained by looking at the mDNS packets
        // source address.
        // This differs from historical behavior that just used "Android.local" for many devices,
        // creating a lot of conflicts.
        // Having a different hostname per interface is an acceptable option as per
        // https://datatracker.ietf.org/doc/html/rfc6762#section-14
        byte[] hwAddr;
        try {
            hwAddr = iface.getHardwareAddress();
        } catch (SocketException e) {
            // SocketException is only thrown when the interface is gone. The hostname returned
            // here will not be sent anywhere in that case.
            hwAddr = new byte[6];
        }
        return new String[] { "Android_" + HexDump.toHexString(hwAddr), "local" };
    }
}
