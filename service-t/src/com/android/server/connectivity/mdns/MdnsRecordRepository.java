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
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.HexDump;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 *
 * Not thread-safe
 */
public class MdnsRecordRepository {
    private static final String TAG = MdnsRecordRepository.class.getSimpleName();
    // RFC6762 p.15
    private static final long MIN_MULTICAST_REPLY_INTERVAL_MS = 1_000L;

    // TTLs as per RFC6762 10.
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

    // Service unique ID -> records for service
    @NonNull
    private final SparseArray<ServiceRegistration> mServices = new SparseArray<>();
    @NonNull
    private final List<RecordInfo<?>> mGeneralRecords = new ArrayList<>();
    @NonNull
    private final Looper mLooper;
    @NonNull
    private String[] mDeviceHostname;
    @Nullable
    private InetSocketAddress mInet6MulticastAddress;
    @Nullable
    private InetSocketAddress mInet4MulticastAddress;
    @NonNull
    private ArrayList<SocketAddress> mProbeAnnounceDestinations;
    @NonNull
    private final Supplier<Iterable<SocketAddress>> mProbeAnnounceDestSupplier;

    public MdnsRecordRepository(@NonNull NetworkInterface iface, @NonNull Looper looper) {
        this(iface, looper, new Dependencies());
    }

    @VisibleForTesting
    public MdnsRecordRepository(@NonNull NetworkInterface iface, @NonNull Looper looper,
            @NonNull Dependencies deps) {
        mIface = iface;
        mDeviceHostname = deps.getHostname();
        mLooper = looper;
        mProbeAnnounceDestSupplier = () -> {
            if (Thread.currentThread() != mLooper.getThread()) {
                throw new IllegalStateException(
                        "Destination supplier must be called on looper thread");
            }
            return mProbeAnnounceDestinations;
        };

        // TODO: handle interface address changes, and set hostname based on one address
        boolean hasIpv4Addr = false;
        boolean hasIpv6Addr = false;
        final Enumeration<InetAddress> ifaceAddrs = deps.getInterfaceInetAddresses(iface);
        while (ifaceAddrs.hasMoreElements()) {
            final InetAddress addr = ifaceAddrs.nextElement();
            if (addr instanceof Inet4Address) {
                hasIpv4Addr = true;
            } else if (addr instanceof Inet6Address) {
                hasIpv6Addr = true;
            }
            final String[] revDnsAddr = getReverseDnsAddress(addr);
            mGeneralRecords.add(new RecordInfo<>(
                    null /* serviceInfo */,
                    new MdnsPointerRecord(
                        revDnsAddr,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        NAME_RECORDS_TTL_MILLIS,
                        mDeviceHostname),
                    false /* sharedName */, false /* probing */));

            mGeneralRecords.add(new RecordInfo<>(
                    null /* serviceInfo */,
                    new MdnsInetAddressRecord(
                        mDeviceHostname,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        NAME_RECORDS_TTL_MILLIS,
                        addr),
                    false /* sharedName */, false /* probing */));
        }
        mProbeAnnounceDestinations = new ArrayList<>(2);
        if (hasIpv6Addr) {
            try {
                mInet6MulticastAddress = new InetSocketAddress(
                        Inet6Address.getByAddress(null,
                                MdnsConstants.getMdnsIPv6Address().getAddress(),
                                iface),
                        MdnsConstants.MDNS_PORT);
                mProbeAnnounceDestinations.add(mInet6MulticastAddress);
            } catch (UnknownHostException e) {
                Log.e(TAG, "No inet6 multicast address on " + iface, e);
            }
        }
        if (hasIpv4Addr) {
            mInet4MulticastAddress = new InetSocketAddress(
                    MdnsConstants.getMdnsIPv4Address(),
                    MdnsConstants.MDNS_PORT);
            mProbeAnnounceDestinations.add(mInet4MulticastAddress);
        }
    }

