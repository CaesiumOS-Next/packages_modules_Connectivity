/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.ip;

import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_ICMPV6;
import static android.system.OsConstants.SOCK_NONBLOCK;
import static android.system.OsConstants.SOCK_RAW;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_SLLA;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_RA_HEADER_LEN;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_SOLICITATION;
import static com.android.net.module.util.NetworkStackConstants.IPV6_MIN_MTU;
import static com.android.net.module.util.NetworkStackConstants.PIO_FLAG_AUTONOMOUS;
import static com.android.net.module.util.NetworkStackConstants.PIO_FLAG_ON_LINK;
import static com.android.net.module.util.NetworkStackConstants.RFC7421_PREFIX_LENGTH;
import static com.android.net.module.util.NetworkStackConstants.TAG_SYSTEM_NEIGHBOR;
import static com.android.networkstack.tethering.util.TetheringUtils.getAllNodesForScopeId;

import android.content.Context;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.MacAddress;
import android.net.TrafficStats;
import android.net.util.SocketUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.WakeupMessage;
import com.android.net.module.util.FdEventsReader;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.structs.Icmpv6Header;
import com.android.net.module.util.structs.LlaOption;
import com.android.net.module.util.structs.MtuOption;
import com.android.net.module.util.structs.PrefixInformationOption;
import com.android.net.module.util.structs.RaHeader;
import com.android.net.module.util.structs.RdnssOption;
import com.android.networkstack.tethering.util.TetheringUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Basic IPv6 Router Advertisement Daemon.
 *
 * @hide
 */
public class RouterAdvertisementDaemon {
    private static final String TAG = RouterAdvertisementDaemon.class.getSimpleName();

    // Summary of various timers and lifetimes.
    private static final int MIN_RTR_ADV_INTERVAL_SEC = 300;
    private static final int MAX_RTR_ADV_INTERVAL_SEC = 600;
    // In general, router, prefix, and DNS lifetimes are all advised to be
    // greater than or equal to 3 * MAX_RTR_ADV_INTERVAL.  Here, we quadruple
    // that to allow for multicast packet loss.
    //
    // This MAX_RTR_ADV_INTERVAL_SEC and DEFAULT_LIFETIME are also consistent
    // with the https://tools.ietf.org/html/rfc7772#section-4 discussion of
    // "approximately 7 RAs per hour".
    private static final int DEFAULT_LIFETIME = 12 * MAX_RTR_ADV_INTERVAL_SEC;
    // From https://tools.ietf.org/html/rfc4861#section-10 .
    private static final int MIN_DELAY_BETWEEN_RAS_SEC = 3;
    // Both initial and final RAs, but also for changes in RA contents.
    // From https://tools.ietf.org/html/rfc4861#section-10 .
    private static final int  MAX_URGENT_RTR_ADVERTISEMENTS = 5;

    private static final int DAY_IN_SECONDS = 86_400;

    // Commands for IpServer to control RouterAdvertisementDaemon
    private static final int CMD_START                  = 1;
    private static final int CMD_STOP                   = 2;
    private static final int CMD_BUILD_NEW_RA_AND_SEND  = 3;
    // Command to send multicast RA
    private static final int CMD_SEND_MULTICAST_RA      = 4;

    private final Context mContext;
    private final InterfaceParams mInterface;
    private final InetSocketAddress mAllNodes;
    private final Random mRandom = new Random();
    private final AtomicInteger mUrgentAnnouncements = new AtomicInteger(0);
    private final Clock mClock;
    private final int mMinRtrAdvIntervalSec;
    private final int mMaxRtrAdvIntervalSec;
    private final int mMinDelayBetweenRasSec;

    // To be accessed only from RaMessageHandler
    private final DeprecatedInfoTracker mDeprecatedInfoTracker;
    private RsPacketListener mRsPacketListener;
    private final WakeupMessage mMulticastRaAlarm;
    private RaParams mRaParams;
    private final byte[] mRA = new byte[IPV6_MIN_MTU];
    private int mRaLength;

    private volatile FileDescriptor mSocket;
    private volatile RaMessageHandler mRaMessageHandler;
    private volatile HandlerThread mRaHandlerThread;

