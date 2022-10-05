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


import android.annotation.Nullable;
import android.content.Context;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.Log;

import com.android.net.module.util.HexDump;
import com.android.server.connectivity.mdns.MdnsPacketRepeater.PacketRepeaterCallback;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;

/**
 * This class is not thread-safe, and all methods must be called on the Looper provided on
 * creation.
 */
public class MdnsAdvertiser {
    private static final String TAG = MdnsAdvertiser.class.getSimpleName();
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int CMD_ADD_SERVICE = 1;
    private static final int CMD_REMOVE_SERVICE = 2;
    private static final int CMD_SEND_PROBE = 3;

    // https://datatracker.ietf.org/doc/html/rfc6762#section-8.1
    private static final long PROBE_DELAY_MS = 250;
    private static final int PROBE_COUNT = 2;
    static final SocketAddress IPV6_ADDR = new InetSocketAddress(
            MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT);
    static final SocketAddress IPV4_ADDR = new InetSocketAddress(
                    MdnsConstants.getMdnsIPv4Address(), MdnsConstants.MDNS_PORT);

    private final Context mContext;
    private final Looper mLooper;

    // Max-sized buffers to be used as temporary buffer to read/build packets. May be used by
    // multiple components, but only for self-contained operations in the looper thread, so not
    // concurrently.
    private final byte[] mPacketCreationBuffer = new byte[1500];
    private final byte[] mPacketReaderBuffer = new byte[1500];

    private final ArrayMap<NetworkInterface, InterfaceAdvertiser> mInterfaceAdvertisers
            = new ArrayMap<>();

    private class InterfaceAdvertiser implements MulticastPacketReader.PacketHandler {
        private final MulticastSocket mSocket;
        private final MdnsRecordRepository mRecordRepository;
        private final MdnsAnnouncer mAnnouncer;
        private final MdnsProber mProber;
        private final MdnsReplySender mReplySender;
        private final MulticastPacketReader mPacketReader;

        private List<InterfaceAddress> mAddresses;
        private boolean mJoinedIpv4;
        private boolean mJoinedIpv6;

        private class ProbingCallback implements PacketRepeaterCallback<ProbingInfo> {
            @Override
            public void onFinished(ProbingInfo info) {
                final MdnsAnnouncer.AnnouncementInfo announcementInfo;
                if (DBG) {
                    Log.v(TAG, "Probing finished for service " + info.getServiceId());
                }
                try {
                    announcementInfo = mRecordRepository.getRecordsToAnnounce(info);
                } catch (IOException e) {
                    Log.e(TAG, "Error building announcements", e);
                    return;
                }

                mAnnouncer.startSending(info.getServiceId(), announcementInfo,
                        0L /* initialDelayMs */);
            }
        }

        public InterfaceAdvertiser(NetworkInterface iface) throws IOException {
            mRecordRepository = new MdnsRecordRepository(iface, mPacketCreationBuffer);
            // TODO: this may return addresses from stacked interfaces ? Avoid that
            mAddresses = iface.getInterfaceAddresses();
            mSocket = new MulticastSocket(MdnsConstants.MDNS_PORT);
            mAnnouncer = new MdnsAnnouncer(iface.getName(), mLooper, mSocket);
            mProber = new MdnsProber(iface.getName(), mLooper, mSocket, mRecordRepository,
                    new ProbingCallback());
            mReplySender = new MdnsReplySender(iface.getName(), mLooper, mSocket,
                    mPacketCreationBuffer);
            // TTL as per https://datatracker.ietf.org/doc/html/rfc6762#section-11
            mSocket.setTimeToLive(255);
            mSocket.setNetworkInterface(iface);
            final ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(mSocket);
            try {
                final int flags = Os.fcntlInt(pfd.getFileDescriptor(), OsConstants.F_GETFL, 0);
                Os.fcntlInt(pfd.getFileDescriptor(), OsConstants.F_SETFL,
                        flags | OsConstants.SOCK_NONBLOCK);
            } catch (ErrnoException e) {
                throw new IOException("Error setting socket options", e);
            }

            maybeJoinIpv6(iface, mAddresses);
            maybeJoinIpv4(iface, mAddresses);
            mPacketReader = new MulticastPacketReader(iface.getName(), pfd,
                    new Handler(mLooper), mPacketReaderBuffer, this /* packetHandler */);
            mPacketReader.start();
        }

        public void onLinkPropertiesUpdated(NetworkInterface iface) {
            final List<InterfaceAddress> addrs = iface.getInterfaceAddresses();
            final boolean changed = !Objects.equals(addrs, mAddresses);
            if (changed) {
                mAddresses = addrs;
                maybeJoinIpv6(iface, addrs);
                maybeJoinIpv4(iface, addrs);
                // Do something: notify repository to update records, go back to advertising etc.
            }
        }