    @VisibleForTesting
    public static class Dependencies {
        @NonNull
        public String[] getHostname() {
            // Generate a very-probably-unique hostname. This allows minimizing possible conflicts
            // to the point that probing for it is no longer necessary (as per RFC6762 8.1 last
            // paragraph), and does not leak more information than what could already be obtained by
            // looking at the mDNS packets source address.
            // This differs from historical behavior that just used "Android.local" for many
            // devices, creating a lot of conflicts.
            // Having a different hostname per interface is an acceptable option as per RFC6762 14.
            // This hostname will change every time the interface is reconnected, so this does not
            // allow tracking the device.
            // TODO: consider deriving a hostname from other sources, such as the IPv6 addresses
            // (reusing the same privacy-protecting mechanics).
            return new String[] { "Android_" + UUID.randomUUID().toString().replace("-", ""), "local" };
        }

        @NonNull
        public Enumeration<InetAddress> getInterfaceInetAddresses(@NonNull NetworkInterface iface) {
            return iface.getInetAddresses();
        }
    }

    private static class RecordInfo<T extends MdnsRecord> {
        final T record;
        final NsdServiceInfo serviceInfo;

        /**
         * Whether the name of this record is expected to be fully owned by the service or may be
         * advertised by other hosts as well (shared).
         */
        final boolean isSharedName;

        /**
         * Whether probing is still in progress for the record.
         */
        boolean isProbing;

        /**
         * Last time (as per SystemClock.elapsedRealtime) when advertised via multicast, 0 if never
         */
        long lastAdvertisedTimeMs;

        /**
         * Last time (as per SystemClock.elapsedRealtime) when sent via unicast or multicast,
         * 0 if never
         */
        long lastSentTimeMs;

         RecordInfo(NsdServiceInfo serviceInfo, T record, boolean sharedName,
                 boolean probing) {
            this.serviceInfo = serviceInfo;
            this.record = record;
            this.isSharedName = sharedName;
            this.isProbing = probing;
        }
    }

    private static class ServiceRegistration {
        final List<RecordInfo<?>> allRecords;
        @NonNull
        final RecordInfo<MdnsPointerRecord> ptrRecord;
        @NonNull
        final RecordInfo<MdnsServiceRecord> srvRecord;
        @NonNull
        final RecordInfo<MdnsTextRecord> txtRecord;
        @NonNull
        final NsdServiceInfo serviceInfo;

        /**
         * Create a ServiceRegistration for dns-sd service registration (RFC6763).
         *
         * @param deviceHostname Hostname of the device (for the interface used)
         * @param serviceInfo Service to advertise
         */
        ServiceRegistration(@NonNull String[] deviceHostname, @NonNull NsdServiceInfo serviceInfo) {
            this.serviceInfo = serviceInfo;

            final String[] serviceType = splitServiceType(serviceInfo);
            final String[] serviceName = splitFullyQualifiedName(serviceInfo, serviceType);

            // Service PTR record
            ptrRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsPointerRecord(
                            serviceType,
                            0L /* receiptTimeMillis */,
                            false /* cacheFlush */,
                            NON_NAME_RECORDS_TTL_MILLIS,
                            serviceName),
                    true /* sharedName */, true /* probing */);

            srvRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsServiceRecord(serviceName,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */,
                            NAME_RECORDS_TTL_MILLIS, 0 /* servicePriority */, 0 /* serviceWeight */,
                            serviceInfo.getPort(),
                            deviceHostname),
                    false /* sharedName */, true /* probing */);

            txtRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsTextRecord(serviceName,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */, // Service name is verified unique after probing
                            NON_NAME_RECORDS_TTL_MILLIS,
                            attributesToList(serviceInfo.getAttributes())),
                    false /* sharedName */, true /* probing */);

            final ArrayList<RecordInfo<?>> allRecords = new ArrayList<>(4);
            allRecords.add(ptrRecord);
            allRecords.add(srvRecord);
            allRecords.add(txtRecord);
            // Service type enumeration record (RFC6763 9.)
            allRecords.add(new RecordInfo<>(
                    serviceInfo,
                    new MdnsPointerRecord(
                            DNS_SD_SERVICE_NAME,
                            0L /* receiptTimeMillis */,
                            false /* cacheFlush */,
                            NON_NAME_RECORDS_TTL_MILLIS,
                            serviceType),
                    true /* sharedName */, true /* probing */));

            this.allRecords = Collections.unmodifiableList(allRecords);
        }

        void setProbing(boolean probing) {
            for (RecordInfo<?> info : allRecords) {
                info.isProbing = probing;
            }
        }
    }

    public MdnsProber.ProbingInfo addService(int serviceId, NsdServiceInfo serviceInfo)
            throws IOException {

        // TODO: immediately report a conflict if the service conflicts with another known service
        final ServiceRegistration registration = new ServiceRegistration(
                mDeviceHostname, serviceInfo);
        mServices.put(serviceId, registration);

        final MdnsServiceRecord srvRecord = registration.srvRecord.record;
        return makeProbingInfo(serviceId, serviceInfo, srvRecord  /* isInitialProbing */);
    }

    private MdnsProber.ProbingInfo makeProbingInfo(int serviceId,
            @NonNull NsdServiceInfo serviceInfo, @NonNull MdnsServiceRecord srvRecord) {
        final List<MdnsRecord> probingRecords = new ArrayList<>();
        // Probe with cacheFlush cleared; it is set when announcing, as it was verified unique:
        // RFC6762 10.2
        probingRecords.add(new MdnsServiceRecord(srvRecord.getName(),
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                srvRecord.getTtl(),
                srvRecord.getServicePriority(), srvRecord.getServiceWeight(),
                srvRecord.getServicePort(),
                srvRecord.getServiceHost()));

        return new MdnsProber.ProbingInfo(serviceId, probingRecords, serviceInfo,
                mProbeAnnounceDestSupplier);
    }

    private static List<MdnsServiceInfo.TextEntry> attributesToList(Map<String, byte[]> attrs) {
        final List<MdnsServiceInfo.TextEntry> out = new ArrayList<>(attrs.size());
        for (Map.Entry<String, byte[]> attr : attrs.entrySet()) {
            out.add(new MdnsServiceInfo.TextEntry(attr.getKey(), attr.getValue()));
        }
        return out;
    }

    public static class ReplyInfo {
        public final List<MdnsRecord> answers;
        public final List<MdnsRecord> additionalAnswers;
        public final long sendDelayMs;
        public final InetSocketAddress destination;

        public ReplyInfo(
                List<MdnsRecord> answers,
                List<MdnsRecord> additionalAnswers,
                long sendDelayMs,
                InetSocketAddress destination) {
            this.answers = answers;
            this.additionalAnswers = additionalAnswers;
            this.sendDelayMs = sendDelayMs;
            this.destination = destination;
        }

        @Override
        public String toString() {
            return "{ReplyInfo to " + destination + ", answers: " + answers.size()
                    + ", additionalAnswers: " + additionalAnswers.size()
                    + ", sendDelayMs " + sendDelayMs + "}";
        }
    }

    /**
     * Remove a service from the repository.
     * @param id ID of the service, used at registration time.
     * @return The "reply" to send to indicate the service was removed, or null if not necessary.
     */
    @Nullable
    public MdnsAnnouncer.ExitAnnouncementInfo removeService(int id) {
        final ServiceRegistration registration = mServices.get(id);
        if (registration == null) return null;

        mServices.delete(id);
        // Send exit (TTL 0) for the PTR record, if the record was sent (in particular don't send
        // if still probing)
        if (registration.ptrRecord.lastSentTimeMs == 0L) return null;

        final MdnsPointerRecord expiredRecord = new MdnsPointerRecord(
                registration.ptrRecord.record.getName(),
                0L /* receiptTimeMillis */,
                true /* cacheFlush */,
                0L /* ttlMillis */,
                registration.ptrRecord.record.getPointer());

        // Exit should be skipped if the record is still advertised by another service, but that
        // would be a conflict (2 service registrations with the same service name), so it would
        // not have been allowed by the repository.
        return new MdnsAnnouncer.ExitAnnouncementInfo(id,
                Collections.singletonList(expiredRecord), mProbeAnnounceDestSupplier);
    }

    /**
     * @return The number of services currently held in the repository.
     */
    public int getServicesCount() {
        return mServices.size();
    }

    @Nullable
    public ReplyInfo getReply(MdnsPacket packet, InetSocketAddress src) {
        final long now = SystemClock.elapsedRealtime();
        final boolean replyUnicast = (packet.flags & MdnsConstants.QCLASS_UNICAST) != 0;
        final ArrayList<MdnsRecord> additionalAnswerRecords = new ArrayList<>();
        final ArrayList<RecordInfo<?>> answerInfo = new ArrayList<>();
        for (MdnsRecord question : packet.questions) {
            // Add answers from general records
            addReplyFromService(question, mGeneralRecords, null /* servicePtrRecord */,
                    null /* serviceSrvRecord */, null /* serviceTxtRecord */, replyUnicast, now,
                    answerInfo, additionalAnswerRecords);

            // Add answers from each service
            for (int i = 0; i < mServices.size(); i++) {
                final ServiceRegistration registration = mServices.valueAt(i);
                addReplyFromService(question, registration.allRecords, registration.ptrRecord,
                        registration.srvRecord, registration.txtRecord, replyUnicast, now,
                        answerInfo, additionalAnswerRecords);
            }
        }

        if (answerInfo.size() == 0 && additionalAnswerRecords.size() == 0) {
            return null;
        }

        final long delayMs;
        if ((packet.flags & MdnsConstants.FLAG_TRUNCATED) != 0) {
            // RFC 6762 6.: 400-500ms delay if TC bit is set
            delayMs = 400L + mDelayGenerator.nextInt(100);
        } else if (packet.questions.size() > 1
                || CollectionUtils.any(answerInfo, a -> a.isSharedName)) {
            // 20-120ms if there may be responses from other hosts (not a fully owned
            // name) (RFC 6762 6.), or if there are multiple questions (6.3).
            // TODO: this should be 0 if this is a probe query ("can be distinguished from a
            // normal query by the fact that a probe query contains a proposed record in the
            // Authority Section that answers the question" in 6.), and the reply is for a fully
            // owned record.
            delayMs = 20L + mDelayGenerator.nextInt(100);
        } else {
            delayMs = 0L;
        }

        final InetSocketAddress dest;
        if (replyUnicast) {
            dest = src;
        } else if (src.getAddress() instanceof Inet4Address) {
            dest = MdnsAdvertiser.IPV4_ADDR;
        } else if (mInet6MulticastAddress == null) {
            Log.e(TAG, "Cannot reply via IPv6 multicast: link-local address unknown on " + mIface);
            return null;
        } else {
            // TODO: maybe could use an address without the scope, since the MulticastSocket is
            // bound to the interface anyway ?
            dest = mInet6MulticastAddress;
        }

        final ArrayList<MdnsRecord> answerRecords = new ArrayList<>(answerInfo.size());
        for (RecordInfo<?> info : answerInfo) {
            // TODO: consider actual packet send delay after response aggregation
            info.lastSentTimeMs = now + delayMs;
            if (!replyUnicast) {
                info.lastAdvertisedTimeMs = info.lastSentTimeMs;
            }
            answerRecords.add(info.record);
        }

        return new ReplyInfo(answerRecords, additionalAnswerRecords, delayMs, dest);
    }

    /**
     * Add answers and additional answers for a question, from a ServiceRegistration.
     */
    private void addReplyFromService(@NonNull MdnsRecord question,
            @NonNull List<RecordInfo<?>> serviceRecords,
            @Nullable RecordInfo<MdnsPointerRecord> servicePtrRecord,
            @Nullable RecordInfo<MdnsServiceRecord> serviceSrvRecord,
            @Nullable RecordInfo<MdnsTextRecord> serviceTxtRecord,
            boolean replyUnicast, long now, @NonNull List<RecordInfo<?>> answerInfo,
            @NonNull List<MdnsRecord> additionalAnswerRecords) {
        boolean hasDnsSdPtrRecordAnswer = false;
        boolean hasDnsSdSrvRecordAnswer = false;
        boolean hasFullyOwnedNameMatch = false;
        boolean hasKnownAnswer = false;

        final int answersStartIndex = answerInfo.size();
        for (RecordInfo<?> info : serviceRecords) {
            if (info.isProbing) continue;

             /* RFC6762 6.: the record name must match the question name, the record rrtype
             must match the question qtype unless the qtype is "ANY" (255) or the rrtype is
             "CNAME" (5), and the record rrclass must match the question qclass unless the
             qclass is "ANY" (255) */
            if (!Arrays.equals(info.record.getName(), question.getName())) continue;
            hasFullyOwnedNameMatch |= !info.isSharedName;

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
            hasDnsSdPtrRecordAnswer |= (info == servicePtrRecord);
            hasDnsSdSrvRecordAnswer |= (info == serviceSrvRecord);

            // TODO: responses to probe queries should bypass this check and only ensure the
            // reply is sent 250ms after the last sent time (RFC 6762 p.15); this was not
            // implemented by the legacy MDNS implementation either though.
            // TODO: recheck that assertion
            if (!replyUnicast && info.lastAdvertisedTimeMs > 0L
                    && now - info.lastAdvertisedTimeMs < MIN_MULTICAST_REPLY_INTERVAL_MS) {
                continue;
            }

            // TODO: Don't reply if in known answers of the querier (7.1) if TTL is > half

            answerInfo.add(info);
        }

        // RFC6762 6.1:
        // "Any time a responder receives a query for a name for which it has verified exclusive
        // ownership, for a type for which that name has no records, the responder MUST [...]
        // respond asserting the nonexistence of that record"
        if (hasFullyOwnedNameMatch && !hasKnownAnswer) {
            additionalAnswerRecords.add(new MdnsNsecRecord(
                    question.getName(),
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    // TODO: RFC6762 6.1: "In general, the TTL given for an NSEC record SHOULD
                    // be the same as the TTL that the record would have had, had it existed."
                    NAME_RECORDS_TTL_MILLIS,
                    question.getName(),
                    new int[] { question.getType() }));
        }

        // No more records to add if no answer
        if (answerInfo.size() == 0) return;

        final List<RecordInfo<?>> additionalAnswerInfo = new ArrayList<>();
        // RFC6763 12.1: if including PTR record, include the SRV and TXT records it names
        if (hasDnsSdPtrRecordAnswer) {
            if (serviceTxtRecord != null) {
                additionalAnswerInfo.add(serviceTxtRecord);
            }
            if (serviceSrvRecord != null) {
                additionalAnswerInfo.add(serviceSrvRecord);
            }
        }

        // RFC6763 12.1&.2: if including PTR or SRV record, include the address records it names
        if (hasDnsSdPtrRecordAnswer || hasDnsSdSrvRecordAnswer) {
            for (RecordInfo<?> record : mGeneralRecords) {
                if (record.record instanceof MdnsInetAddressRecord) {
                    additionalAnswerInfo.add(record);
                }
            }
        }

        for (RecordInfo<?> info : additionalAnswerInfo) {
            additionalAnswerRecords.add(info.record);
        }

        // RFC6762 6.1: negative responses
        addNsecRecordsForUniqueNames(answerInfo, answersStartIndex,
                additionalAnswerInfo, additionalAnswerRecords);
    }

    /**
     * Add NSEC records indicating that the response records are unique.
     *
     * Following RFC6762 6.1:
     * "On receipt of a question for a particular name, rrtype, and rrclass, for which a responder
     * does have one or more unique answers, the responder MAY also include an NSEC record in the
     * Additional Record Section indicating the nonexistence of other rrtypes for that name and
     * rrclass."
     * @param answers List of answered records based on which to add NSEC records
     * @param additionalAnswerRecords List to add the records to. Maybe the same as {@code answers}.
     */
    private static void addNsecRecordsForUniqueNames(
            List<RecordInfo<?>> answers, int answersStartIndex,
            List<RecordInfo<?>> additionalAnswerInfo, List<MdnsRecord> additionalAnswerRecords) {
        // Group unique records by name. Use a TreeMap with comparator as arrays don't implement
        // equals / hashCode.
        final Map<String[], List<MdnsRecord>> nsecByName = new TreeMap<>(Arrays::compare);
        // But keep the list of names in added order, otherwise records would be sorted in
        // alphabetical order instead of the order of the original records, which would look like
        // inconsistent behavior depending on service name.
        final List<String[]> namesInAddedOrder = new ArrayList<>();
        addUniqueRecords(answers, answersStartIndex, nsecByName, namesInAddedOrder);
        addUniqueRecords(additionalAnswerInfo, 0, nsecByName, namesInAddedOrder);

        for (String[] nsecName : namesInAddedOrder) {
            final List<MdnsRecord> entryRecords = nsecByName.get(nsecName);
            long minTtl = Long.MAX_VALUE;
            final Set<Integer> types = new ArraySet<>(entryRecords.size());
            for (MdnsRecord record : entryRecords) {
                if (minTtl > record.getTtl()) minTtl = record.getTtl();
                types.add(record.getType());
            }

            additionalAnswerRecords.add(new MdnsNsecRecord(
                    nsecName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    minTtl,
                    nsecName,
                    CollectionUtils.toIntArray(types)));
        }
    }

    private static void addUniqueRecords(
            List<RecordInfo<?>> records, int startIndex, Map<String[], List<MdnsRecord>> dest,
            List<String[]> namesInAddedOrder) {
        for (int i = startIndex; i < records.size(); i++) {
            final RecordInfo<?> answer = records.get(i);
            if (answer.isSharedName) continue;
            final List<MdnsRecord> recordsForName = dest.computeIfAbsent(answer.record.name,
                    key -> {
                namesInAddedOrder.add(key);
                return new ArrayList<>();
            });
            recordsForName.add(answer.record);
        }
    }

    public MdnsAnnouncer.AnnouncementInfo onProbingSucceeded(
            MdnsProber.ProbingInfo probeSuccessInfo)
            throws IOException {

        final ServiceRegistration registration = mServices.get(probeSuccessInfo.getServiceId());
        if (registration == null) throw new IOException(
                "Service is not registered: " + probeSuccessInfo.getServiceId());
        registration.setProbing(false);

        final ArrayList<MdnsRecord> answers = new ArrayList<>();
        final ArrayList<MdnsRecord> additionalAnswers = new ArrayList<>();

        // Interface address pointer records
        final int[] ptrTypes = new int[] { MdnsRecord.TYPE_PTR };
        for (RecordInfo<?> record : mGeneralRecords) {
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
        for (RecordInfo<?> info : registration.allRecords) {
            answers.add(info.record);
        }

        // A / AAAA NSEC record for the hostname. If A / AAAA records were added this record shows
        // there is no other address. If they were not, it indicates that the device does not have
        // IPv4 or IPv6 addresses.
        // See rfc6762 6.2
        additionalAnswers.add(new MdnsNsecRecord(
                mDeviceHostname,
                0L,
                true /* cacheFlush */,
                NAME_RECORDS_TTL_MILLIS,
                mDeviceHostname /* nextDomain */,
                new int[] { MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA }));

        return new MdnsAnnouncer.AnnouncementInfo(probeSuccessInfo.getServiceId(),
                answers, additionalAnswers, mProbeAnnounceDestSupplier);
    }

    public Set<Integer> getConflictingServices(MdnsPacket packet) {
        // Avoid allocating a new set for each incoming packet
        Set<Integer> conflicting = Collections.emptySet();
        for (MdnsRecord record : packet.answers) {
            for (int i = 0; i < mServices.size(); i++) {
                final ServiceRegistration registration = mServices.valueAt(i);

                // Only look for conflicts in service name, as a different service name can be used
                // if there is a conflict, but there is nothing actionable if any other conflict
                // happens. In fact probing is only done for the service name in the SRV record.
                // This means only SRV and TXT records need to be checked.
                final RecordInfo<MdnsServiceRecord> srvRecord = registration.srvRecord;
                if (!Arrays.equals(record.getName(), srvRecord.record.getName())) continue;

                // As per RFC6762 9., it's fine if the "conflict" is an identical record with same
                // data.
                if (record instanceof MdnsServiceRecord) {
                    final MdnsServiceRecord local = srvRecord.record;
                    final MdnsServiceRecord other = (MdnsServiceRecord) record;
                    if (other.getServicePriority() == local.getServicePriority()
                            && other.getServiceWeight() == local.getServiceWeight()
                            && other.getServicePort() == local.getServicePort()
                            && Arrays.equals(other.getServiceHost(), local.getServiceHost())) {
                        continue;
                    }
                }

                if (record instanceof MdnsTextRecord && registration.txtRecord != null) {
                    final MdnsTextRecord local = registration.txtRecord.record;
                    final MdnsTextRecord other = (MdnsTextRecord) record;
                    if (other.getEntries().equals(local.getEntries())) {
                        continue;
                    }
                }

                if (conflicting.size() == 0) {
                    conflicting = new ArraySet<>();
                }
                final int serviceId = mServices.keyAt(i);
                conflicting.add(serviceId);
            }
        }

        return conflicting;
    }

    @Nullable
    public MdnsProber.ProbingInfo setServiceProbing(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return null;

        registration.setProbing(true);
        return makeProbingInfo(
                serviceId, registration.serviceInfo, registration.srvRecord.record);
    }

    @Nullable
    public MdnsProber.ProbingInfo renameServiceForConflict(int serviceId, NsdServiceInfo newInfo) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return null;

        final ServiceRegistration newService = new ServiceRegistration(
                mDeviceHostname, newInfo);
        mServices.put(serviceId, newService);
        return makeProbingInfo(
                serviceId, newService.serviceInfo, newService.srvRecord.record
                /* isInitialProbing */);
    }

    public void onAdvertisementSent(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return;

        final long now = SystemClock.elapsedRealtime();
        for (RecordInfo<?> record : registration.allRecords) {
            record.lastSentTimeMs = now;
        }
    }

    public boolean isDoneProbing(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return true;

        return !registration.srvRecord.isProbing;
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

    private static String[] splitFullyQualifiedName(
            @NonNull NsdServiceInfo info, @NonNull String[] serviceType) {
        final String[] split = new String[serviceType.length + 1];
        split[0] = info.getServiceName();
        System.arraycopy(serviceType, 0, split, 1, serviceType.length);

        return split;
    }

    private static String[] splitServiceType(@NonNull NsdServiceInfo info) {
        // String.split(pattern, 0) removes trailing empty strings, which would appear when
        // splitting "domain.name." (with a dot a the end)
        final String[] split = info.getServiceType().split("\\.", 0);
        final String[] type = new String[split.length + 1];
        System.arraycopy(split, 0, type, 0, split.length);
        type[split.length] = "local";

        return type;
    }
}