    /** Configuration for the RouterAdvertisementDaemon */
    public static class RouterAdvertisementConfiguration {
        public int minRtrAdvIntervalSec = MIN_RTR_ADV_INTERVAL_SEC;
        public int maxRtrAdvIntervalSec = MAX_RTR_ADV_INTERVAL_SEC;
        public int minDelayBetweenRasSec = MIN_DELAY_BETWEEN_RAS_SEC;
    }

    /** A wrapper class of {@link SystemClock} to be mocked in unit tests. */
    public static class Clock {
        /**
         * @see SystemClock#elapsedRealtime
         */
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }

    /** Encapsulate the RA parameters for RouterAdvertisementDaemon.*/
    public static class RaParams {
        // Tethered traffic will have the hop limit properly decremented.
        // Consequently, set the hoplimit greater by one than the upstream
        // unicast hop limit.
        static final byte DEFAULT_HOPLIMIT = 65;

        public boolean hasDefaultRoute;
        public byte hopLimit;
        public int mtu;
        public HashSet<IpPrefix> prefixes;
        public HashSet<Inet6Address> dnses;

        public RaParams() {
            hasDefaultRoute = false;
            hopLimit = DEFAULT_HOPLIMIT;
            mtu = IPV6_MIN_MTU;
            prefixes = new HashSet<IpPrefix>();
            dnses = new HashSet<Inet6Address>();
        }

        public RaParams(RaParams other) {
            hasDefaultRoute = other.hasDefaultRoute;
            hopLimit = other.hopLimit;
            mtu = other.mtu;
            prefixes = (HashSet) other.prefixes.clone();
            dnses = (HashSet) other.dnses.clone();
        }

        /**
         * Returns the subset of RA parameters that become deprecated when
         * moving from announcing oldRa to announcing newRa.
         *
         * Currently only tracks differences in |prefixes| and |dnses|.
         */
        public static RaParams getDeprecatedRaParams(RaParams oldRa, RaParams newRa) {
            RaParams newlyDeprecated = new RaParams();

            if (oldRa != null) {
                for (IpPrefix ipp : oldRa.prefixes) {
                    if (newRa == null || !newRa.prefixes.contains(ipp)) {
                        newlyDeprecated.prefixes.add(ipp);
                    }
                }

                for (Inet6Address dns : oldRa.dnses) {
                    if (newRa == null || !newRa.dnses.contains(dns)) {
                        newlyDeprecated.dnses.add(dns);
                    }
                }
            }

            return newlyDeprecated;
        }
    }

    private static class DeprecatedInfoTracker {
        private final HashMap<IpPrefix, Integer> mPrefixes = new HashMap<>();
        private final HashMap<Inet6Address, Integer> mDnses = new HashMap<>();

        Set<IpPrefix> getPrefixes() {
            return mPrefixes.keySet();
        }

        void putPrefixes(Set<IpPrefix> prefixes) {
            for (IpPrefix ipp : prefixes) {
                mPrefixes.put(ipp, MAX_URGENT_RTR_ADVERTISEMENTS);
            }
        }

        void removePrefixes(Set<IpPrefix> prefixes) {
            for (IpPrefix ipp : prefixes) {
                mPrefixes.remove(ipp);
            }
        }

        Set<Inet6Address> getDnses() {
            return mDnses.keySet();
        }

        void putDnses(Set<Inet6Address> dnses) {
            for (Inet6Address dns : dnses) {
                mDnses.put(dns, MAX_URGENT_RTR_ADVERTISEMENTS);
            }
        }

        void removeDnses(Set<Inet6Address> dnses) {
            for (Inet6Address dns : dnses) {
                mDnses.remove(dns);
            }
        }

        boolean isEmpty() {
            return mPrefixes.isEmpty() && mDnses.isEmpty();
        }

        private boolean decrementCounters() {
            boolean removed = decrementCounter(mPrefixes);
            removed |= decrementCounter(mDnses);
            return removed;
        }

        private <T> boolean decrementCounter(HashMap<T, Integer> map) {
            boolean removed = false;

            for (Iterator<Map.Entry<T, Integer>> it = map.entrySet().iterator();
                    it.hasNext();) {
                Map.Entry<T, Integer> kv = it.next();
                // TODO: This is a bug. A counter initialized with MAX_URGENT_RTR_ADVERTISEMENTS
                // needs MAX_URGENT_RTR_ADVERTISEMENTS + 1 times of decrementCounter() before it is
                // removed from the HashMap. This behavior exists for a long time, fix it when we
                // feel safe to do so.
                if (kv.getValue() == 0) {
                    it.remove();
                    removed = true;
                } else {
                    kv.setValue(kv.getValue() - 1);
                }
            }

            return removed;
        }
    }

