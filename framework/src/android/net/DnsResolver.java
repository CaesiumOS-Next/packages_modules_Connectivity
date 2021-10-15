/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import static android.net.NetworkUtils.getDnsNetwork;
import static android.net.NetworkUtils.resNetworkCancel;
import static android.net.NetworkUtils.resNetworkQuery;
import static android.net.NetworkUtils.resNetworkResult;
import static android.net.NetworkUtils.resNetworkSend;
import static android.net.util.DnsUtils.haveIpv4;
import static android.net.util.DnsUtils.haveIpv6;
import static android.net.util.DnsUtils.rfc6724Sort;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
import static android.system.OsConstants.ENONET;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.MessageQueue;
import android.system.ErrnoException;
import android.util.Log;

import com.android.net.module.util.DnsPacket;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Dns resolver class for asynchronous dns querying
 *
 * Note that if a client sends a query with more than 1 record in the question section but
 * the remote dns server does not support this, it may not respond at all, leading to a timeout.
 *
 */
public final class DnsResolver {
    private static final String TAG = "DnsResolver";
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    private static final int MAXPACKET = 8 * 1024;
    private static final int SLEEP_TIME_MS = 2;

    @IntDef(prefix = { "CLASS_" }, value = {
            CLASS_IN
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryClass {}
    public static final int CLASS_IN = 1;

    @IntDef(prefix = { "TYPE_" },  value = {
            TYPE_A,
            TYPE_AAAA,
            TYPE_AFSDB,
            TYPE_AMTRELAY,
            TYPE_ANY,
            TYPE_APL,
            TYPE_ATMA,
            TYPE_AVC,
            TYPE_AXFR,
            TYPE_CAA,
            TYPE_CDNSKEY,
            TYPE_CDS,
            TYPE_CERT,
            TYPE_CNAME,
            TYPE_CSYNC,
            TYPE_DHCID,
            TYPE_DNAME,
            TYPE_DNSKEY,
            TYPE_DOA,
            TYPE_DS,
            TYPE_EID,
            TYPE_EUI48,
            TYPE_EUI64,
            TYPE_GID,
            TYPE_GPOS,
            TYPE_HINFO,
            TYPE_HIP,
            TYPE_HTTPS,
            TYPE_IPSECKEY,
            TYPE_ISDN,
            TYPE_IXFR,
            TYPE_KEY,
            TYPE_KX,
            TYPE_L32,
            TYPE_L64,
            TYPE_LOC,
            TYPE_LP,
            TYPE_MAILB,
            TYPE_MB,
            TYPE_MG,
            TYPE_MINFO,
            TYPE_MR,
            TYPE_MX,
            TYPE_NAPTR,
            TYPE_NID,
            TYPE_NIMLOC,
            TYPE_NINFO,
            TYPE_NS,
            TYPE_NSAP,
            TYPE_NSAP_PTR,
            TYPE_NSEC,
            TYPE_NSEC3,
            TYPE_NSEC3PARAM,
            TYPE_NULL,
            TYPE_OPENPGPKEY,
            TYPE_OPT,
            TYPE_PRIVATE USE,
            TYPE_PTR,
            TYPE_PX,
            TYPE_RKEY,
            TYPE_RP,
            TYPE_RRSIG,
            TYPE_RT,
            TYPE_SIG,
            TYPE_SINK,
            TYPE_SMIMEA,
            TYPE_SOA,
            TYPE_SPF,
            TYPE_SRV,
            TYPE_SSHFP,
            TYPE_SVCB,
            TYPE_TA,
            TYPE_TALINK,
            TYPE_TKEY,
            TYPE_TLSA,
            TYPE_TSIG,
            TYPE_TXT,
            TYPE_UID,
            TYPE_UINFO,
            TYPE_UNSPEC,
            TYPE_URI,
            TYPE_WKS,
            TYPE_X25,
            TYPE_ZONEMD
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryType {}

    /** a host address [RFC1035] */
    public static final int TYPE_A = 1;

    /** an authoritative name server [RFC1035] */
    public static final int TYPE_NS = 2;

    /** the canonical name for an alias [RFC1035] */
    public static final int TYPE_CNAME = 5;

    /** marks the start of a zone of authority [RFC1035] */
    public static final int TYPE_SOA = 6;

    /** a mailbox domain name (EXPERIMENTAL) [RFC1035] */
    public static final int TYPE_MB = 7;

    /** a mail group member (EXPERIMENTAL) [RFC1035] */
    public static final int TYPE_MG = 8;

    /** a mail rename domain name (EXPERIMENTAL) [RFC1035] */
    public static final int TYPE_MR = 9;

    /** a null RR (EXPERIMENTAL) [RFC1035] */
    public static final int TYPE_NULL = 10;

    /** a well known service description [RFC1035] */
    public static final int TYPE_WKS = 11;

    /** a domain name pointer [RFC1035] */
    public static final int TYPE_PTR = 12;

    /** host information [RFC1035] */
    public static final int TYPE_HINFO = 13;

    /** mailbox or mail list information [RFC1035] */
    public static final int TYPE_MINFO = 14;

    /** mail exchange [RFC1035] */
    public static final int TYPE_MX = 15;

    /** text strings [RFC1035] */
    public static final int TYPE_TXT = 16;

    /** for Responsible Person [RFC1183] */
    public static final int TYPE_RP = 17;

    /** for AFS Data Base location [RFC1183][RFC5864] */
    public static final int TYPE_AFSDB = 18;

    /** for X.25 PSDN address [RFC1183] */
    public static final int TYPE_X25 = 19;

    /** for ISDN address [RFC1183] */
    public static final int TYPE_ISDN = 20;

    /** for Route Through [RFC1183] */
    public static final int TYPE_RT = 21;

    /** for NSAP address, NSAP style A record [RFC1706] */
    public static final int TYPE_NSAP = 22;

    /** for domain name pointer, NSAP style [RFC1348][RFC1637][RFC1706] */
    public static final int TYPE_NSAP_PTR = 23;

    /** security signature RFC2535, RFC2536, RFC2537, RFC2931, RFC3008, RFC3110, RFC3755, RFC4034 */
    public static final int TYPE_SIG = 24;

    /** for security key [RFC2535][RFC2536][RFC2537][RFC2539][RFC3008][RFC3110][RFC3755][RFC4034] */
    public static final int TYPE_KEY = 25;

    /** X.400 mail mapping information [RFC2163] */
    public static final int TYPE_PX = 26;

    /** Geographical Position [RFC1712] */
    public static final int TYPE_GPOS = 27;

    /** IP6 Address [RFC3596] */
    public static final int TYPE_AAAA = 28;

    /** Location Information [RFC1876] */
    public static final int TYPE_LOC = 29;

    /** Endpoint Identifier [Michael_Patton][http://ana-3.lcs.mit.edu/~jnc/nimrod/dns.txt] */
    public static final int TYPE_EID = 31;

    /** Nimrod Locator [1][Michael_Patton][http://ana-3.lcs.mit.edu/~jnc/nimrod/dns.txt] */
    public static final int TYPE_NIMLOC = 32;

    /** Server Selection [1][RFC2782] */
    public static final int TYPE_SRV = 33;

    /** ATM Address
        ATM Forum Technical Committee, "ATM Name System, V2.0", Doc
        ID: AF-DANS-0152.000, July 2000. Available from and held in
        escrow by IANA. */
    public static final int TYPE_ATMA = 34;

    /** Naming Authority Pointer [RFC2168][RFC2915][RFC3403] */
    public static final int TYPE_NAPTR = 35;

    /** Key Exchanger [RFC2230] */
    public static final int TYPE_KX = 36;

    /** CERT [RFC4398] */
    public static final int TYPE_CERT = 37;

    /** DNAME [RFC6672] */
    public static final int TYPE_DNAME = 39;

    /** SINK [Donald_E_Eastlake][http://tools.ietf.org/html/draft-eastlake-kitchen-sink] */
    public static final int TYPE_SINK = 40;

    /** OPT [RFC3225][RFC6891] */
    public static final int TYPE_OPT = 41;

    /** APL [RFC3123] */
    public static final int TYPE_APL = 42;

    /** Delegation Signer [RFC3658][RFC4034] */
    public static final int TYPE_DS = 43;

    /** SSH Key Fingerprint [RFC4255] */
    public static final int TYPE_SSHFP = 44;

    /** IPSECKEY [RFC4025] */
    public static final int TYPE_IPSECKEY = 45;

    /** RRSIG [RFC3755][RFC4034] */
    public static final int TYPE_RRSIG = 46;

    /** NSEC [RFC3755][RFC4034][RFC9077] */
    public static final int TYPE_NSEC = 47;

    /** DNSKEY [RFC3755][RFC4034] */
    public static final int TYPE_DNSKEY = 48;

    /** DHCID [RFC4701] */
    public static final int TYPE_DHCID = 49;

    /** NSEC3 [RFC5155][RFC9077] */
    public static final int TYPE_NSEC3 = 50;

    /** NSEC3PARAM [RFC5155] */
    public static final int TYPE_NSEC3PARAM = 51;

    /** TLSA [RFC6698] */
    public static final int TYPE_TLSA = 52;

    /** S/MIME cert association [RFC8162] */
    public static final int TYPE_SMIMEA = 53;

    /** Host Identity Protocol [RFC8005] */
    public static final int TYPE_HIP = 55;

    /** NINFO [Jim_Reid] */
    public static final int TYPE_NINFO = 56;

    /** RKEY [Jim_Reid] */
    public static final int TYPE_RKEY = 57;

    /** Trust Anchor LINK [Wouter_Wijngaards] */
    public static final int TYPE_TALINK = 58;

    /** Child DS [RFC7344] */
    public static final int TYPE_CDS = 59;

    /** DNSKEY(s) the Child wants reflected in DS [RFC7344] */
    public static final int TYPE_CDNSKEY = 60;

    /** OpenPGP Key [RFC7929] */
    public static final int TYPE_OPENPGPKEY = 61;

    /** Child-To-Parent Synchronization [RFC7477] */
    public static final int TYPE_CSYNC = 62;

    /** Message Digest Over Zone Data [RFC8976] */
    public static final int TYPE_ZONEMD = 63;

    /** Service Binding [draft-ietf-dnsop-svcb-https-00] */
    public static final int TYPE_SVCB = 64;

    /** HTTPS Binding [draft-ietf-dnsop-svcb-https-00] */
    public static final int TYPE_HTTPS = 65;

    /**  [RFC7208] */
    public static final int TYPE_SPF = 99;

    /**  [IANA-Reserved] */
    public static final int TYPE_UINFO = 100;

    /**  [IANA-Reserved] */
    public static final int TYPE_UID = 101;

    /**  [IANA-Reserved] */
    public static final int TYPE_GID = 102;

    /**  [IANA-Reserved] */
    public static final int TYPE_UNSPEC = 103;

    /**  [RFC6742] */
    public static final int TYPE_NID = 104;

    /**  [RFC6742] */
    public static final int TYPE_L32 = 105;

    /**  [RFC6742] */
    public static final int TYPE_L64 = 106;

    /**  [RFC6742] */
    public static final int TYPE_LP = 107;

    /** an EUI-48 address [RFC7043] */
    public static final int TYPE_EUI48 = 108;

    /** an EUI-64 address [RFC7043] */
    public static final int TYPE_EUI64 = 109;

    /** Transaction Key [RFC2930] */
    public static final int TYPE_TKEY = 249;

    /** Transaction Signature [RFC8945] */
    public static final int TYPE_TSIG = 250;

    /** incremental transfer [RFC1995] */
    public static final int TYPE_IXFR = 251;

    /** transfer of an entire zone [RFC1035][RFC5936] */
    public static final int TYPE_AXFR = 252;

    /** mailbox-related RRs (MB, MG or MR) [RFC1035] */
    public static final int TYPE_MAILB = 253;

    /** A request for some or all records the server has available [RFC1035][RFC6895][RFC8482] */
    public static final int TYPE_ANY = 255;

    /** URI [RFC7553] */
    public static final int TYPE_URI = 256;

    /** Certification Authority Restriction [RFC8659] */
    public static final int TYPE_CAA = 257;

    /** Application Visibility and Control [Wolfgang_Riedel] */
    public static final int TYPE_AVC = 258;

    /** Digital Object Architecture [draft-durand-doa-over-dns] */
    public static final int TYPE_DOA = 259;

    /** Automatic Multicast Tunneling Relay [RFC8777] */
    public static final int TYPE_AMTRELAY = 260;

    /** DNSSEC Trust Authorities
        [Sam_Weiler][http://cameo.library.cmu.edu/] Deploying DNSSEC
        Without a Signed Root.  Technical Report 1999-19, Information
        Networking Institute, Carnegie Mellon University, April
        2004. */
    public static final int TYPE_TA = 32768;

    @IntDef(prefix = { "FLAG_" }, value = {
            FLAG_EMPTY,
            FLAG_NO_RETRY,
            FLAG_NO_CACHE_STORE,
            FLAG_NO_CACHE_LOOKUP
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryFlag {}
    public static final int FLAG_EMPTY = 0;
    public static final int FLAG_NO_RETRY = 1 << 0;
    public static final int FLAG_NO_CACHE_STORE = 1 << 1;
    public static final int FLAG_NO_CACHE_LOOKUP = 1 << 2;

    @IntDef(prefix = { "ERROR_" }, value = {
            ERROR_PARSE,
            ERROR_SYSTEM
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DnsError {}
    /**
     * Indicates that there was an error parsing the response the query.
     * The cause of this error is available via getCause() and is a {@link ParseException}.
     */
    public static final int ERROR_PARSE = 0;
    /**
     * Indicates that there was an error sending the query.
     * The cause of this error is available via getCause() and is an ErrnoException.
     */
    public static final int ERROR_SYSTEM = 1;

    private static final int NETID_UNSET = 0;

    private static final DnsResolver sInstance = new DnsResolver();

    /**
     * Get instance for DnsResolver
     */
    public static @NonNull DnsResolver getInstance() {
        return sInstance;
    }

    private DnsResolver() {}

    /**
     * Base interface for answer callbacks
     *
     * @param <T> The type of the answer
     */
    public interface Callback<T> {
        /**
         * Success response to
         * {@link android.net.DnsResolver#query query()} or
         * {@link android.net.DnsResolver#rawQuery rawQuery()}.
         *
         * Invoked when the answer to a query was successfully parsed.
         *
         * @param answer <T> answer to the query.
         * @param rcode The response code in the DNS response.
         *
         * {@see android.net.DnsResolver#query query()}
         */
        void onAnswer(@NonNull T answer, int rcode);
        /**
         * Error response to
         * {@link android.net.DnsResolver#query query()} or
         * {@link android.net.DnsResolver#rawQuery rawQuery()}.
         *
         * Invoked when there is no valid answer to
         * {@link android.net.DnsResolver#query query()}
         * {@link android.net.DnsResolver#rawQuery rawQuery()}.
         *
         * @param error a {@link DnsException} object with additional
         *    detail regarding the failure
         */
        void onError(@NonNull DnsException error);
    }

    /**
     * Class to represent DNS error
     */
    public static class DnsException extends Exception {
       /**
        * DNS error code as one of the ERROR_* constants
        */
        @DnsError public final int code;

        DnsException(@DnsError int code, @Nullable Throwable cause) {
            super(cause);
            this.code = code;
        }
    }

    /**
     * Send a raw DNS query.
     * The answer will be provided asynchronously through the provided {@link Callback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param query blob message to query
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the caller
     *    of the result of dns query.
     */
    public void rawQuery(@Nullable Network network, @NonNull byte[] query, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super byte[]> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final Object lock = new Object();
        final FileDescriptor queryfd;
        try {
            queryfd = resNetworkSend((network != null)
                    ? network.getNetIdForResolv() : NETID_UNSET, query, query.length, flags);
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }

        synchronized (lock)  {
            registerFDListener(executor, queryfd, callback, cancellationSignal, lock);
            if (cancellationSignal == null) return;
            addCancellationSignal(cancellationSignal, queryfd, lock);
        }
    }

    /**
     * Send a DNS query with the specified name, class and query type.
     * The answer will be provided asynchronously through the provided {@link Callback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
     * @param nsClass dns class as one of the CLASS_* constants
     * @param nsType dns resource record (RR) type as one of the TYPE_* constants
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the caller
     *    of the result of dns query.
     */
    public void rawQuery(@Nullable Network network, @NonNull String domain,
            @QueryClass int nsClass, @QueryType int nsType, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super byte[]> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final Object lock = new Object();
        final FileDescriptor queryfd;
        try {
            queryfd = resNetworkQuery((network != null)
                    ? network.getNetIdForResolv() : NETID_UNSET, domain, nsClass, nsType, flags);
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }
        synchronized (lock)  {
            registerFDListener(executor, queryfd, callback, cancellationSignal, lock);
            if (cancellationSignal == null) return;
            addCancellationSignal(cancellationSignal, queryfd, lock);
        }
    }

    private class InetAddressAnswerAccumulator implements Callback<byte[]> {
        private final List<InetAddress> mAllAnswers;
        private final Network mNetwork;
        private int mRcode;
        private DnsException mDnsException;
        private final Callback<? super List<InetAddress>> mUserCallback;
        private final int mTargetAnswerCount;
        private int mReceivedAnswerCount = 0;

        InetAddressAnswerAccumulator(@NonNull Network network, int size,
                @NonNull Callback<? super List<InetAddress>> callback) {
            mNetwork = network;
            mTargetAnswerCount = size;
            mAllAnswers = new ArrayList<>();
            mUserCallback = callback;
        }

        private boolean maybeReportError() {
            if (mRcode != 0) {
                mUserCallback.onAnswer(mAllAnswers, mRcode);
                return true;
            }
            if (mDnsException != null) {
                mUserCallback.onError(mDnsException);
                return true;
            }
            return false;
        }

        private void maybeReportAnswer() {
            if (++mReceivedAnswerCount != mTargetAnswerCount) return;
            if (mAllAnswers.isEmpty() && maybeReportError()) return;
            mUserCallback.onAnswer(rfc6724Sort(mNetwork, mAllAnswers), mRcode);
        }

        @Override
        public void onAnswer(@NonNull byte[] answer, int rcode) {
            // If at least one query succeeded, return an rcode of 0.
            // Otherwise, arbitrarily return the first rcode received.
            if (mReceivedAnswerCount == 0 || rcode == 0) {
                mRcode = rcode;
            }
            try {
                mAllAnswers.addAll(new DnsAddressAnswer(answer).getAddresses());
            } catch (DnsPacket.ParseException e) {
                // Convert the com.android.net.module.util.DnsPacket.ParseException to an
                // android.net.ParseException. This is the type that was used in Q and is implied
                // by the public documentation of ERROR_PARSE.
                //
                // DnsPacket cannot throw android.net.ParseException directly because it's @hide.
                ParseException pe = new ParseException(e.reason, e.getCause());
                pe.setStackTrace(e.getStackTrace());
                mDnsException = new DnsException(ERROR_PARSE, pe);
            }
            maybeReportAnswer();
        }

        @Override
        public void onError(@NonNull DnsException error) {
            mDnsException = error;
            maybeReportAnswer();
        }
    }

    /**
     * Send a DNS query with the specified name on a network with both IPv4 and IPv6,
     * get back a set of InetAddresses with rfc6724 sorting style asynchronously.
     *
     * This method will examine the connection ability on given network, and query IPv4
     * and IPv6 if connection is available.
     *
     * If at least one query succeeded with valid answer, rcode will be 0
     *
     * The answer will be provided asynchronously through the provided {@link Callback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the
     *    caller of the result of dns query.
     */
    public void query(@Nullable Network network, @NonNull String domain, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super List<InetAddress>> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final Object lock = new Object();
        final Network queryNetwork;
        try {
            queryNetwork = (network != null) ? network : getDnsNetwork();
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }
        final boolean queryIpv6 = haveIpv6(queryNetwork);
        final boolean queryIpv4 = haveIpv4(queryNetwork);

        // This can only happen if queryIpv4 and queryIpv6 are both false.
        // This almost certainly means that queryNetwork does not exist or no longer exists.
        if (!queryIpv6 && !queryIpv4) {
            executor.execute(() -> callback.onError(
                    new DnsException(ERROR_SYSTEM, new ErrnoException("resNetworkQuery", ENONET))));
            return;
        }

        final FileDescriptor v4fd;
        final FileDescriptor v6fd;

        int queryCount = 0;

        if (queryIpv6) {
            try {
                v6fd = resNetworkQuery(queryNetwork.getNetIdForResolv(), domain, CLASS_IN,
                        TYPE_AAAA, flags);
            } catch (ErrnoException e) {
                executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
                return;
            }
            queryCount++;
        } else v6fd = null;

        // Avoiding gateways drop packets if queries are sent too close together
        try {
            Thread.sleep(SLEEP_TIME_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        if (queryIpv4) {
            try {
                v4fd = resNetworkQuery(queryNetwork.getNetIdForResolv(), domain, CLASS_IN, TYPE_A,
                        flags);
            } catch (ErrnoException e) {
                if (queryIpv6) resNetworkCancel(v6fd);  // Closes fd, marks it invalid.
                executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
                return;
            }
            queryCount++;
        } else v4fd = null;

        final InetAddressAnswerAccumulator accumulator =
                new InetAddressAnswerAccumulator(queryNetwork, queryCount, callback);

        synchronized (lock)  {
            if (queryIpv6) {
                registerFDListener(executor, v6fd, accumulator, cancellationSignal, lock);
            }
            if (queryIpv4) {
                registerFDListener(executor, v4fd, accumulator, cancellationSignal, lock);
            }
            if (cancellationSignal == null) return;
            cancellationSignal.setOnCancelListener(() -> {
                synchronized (lock)  {
                    if (queryIpv4) cancelQuery(v4fd);
                    if (queryIpv6) cancelQuery(v6fd);
                }
            });
        }
    }

    /**
     * Send a DNS query with the specified name and query type, get back a set of
     * InetAddresses with rfc6724 sorting style asynchronously.
     *
     * The answer will be provided asynchronously through the provided {@link Callback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
     * @param nsType dns resource record (RR) type as one of the TYPE_* constants
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the caller
     *    of the result of dns query.
     */
    public void query(@Nullable Network network, @NonNull String domain,
            @QueryType int nsType, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super List<InetAddress>> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final Object lock = new Object();
        final FileDescriptor queryfd;
        final Network queryNetwork;
        try {
            queryNetwork = (network != null) ? network : getDnsNetwork();
            queryfd = resNetworkQuery(queryNetwork.getNetIdForResolv(), domain, CLASS_IN, nsType,
                    flags);
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }
        final InetAddressAnswerAccumulator accumulator =
                new InetAddressAnswerAccumulator(queryNetwork, 1, callback);
        synchronized (lock)  {
            registerFDListener(executor, queryfd, accumulator, cancellationSignal, lock);
            if (cancellationSignal == null) return;
            addCancellationSignal(cancellationSignal, queryfd, lock);
        }
    }

    /**
     * Class to retrieve DNS response
     *
     * @hide
     */
    public static final class DnsResponse {
        public final @NonNull byte[] answerbuf;
        public final int rcode;
        public DnsResponse(@NonNull byte[] answerbuf, int rcode) {
            this.answerbuf = answerbuf;
            this.rcode = rcode;
        }
    }

    private void registerFDListener(@NonNull Executor executor,
            @NonNull FileDescriptor queryfd, @NonNull Callback<? super byte[]> answerCallback,
            @Nullable CancellationSignal cancellationSignal, @NonNull Object lock) {
        final MessageQueue mainThreadMessageQueue = Looper.getMainLooper().getQueue();
        mainThreadMessageQueue.addOnFileDescriptorEventListener(
                queryfd,
                FD_EVENTS,
                (fd, events) -> {
                    // b/134310704
                    // Unregister fd event listener before resNetworkResult is called to prevent
                    // race condition caused by fd reused.
                    // For example when querying v4 and v6, it's possible that the first query ends
                    // and the fd is closed before the second request starts, which might return
                    // the same fd for the second request. By that time, the looper must have
                    // unregistered the fd, otherwise another event listener can't be registered.
                    mainThreadMessageQueue.removeOnFileDescriptorEventListener(fd);

                    executor.execute(() -> {
                        DnsResponse resp = null;
                        ErrnoException exception = null;
                        synchronized (lock) {
                            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                                return;
                            }
                            try {
                                resp = resNetworkResult(fd);  // Closes fd, marks it invalid.
                            } catch (ErrnoException e) {
                                Log.w(TAG, "resNetworkResult:" + e.toString());
                                exception = e;
                            }
                        }
                        if (exception != null) {
                            answerCallback.onError(new DnsException(ERROR_SYSTEM, exception));
                            return;
                        }
                        answerCallback.onAnswer(resp.answerbuf, resp.rcode);
                    });

                    // The file descriptor has already been unregistered, so it does not really
                    // matter what is returned here. In spirit 0 (meaning "unregister this FD")
                    // is still the closest to what the looper needs to do. When returning 0,
                    // Looper knows to ignore the fd if it has already been unregistered.
                    return 0;
                });
    }

    private void cancelQuery(@NonNull FileDescriptor queryfd) {
        if (!queryfd.valid()) return;
        Looper.getMainLooper().getQueue().removeOnFileDescriptorEventListener(queryfd);
        resNetworkCancel(queryfd);  // Closes fd, marks it invalid.
    }

    private void addCancellationSignal(@NonNull CancellationSignal cancellationSignal,
            @NonNull FileDescriptor queryfd, @NonNull Object lock) {
        cancellationSignal.setOnCancelListener(() -> {
            synchronized (lock)  {
                cancelQuery(queryfd);
            }
        });
    }

    private static class DnsAddressAnswer extends DnsPacket {
        private static final String TAG = "DnsResolver.DnsAddressAnswer";
        private static final boolean DBG = false;

        private final int mQueryType;

        DnsAddressAnswer(@NonNull byte[] data) throws ParseException {
            super(data);
            if ((mHeader.flags & (1 << 15)) == 0) {
                throw new ParseException("Not an answer packet");
            }
            if (mHeader.getRecordCount(QDSECTION) == 0) {
                throw new ParseException("No question found");
            }
            // Expect only one question in question section.
            mQueryType = mRecords[QDSECTION].get(0).nsType;
        }

        public @NonNull List<InetAddress> getAddresses() {
            final List<InetAddress> results = new ArrayList<InetAddress>();
            if (mHeader.getRecordCount(ANSECTION) == 0) return results;

            for (final DnsRecord ansSec : mRecords[ANSECTION]) {
                // Only support A and AAAA, also ignore answers if query type != answer type.
                int nsType = ansSec.nsType;
                if (nsType != mQueryType || (nsType != TYPE_A && nsType != TYPE_AAAA)) {
                    continue;
                }
                try {
                    results.add(InetAddress.getByAddress(ansSec.getRR()));
                } catch (UnknownHostException e) {
                    if (DBG) {
                        Log.w(TAG, "rr to address fail");
                    }
                }
            }
            return results;
        }
    }

}