        public void destroy() {
            // TODO: stop responder
            mPacketReader.stop();
            try {
                if (mJoinedIpv4) {
                    mSocket.leaveGroup(MdnsConstants.getMdnsIPv4Address());
                }
                if (mJoinedIpv6) {
                    mSocket.leaveGroup(MdnsConstants.getMdnsIPv6Address());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error leaving multicast groups", e);
            }
            mSocket.close();
        }

        public void addService(int id, NsdServiceInfo service) throws IOException {
            final ProbingInfo info = mRecordRepository.addService(id, service);
            mProber.startProbing(info);
        }

        public int removeService(int id) {
            mProber.stop(id);
            mAnnouncer.stop(id);
            return mRecordRepository.removeService(id);
        }

        private <T extends InetAddress> boolean hasAddress(List<InterfaceAddress> addrs,
                Class<T> addrClass) {
            for (InterfaceAddress addr : addrs) {
                if (addrClass.isAssignableFrom(addr.getAddress().getClass())) {
                    return true;
                }
            }

            return false;
        }

        private boolean joinGroup(NetworkInterface iface, InetAddress multicastAddr) {
            try {
                mSocket.joinGroup(multicastAddr);
                return true;
            } catch (IOException e) {
                // The IPv4 address may have just been removed
                Log.e(TAG, "Error joining multicast group for " + iface, e);
                return false;
            }
        }

        private void maybeJoinIpv4(NetworkInterface iface, List<InterfaceAddress> addrs) {
            final boolean hasAddr = hasAddress(addrs, Inet4Address.class);
            if (!mJoinedIpv4 && hasAddr) {
                mJoinedIpv4 = joinGroup(iface, MdnsConstants.getMdnsIPv4Address());
            } else if (!hasAddr) {
                // Lost IPv4 address
                mJoinedIpv4 = false;
            }
        }

        private void maybeJoinIpv6(NetworkInterface iface, List<InterfaceAddress> addrs) {
            final boolean hasAddr = hasAddress(addrs, Inet6Address.class);
            if (!mJoinedIpv6 && hasAddr) {
                mJoinedIpv6 = joinGroup(iface, MdnsConstants.getMdnsIPv6Address());
            } else if (!hasAddr) {
                // Lost IPv6 address
                mJoinedIpv6 = false;
            }
        }

        @Override
        public void handlePacket(byte[] recvbuf, int length, InetSocketAddress src) {
            final MdnsPacket packet;
            try {
                packet = MdnsPacket.parse(new MdnsPacketReader(recvbuf, length));
            } catch (IOException e) {
                Log.e(TAG, "Error parsing mDNS packet", e);
                if (DBG) Log.v(TAG, "Packet: " + HexDump.toHexString(recvbuf, 0, length));
                return;
            }

            for (ProbingInfo restartedServices : mProber.maybeHandleConflict(packet)) {
                mAnnouncer.stop(restartedServices.getServiceId());
            }
            final MdnsRecordRepository.ReplyInfo answers =
                    mRecordRepository.getReply(packet, src);

            if (answers == null) return;
            mReplySender.queueReply(answers);
        }
    }

    public MdnsAdvertiser(Looper looper, Context context) {
        mLooper = looper;
        mContext = context;
    }

    // NsdService should work with Network objects and file a NetworkCallback to get LinkProperties
    // updates. It sends services to advertise down to MdnsAdvertiser after resolving the interface
    // that goes with the Network.
    public void addService(int id, NsdServiceInfo service, List<NetworkInterface> ifaces) {
        if (DBG) {
            Log.i(TAG, "Adding service " + service + " with ID " + id + " to interfaces " + ifaces);
        }
        try {
            for (NetworkInterface iface : ifaces) {
                InterfaceAdvertiser advertiser = mInterfaceAdvertisers.get(iface);
                if (advertiser == null) {
                    advertiser = new InterfaceAdvertiser(iface);
                    mInterfaceAdvertisers.put(iface, advertiser);
                }
                advertiser.addService(id, service);
                // TODO: do probing
            }
        } catch (IOException e) {
            Log.e(TAG, "Error adding service " + service, e);
            removeService(id);
        }
    }

    public void onLinkPropertiesUpdated(NetworkInterface iface) {
        // TODO: sent by NsdService when there is a LinkProperties update for a Network on the given
        // interface. To be used to reevaluate records and possibly go back to advertising (as per
        // RFC on network changes).
    }

    public void removeService(int id) {
        if (DBG) {
            Log.i(TAG, "Removing service with ID " + id);
        }
        for (int i = mInterfaceAdvertisers.size() - 1; i >= 0; i--) {
            final InterfaceAdvertiser advertiser = mInterfaceAdvertisers.valueAt(i);
            if (advertiser.removeService(id) == 0) {
                advertiser.destroy();
                mInterfaceAdvertisers.removeAt(i);
            }
        }
    }
}