    private class RaMessageHandler extends Handler {
        RaMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CMD_START:
                    mRsPacketListener = new RsPacketListener(this);
                    mRsPacketListener.start();
                    if (mRaLength >= ICMPV6_RA_HEADER_LEN) {
                        mMulticastRaAlarm.schedule(getNextMulticastTransmitTimeMs());
                    }
                    break;
                case CMD_STOP:
                    mMulticastRaAlarm.cancel();
                    if (mRsPacketListener != null) {
                        mRsPacketListener.stop();
                        mRsPacketListener = null;
                    }
                    break;
                case CMD_BUILD_NEW_RA_AND_SEND:
                    // raInfo.first is deprecatedParams and raInfo.second is newParams.
                    final Pair<RaParams, RaParams> raInfo = (Pair<RaParams, RaParams>) msg.obj;
                    if (raInfo.first != null) {
                        mDeprecatedInfoTracker.putPrefixes(raInfo.first.prefixes);
                        mDeprecatedInfoTracker.putDnses(raInfo.first.dnses);
                    }

                    if (raInfo.second != null) {
                        // Process information that is no longer deprecated.
                        mDeprecatedInfoTracker.removePrefixes(raInfo.second.prefixes);
                        mDeprecatedInfoTracker.removeDnses(raInfo.second.dnses);
                    }
                    mRaParams = raInfo.second;
                    assembleRa();

