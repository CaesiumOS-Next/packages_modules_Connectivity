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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import android.util.SparseArray;

import com.android.net.module.util.HexDump;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 *
 * Not thread-safe
 */
public class MdnsRecordRepository {
    private static final String TAG = MdnsRecordRepository.class.getSimpleName();
    // RFC6762 p.15
    private static final long MIN_MULTICAST_REPLY_INTERVAL_MS = 1_000L;

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
    private final Random mDelayGenerator = new Random();

    @NonNull
    private final NetworkInterface mIface;
    @NonNull
    private final byte[] mPacketCreationBuffer;

    // Service unique ID -> records for service
    // ID 0 reserved for non-service-specific records (like address records)
    @NonNull
    private final SparseArray<List<RecordInfo>> mServices = new SparseArray<>();
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
        // Last time (as per SystemClock.elapsedRealtime) when advertised via multicast, 0 if never
        long lastAdvertisedTimeMs;

         RecordInfo(NsdServiceInfo serviceInfo, MdnsRecord record, int state) {
            this.serviceInfo = serviceInfo;
            this.record = record;
            this.state = state;
        }

        public boolean recordNameIsFullyOwned() {
             return state == RECORD_STATE_PROBING_COMPLETE || state == RECORD_STATE_UNIQUE;
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

    public static class ReplyInfo {
        public final List<MdnsRecord> answers;
        public final List<MdnsRecord> additionalAnswers;
        public final long sendDelayMs;
        public final SocketAddress destination;

        public ReplyInfo(
                List<MdnsRecord> answers,
                List<MdnsRecord> additionalAnswers,
                long sendDelayMs,
                SocketAddress destination) {
            this.answers = answers;
            this.additionalAnswers = additionalAnswers;
            this.sendDelayMs = sendDelayMs;
            this.destination = destination;
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

    @Nullable
    public ReplyInfo getReply(MdnsPacket packet, InetSocketAddress src) {
        final long now = SystemClock.elapsedRealtime();
        final boolean replyUnicast = (packet.flags & MdnsConstants.QCLASS_UNICAST) != 0;
        final ArrayList<MdnsRecord> answerRecords = new ArrayList<>();
        final ArrayList<MdnsRecord> additionalAnswerRecords = new ArrayList<>();
        boolean hasSharedRecordAnswer = false;
        for (MdnsRecord question : packet.questions) {
            // TODO: use to send NSEC replies if true and there is no answer
            boolean hasFullyOwnedNameMatch = false;
            boolean hasKnownAnswer = false;
            for (int i = 0; i < mServices.size(); i++) {
                final List<RecordInfo> serviceRecords = mServices.valueAt(i);

                for (RecordInfo info : serviceRecords) {
                    if (info.state == RecordInfo.RECORD_STATE_PROBING) continue;

                     /* RFC6762 6.: the record name must match the question name, the record rrtype
                     must match the question qtype unless the qtype is "ANY" (255) or the rrtype is
                     "CNAME" (5), and the record rrclass must match the question qclass unless the
                     qclass is "ANY" (255) */
                    if (!Arrays.equals(info.record.getName(), question.getName())) continue;
                    hasFullyOwnedNameMatch |= info.recordNameIsFullyOwned();

                    // The repository does not store CNAME records
                    if (question.getType() != MdnsRecord.TYPE_ANY
                            && question.getType() != info.record.getType()) {
                        continue;
                    }
                    if (question.getRecordClass() != MdnsRecord.CLASS_ANY
                            && question.getRecordClass() != info.record.getRecordClass()) {
                        continue;
                    }

                    hasKnownAnswer = true;

                    // TODO: responses to probe queries should bypass this check and only ensure the
                    // reply is sent 250ms after the last sent time (RFC 6762 p.15); this was not
                    // implemented by the legacy MDNS implementation either though.
                    if (!replyUnicast && info.lastAdvertisedTimeMs > 0L
                            && now - info.lastAdvertisedTimeMs < MIN_MULTICAST_REPLY_INTERVAL_MS) {
                        continue;
                    }

                    // TODO: Don't reply if in known answers of the querier (7.1) if TTL is > half

                    answerRecords.add(info.record);
                    hasSharedRecordAnswer |= !info.recordNameIsFullyOwned();
                }
            }

            // RFC6762 6.1
            if (hasFullyOwnedNameMatch && !hasKnownAnswer) {
                additionalAnswerRecords.add(new MdnsNsecRecord(
                        question.getName(),
                        0L,
                        true /* cacheFlush */,
                        // TODO: RFC6762 6.1: "In general, the TTL given for an NSEC record SHOULD
                        // be the same as the TTL that the record would have had, had it existed."
                        NAME_RECORDS_TTL_MILLIS,
                        question.getName(),
                        new int[] { question.getType() }));
            }
        }

        if (answerRecords.size() == 0 && additionalAnswerRecords.size() == 0) {
            return null;
        }

        final long delayMs;
        if ((packet.flags & MdnsConstants.FLAG_TRUNCATED) != 0) {
            // RFC 6762 6.: 400-500ms delay if TC bit is set
            delayMs = 400L + mDelayGenerator.nextInt(100);
        } else if (hasSharedRecordAnswer) {
            // RFC 6762 6.: 20-120ms if there may be responses from other hosts
            // TODO: this should be 0 if this is a probe query ("can be distinguished from a
            // normal query by the fact that a probe query contains a proposed record in the
            // Authority Section that answers the question"), and the reply is for a fully owned
            // record.
            delayMs = 20L + mDelayGenerator.nextInt(100);
        } else {
            delayMs = 0L;
        }

        final SocketAddress dest;
        if (replyUnicast) {
            dest = src;
        } else if (src.getAddress() instanceof Inet4Address) {
            dest = MdnsAdvertiser.IPV4_ADDR;
        } else {
            dest = MdnsAdvertiser.IPV6_ADDR;
        }

        return new ReplyInfo(answerRecords, additionalAnswerRecords, delayMs, dest);
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
        final int[] ptrTypes = new int[] { MdnsRecord.TYPE_PTR };
        for (RecordInfo record : mServices.get(0)) {
            answers.add(record.record);
            additionalAnswers.add(new MdnsNsecRecord(
                    record.record.getName(),
                    0L,
                    true /* cacheFlush */,
                    NAME_RECORDS_TTL_MILLIS,
                    record.record.getName() /* nextDomain */,
                    ptrTypes));
        }

        // All service records
        for (RecordInfo info : recordInfo) {
            answers.add(info.record);
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
        // packet may be probes in case of simultaneous probe tiebreaking, or may be responses
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
