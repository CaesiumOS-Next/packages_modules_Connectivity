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
import android.net.nsd.NsdServiceInfo;
import android.net.util.SocketUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.android.net.module.util.HexDump;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.List;
import java.util.Objects;

class MdnsInterfaceAdvertiser implements MulticastPacketReader.PacketHandler {
    private final boolean DBG = MdnsAdvertiser.DBG;
    @NonNull
    private final String mTag;
    @NonNull
    private final ProbingCallback mProbingCallback = new ProbingCallback();
    @NonNull
    private final AnnouncingCallback mAnnouncingCallback = new AnnouncingCallback();
    @NonNull
    private final MulticastSocket mSocket;
    private final MdnsRecordRepository mRecordRepository;
    @NonNull
    private final MdnsAnnouncer mAnnouncer;
    @NonNull
    private final MdnsProber mProber;
    private final MdnsReplySender mReplySender;
    @NonNull
    private final MulticastPacketReader mPacketReader;
    @NonNull
    private final Callback mCb;

    private List<InterfaceAddress> mAddresses;
    private boolean mJoinedIpv4;
    private boolean mJoinedIpv6;

    interface Callback {
        void onRegisterServiceSucceeded(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId);
        void onServiceConflict(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId);
    }

    private class ProbingCallback implements
            MdnsPacketRepeater.PacketRepeaterCallback<MdnsProber.ProbingInfo> {
        @Override
        public void onFinished(MdnsProber.ProbingInfo info) {
            final MdnsAnnouncer.AnnouncementInfo announcementInfo;
            if (DBG) {
                Log.v(mTag, "Probing finished for service " + info.getServiceId());
            }
            try {
                announcementInfo = mRecordRepository.onProbingSucceeded(info);
            } catch (IOException e) {
                Log.e(mTag, "Error building announcements", e);
                return;
            }

            mCb.onRegisterServiceSucceeded(MdnsInterfaceAdvertiser.this, info.getServiceId());
            mAnnouncer.startSending(info.getServiceId(), announcementInfo,
                    0L /* initialDelayMs */);
        }
    }

    private class AnnouncingCallback
            implements MdnsPacketRepeater.PacketRepeaterCallback<MdnsAnnouncer.AnnouncementInfo> {
        @Override
        public void onSent(int index, @NonNull MdnsAnnouncer.AnnouncementInfo info) {
            mRecordRepository.onAdvertisementSent(info.getServiceId());
        }

        @Override
        public void onFinished(@NonNull MdnsAnnouncer.AnnouncementInfo info) {
            if (info instanceof MdnsAnnouncer.ExitAnnouncementInfo
                    && mRecordRepository.getServicesCount() == 0) {
                destroy();
            }
        }
    }