                    // Set to one fewer than the desired number, because as soon as
                    // the CMD_BUILD_NEW_RA_AND_SEND message is processed we immediately send
                    // a RA and mUrgentAnnouncements is not examined until the next
                    // mMulticastRaAlarm is scheduled (i.e. this way we send 4 and not 5).
                    mUrgentAnnouncements.set(MAX_URGENT_RTR_ADVERTISEMENTS - 1);
                    sendMulticastRaAndScheduleNext();
                    break;
                case CMD_SEND_MULTICAST_RA:
                    sendMulticastRaAndScheduleNext();
                    break;
                default:
                    Log.e(TAG, "Unknown message, cmd = " + String.valueOf(msg.what));
                    break;
            }
        }
    }

    private class RsPacketListener extends FdEventsReader<RsPacketListener.RecvBuffer> {
        private static final class RecvBuffer {
            // The recycled buffer for receiving Router Solicitations from clients.
            // If the RS is larger than IPV6_MIN_MTU the packets are truncated.
            // This is fine since currently only byte 0 is examined anyway.
            final byte[] mBytes = new byte[IPV6_MIN_MTU];
            final InetSocketAddress mSrcAddr = new InetSocketAddress(0);
        }

        RsPacketListener(@NonNull Handler handler) {
            super(handler, new RecvBuffer());
        }

        @Override
        protected int recvBufSize(@NonNull RecvBuffer buffer) {
            return buffer.mBytes.length;
        }

        @Override
        protected FileDescriptor createFd() {
            return mSocket;
        }

        @Override
        protected int readPacket(@NonNull FileDescriptor fd, @NonNull RecvBuffer buffer)
                throws Exception {
            return Os.recvfrom(
                    fd, buffer.mBytes, 0, buffer.mBytes.length, 0 /* flags */, buffer.mSrcAddr);
        }

        @Override
        protected final void handlePacket(@NonNull RecvBuffer buffer, int length) {
            // Do the least possible amount of validations.
            if (buffer.mSrcAddr == null
                    || length <= 0
                    || buffer.mBytes[0] != asByte(ICMPV6_ROUTER_SOLICITATION)) {
                return;
            }

            maybeSendRA(buffer.mSrcAddr);
        }
    }

    public RouterAdvertisementDaemon(Context context, InterfaceParams ifParams) {
        this(context, ifParams, new RouterAdvertisementConfiguration(), new Clock());
    }

    @VisibleForTesting
    public RouterAdvertisementDaemon(Context context, InterfaceParams ifParams,
            RouterAdvertisementConfiguration config, Clock clock) {
        mContext = context;
        mInterface = ifParams;
        mClock = clock;
        mMinRtrAdvIntervalSec = config.minRtrAdvIntervalSec;
        mMaxRtrAdvIntervalSec = config.maxRtrAdvIntervalSec;
        mMinDelayBetweenRasSec = config.minDelayBetweenRasSec;
        mAllNodes = new InetSocketAddress(getAllNodesForScopeId(mInterface.index), 0);
        mDeprecatedInfoTracker = new DeprecatedInfoTracker();
        mRaHandlerThread = new HandlerThread(TAG);
        mRaHandlerThread.start();
        mRaMessageHandler = new RaMessageHandler(mRaHandlerThread.getLooper());
        mMulticastRaAlarm = makeWakeupMessage("SEND_MULTICAST_RA", CMD_SEND_MULTICAST_RA);
    }

    /** Build new RA.*/
    public void buildNewRaAndSend(RaParams deprecatedParams, RaParams newParams) {
        final Pair<RaParams, RaParams> raInfo = new Pair<>(deprecatedParams, newParams);
        sendMessage(CMD_BUILD_NEW_RA_AND_SEND, raInfo);
    }

    /** Start router advertisement daemon. */
    public boolean start() {
        if (!createSocket()) {
            Log.e(TAG, "Failed to start RouterAdvertisementDaemon.");
            return false;
        }

        return sendMessage(CMD_START);
    }

    /** Stop router advertisement daemon. */
    public void stop() {
        if (!sendMessage(CMD_STOP)) {
            Log.e(TAG, "RouterAdvertisementDaemon has been stopped or was never started.");
            return;
        }

        closeSocket();
    }

    private WakeupMessage makeWakeupMessage(@NonNull String cmdName, int cmd) {
        cmdName = TAG + "." + mInterface.name + "." + cmdName;
        return new WakeupMessage(mContext, mRaMessageHandler, cmdName, cmd);
    }

    private void assembleRa() {
        final ByteBuffer ra = ByteBuffer.wrap(mRA);
        ra.order(ByteOrder.BIG_ENDIAN);

        final boolean haveRaParams = (mRaParams != null);
        boolean shouldSendRA = false;

        try {
            putHeader(ra, haveRaParams && mRaParams.hasDefaultRoute,
                    haveRaParams ? mRaParams.hopLimit : RaParams.DEFAULT_HOPLIMIT);
            putSlla(ra, mInterface.macAddr.toByteArray());
            mRaLength = ra.position();

            // https://tools.ietf.org/html/rfc5175#section-4 says:
            //
            //     "MUST NOT be added to a Router Advertisement message
            //      if no flags in the option are set."
            //
            // putExpandedFlagsOption(ra);

            if (haveRaParams) {
                putMtu(ra, mRaParams.mtu);
                mRaLength = ra.position();

                for (IpPrefix ipp : mRaParams.prefixes) {
                    putPio(ra, ipp, DEFAULT_LIFETIME, DEFAULT_LIFETIME);
                    mRaLength = ra.position();
                    shouldSendRA = true;
                }

                if (mRaParams.dnses.size() > 0) {
                    putRdnss(ra, mRaParams.dnses, DEFAULT_LIFETIME);
                    mRaLength = ra.position();
                    shouldSendRA = true;
                }
            }

            for (IpPrefix ipp : mDeprecatedInfoTracker.getPrefixes()) {
                putPio(ra, ipp, 0, 0);
                mRaLength = ra.position();
                shouldSendRA = true;
            }

            final Set<Inet6Address> deprecatedDnses = mDeprecatedInfoTracker.getDnses();
            if (!deprecatedDnses.isEmpty()) {
                putRdnss(ra, deprecatedDnses, 0);
                mRaLength = ra.position();
                shouldSendRA = true;
            }
        } catch (BufferOverflowException e) {
            // The packet up to mRaLength  is valid, since it has been updated
            // progressively as the RA was built. Log an error, and continue
            // on as best as possible.
            Log.e(TAG, "Could not construct new RA: " + e);
        }

        // We have nothing worth announcing; indicate as much to maybeSendRA().
        if (!shouldSendRA) {
            mRaLength = 0;
        }
    }

    private static byte asByte(int value) {
        return (byte) value;
    }
    private static short asShort(int value) {
        return (short) value;
    }

    private static void putHeader(ByteBuffer ra, boolean hasDefaultRoute, byte hopLimit) {
        // RFC 4191 "high" preference, iff. advertising a default route.
        final byte flags = hasDefaultRoute ? asByte(0x08) : asByte(0);
        final short lifetime = hasDefaultRoute ? asShort(DEFAULT_LIFETIME) : asShort(0);
        final Icmpv6Header icmpv6Header =
                new Icmpv6Header(asByte(ICMPV6_ROUTER_ADVERTISEMENT) /* type */,
                        asByte(0) /* code */, asShort(0) /* checksum */);
        final RaHeader raHeader = new RaHeader(hopLimit, flags, lifetime, 0 /* reachableTime */,
                0 /* retransTimer */);
        icmpv6Header.writeToByteBuffer(ra);
        raHeader.writeToByteBuffer(ra);
    }

    private static void putSlla(ByteBuffer ra, byte[] slla) {
        if (slla == null || slla.length != 6) {
            // Only IEEE 802.3 6-byte addresses are supported.
            return;
        }

        final ByteBuffer sllaOption = LlaOption.build(asByte(ICMPV6_ND_OPTION_SLLA),
                MacAddress.fromBytes(slla));
        ra.put(sllaOption);
    }

    private static void putExpandedFlagsOption(ByteBuffer ra) {
        /**
            Router Advertisement Expanded Flags Option

             0                   1                   2                   3
             0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |     Type      |    Length     |         Bit fields available ..
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            ... for assignment                                              |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         */

        final byte nd_option__efo = 26;
        final byte efo_num_8octets = 1;

        ra.put(nd_option__efo)
            .put(efo_num_8octets)
            .putShort(asShort(0))
            .putInt(0);
    }

    private static void putMtu(ByteBuffer ra, int mtu) {
        final ByteBuffer mtuOption = MtuOption.build((mtu < IPV6_MIN_MTU) ? IPV6_MIN_MTU : mtu);
        ra.put(mtuOption);
    }

    private static void putPio(ByteBuffer ra, IpPrefix ipp,
                               int validTime, int preferredTime) {
        final int prefixLength = ipp.getPrefixLength();
        if (prefixLength != 64) {
            return;
        }

        if (validTime < 0) validTime = 0;
        if (preferredTime < 0) preferredTime = 0;
        if (preferredTime > validTime) preferredTime = validTime;

        final ByteBuffer pioOption = PrefixInformationOption.build(ipp,
                asByte(PIO_FLAG_ON_LINK | PIO_FLAG_AUTONOMOUS), validTime, preferredTime);
        ra.put(pioOption);
    }

    private static void putRio(ByteBuffer ra, IpPrefix ipp) {
        /**
            Route Information Option

             0                   1                   2                   3
             0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |     Type      |    Length     | Prefix Length |Resvd|Prf|Resvd|
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |                        Route Lifetime                         |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |                   Prefix (Variable Length)                    |
            .                                                               .
            .                                                               .
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         */
        final int prefixLength = ipp.getPrefixLength();
        if (prefixLength > 64) {
            return;
        }
        final byte nd_option_rio = 24;
        final byte rio_num_8octets = asByte(
                (prefixLength == 0) ? 1 : (prefixLength <= 8) ? 2 : 3);

        final byte[] addr = ipp.getAddress().getAddress();
        ra.put(nd_option_rio)
            .put(rio_num_8octets)
            .put(asByte(prefixLength))
            .put(asByte(0x18))
            .putInt(DEFAULT_LIFETIME);

        // Rely upon an IpPrefix's address being properly zeroed.
        if (prefixLength > 0) {
            ra.put(addr, 0, (prefixLength <= 64) ? 8 : 16);
        }
    }

    private static void putRdnss(ByteBuffer ra, Set<Inet6Address> dnses, int lifetime) {
        final HashSet<Inet6Address> filteredDnses = new HashSet<>();
        for (Inet6Address dns : dnses) {
            if ((new LinkAddress(dns, RFC7421_PREFIX_LENGTH)).isGlobalPreferred()) {
                filteredDnses.add(dns);
            }
        }
        if (filteredDnses.isEmpty()) return;

        final Inet6Address[] dnsesArray =
                filteredDnses.toArray(new Inet6Address[filteredDnses.size()]);
        final ByteBuffer rdnssOption = RdnssOption.build(lifetime, dnsesArray);
        // NOTE: If the full of list DNS servers doesn't fit in the packet,
        // this code will cause a buffer overflow and the RA won't include
        // this instance of the option at all.
        //
        // TODO: Consider looking at ra.remaining() to determine how many
        // DNS servers will fit, and adding only those.
        ra.put(rdnssOption);
    }

    private boolean createSocket() {
        final int send_timout_ms = 300;

        final int oldTag = TrafficStats.getAndSetThreadStatsTag(TAG_SYSTEM_NEIGHBOR);
        try {
            mSocket = Os.socket(AF_INET6, SOCK_RAW | SOCK_NONBLOCK, IPPROTO_ICMPV6);
            // Setting SNDTIMEO is purely for defensive purposes.
            Os.setsockoptTimeval(
                    mSocket, SOL_SOCKET, SO_SNDTIMEO, StructTimeval.fromMillis(send_timout_ms));
            SocketUtils.bindSocketToInterface(mSocket, mInterface.name);
            TetheringUtils.setupRaSocket(mSocket, mInterface.index);
        } catch (ErrnoException | IOException e) {
            Log.e(TAG, "Failed to create RA daemon socket: " + e);
            return false;
        } finally {
            TrafficStats.setThreadStatsTag(oldTag);
        }

        return true;
    }

    private void closeSocket() {
        if (mSocket != null) {
            try {
                SocketUtils.closeSocket(mSocket);
            } catch (IOException ignored) { }
        }
        mSocket = null;
    }

    private boolean isSocketValid() {
        final FileDescriptor s = mSocket;
        return (s != null) && s.valid();
    }

    private boolean isSuitableDestination(InetSocketAddress dest) {
        if (mAllNodes.equals(dest)) {
            return true;
        }

        final InetAddress destip = dest.getAddress();
        return (destip instanceof Inet6Address)
               && destip.isLinkLocalAddress()
               && (((Inet6Address) destip).getScopeId() == mInterface.index);
    }

    private void maybeSendRA(InetSocketAddress dest) {
        if (dest == null || !isSuitableDestination(dest)) {
            dest = mAllNodes;
        }

        try {
            if (mRaLength < ICMPV6_RA_HEADER_LEN) {
                // No actual RA to send.
                return;
            }
            Os.sendto(mSocket, mRA, 0, mRaLength, 0, dest);
            Log.d(TAG, "RA sendto " + dest.getAddress().getHostAddress());
        } catch (ErrnoException | SocketException e) {
            if (isSocketValid()) {
                Log.e(TAG, "sendto error: " + e);
            }
        }
    }

    private void sendMulticastRaAndScheduleNext() {
        maybeSendRA(mAllNodes);
        if (mDeprecatedInfoTracker.decrementCounters()) {
            // At least one deprecated PIO has been removed;
            // reassemble the RA.
            assembleRa();
        }
        // If schedule is called multiple times without the message being dispatched
        // then the alarm is rescheduled to the new time.
        if (mRaLength < ICMPV6_RA_HEADER_LEN) return;
        mMulticastRaAlarm.schedule(getNextMulticastTransmitTimeMs());
    }

    private boolean sendMessage(int cmd) {
        return sendMessage(cmd, null);
    }

    private boolean sendMessage(int cmd, @Nullable Object obj) {
        if (mRaMessageHandler == null) {
            return false;
        }

        return mRaMessageHandler.sendMessage(
                Message.obtain(mRaMessageHandler, cmd, obj));
    }

    private long getNextMulticastTransmitTimeMs() {
        final long nowMs = mClock.elapsedRealtime();
        boolean deprecationInProgress = false;
        if (mRaLength < ICMPV6_RA_HEADER_LEN) {
            // No actual RA to send; just sleep for 1 day.
            return nowMs + 1000 * DAY_IN_SECONDS;
        }
        deprecationInProgress = !mDeprecatedInfoTracker.isEmpty();

        final int urgentPending = mUrgentAnnouncements.getAndDecrement();
        if ((urgentPending > 0) || deprecationInProgress) {
            return nowMs + 1000 * mMinDelayBetweenRasSec;
        }

        return nowMs + 1000 * (mMinRtrAdvIntervalSec
                + mRandom.nextInt(mMaxRtrAdvIntervalSec - mMinRtrAdvIntervalSec));
    }
}
