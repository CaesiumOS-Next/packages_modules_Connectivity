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
import android.net.Network;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.util.Log;

import com.android.net.module.util.HexDump;
import com.android.server.connectivity.mdns.MdnsAnnouncer.BaseAnnouncementInfo;

import java.io.IOException;
import java.net.InetSocketAddress;

class MdnsInterfaceAdvertiser implements MulticastPacketReader.PacketHandler {
    private static final long EXIT_ANNOUNCEMENT_DELAY_MS = 100L;

    private final boolean DBG = MdnsAdvertiser.DBG;
    @Nullable
    private final Network mNetwork;
    @NonNull
    private final String mTag;
    @NonNull
    private final ProbingCallback mProbingCallback = new ProbingCallback();
    @NonNull
    private final AnnouncingCallback mAnnouncingCallback = new AnnouncingCallback();
    private final MdnsRecordRepository mRecordRepository;
    @NonNull
    private final Callback mCb;

    @Nullable
    private MdnsAnnouncer mAnnouncer;
    @Nullable
    private MdnsProber mProber;
    @Nullable
    private MdnsReplySender mReplySender;

    interface Callback {
        void onRegisterServiceSucceeded(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId);
        void onServiceConflict(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId);
        void onDestroyed(@Nullable Network network);
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
            if (mAnnouncer == null) {
                Log.wtf(mTag, "Received probing callback after prober/announcer were destroyed");
                return;
            }
            mAnnouncer.startSending(info.getServiceId(), announcementInfo,
                    0L /* initialDelayMs */);
        }
    }

    private class AnnouncingCallback
            implements MdnsPacketRepeater.PacketRepeaterCallback<BaseAnnouncementInfo> {
        @Override
        public void onSent(int index, @NonNull BaseAnnouncementInfo info) {
            mRecordRepository.onAdvertisementSent(info.getServiceId());
        }

        @Override
        public void onFinished(@NonNull BaseAnnouncementInfo info) {
            if (info instanceof MdnsAnnouncer.ExitAnnouncementInfo) {
                mRecordRepository.removeService(info.getServiceId());

                if (mRecordRepository.getServicesCount() == 0) {
                    destroy();
                }
            }
        }
    }

    public MdnsInterfaceAdvertiser(@Nullable Network network,
            @NonNull MdnsRecordRepository repository,
            @NonNull MdnsInterfaceSocket socket,
            @NonNull Looper looper, @NonNull byte[] packetCreationBuffer, @NonNull Callback cb) {
        final String networkLogTag = (network == null ? "all" : String.valueOf(network.netId));
        mTag = MdnsInterfaceAdvertiser.class.getSimpleName() + "/" + networkLogTag;
        mNetwork = network;
        mRecordRepository = repository;
        mCb = cb;
        mReplySender = new MdnsReplySender(networkLogTag, looper, socket, packetCreationBuffer);
        mAnnouncer = new MdnsAnnouncer(networkLogTag, looper, mReplySender,
                mAnnouncingCallback);
        mProber = new MdnsProber(networkLogTag, looper, mReplySender, mRecordRepository,
                mProbingCallback);
    }

    /**
     * Start probing for existing services, after advertiser creation.
     */
    public void startAllProbing() {
        for (MdnsProber.ProbingInfo info : mRecordRepository.setAllProbing()) {
            mProber.startProbing(info);
        }
    }

    private void destroy() {
        mCb.onDestroyed(mNetwork);
    }

    public void addService(int id, NsdServiceInfo service) throws IOException,
            MdnsRecordRepository.NameConflictException {
        final int replacedService = mRecordRepository.addService(id, service);
        // Cancel announcements for the existing service. This only happens for exiting services
        // (so cancelling exiting announcements), since addService would throw a conflict otherwise.
        if (replacedService >= 0) {
            if (DBG) {
                Log.d(mTag, "Service " + replacedService +
                        " getting re-added, cancelling exit announcements");
            }
            if (mAnnouncer != null) mAnnouncer.stop(replacedService);
        }
        mProber.startProbing(mRecordRepository.setServiceProbing(id));
    }

    public void removeService(int id) {
        if (mProber != null) mProber.stop(id);
        if (mAnnouncer != null) mAnnouncer.stop(id);
        final MdnsAnnouncer.ExitAnnouncementInfo exitInfo = mRecordRepository.exitService(id);
        if (exitInfo != null && mAnnouncer != null) {
            // This effectively schedules destroy(), as it is called when the exit announcement
            // finishes if there is no service left.
            // A non-zero exit announcement delay follows legacy mdnsresponder behavior, and is
            // also useful to ensure that when a host receives the exit announcement, the service
            // has been unregistered on all interfaces; so an announcement sent from interface A
            // that was already in-flight while unregistering won't be received after the exit on
            // interface B.
            mAnnouncer.startSending(id, exitInfo, EXIT_ANNOUNCEMENT_DELAY_MS);
        } else {
            mRecordRepository.removeService(id);
            if (mRecordRepository.getServicesCount() == 0) {
                destroy();
            }
        }
    }

    public void cancelAnnouncements(int id) {
        mAnnouncer.stop(id);
    }

    /**
     * Destroy the advertiser immediately, not sending any exit announcement.
     *
     * <p>Useful when the underlying network went away.
     */
    public void destroyNow() {
        for (int serviceId : mRecordRepository.clearServices()) {
            mProber.stop(serviceId);
            mAnnouncer.stop(serviceId);
        }
        mReplySender.cancelAll();
        destroy();
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
        if (mReplySender != null) mReplySender.queueReply(answers);
    }

    public void restartProbingForConflict(int serviceId) {
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