    public MdnsInterfaceAdvertiser(@NonNull NetworkInterface iface, @NonNull Looper looper,
            @NonNull byte[] packetReadBuffer, @NonNull byte[] packetCreationBuffer,
            @NonNull Callback cb)
            throws IOException {
        mTag = MdnsInterfaceAdvertiser.class.getSimpleName() + "/" + iface.getName();
        mCb = cb;
        mRecordRepository = new MdnsRecordRepository(iface, looper);
        // TODO: this may return addresses from stacked interfaces ? Avoid that
        mAddresses = iface.getInterfaceAddresses();
        mSocket = new MulticastSocket(MdnsConstants.MDNS_PORT);
        mReplySender = new MdnsReplySender(iface.getName(), looper, mSocket,
                packetCreationBuffer);
        mAnnouncer = new MdnsAnnouncer(iface.getName(), looper, mReplySender,
                mAnnouncingCallback);
        mProber = new MdnsProber(iface.getName(), looper, mReplySender, mRecordRepository,
                mProbingCallback);
        // TTL as per https://datatracker.ietf.org/doc/html/rfc6762#section-11
        mSocket.setTimeToLive(255);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(mSocket);
        try {
            final FileDescriptor fd = pfd.getFileDescriptor();
            final int flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0);
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags | OsConstants.SOCK_NONBLOCK);
            // TODO: if the advertiser sees its own packets it may detect conflicts, in case
            // records have changed between the time a packet is sent and received back.
            // But disabling IP_MULTICAST_LOOP prevents mdnsresponder from seeing packets
            // from this advertiser.
            // Figure out a way to fix that.
            // Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_MULTICAST_LOOP, 0);
            // Os.setsockoptInt(fd, OsConstants.IPPROTO_IPV6,
            //         OsConstants.IPV6_MULTICAST_LOOP, 0);
            // Ensure that received packets are only from that interface
            SocketUtils.bindSocketToInterface(fd, iface.getName());
        } catch (ErrnoException e) {
            throw new IOException("Error setting socket options", e);
        }

        mSocket.setNetworkInterface(iface);
        maybeJoinIpv6(iface, mAddresses);
        maybeJoinIpv4(iface, mAddresses);
        mPacketReader = new MulticastPacketReader(iface.getName(), pfd,
                new Handler(looper), packetReadBuffer, this /* packetHandler */);
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

    private void destroy() {
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
            Log.e(mTag, "Error leaving multicast groups", e);
        }
        mSocket.close();
    }

    public void addService(int id, NsdServiceInfo service) throws IOException {
        final MdnsProber.ProbingInfo info = mRecordRepository.addService(id, service);
        mProber.startProbing(info);
    }

    public boolean removeServiceAndMaybeDestroy(int id) {
        mProber.stop(id);
        mAnnouncer.stop(id);
        final MdnsAnnouncer.ExitAnnouncementInfo exitInfo = mRecordRepository.removeService(id);
        if (exitInfo != null) {
            // This effectively schedules destroy(), as it is called when the exit announcement
            // finishes if there is no service left.
            mAnnouncer.startSending(id, exitInfo, 0L /* initialDelayMs */);
        } else if (mRecordRepository.getServicesCount() == 0) {
            destroy();
        }
        return mRecordRepository.getServicesCount() == 0;
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
            mSocket.joinGroup(
                    new InetSocketAddress(multicastAddr, MdnsConstants.MDNS_PORT), iface);
            return true;
        } catch (IOException e) {
            // The address may have just been removed
            Log.e(mTag, "Error joining multicast group for " + iface, e);
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
            Log.e(mTag, "Error parsing mDNS packet", e);
            if (DBG) {
                Log.v(
                        mTag, "Packet: " + HexDump.toHexString(recvbuf, 0, length));
            }
            return;
        }

        if (DBG) {
            Log.v(mTag,
                    "Parsed packet with " + packet.questions.size() + " questions, "
                            + packet.answers.size() + " answers, "
                            + packet.authorityRecords.size() + " authority, "
                            + packet.additionalRecords.size() + " additional from " + src);
        }

        for (int conflictServiceId : mRecordRepository.getConflictingServices(packet)) {
            mCb.onServiceConflict(this, conflictServiceId);
        }

        final MdnsRecordRepository.ReplyInfo answers =
                mRecordRepository.getReply(packet, src);

        if (answers == null) return;
        mReplySender.queueReply(answers);
    }

    public void restartProbing(int serviceId) {
        final MdnsProber.ProbingInfo probingInfo = mRecordRepository.setServiceProbing(serviceId);
        if (probingInfo == null) return;

        mProber.restartForConflict(probingInfo);
    }

    public void renameServiceForConflict(int serviceId, NsdServiceInfo newInfo) {
        final MdnsProber.ProbingInfo probingInfo = mRecordRepository.renameServiceForConflict(
                serviceId, newInfo);
        if (probingInfo == null) return;

        mProber.restartForConflict(probingInfo);
    }

    public boolean isDoneProbing(int serviceId) {
        return mRecordRepository.isDoneProbing(serviceId);
    }
}
